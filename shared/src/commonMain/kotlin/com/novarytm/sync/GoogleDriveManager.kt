package com.novarytm.sync

import com.novarytm.auth.GoogleAuthManager
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class DriveStorageFullException(
    val usedBytes: Long,
    val limitBytes: Long
) : Exception("Google Drive storage full ($usedBytes / $limitBytes bytes)")

class DriveAuthRequiredException(message: String) : Exception(message)
class DriveSyncException(message: String, val isRetryable: Boolean = false) : Exception(message)

@Serializable
data class DriveFileAppFolder(val id: String, val name: String)

@Serializable
data class DriveFileListAppFolder(val files: List<DriveFileAppFolder> = emptyList())

class GoogleDriveManager(
    private val authManager: GoogleAuthManager,
    private val httpClient: HttpClient
) {
    private val driveBaseUrl = "https://www.googleapis.com/drive/v3/files"
    private val driveUploadUrl = "https://www.googleapis.com/upload/drive/v3/files"
    // Use restricted drive.file scope matching the OAuth login flow
    private val scopes = listOf("https://www.googleapis.com/auth/drive.file")

    @Serializable
    private data class DrivePermission(
        val type: String,
        val role: String,
        val emailAddress: String
    )

    @Serializable
    private data class DriveFileMetadata(val name: String)

    @Serializable
    private data class DriveFileCreated(val id: String)

    @Serializable
    private data class StorageQuota(
        val limit: String? = null,
        val usage: String? = null
    )

    @Serializable
    private data class AboutResponse(
        val storageQuota: StorageQuota? = null
    )

    private suspend fun getAuthToken(): String? = authManager.getAccessToken(scopes)

    private suspend fun getRequiredAuthToken(): String {
        val token = getAuthToken()
        if (token != null) return token
        if (authManager.needsInteractiveReauth) {
            throw DriveAuthRequiredException("Google Drive authorization expired or missing")
        }
        throw DriveSyncException("Unable to obtain Google Drive token; check network connection", isRetryable = true)
    }

    private suspend fun checkAndClearTokenIfUnauthorized(response: HttpResponse, token: String?) {
        if (response.status == HttpStatusCode.Unauthorized && token != null) {
            try {
                authManager.clearToken(token)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private suspend fun executeWithAuthRetry(
        initialToken: String,
        request: suspend (String) -> HttpResponse
    ): Pair<HttpResponse, String> {
        var currentToken = initialToken
        var response = request(currentToken)
        if (response.status == HttpStatusCode.Unauthorized) {
            authManager.clearToken(currentToken)
            currentToken = getRequiredAuthToken()
            response = request(currentToken)
            if (response.status == HttpStatusCode.Unauthorized) {
                authManager.clearToken(currentToken)
                throw DriveAuthRequiredException("Drive API returned 401 Unauthorized after token refresh")
            }
        }
        return response to currentToken
    }

    private fun isHtmlContentType(response: HttpResponse): Boolean {
        val contentType = response.contentType()?.toString()?.lowercase() ?: return false
        return contentType.contains("text/html") || contentType.contains("application/xhtml")
    }

    private fun isHtmlBody(body: String): Boolean {
        val trimmed = body.trimStart().lowercase()
        return trimmed.startsWith("<!doctype") || trimmed.startsWith("<html") || trimmed.startsWith("<head")
    }

    private suspend fun readValidatedTextBody(response: HttpResponse): String? {
        if (isHtmlContentType(response)) {
            throw DriveAuthRequiredException("Google Drive returned an HTML sign-in or consent page; authorization required")
        }
        val body = readBoundedResponse(response) ?: return null
        if (isHtmlBody(body)) {
            throw DriveAuthRequiredException("Google Drive returned an HTML sign-in or consent page; authorization required")
        }
        return body
    }

    private suspend fun ensureReaderPermission(
        fileId: String,
        partnerEmail: String,
        initialToken: String
    ): String {
        val cleanEmail = partnerEmail.trim()
        if (cleanEmail.isEmpty()) return initialToken

        val (permResponse, activeToken) = executeWithAuthRetry(initialToken) { t ->
            httpClient.post("$driveBaseUrl/$fileId/permissions") {
                header(HttpHeaders.Authorization, "Bearer $t")
                contentType(ContentType.Application.Json)
                setBody(DrivePermission(type = "user", role = "reader", emailAddress = cleanEmail))
            }
        }
        if (isHtmlContentType(permResponse)) {
            throw DriveAuthRequiredException("Google Drive returned HTML during permission grant; authorization required")
        }
        if (permResponse.status == HttpStatusCode.Forbidden) {
            throw DriveSyncException("Drive API PERMISSION returned 403 Forbidden")
        }
        if (!permResponse.status.isSuccess()) {
            throw DriveSyncException(
                "Drive API PERMISSION failed: ${permResponse.status}",
                isRetryable = permResponse.status.value >= 500 || permResponse.status.value == 429
            )
        }
        return activeToken
    }

    suspend fun pushSyncBlob(
        encryptedData: ByteArray,
        preferredFileId: String? = null,
        partnerEmail: String? = null,
        onPreferredFileNotFound: (() -> Unit)? = null
    ): String? = withContext(Dispatchers.Default) {
        var currentPreferredId = preferredFileId

        retryWithBackoff {
            val token = getRequiredAuthToken()

            suspend fun patchMedia(fileId: String, currentToken: String): Pair<HttpResponse, String> {
                return executeWithAuthRetry(currentToken) { t ->
                    httpClient.patch("$driveUploadUrl/$fileId?uploadType=media") {
                        header(HttpHeaders.Authorization, "Bearer $t")
                        contentType(ContentType.Application.OctetStream)
                        setBody(encryptedData)
                    }
                }
            }

            if (currentPreferredId != null) {
                val targetId = currentPreferredId!!
                val (patchResponse, activeToken) = patchMedia(targetId, token)
                if (isHtmlContentType(patchResponse)) {
                    throw DriveAuthRequiredException("Google Drive returned HTML during upload; authorization required")
                }
                when {
                    patchResponse.status.isSuccess() -> {
                        if (!partnerEmail.isNullOrBlank()) {
                            ensureReaderPermission(targetId, partnerEmail, activeToken)
                        }
                        return@retryWithBackoff targetId
                    }
                    patchResponse.status == HttpStatusCode.NotFound -> {
                        // Cached preferred file was deleted; clear cache and fall through to findSyncBlob
                        currentPreferredId = null
                        onPreferredFileNotFound?.invoke()
                    }
                    patchResponse.status == HttpStatusCode.Forbidden -> {
                        throwStorageFullIfApplicable(activeToken)
                        throw DriveSyncException("Drive API PATCH returned 403 Forbidden")
                    }
                    else -> {
                        throw DriveSyncException(
                            "Drive API PATCH failed: ${patchResponse.status}",
                            isRetryable = patchResponse.status.value >= 500 || patchResponse.status.value == 429
                        )
                    }
                }
            }

            val (foundFileId, searchToken) = findSyncBlobWithToken(token)
            if (foundFileId != null) {
                val (patchResponse, activeToken) = patchMedia(foundFileId, searchToken)
                if (isHtmlContentType(patchResponse)) {
                    throw DriveAuthRequiredException("Google Drive returned HTML during upload; authorization required")
                }
                when {
                    patchResponse.status.isSuccess() -> {
                        if (!partnerEmail.isNullOrBlank()) {
                            ensureReaderPermission(foundFileId, partnerEmail, activeToken)
                        }
                        return@retryWithBackoff foundFileId
                    }
                    patchResponse.status == HttpStatusCode.NotFound -> {
                        throw DriveSyncException("Drive file disappeared during update (404)", isRetryable = true)
                    }
                    patchResponse.status == HttpStatusCode.Forbidden -> {
                        throwStorageFullIfApplicable(activeToken)
                        throw DriveSyncException("Drive API PATCH returned 403 Forbidden")
                    }
                    else -> {
                        throw DriveSyncException(
                            "Drive API PATCH failed: ${patchResponse.status}",
                            isRetryable = patchResponse.status.value >= 500 || patchResponse.status.value == 429
                        )
                    }
                }
            }

            // Confirmed absent: POST to create a single sync_blob.enc, upload media, and share read-only
            val (createResponse, createToken) = executeWithAuthRetry(searchToken) { t ->
                httpClient.post(driveBaseUrl) {
                    header(HttpHeaders.Authorization, "Bearer $t")
                    contentType(ContentType.Application.Json)
                    setBody(DriveFileMetadata("sync_blob.enc"))
                }
            }
            if (isHtmlContentType(createResponse)) {
                throw DriveAuthRequiredException("Google Drive returned HTML during file creation; authorization required")
            }
            if (createResponse.status == HttpStatusCode.Forbidden) {
                throwStorageFullIfApplicable(createToken)
                throw DriveSyncException("Drive API CREATE returned 403 Forbidden")
            }
            if (!createResponse.status.isSuccess()) {
                throw DriveSyncException(
                    "Drive API CREATE failed: ${createResponse.status}",
                    isRetryable = createResponse.status.value >= 500 || createResponse.status.value == 429
                )
            }

            val newFileId = createResponse.body<DriveFileCreated>().id

            val (uploadResponse, uploadToken) = patchMedia(newFileId, createToken)
            if (isHtmlContentType(uploadResponse)) {
                throw DriveAuthRequiredException("Google Drive returned HTML during upload; authorization required")
            }
            if (uploadResponse.status == HttpStatusCode.Forbidden) {
                throwStorageFullIfApplicable(uploadToken)
                throw DriveSyncException("Drive API UPLOAD returned 403 Forbidden")
            }
            if (!uploadResponse.status.isSuccess()) {
                throw DriveSyncException(
                    "Drive API UPLOAD failed: ${uploadResponse.status}",
                    isRetryable = uploadResponse.status.value >= 500 || uploadResponse.status.value == 429
                )
            }

            if (!partnerEmail.isNullOrBlank()) {
                ensureReaderPermission(newFileId, partnerEmail, uploadToken)
            }

            newFileId
        }
    }

    suspend fun pushSyncBlob(
        encryptedBase64: String,
        partnerEmail: String? = null,
        preferredFileId: String? = null
    ): String? = pushSyncBlob(
        encryptedData = encryptedBase64.encodeToByteArray(),
        preferredFileId = preferredFileId,
        partnerEmail = partnerEmail
    )

    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1000L,
        block: suspend () -> T
    ): T {
        var delayMs = initialDelayMs
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveStorageFullException) {
                throw e
            } catch (e: DriveAuthRequiredException) {
                throw e
            } catch (e: DriveSyncException) {
                if (!e.isRetryable || attempt == maxAttempts) throw e
                lastException = e
            } catch (e: Exception) {
                if (attempt == maxAttempts) throw e
                lastException = e
            }
            delay(delayMs)
            delayMs *= 2
        }
        throw lastException ?: DriveSyncException("Operation failed after $maxAttempts attempts")
    }

    suspend fun checkStorageQuota(): Pair<Long, Long>? {
        return try {
            val token = getAuthToken() ?: return null
            val (response, _) = executeWithAuthRetry(token) { t ->
                httpClient.get("https://www.googleapis.com/drive/v3/about?fields=storageQuota") {
                    header(HttpHeaders.Authorization, "Bearer $t")
                }
            }
            if (isHtmlContentType(response) || !response.status.isSuccess()) return null
            val about = response.body<AboutResponse>()
            val limit = about.storageQuota?.limit?.toLongOrNull() ?: return null
            val usage = about.storageQuota?.usage?.toLongOrNull() ?: return null
            Pair(usage, limit)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun throwStorageFullIfApplicable(token: String) {
        try {
            val response = httpClient.get("https://www.googleapis.com/drive/v3/about?fields=storageQuota") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            checkAndClearTokenIfUnauthorized(response, token)
            if (!isHtmlContentType(response) && response.status.isSuccess()) {
                val about = response.body<AboutResponse>()
                val limit = about.storageQuota?.limit?.toLongOrNull() ?: return
                val usage = about.storageQuota?.usage?.toLongOrNull() ?: return
                val freeBytes = limit - usage
                if (freeBytes < 1_048_576L) { // Less than 1 MB free
                    throw DriveStorageFullException(usage, limit)
                }
            }
        } catch (e: DriveStorageFullException) {
            throw e
        } catch (_: Exception) {
            // Couldn't check quota, let the original error propagate
        }
    }

    suspend fun pullSyncBlob(): String? = withContext(Dispatchers.Default) {
        retryWithBackoff {
            val token = getRequiredAuthToken()
            val (fileId, searchToken) = findSyncBlobWithToken(token)
            if (fileId == null) return@retryWithBackoff null

            val (response, activeToken) = executeWithAuthRetry(searchToken) { t ->
                httpClient.get("$driveBaseUrl/$fileId?alt=media") {
                    header(HttpHeaders.Authorization, "Bearer $t")
                }
            }
            if (isHtmlContentType(response)) {
                throw DriveAuthRequiredException("Google Drive returned HTML instead of sync data; authorization required")
            }
            if (response.status == HttpStatusCode.NotFound) {
                return@retryWithBackoff null
            }
            if (response.status == HttpStatusCode.Forbidden) {
                throwStorageFullIfApplicable(activeToken)
                throw DriveAuthRequiredException("Drive API pull returned 403 Forbidden")
            }
            if (!response.status.isSuccess()) {
                throw DriveSyncException(
                    "Drive API pull failed: ${response.status}",
                    isRetryable = response.status.value >= 500 || response.status.value == 429
                )
            }
            readValidatedTextBody(response)
        }
    }

    suspend fun downloadPublicSyncBlob(fileId: String): String? = withContext(Dispatchers.Default) {
        if (!fileId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return@withContext null
        }
        retryWithBackoff {
            var token = getAuthToken()
            // First try downloading via official Drive API endpoint if signed in
            if (token != null) {
                val (apiResponse, activeToken) = executeWithAuthRetry(token) { t ->
                    httpClient.get("$driveBaseUrl/$fileId?alt=media") {
                        header(HttpHeaders.Authorization, "Bearer $t")
                    }
                }
                token = activeToken
                if (isHtmlContentType(apiResponse)) {
                    throw DriveAuthRequiredException("Google Drive sign-in required to access shared file")
                }
                if (apiResponse.status.isSuccess()) {
                    return@retryWithBackoff readValidatedTextBody(apiResponse)
                }
                if (apiResponse.status == HttpStatusCode.NotFound || apiResponse.status == HttpStatusCode.Forbidden) {
                    return@retryWithBackoff "REVOKED"
                }
                if (apiResponse.status.value >= 500 || apiResponse.status.value == 429) {
                    throw DriveSyncException(
                        "Drive API download failed: ${apiResponse.status}",
                        isRetryable = true
                    )
                }
            }

            // Fallback to direct export URL (works when token is null or file is publicly shared)
            val url = "https://drive.google.com/uc?export=download&id=$fileId"
            val response = httpClient.get(url) {
                if (token != null) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
            if (response.status == HttpStatusCode.Unauthorized) {
                if (token != null) authManager.clearToken(token)
                throw DriveAuthRequiredException("Access to shared Google Drive file requires authorization (401)")
            }
            if (isHtmlContentType(response)) {
                throw DriveAuthRequiredException("Google Drive sign-in or permission required to access shared file")
            }
            if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.NotFound) {
                return@retryWithBackoff "REVOKED"
            }
            if (!response.status.isSuccess()) {
                throw DriveSyncException(
                    "Failed to download shared sync file: ${response.status}",
                    isRetryable = response.status.value >= 500 || response.status.value == 429
                )
            }
            readValidatedTextBody(response)
        }
    }

    private suspend fun findSyncBlobWithToken(
        initialToken: String,
        name: String = "sync_blob.enc"
    ): Pair<String?, String> {
        val cleanName = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        val (response, activeToken) = executeWithAuthRetry(initialToken) { t ->
            httpClient.get(driveBaseUrl) {
                parameter("q", "name = '$cleanName' and 'me' in owners and trashed = false")
                parameter("pageSize", 10)
                parameter("orderBy", "modifiedTime desc")
                parameter("fields", "files(id, name, modifiedTime)")
                header(HttpHeaders.Authorization, "Bearer $t")
            }
        }
        if (isHtmlContentType(response)) {
            throw DriveAuthRequiredException("Google Drive returned HTML during file search; authorization required")
        }
        if (response.status == HttpStatusCode.Forbidden) {
            throwStorageFullIfApplicable(activeToken)
            throw DriveAuthRequiredException("Drive API search returned 403 Forbidden")
        }
        if (!response.status.isSuccess()) {
            throw DriveSyncException(
                "Drive API search failed with status ${response.status}",
                isRetryable = response.status.value >= 500 || response.status.value == 429
            )
        }
        val fileList = response.body<DriveFileListAppFolder>()
        return fileList.files.firstOrNull()?.id to activeToken
    }

    suspend fun findSyncBlob(token: String, name: String = "sync_blob.enc"): String? = withContext(Dispatchers.Default) {
        findSyncBlobWithToken(token, name).first
    }

    suspend fun deleteSyncBlob(): Boolean = withContext(Dispatchers.Default) {
        retryWithBackoff {
            val token = getRequiredAuthToken()
            val (fileId, searchToken) = findSyncBlobWithToken(token)
            if (fileId == null) return@retryWithBackoff true // already gone

            val (response, _) = executeWithAuthRetry(searchToken) { t ->
                httpClient.delete("$driveBaseUrl/$fileId") {
                    header(HttpHeaders.Authorization, "Bearer $t")
                }
            }
            if (isHtmlContentType(response)) {
                throw DriveAuthRequiredException("Google Drive returned HTML during delete; authorization required")
            }
            if (response.status == HttpStatusCode.NotFound) {
                return@retryWithBackoff true
            }
            if (response.status == HttpStatusCode.Forbidden) {
                throw DriveAuthRequiredException("Drive API delete returned 403 Forbidden")
            }
            if (!response.status.isSuccess()) {
                throw DriveSyncException(
                    "Drive API delete failed: ${response.status}",
                    isRetryable = response.status.value >= 500 || response.status.value == 429
                )
            }
            true
        }
    }

    private suspend fun readBoundedResponse(response: HttpResponse, maxLimit: Int = 2 * 1024 * 1024): String? {
        val contentLength = response.contentLength() ?: -1L
        if (contentLength > maxLimit) return null

        val channel = response.bodyAsChannel()
        val buffer = ByteArray(maxLimit + 1)
        var totalRead = 0
        while (!channel.isClosedForRead && totalRead < buffer.size) {
            val read = channel.readAvailable(buffer, totalRead, buffer.size - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        if (totalRead > maxLimit) return null
        return buffer.decodeToString(0, totalRead)
    }
}

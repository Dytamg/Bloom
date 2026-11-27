package com.bloom.sync

import com.bloom.auth.GoogleAuthManager
import com.bloom.utils.AppJson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

interface DriveAuthProvider {
    val needsInteractiveReauth: Boolean
    suspend fun getAccessToken(scopes: List<String>): String?
    suspend fun clearToken(token: String)
    suspend fun invalidateAndRefreshAccessToken(token: String): String?
}

internal class GoogleAuthDriveProvider(
    private val authManager: GoogleAuthManager
) : DriveAuthProvider {
    override val needsInteractiveReauth: Boolean
        get() = authManager.needsInteractiveReauth

    override suspend fun getAccessToken(scopes: List<String>): String? =
        authManager.getAccessToken(scopes)

    override suspend fun clearToken(token: String) =
        authManager.clearToken(token)

    override suspend fun invalidateAndRefreshAccessToken(token: String): String? {
        try {
            authManager.clearToken(token)
        } catch (_: Exception) {}
        return authManager.getAccessToken(listOf("https://www.googleapis.com/auth/drive.file"))
    }
}

interface DrivePermissionCache {
    fun getPrimarySyncFileId(): String?
    fun getGrantedPartnerEmail(): String?
    fun saveGrantedPartnerEmail(email: String?)
    fun getPartnerPermissionId(): String?
    fun savePartnerPermissionId(permissionId: String?)
}

interface DriveBlobClient {
    suspend fun pushSyncBlob(
        encryptedData: ByteArray,
        preferredFileId: String? = null,
        partnerEmail: String? = null,
        onPreferredFileNotFound: (() -> Unit)? = null
    ): String?

    suspend fun pullSyncBlob(): String?

    suspend fun downloadPublicSyncBlob(fileId: String): String?

    suspend fun deleteSyncBlob(): Boolean

    suspend fun revokePartnerAccess(fileId: String, permissionId: String? = null): Boolean = true
}

class GoogleDriveManager(
    private val authProvider: DriveAuthProvider,
    private val httpClient: HttpClient,
    private val retryBaseDelayMs: Long = 1000L,
    private val permissionCache: DrivePermissionCache? = null
) : DriveBlobClient {

    constructor(
        authManager: GoogleAuthManager,
        httpClient: HttpClient,
        retryBaseDelayMs: Long = 1000L,
        permissionCache: DrivePermissionCache? = null
    ) : this(GoogleAuthDriveProvider(authManager), httpClient, retryBaseDelayMs, permissionCache)

    constructor(
        authManager: GoogleAuthManager,
        httpClientEngine: HttpClientEngine?,
        retryBaseDelayMs: Long = 1000L,
        permissionCache: DrivePermissionCache? = null
    ) : this(
        GoogleAuthDriveProvider(authManager),
        httpClientEngine?.let {
            HttpClient(it) {
                install(ContentNegotiation) {
                    json(AppJson)
                }
            }
        } ?: HttpClient {
            install(ContentNegotiation) {
                json(AppJson)
            }
        },
        retryBaseDelayMs,
        permissionCache
    )

    constructor(
        authProvider: DriveAuthProvider,
        httpClientEngine: HttpClientEngine?,
        retryBaseDelayMs: Long = 1000L,
        permissionCache: DrivePermissionCache? = null
    ) : this(
        authProvider,
        httpClientEngine?.let {
            HttpClient(it) {
                install(ContentNegotiation) {
                    json(AppJson)
                }
            }
        } ?: HttpClient {
            install(ContentNegotiation) {
                json(AppJson)
            }
        },
        retryBaseDelayMs,
        permissionCache
    )

    private val driveBaseUrl = "https://www.googleapis.com/drive/v3/files"
    private val driveUploadUrl = "https://www.googleapis.com/upload/drive/v3/files"
    private val scopes = listOf("https://www.googleapis.com/auth/drive.file")
    private val permissionMutex = Mutex()
    private val grantedPermissions = mutableMapOf<Pair<String, String>, String?>()

    @Serializable
    private data class DrivePermission(
        val type: String,
        val role: String,
        val emailAddress: String
    )

    @Serializable
    private data class DrivePermissionCreated(val id: String? = null)

    @Serializable
    private data class DrivePermissionListItem(val id: String, val role: String? = null)

    @Serializable
    private data class DrivePermissionListResponse(val permissions: List<DrivePermissionListItem> = emptyList())

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

    private suspend fun getAuthToken(): String? = authProvider.getAccessToken(scopes)

    private suspend fun getRequiredAuthToken(): String {
        val token = getAuthToken()
        if (token != null) return token
        if (authProvider.needsInteractiveReauth) {
            throw DriveAuthRequiredException("Google Drive authorization expired or missing")
        }
        throw DriveSyncException("Unable to obtain Google Drive token; check network connection", isRetryable = true)
    }

    internal suspend fun executeAuthorizedDriveRequest(
        initialToken: String,
        request: suspend (String) -> HttpResponse
    ): Pair<HttpResponse, String> {
        var currentToken = initialToken
        var response = request(currentToken)
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = authProvider.invalidateAndRefreshAccessToken(currentToken)
                ?: getRequiredAuthToken()
            currentToken = refreshed
            response = request(currentToken)
            if (response.status == HttpStatusCode.Unauthorized) {
                authProvider.clearToken(currentToken)
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

    private fun isPermissionCached(fileId: String, normalizedEmail: String): Boolean {
        if (grantedPermissions.containsKey(fileId to normalizedEmail)) return true
        val cachedEmail = permissionCache?.getGrantedPartnerEmail()?.trim()?.lowercase()
        if (permissionCache?.getPrimarySyncFileId() == fileId && cachedEmail == normalizedEmail) {
            grantedPermissions[fileId to normalizedEmail] = permissionCache.getPartnerPermissionId()
            return true
        }
        return false
    }

    private suspend fun parseCreatedPermissionId(response: HttpResponse): String? {
        val bodyText = runCatching { response.bodyAsText() }.getOrNull()?.trim() ?: return null
        if (bodyText.isEmpty()) return null
        return runCatching { AppJson.decodeFromString<DrivePermissionCreated>(bodyText).id }.getOrNull()
    }

    private fun savePermissionGrant(fileId: String, cleanEmail: String, permissionId: String?) {
        grantedPermissions[fileId to cleanEmail.lowercase()] = permissionId
        permissionCache?.saveGrantedPartnerEmail(cleanEmail)
        permissionCache?.savePartnerPermissionId(permissionId)
    }

    private fun clearPermissionCacheForFile(fileId: String) {
        val keysToRemove = grantedPermissions.keys.filter { it.first == fileId }
        keysToRemove.forEach { grantedPermissions.remove(it) }
        if (permissionCache?.getPrimarySyncFileId() == fileId) {
            permissionCache.saveGrantedPartnerEmail(null)
            permissionCache.savePartnerPermissionId(null)
        }
    }

    private suspend fun ensureReaderPermission(
        fileId: String,
        partnerEmail: String,
        initialToken: String
    ): String {
        val cleanEmail = partnerEmail.trim()
        if (cleanEmail.isEmpty()) return initialToken
        val normalizedEmail = cleanEmail.lowercase()

        return permissionMutex.withLock {
            if (isPermissionCached(fileId, normalizedEmail)) {
                return@withLock initialToken
            }
            val (permResponse, activeToken) = executeAuthorizedDriveRequest(initialToken) { t ->
                httpClient.post("$driveBaseUrl/$fileId/permissions") {
                    parameter("sendNotificationEmail", "false")
                    header(HttpHeaders.Authorization, "Bearer $t")
                    contentType(ContentType.Application.Json)
                    setBody(DrivePermission(type = "user", role = "reader", emailAddress = cleanEmail))
                }
            }
            validatePermissionResponse(permResponse)
            val createdId = parseCreatedPermissionId(permResponse)
            savePermissionGrant(fileId, cleanEmail, createdId)
            activeToken
        }
    }

    private suspend fun ensureDriveSuccessStatus(
        response: HttpResponse,
        actionLabel: String,
        token: String? = null,
        checkStorage: Boolean = false,
        authOnForbidden: Boolean = false,
        htmlMsg: String = "Google Drive returned HTML during $actionLabel; authorization required",
        forbiddenMsg: String = "Drive API $actionLabel returned 403 Forbidden",
        failMsg: String = "Drive API $actionLabel failed: ${response.status}"
    ) {
        if (isHtmlContentType(response)) {
            throw DriveAuthRequiredException(htmlMsg)
        }
        if (response.status == HttpStatusCode.Forbidden) {
            if (checkStorage && token != null) {
                throwStorageFullIfApplicable(token)
            }
            if (authOnForbidden) {
                throw DriveAuthRequiredException(forbiddenMsg)
            } else {
                throw DriveSyncException(forbiddenMsg)
            }
        }
        if (!response.status.isSuccess()) {
            throw DriveSyncException(
                failMsg,
                isRetryable = response.status.value >= 500 || response.status.value == 429
            )
        }
    }

    private suspend fun validatePermissionResponse(response: HttpResponse) {
        ensureDriveSuccessStatus(response, "permission")
    }

    private suspend fun patchMedia(fileId: String, currentToken: String, encryptedData: ByteArray): Pair<HttpResponse, String> {
        return executeAuthorizedDriveRequest(currentToken) { t ->
            httpClient.patch("$driveUploadUrl/$fileId?uploadType=media") {
                header(HttpHeaders.Authorization, "Bearer $t")
                contentType(ContentType.Application.OctetStream)
                setBody(encryptedData)
            }
        }
    }

    private suspend fun handlePatchSuccess(
        response: HttpResponse,
        activeToken: String,
        fileId: String,
        partnerEmail: String?
    ): String {
        if (!partnerEmail.isNullOrBlank()) {
            ensureReaderPermission(fileId, partnerEmail, activeToken)
        }
        return fileId
    }

    private suspend fun tryPatchPreferred(
        preferredId: String,
        encryptedData: ByteArray,
        token: String,
        partnerEmail: String?,
        onPreferredFileNotFound: (() -> Unit)?
    ): String? {
        val (response, activeToken) = patchMedia(preferredId, token, encryptedData)
        if (isHtmlContentType(response)) {
            throw DriveAuthRequiredException("Google Drive returned HTML during upload; authorization required")
        }
        if (response.status.isSuccess()) {
            return handlePatchSuccess(response, activeToken, preferredId, partnerEmail)
        }
        if (response.status == HttpStatusCode.NotFound) {
            permissionMutex.withLock { clearPermissionCacheForFile(preferredId) }
            onPreferredFileNotFound?.invoke()
            return null
        }
        if (response.status == HttpStatusCode.Forbidden) {
            throwStorageFullIfApplicable(activeToken)
            throw DriveSyncException("Drive API PATCH returned 403 Forbidden", isRetryable = false)
        }
        throw DriveSyncException(
            "Drive API PATCH failed: ${response.status}",
            isRetryable = response.status.value >= 500 || response.status.value == 429
        )
    }

    private suspend fun tryPatchFound(
        foundId: String,
        encryptedData: ByteArray,
        token: String,
        partnerEmail: String?
    ): String {
        val (response, activeToken) = patchMedia(foundId, token, encryptedData)
        if (isHtmlContentType(response)) {
            throw DriveAuthRequiredException("Google Drive returned HTML during upload; authorization required")
        }
        if (response.status.isSuccess()) {
            return handlePatchSuccess(response, activeToken, foundId, partnerEmail)
        }
        if (response.status == HttpStatusCode.NotFound) {
            throw DriveSyncException("Drive file disappeared during update (404)", isRetryable = true)
        }
        if (response.status == HttpStatusCode.Forbidden) {
            throwStorageFullIfApplicable(activeToken)
            throw DriveSyncException("Drive API PATCH returned 403 Forbidden")
        }
        throw DriveSyncException("Drive API PATCH failed: ${response.status}", isRetryable = true)
    }

    private suspend fun createAndUploadNewFile(
        encryptedData: ByteArray,
        token: String,
        partnerEmail: String?
    ): String {
        val (createResponse, createToken) = executeAuthorizedDriveRequest(token) { t ->
            httpClient.post(driveBaseUrl) {
                header(HttpHeaders.Authorization, "Bearer $t")
                contentType(ContentType.Application.Json)
                setBody(DriveFileMetadata("sync_blob.enc"))
            }
        }
        validateCreateResponse(createResponse, createToken)
        val newFileId = createResponse.body<DriveFileCreated>().id
        return tryPatchFound(newFileId, encryptedData, createToken, partnerEmail)
    }

    private suspend fun validateCreateResponse(response: HttpResponse, token: String) {
        ensureDriveSuccessStatus(response, "CREATE", token, checkStorage = true)
    }

    override suspend fun pushSyncBlob(
        encryptedData: ByteArray,
        preferredFileId: String?,
        partnerEmail: String?,
        onPreferredFileNotFound: (() -> Unit)?
    ): String? = withContext(Dispatchers.Default) {
        retryWithBackoff {
            val token = getRequiredAuthToken()
            if (preferredFileId != null) {
                val result = tryPatchPreferred(preferredFileId, encryptedData, token, partnerEmail, onPreferredFileNotFound)
                if (result != null) return@retryWithBackoff result
            }
            val (foundFileId, searchToken) = findSyncBlobWithToken(token)
            if (foundFileId != null) {
                return@retryWithBackoff tryPatchFound(foundFileId, encryptedData, searchToken, partnerEmail)
            }
            createAndUploadNewFile(encryptedData, searchToken, partnerEmail)
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
        initialDelayMs: Long = retryBaseDelayMs,
        block: suspend () -> T
    ): T {
        var delayMs = initialDelayMs.coerceAtLeast(0L)
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
            if (delayMs > 0L) {
                delay(delayMs)
            }
            delayMs *= 2
        }
        throw lastException ?: DriveSyncException("Operation failed after $maxAttempts attempts")
    }

    suspend fun checkStorageQuota(): Pair<Long, Long>? {
        return try {
            val token = getAuthToken() ?: return null
            val (response, _) = executeAuthorizedDriveRequest(token) { t ->
                httpClient.get("https://www.googleapis.com/drive/v3/about?fields=storageQuota") {
                    header(HttpHeaders.Authorization, "Bearer $t")
                }
            }
            parseQuotaResponse(response)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun parseQuotaFields(about: AboutResponse?): Pair<Long, Long>? {
        val lim = about?.storageQuota?.limit?.toLongOrNull() ?: return null
        val usage = about.storageQuota?.usage?.toLongOrNull() ?: return null
        return Pair(usage, lim)
    }

    private suspend fun parseQuotaResponse(response: HttpResponse): Pair<Long, Long>? {
        if (isHtmlContentType(response)) return null
        if (!response.status.isSuccess()) return null
        return runCatching { parseQuotaFields(response.body<AboutResponse>()) }.getOrNull()
    }

    private suspend fun checkStorageLimitAndThrow(usage: Long, limit: Long) {
        if (limit - usage < 1_048_576L) {
            throw DriveStorageFullException(usage, limit)
        }
    }

    private suspend fun fetchQuotaAboutResponse(token: String): AboutResponse? {
        val (response, _) = executeAuthorizedDriveRequest(token) { t ->
            httpClient.get("https://www.googleapis.com/drive/v3/about?fields=storageQuota") {
                header(HttpHeaders.Authorization, "Bearer $t")
            }
        }
        if (isHtmlContentType(response)) return null
        if (!response.status.isSuccess()) return null
        return runCatching { response.body<AboutResponse>() }.getOrNull()
    }

    private suspend fun throwStorageFullIfApplicable(token: String) {
        try {
            val about = fetchQuotaAboutResponse(token) ?: return
            val quota = parseQuotaFields(about) ?: return
            checkStorageLimitAndThrow(quota.first, quota.second)
        } catch (e: DriveStorageFullException) {
            throw e
        } catch (_: Exception) {}
    }

    private suspend fun fetchBlobContent(fileId: String, searchToken: String): String? {
        val (response, activeToken) = executeAuthorizedDriveRequest(searchToken) { t ->
            httpClient.get("$driveBaseUrl/$fileId?alt=media") {
                header(HttpHeaders.Authorization, "Bearer $t")
            }
        }
        if (isHtmlContentType(response)) {
            throw DriveAuthRequiredException("Google Drive returned HTML instead of sync data; authorization required")
        }
        if (response.status == HttpStatusCode.NotFound) return null
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
        return readValidatedTextBody(response)
    }

    override suspend fun pullSyncBlob(): String? = withContext(Dispatchers.Default) {
        retryWithBackoff {
            val token = getRequiredAuthToken()
            val (fileId, searchToken) = findSyncBlobWithToken(token)
            if (fileId == null) return@retryWithBackoff null
            fetchBlobContent(fileId, searchToken)
        }
    }

    private suspend fun downloadApiBlob(fileId: String, token: String): String? {
        val (apiResponse, _) = executeAuthorizedDriveRequest(token) { t ->
            httpClient.get("$driveBaseUrl/$fileId?alt=media") {
                header(HttpHeaders.Authorization, "Bearer $t")
            }
        }
        if (isHtmlContentType(apiResponse)) {
            throw DriveAuthRequiredException("Google Drive sign-in required to access shared file")
        }
        if (apiResponse.status.isSuccess()) {
            return readValidatedTextBody(apiResponse)
        }
        if (apiResponse.status == HttpStatusCode.NotFound || apiResponse.status == HttpStatusCode.Forbidden) {
            return "REVOKED"
        }
        if (apiResponse.status.value >= 500 || apiResponse.status.value == 429) {
            throw DriveSyncException("Drive API download failed: ${apiResponse.status}", isRetryable = true)
        }
        return null
    }

    private suspend fun downloadDirectUcBlob(fileId: String, token: String?): String? {
        val url = "https://drive.google.com/uc?export=download&id=$fileId"
        val response = httpClient.get(url) {
            if (token != null) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            if (token != null) authProvider.clearToken(token)
            throw DriveAuthRequiredException("Access to shared Google Drive file requires authorization (401)")
        }
        if (isHtmlContentType(response)) {
            throw DriveAuthRequiredException("Google Drive sign-in or permission required to access shared file")
        }
        if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.NotFound) {
            return "REVOKED"
        }
        if (!response.status.isSuccess()) {
            throw DriveSyncException(
                "Failed to download shared sync file: ${response.status}",
                isRetryable = response.status.value >= 500 || response.status.value == 429
            )
        }
        return readValidatedTextBody(response)
    }

    override suspend fun downloadPublicSyncBlob(fileId: String): String? = withContext(Dispatchers.Default) {
        if (!fileId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return@withContext null
        }
        retryWithBackoff {
            val token = getAuthToken()
            if (token != null) {
                val apiResult = downloadApiBlob(fileId, token)
                if (apiResult != null) return@retryWithBackoff apiResult
            }
            downloadDirectUcBlob(fileId, token)
        }
    }

    private suspend fun findSyncBlobWithToken(
        initialToken: String,
        name: String = "sync_blob.enc"
    ): Pair<String?, String> {
        val cleanName = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        val (response, activeToken) = executeAuthorizedDriveRequest(initialToken) { t ->
            httpClient.get(driveBaseUrl) {
                parameter("q", "name = '$cleanName' and 'me' in owners and trashed = false")
                parameter("pageSize", 10)
                parameter("orderBy", "modifiedTime desc")
                parameter("fields", "files(id, name, modifiedTime)")
                header(HttpHeaders.Authorization, "Bearer $t")
            }
        }
        validateSearchResponse(response, activeToken)
        val fileList = response.body<DriveFileListAppFolder>()
        return fileList.files.firstOrNull()?.id to activeToken
    }

    private suspend fun validateSearchResponse(response: HttpResponse, token: String) {
        ensureDriveSuccessStatus(
            response = response,
            actionLabel = "search",
            token = token,
            checkStorage = true,
            authOnForbidden = true,
            htmlMsg = "Google Drive returned HTML during file search; authorization required",
            failMsg = "Drive API search failed with status ${response.status}"
        )
    }

    suspend fun findSyncBlob(token: String, name: String = "sync_blob.enc"): String? = withContext(Dispatchers.Default) {
        findSyncBlobWithToken(token, name).first
    }

    override suspend fun revokePartnerAccess(fileId: String, permissionId: String?): Boolean = withContext(Dispatchers.Default) {
        if (fileId.isBlank()) return@withContext true
        retryWithBackoff {
            val token = getRequiredAuthToken()
            if (!permissionId.isNullOrBlank()) {
                deleteSinglePermission(fileId, permissionId, token)
            } else {
                revokeAllReaderPermissions(fileId, token)
            }
            permissionMutex.withLock { clearPermissionCacheForFile(fileId) }
            true
        }
    }

    private suspend fun deleteSinglePermission(fileId: String, permissionId: String, token: String): String {
        val (response, activeToken) = executeAuthorizedDriveRequest(token) { t ->
            httpClient.delete("$driveBaseUrl/$fileId/permissions/$permissionId") {
                header(HttpHeaders.Authorization, "Bearer $t")
            }
        }
        validateDeleteResponse(response)
        return activeToken
    }

    private suspend fun revokeAllReaderPermissions(fileId: String, initialToken: String) {
        val (listResponse, activeToken) = executeAuthorizedDriveRequest(initialToken) { t ->
            httpClient.get("$driveBaseUrl/$fileId/permissions") {
                parameter("fields", "permissions(id,role)")
                header(HttpHeaders.Authorization, "Bearer $t")
            }
        }
        if (listResponse.status == HttpStatusCode.NotFound) return
        validatePermissionResponse(listResponse)
        val bodyText = readValidatedTextBody(listResponse) ?: return
        val listed = runCatching { AppJson.decodeFromString<DrivePermissionListResponse>(bodyText) }.getOrNull() ?: return
        var currentToken = activeToken
        for (perm in listed.permissions) {
            if (perm.role == "reader" && perm.id.isNotBlank()) {
                currentToken = deleteSinglePermission(fileId, perm.id, currentToken)
            }
        }
    }

    override suspend fun deleteSyncBlob(): Boolean = withContext(Dispatchers.Default) {
        retryWithBackoff {
            val token = getRequiredAuthToken()
            val (fileId, searchToken) = findSyncBlobWithToken(token)
            if (fileId == null) return@retryWithBackoff true

            val (response, _) = executeAuthorizedDriveRequest(searchToken) { t ->
                httpClient.delete("$driveBaseUrl/$fileId") {
                    header(HttpHeaders.Authorization, "Bearer $t")
                }
            }
            validateDeleteResponse(response)
            true
        }
    }

    private fun validateDeleteResponse(response: HttpResponse) {
        if (isHtmlContentType(response)) {
            throw DriveAuthRequiredException("Google Drive returned HTML during delete; authorization required")
        }
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent) {
            return
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

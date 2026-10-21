package com.novarytm.sync

import com.novarytm.auth.GoogleAuthManager
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class DriveStorageFullException(
    val usedBytes: Long,
    val limitBytes: Long
) : Exception("Google Drive storage full ($usedBytes / $limitBytes bytes)")

@Serializable
data class DriveFileAppFolder(val id: String, val name: String)

@Serializable
data class DriveFileListAppFolder(val files: List<DriveFileAppFolder>)

class GoogleDriveManager(
    private val authManager: GoogleAuthManager,
    private val httpClient: HttpClient
) {
    private val driveBaseUrl = "https://www.googleapis.com/drive/v3/files"
    private val driveUploadUrl = "https://www.googleapis.com/upload/drive/v3/files"
    // Use restricted drive.file scope matching the OAuth login flow
    private val scopes = listOf("https://www.googleapis.com/auth/drive.file")

    private suspend fun getAuthToken(): String? = authManager.getAccessToken(scopes)

    private suspend fun checkAndClearTokenIfUnauthorized(response: HttpResponse, token: String?) {
        if (response.status == HttpStatusCode.Unauthorized && token != null) {
            try {
                authManager.clearToken(token)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    suspend fun pushSyncBlob(encryptedBase64: String, partnerEmail: String? = null): String? = withContext(Dispatchers.Default) {
        if (partnerEmail.isNullOrBlank()) {
            throw Exception("partnerEmail is missing; cannot share file securely.")
        }
        retryWithBackoff {
            val token = getAuthToken() ?: throw Exception("getAuthToken returned null (Permissions not fully granted)")
            val fileName = "sync_blob.enc"
            val existingFileId = findSyncBlob(token, fileName)

            val fileIdToReturn: String

            @Serializable
            data class DrivePermission(val type: String, val role: String, val emailAddress: String)

            if (existingFileId == null) {
                // Create in regular app-accessible Drive space so DrivePermission sharing works
                @Serializable
                data class DriveFileMetadata(val name: String)

                val createResponse = httpClient.post(driveBaseUrl) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(DriveFileMetadata(fileName))
                }
                checkAndClearTokenIfUnauthorized(createResponse, token)
                if (!createResponse.status.isSuccess()) {
                    if (createResponse.status.value == 403) {
                        throwStorageFullIfApplicable(token)
                    }
                    throw Exception("Drive API CREATE failed: ${createResponse.status}")
                }
                
                @Serializable
                data class DriveFileCreated(val id: String)
                val newFile = createResponse.body<DriveFileCreated>()
                fileIdToReturn = newFile.id
                
                // Set Permissions to specific user email
                val permResponse = httpClient.post("$driveBaseUrl/${newFile.id}/permissions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(DrivePermission("user", "reader", partnerEmail))
                }
                checkAndClearTokenIfUnauthorized(permResponse, token)
                if (!permResponse.status.isSuccess()) {
                    throw Exception("Drive API PERMISSION failed: ${permResponse.status}")
                }
                
                // Upload data
                val uploadResponse = httpClient.patch("$driveUploadUrl/${newFile.id}?uploadType=media") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Text.Plain)
                    setBody(encryptedBase64)
                }
                checkAndClearTokenIfUnauthorized(uploadResponse, token)
                if (!uploadResponse.status.isSuccess()) {
                    if (uploadResponse.status.value == 403) {
                        throwStorageFullIfApplicable(token)
                    }
                    throw Exception("Drive API UPLOAD failed: ${uploadResponse.status}")
                }
            } else {
                // Update
                fileIdToReturn = existingFileId
                val response = httpClient.patch("$driveUploadUrl/$existingFileId?uploadType=media") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Text.Plain)
                    setBody(encryptedBase64)
                }
                checkAndClearTokenIfUnauthorized(response, token)
                if (!response.status.isSuccess()) {
                    if (response.status.value == 403) {
                        throwStorageFullIfApplicable(token)
                    }
                    throw Exception("Drive API PATCH failed: ${response.status}")
                }
                if (!partnerEmail.isNullOrBlank()) {
                    try {
                        val permResponse = httpClient.post("$driveBaseUrl/$existingFileId/permissions") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(DrivePermission("user", "reader", partnerEmail))
                        }
                        checkAndClearTokenIfUnauthorized(permResponse, token)
                    } catch (e: Exception) {
                        // ignore if already granted or error
                    }
                }
            }
            
            return@retryWithBackoff fileIdToReturn
        }
    }

    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1000L,
        block: suspend () -> T?
    ): T? {
        var delayMs = initialDelayMs
        for (attempt in 1 until maxAttempts) {
            try {
                val res = block()
                if (res != null) return res
            } catch (e: DriveStorageFullException) {
                throw e // Don't retry storage-full errors
            } catch (e: Exception) {
                // retry on failure
            }
            delay(delayMs)
            delayMs *= 2
        }
        return try { block() } catch (e: DriveStorageFullException) { throw e } catch (e: Exception) { null }
    }

    @Serializable
    private data class StorageQuota(
        val limit: String? = null,
        val usage: String? = null
    )

    @Serializable
    private data class AboutResponse(
        val storageQuota: StorageQuota? = null
    )

    suspend fun checkStorageQuota(): Pair<Long, Long>? {
        return try {
            val token = getAuthToken() ?: return null
            val response = httpClient.get("https://www.googleapis.com/drive/v3/about?fields=storageQuota") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (!response.status.isSuccess()) return null
            val about = response.body<AboutResponse>()
            val limit = about.storageQuota?.limit?.toLongOrNull() ?: return null
            val usage = about.storageQuota?.usage?.toLongOrNull() ?: return null
            Pair(usage, limit)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun throwStorageFullIfApplicable(token: String) {
        try {
            val response = httpClient.get("https://www.googleapis.com/drive/v3/about?fields=storageQuota") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (response.status.isSuccess()) {
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
        } catch (e: Exception) {
            // Couldn't check quota, let the original error propagate
        }
    }

    suspend fun pullSyncBlob(): String? = withContext(Dispatchers.Default) {
        retryWithBackoff {
            try {
                val token = getAuthToken() ?: return@retryWithBackoff null
                val fileName = "sync_blob.enc"
                val fileId = findSyncBlob(token, fileName) ?: return@retryWithBackoff null

                val response = httpClient.get("$driveBaseUrl/$fileId?alt=media") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                checkAndClearTokenIfUnauthorized(response, token)
                if (response.status.isSuccess()) {
                    return@retryWithBackoff readBoundedResponse(response)
                }
                if (response.status == HttpStatusCode.Unauthorized) {
                    throw Exception("Drive API returned 401 Unauthorized")
                }
                null
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun downloadPublicSyncBlob(fileId: String): String? = withContext(Dispatchers.Default) {
        if (!fileId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return@withContext null
        }
        retryWithBackoff {
            try {
                val token = getAuthToken()
                // First try downloading via official Drive API endpoint if signed in
                if (token != null) {
                    val apiResponse = httpClient.get("$driveBaseUrl/$fileId?alt=media") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    checkAndClearTokenIfUnauthorized(apiResponse, token)
                    if (apiResponse.status.isSuccess()) {
                        val body = readBoundedResponse(apiResponse)
                        if (body != null && !body.trimStart().startsWith("<")) {
                            return@retryWithBackoff body
                        }
                    }
                }

                // Fallback to direct export URL
                val url = "https://drive.google.com/uc?export=download&id=$fileId"
                val response = httpClient.get(url) {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
                if (token != null) {
                    checkAndClearTokenIfUnauthorized(response, token)
                }
                
                if (response.status.isSuccess()) {
                    val body = readBoundedResponse(response)
                    if (body != null && !body.trimStart().startsWith("<")) {
                        return@retryWithBackoff body
                    }
                }
                
                null
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private suspend fun findSyncBlob(token: String, name: String): String? = withContext(Dispatchers.Default) {
        try {
            val cleanName = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            val response = httpClient.get(driveBaseUrl) {
                parameter("q", "name = '$cleanName' and trashed = false")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            checkAndClearTokenIfUnauthorized(response, token)
            if (response.status == HttpStatusCode.Unauthorized) {
                throw Exception("Drive API returned 401 Unauthorized")
            }
            if (response.status.isSuccess()) {
                val fileList = response.body<DriveFileListAppFolder>()
                return@withContext fileList.files.firstOrNull()?.id
            }
            return@withContext null
        } catch (e: Exception) {
            if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                throw e
            }
            return@withContext null
        }
    }

    suspend fun deleteSyncBlob(): Boolean = withContext(Dispatchers.Default) {
        retryWithBackoff {
            try {
                val token = getAuthToken() ?: return@retryWithBackoff false
                val fileName = "sync_blob.enc"
                val fileId = findSyncBlob(token, fileName) ?: return@retryWithBackoff true // already gone
                
                val response = httpClient.delete("$driveBaseUrl/$fileId") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                checkAndClearTokenIfUnauthorized(response, token)
                return@retryWithBackoff response.status.isSuccess()
            } catch (e: Exception) {
                throw e
            }
        } ?: false
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
package com.novarytm.sync

import com.novarytm.auth.GoogleAuthManager
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    // L-4: Restricted appDataFolder scope
    private val scopes = listOf("https://www.googleapis.com/auth/drive.appdata")

    private suspend fun getAuthToken(): String? = authManager.getAccessToken(scopes)

    suspend fun pushSyncBlob(encryptedBase64: String, partnerEmail: String? = null): String? = withContext(Dispatchers.Default) {
        if (partnerEmail.isNullOrBlank()) {
            throw Exception("partnerEmail is missing; cannot share file securely.")
        }
        val token = getAuthToken() ?: throw Exception("getAuthToken returned null (Permissions not fully granted)")
        val fileName = "sync_blob.enc"
        val existingFileId = findSyncBlob(token, fileName)

        val fileIdToReturn: String

        if (existingFileId == null) {
            // Create
            @Serializable
            data class DriveFileMetadata(val name: String, val parents: List<String> = listOf("appDataFolder"))

            val createResponse = httpClient.post(driveBaseUrl) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(DriveFileMetadata(fileName))
            }
            if (!createResponse.status.isSuccess()) {
                throw Exception("Drive API CREATE failed: ${createResponse.status} - ${createResponse.bodyAsText()}")
            }
            
            @Serializable
            data class DriveFileCreated(val id: String)
            val newFile = createResponse.body<DriveFileCreated>()
            fileIdToReturn = newFile.id
            
            // Set Permissions to specific user email
            @Serializable
            data class DrivePermission(val type: String, val role: String, val emailAddress: String)

            val permResponse = httpClient.post("$driveBaseUrl/${newFile.id}/permissions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(DrivePermission("user", "reader", partnerEmail))
            }
            if (!permResponse.status.isSuccess()) {
                throw Exception("Drive API PERMISSION failed: ${permResponse.status} - ${permResponse.bodyAsText()}")
            }
            
            // Upload data
            val uploadResponse = httpClient.patch("$driveUploadUrl/${newFile.id}?uploadType=media") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Text.Plain)
                setBody(encryptedBase64)
            }
            if (!uploadResponse.status.isSuccess()) {
                throw Exception("Drive API UPLOAD failed: ${uploadResponse.status} - ${uploadResponse.bodyAsText()}")
            }
        } else {
            // Update
            fileIdToReturn = existingFileId
            val response = httpClient.patch("$driveUploadUrl/$existingFileId?uploadType=media") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Text.Plain)
                setBody(encryptedBase64)
            }
            if (!response.status.isSuccess()) {
                throw Exception("Drive API PATCH failed: ${response.status} - ${response.bodyAsText()}")
            }
        }
        
        return@withContext fileIdToReturn
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
            } catch (e: Exception) {
                // retry on failure
            }
            delay(delayMs)
            delayMs *= 2
        }
        return try { block() } catch (e: Exception) { null }
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
                if (response.status.isSuccess()) {
                    val contentLength = response.contentLength() ?: 0L
                    if (contentLength > 2 * 1024 * 1024) return@retryWithBackoff null
                    
                    val bytes = response.body<ByteArray>()
                    if (bytes.size > 2 * 1024 * 1024) return@retryWithBackoff null
                    return@retryWithBackoff bytes.decodeToString()
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
                val url = "https://drive.google.com/uc?export=download&id=$fileId"
                val response = httpClient.get(url) {
                    val token = getAuthToken()
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
                
                if (response.status.isSuccess()) {
                    val contentLength = response.contentLength() ?: 0L
                    if (contentLength > 2 * 1024 * 1024) return@retryWithBackoff null

                    val bytes = response.body<ByteArray>()
                    if (bytes.size > 2 * 1024 * 1024) return@retryWithBackoff null
                    val body = bytes.decodeToString()
                    
                    if (body.trimStart().startsWith("<")) {
                        return@retryWithBackoff "REVOKED"
                    }

                    return@retryWithBackoff body
                }
                
                if (response.status.value == 404 || response.status.value == 403) {
                    return@retryWithBackoff "REVOKED"
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
                parameter("spaces", "appDataFolder") // L-4: Search appDataFolder space
                parameter("q", "name = '$cleanName' and trashed = false")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (response.status.isSuccess()) {
                val fileList = response.body<DriveFileListAppFolder>()
                return@withContext fileList.files.firstOrNull()?.id
            }
            return@withContext null
        } catch (e: Exception) {
            return@withContext null
        }
    }

    suspend fun deleteSyncBlob(): Boolean = withContext(Dispatchers.Default) {
        try {
            val token = getAuthToken() ?: return@withContext false
            val fileName = "sync_blob.enc"
            val fileId = findSyncBlob(token, fileName) ?: return@withContext true // already gone
            
            val response = httpClient.delete("$driveBaseUrl/$fileId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            return@withContext response.status.isSuccess()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}
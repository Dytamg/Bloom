package com.bloom.sync

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GoogleDriveManagerTest {

    @Test
    fun testPushAndPullSyncBlob() {
        runBlocking {
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.contains("/files?q=") -> {
                        respond(
                            content = """{"files": [{"id": "file123", "name": "sync_blob.enc"}]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    url.contains("/files/file123?alt=media") && request.method == HttpMethod.Get -> {
                        respond(
                            content = "encrypted_sync_data",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain")
                        )
                    }
                    url.contains("/files/file123") && request.method == HttpMethod.Patch -> {
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    else -> respond("Not Found", HttpStatusCode.NotFound)
                }
            }

            val authProvider = object : DriveAuthProvider {
                override val needsInteractiveReauth: Boolean = false
                override suspend fun getAccessToken(scopes: List<String>): String? = "valid_token"
                override suspend fun clearToken(token: String) {}
                override suspend fun invalidateAndRefreshAccessToken(token: String): String? = "refreshed_token"
            }

            val manager = GoogleDriveManager(authProvider, mockEngine, retryBaseDelayMs = 10L)
            val fileId = manager.pushSyncBlob("hello".encodeToByteArray())
            assertEquals("file123", fileId)

            val pulled = manager.pullSyncBlob()
            assertEquals("encrypted_sync_data", pulled)
        }
    }

    @Test
    fun test401TokenRefreshRetry() {
        runBlocking {
            var attempts = 0
            val mockEngine = MockEngine { request ->
                attempts++
                if (attempts == 1) {
                    respond("Unauthorized", HttpStatusCode.Unauthorized)
                } else {
                    respond(
                        content = """{"files": [{"id": "file456", "name": "sync_blob.enc"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }

            val authProvider = object : DriveAuthProvider {
                override val needsInteractiveReauth: Boolean = false
                override suspend fun getAccessToken(scopes: List<String>): String? = "expired_token"
                override suspend fun clearToken(token: String) {}
                override suspend fun invalidateAndRefreshAccessToken(token: String): String? = "fresh_token"
            }

            val manager = GoogleDriveManager(authProvider, mockEngine, retryBaseDelayMs = 10L)
            val fileId = manager.findSyncBlob("expired_token")
            assertEquals("file456", fileId)
            assertTrue(attempts >= 2)
        }
    }

    @Test
    fun testStorageQuotaExceeded() {
        runBlocking {
            val mockEngine = MockEngine { request ->
                if (request.url.toString().contains("/about")) {
                    respond(
                        content = """{"storageQuota": {"limit": "1000", "usage": "999999"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond("Forbidden", HttpStatusCode.Forbidden)
                }
            }

            val authProvider = object : DriveAuthProvider {
                override val needsInteractiveReauth: Boolean = false
                override suspend fun getAccessToken(scopes: List<String>): String? = "token"
                override suspend fun clearToken(token: String) {}
                override suspend fun invalidateAndRefreshAccessToken(token: String): String? = "token"
            }

            val manager = GoogleDriveManager(authProvider, mockEngine, retryBaseDelayMs = 10L)
            assertFailsWith<DriveStorageFullException> {
                manager.pushSyncBlob("data".encodeToByteArray(), preferredFileId = "missing")
            }
        }
    }

    @Test
    fun testDownloadPublicSyncBlobHtmlRejection() {
        runBlocking {
            val mockEngine = MockEngine { request ->
                respond(
                    content = "<HTML>Sign in</HTML>",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/html")
                )
            }

            val authProvider = object : DriveAuthProvider {
                override val needsInteractiveReauth: Boolean = false
                override suspend fun getAccessToken(scopes: List<String>): String? = null
                override suspend fun clearToken(token: String) {}
                override suspend fun invalidateAndRefreshAccessToken(token: String): String? = null
            }

            val manager = GoogleDriveManager(authProvider, mockEngine, retryBaseDelayMs = 10L)
            assertFailsWith<DriveAuthRequiredException> {
                manager.downloadPublicSyncBlob("validFileId123")
            }
        }
    }

    @Test
    fun testQuerySanitization() {
        runBlocking {
            var requestedQuery = ""
            val mockEngine = MockEngine { request ->
                requestedQuery = request.url.parameters["q"] ?: ""
                respond(
                    content = """{"files": []}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val authProvider = object : DriveAuthProvider {
                override val needsInteractiveReauth: Boolean = false
                override suspend fun getAccessToken(scopes: List<String>): String? = "token"
                override suspend fun clearToken(token: String) {}
                override suspend fun invalidateAndRefreshAccessToken(token: String): String? = "token"
            }

            val manager = GoogleDriveManager(authProvider, mockEngine, retryBaseDelayMs = 10L)
            manager.findSyncBlob("token", "sync_blob.enc' OR 1=1 --")
            assertTrue(requestedQuery.contains("sync_blob.enc"))
            assertFalse(requestedQuery.contains("OR 1=1"))
        }
    }

    @Test
    fun testEnsureReaderPermissionDeduplicatedAcrossMultiplePushes() {
        runBlocking {
            var permissionPostCount = 0
            var patchCount = 0
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                when {
                    request.method == HttpMethod.Patch && url.contains("/upload/drive/v3/files/file-perm-1") -> {
                        patchCount++
                        respond("""{"id":"file-perm-1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    request.method == HttpMethod.Post && url.contains("/files/file-perm-1/permissions") -> {
                        permissionPostCount++
                        assertEquals("false", request.url.parameters["sendNotificationEmail"])
                        respond("""{"id":"perm-abc-1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    else -> respond("Not Found", HttpStatusCode.NotFound)
                }
            }

            val authProvider = object : DriveAuthProvider {
                override val needsInteractiveReauth: Boolean = false
                override suspend fun getAccessToken(scopes: List<String>): String? = "token"
                override suspend fun clearToken(token: String) {}
                override suspend fun invalidateAndRefreshAccessToken(token: String): String? = "token"
            }

            var cachedFileId: String? = "file-perm-1"
            var cachedEmail: String? = null
            var cachedPermId: String? = null
            val permissionCache = object : DrivePermissionCache {
                override fun getPrimarySyncFileId(): String? = cachedFileId
                override fun getGrantedPartnerEmail(): String? = cachedEmail
                override fun saveGrantedPartnerEmail(email: String?) { cachedEmail = email }
                override fun getPartnerPermissionId(): String? = cachedPermId
                override fun savePartnerPermissionId(permissionId: String?) { cachedPermId = permissionId }
            }

            val manager = GoogleDriveManager(authProvider, mockEngine, retryBaseDelayMs = 10L, permissionCache = permissionCache)
            manager.pushSyncBlob("blob1".encodeToByteArray(), preferredFileId = "file-perm-1", partnerEmail = "Partner@Example.com")
            manager.pushSyncBlob("blob2".encodeToByteArray(), preferredFileId = "file-perm-1", partnerEmail = "partner@example.com")
            manager.pushSyncBlob("blob3".encodeToByteArray(), preferredFileId = "file-perm-1", partnerEmail = "  partner@example.com ")

            assertEquals(3, patchCount)
            assertEquals(1, permissionPostCount, "POST /permissions must be called only once per (fileId, cleanEmail)")
            assertEquals("perm-abc-1", cachedPermId)
            assertEquals("Partner@Example.com", cachedEmail)
        }
    }

    @Test
    fun testRevokePartnerAccessDeletesPermissionByIdAndByListing() {
        runBlocking {
            val deleteUrls = mutableListOf<String>()
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                when {
                    request.method == HttpMethod.Delete && url.contains("/files/file-rev-1/permissions/") -> {
                        deleteUrls.add(url)
                        respond("", HttpStatusCode.NoContent)
                    }
                    request.method == HttpMethod.Get && url.contains("/files/file-rev-2/permissions") -> {
                        respond(
                            content = """{"permissions":[{"id":"owner-p","role":"owner"},{"id":"reader-p1","role":"reader"},{"id":"reader-p2","role":"reader"}]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    request.method == HttpMethod.Delete && url.contains("/files/file-rev-2/permissions/") -> {
                        deleteUrls.add(url)
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respond("Not Found", HttpStatusCode.NotFound)
                }
            }

            val authProvider = object : DriveAuthProvider {
                override val needsInteractiveReauth: Boolean = false
                override suspend fun getAccessToken(scopes: List<String>): String? = "token"
                override suspend fun clearToken(token: String) {}
                override suspend fun invalidateAndRefreshAccessToken(token: String): String? = "token"
            }

            val manager = GoogleDriveManager(authProvider, mockEngine, retryBaseDelayMs = 10L)
            assertTrue(manager.revokePartnerAccess("file-rev-1", "perm-99"))
            assertTrue(deleteUrls.any { it.endsWith("/files/file-rev-1/permissions/perm-99") })

            assertTrue(manager.revokePartnerAccess("file-rev-2", null))
            assertTrue(deleteUrls.any { it.endsWith("/files/file-rev-2/permissions/reader-p1") })
            assertTrue(deleteUrls.any { it.endsWith("/files/file-rev-2/permissions/reader-p2") })
            assertFalse(deleteUrls.any { it.endsWith("/files/file-rev-2/permissions/owner-p") })
        }
    }
}

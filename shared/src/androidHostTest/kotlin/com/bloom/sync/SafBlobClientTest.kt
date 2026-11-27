package com.bloom.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SafBlobClientTest {

    @Test
    fun testTruncateAndWriteToChannelOverwritesLongerFileWithoutTrailingBytes() {
        val tempFile = File.createTempFile("saf_truncate_test_", ".enc")
        try {
            val initialBytes = ByteArray(200) { 'A'.code.toByte() }
            FileOutputStream(tempFile).use { fos ->
                truncateAndWriteToChannel(fos, initialBytes, syncFd = null)
            }
            assertEquals(200L, tempFile.length())

            val shorterBytes = ByteArray(50) { 'B'.code.toByte() }
            FileOutputStream(tempFile, true).use { fos ->
                truncateAndWriteToChannel(fos, shorterBytes, syncFd = null)
            }
            assertEquals(50L, tempFile.length(), "Overwriting 200-byte file with 50 bytes must leave exact 50 bytes")
            assertEquals(shorterBytes.toList(), tempFile.readBytes().toList())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testPushAndPullShrinkingOverwriteBehavior() {
        val tempFile = File.createTempFile("saf_shrink_test_", ".enc")
        try {
            val largeData = ByteArray(512) { 'X'.code.toByte() }
            tempFile.writeBytes(largeData)
            assertEquals(512L, tempFile.length())

            val smallData = ByteArray(32) { 'Y'.code.toByte() }
            FileOutputStream(tempFile).use { fos ->
                truncateAndWriteToChannel(fos, smallData, syncFd = null)
            }
            assertEquals(32L, tempFile.length())
            assertEquals(smallData.toList(), tempFile.readBytes().toList())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testOversizedBlobStreamRejection() {
        val tempFile = File.createTempFile("saf_oversize_test_", ".enc")
        try {
            val oversized = ByteArray(MAX_SAF_BLOB_BYTES + 1024) { 'Z'.code.toByte() }
            tempFile.writeBytes(oversized)

            tempFile.inputStream().use { input ->
                assertFailsWith<IOException> {
                    readBoundedSafStream(input, MAX_SAF_BLOB_BYTES)
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testInvalidUriSchemeRejection() {
        assertFailsWith<SafAccessLostException> {
            validateSafUriSchemeAndAuthority("file:///etc/passwd", "com.bloom.android")
        }
        assertFailsWith<SafAccessLostException> {
            validateSafUriSchemeAndAuthority("http://example.com/sync", "com.bloom.android")
        }
    }

    @Test
    fun testConfusedDeputySelfAuthorityUriRejection() {
        assertFailsWith<SafAccessLostException> {
            validateSafUriSchemeAndAuthority("content://com.bloom.android.fileprovider/root/sync", "com.bloom.android")
        }
        assertFailsWith<SafAccessLostException> {
            validateSafUriSchemeAndAuthority("content://com.bloom.android/root/sync", "com.bloom.android")
        }
        assertFailsWith<SafAccessLostException> {
            validateSafUriSchemeAndAuthority("content://com.bloom.android.subprovider/sync", "com.bloom.android")
        }
    }

    @Test
    fun testDeleteSyncBlobGracefulHandling() {
        val result = runCatching {
            deleteSafUriSafely(null)
        }
        assertEquals(true, result.isSuccess)
    }
}

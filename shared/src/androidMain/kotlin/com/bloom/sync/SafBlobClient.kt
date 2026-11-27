package com.bloom.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_SAF_BLOB_BYTES = 5 * 1024 * 1024

internal fun truncateAndWriteToChannel(
    fos: FileOutputStream,
    data: ByteArray,
    syncFd: (() -> Unit)? = { fos.fd.sync() }
) {
    val channel = fos.channel
    channel.truncate(0L)
    channel.position(0L)
    fos.write(data)
    fos.flush()
    if (syncFd != null) {
        runCatching { syncFd() }
    }
}

internal fun validateSafUriSchemeAndAuthority(rawUri: String, packageName: String) {
    val androidUri = runCatching { Uri.parse(rawUri) }.getOrNull()
    val (scheme, auth) = if (androidUri != null) {
        androidUri.scheme to androidUri.authority
    } else {
        val jvmUri = runCatching { java.net.URI.create(rawUri) }.getOrNull()
            ?: throw SafAccessLostException("Malformed sync file URI.")
        jvmUri.scheme to jvmUri.authority
    }
    if (scheme != ContentResolver.SCHEME_CONTENT) {
        throw SafAccessLostException("Invalid sync file URI scheme: $scheme")
    }
    if (auth.isNullOrBlank() || auth == packageName || auth.startsWith("$packageName.")) {
        throw SafAccessLostException("Untrusted or non-content SAF URI authority: $auth")
    }
}

internal fun readBoundedSafStream(input: InputStream, maxBlobBytes: Int): ByteArray {
    val buffer = ByteArray(8192)
    var total = 0
    val output = java.io.ByteArrayOutputStream()
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBlobBytes) {
            throw IOException("SAF sync blob exceeds max allowed size ($maxBlobBytes bytes)")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun deleteSafUriSafely(rawUri: String?): Boolean {
    if (!rawUri.isNullOrEmpty()) {
        runCatching {
            Uri.parse(rawUri)
        }
    }
    return true
}

class SafBlobClient(
    private val context: Context,
    private val maxBlobBytes: Int = MAX_SAF_BLOB_BYTES,
    private val uriProvider: () -> String?
) : DriveBlobClient {

    constructor(context: Context, uriProvider: () -> String?) : this(context, MAX_SAF_BLOB_BYTES, uriProvider)

    private fun resolveValidatedUri(requireWrite: Boolean): Uri {
        val rawUri = uriProvider()?.trim()
        if (rawUri.isNullOrEmpty()) {
            throw SafAccessLostException("Sync file URI is not configured.")
        }
        validateSafUriSchemeAndAuthority(rawUri, context.packageName)
        val uri = Uri.parse(rawUri)
        val matched = findPersistedPermission(uri)
        val hasAccess = if (requireWrite) matched.isWritePermission else matched.isReadPermission
        if (!hasAccess) {
            throw SafAccessLostException("Missing required sync file permission.")
        }
        return uri
    }

    private fun findPersistedPermission(uri: Uri): android.content.UriPermission {
        val permissions = try {
            context.contentResolver.persistedUriPermissions
        } catch (e: SecurityException) {
            throw SafAccessLostException("Unable to inspect persisted sync file permissions.", e)
        }
        return permissions.firstOrNull { it.uri == uri }
            ?: throw SafAccessLostException("Persisted sync file permission was lost.")
    }

    override suspend fun pushSyncBlob(
        encryptedData: ByteArray,
        preferredFileId: String?,
        partnerEmail: String?,
        onPreferredFileNotFound: (() -> Unit)?
    ): String? = withContext(Dispatchers.IO) {
        val uri = resolveValidatedUri(requireWrite = true)
        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw SafAccessLostException("Unable to open sync file descriptor for writing.")
            descriptor.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    truncateAndWriteToChannel(fos, encryptedData) {
                        pfd.fileDescriptor.sync()
                    }
                }
            }
            SAF_SYNC_TARGET_ID
        } catch (e: SafAccessLostException) {
            throw e
        } catch (e: SecurityException) {
            throw SafAccessLostException("Sync file write permission was revoked.", e)
        } catch (e: FileNotFoundException) {
            throw SafAccessLostException("Sync file is missing or inaccessible.", e)
        }
    }

    override suspend fun pullSyncBlob(): String? = withContext(Dispatchers.IO) {
        val uri = resolveValidatedUri(requireWrite = false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { context.contentResolver.refresh(uri, null, null) }
            }
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw SafAccessLostException("Unable to open sync file for reading.")
            stream.use { input ->
                val bytes = readBoundedSafStream(input, maxBlobBytes)
                if (bytes.isEmpty()) null else bytes.decodeToString()
            }
        } catch (e: SafAccessLostException) {
            throw e
        } catch (e: SecurityException) {
            throw SafAccessLostException("Sync file read permission was revoked.", e)
        } catch (e: FileNotFoundException) {
            throw SafAccessLostException("Sync file is missing or inaccessible.", e)
        } catch (e: IOException) {
            if (e.message?.contains("exceeds max allowed size") == true) {
                throw IllegalStateException(e.message, e)
            }
            throw e
        }
    }

    override suspend fun downloadPublicSyncBlob(fileId: String): String? = pullSyncBlob()

    override suspend fun deleteSyncBlob(): Boolean = withContext(Dispatchers.IO) {
        val rawUri = uriProvider()?.trim()
        if (!rawUri.isNullOrEmpty()) {
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(rawUri))
            }
        }
        true
    }
}

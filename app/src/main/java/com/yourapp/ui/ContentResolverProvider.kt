package com.yourapp.yamahaarranger.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentResolverProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sf2RelativePath = Environment.DIRECTORY_DOWNLOADS + "/YamahaArranger/SF2/"

    fun readBytes(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        Timber.e(e, "Failed reading $uri")
        null
    }

    fun fileName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed querying name for $uri")
        null
    }

    fun openInputStream(uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    fun getFilesDir(): File = context.filesDir

    fun getSoundFontLocation(): String =
        "Download/YamahaArranger/SF2"

    /**
     * Shared, user-visible SF2 storage.
     *
     * Android 10+ uses MediaStore.Downloads so the file appears in the normal
     * Download folder without broad storage permission. Older Android versions
     * fall back to the public Download directory.
     */
    fun ensureSoundFontFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val exists = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.RELATIVE_PATH + "=? AND " +
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                arrayOf(sf2RelativePath, "PUT_SF2_FILES_HERE.txt"),
                null
            )?.use { it.moveToFirst() } == true
            if (!exists) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "PUT_SF2_FILES_HERE.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, sf2RelativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    resolver.openOutputStream(uri)?.use {
                        it.write("Place your SoundFont .sf2 files in this folder.\n".toByteArray())
                    }
                    val done = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                }
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "YamahaArranger/SF2"
            )
            dir.mkdirs()
            File(dir, "PUT_SF2_FILES_HERE.txt").takeIf { !it.exists() }?.writeText(
                "Place your SoundFont .sf2 files in this folder.\n"
            )
        }
    }

    fun saveSoundFont(uri: Uri, displayName: String): Uri? {
        val safeName = displayName.substringAfterLast('/').ifBlank { "font.sf2" }
            .let { if (it.lowercase().endsWith(".sf2")) it else "$it.sf2" }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val existing = findSoundFont(safeName)?.first
            if (existing != null) resolver.delete(existing, null, null)

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, sf2RelativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                val copied = resolver.openInputStream(uri)?.use { input ->
                    resolver.openOutputStream(outUri)?.use { output ->
                        input.copyTo(output)
                        true
                    } ?: false
                } ?: false
                if (!copied) {
                    resolver.delete(outUri, null, null)
                    null
                } else {
                    val done = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(outUri, done, null, null)
                    outUri
                }
            } catch (e: Exception) {
                resolver.delete(outUri, null, null)
                Timber.e(e, "Failed storing SF2 in shared Downloads")
                null
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "YamahaArranger/SF2"
            ).apply { mkdirs() }
            val dest = File(dir, safeName)
            try {
                openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                return if (dest.length() > 0) Uri.fromFile(dest) else null
            } catch (e: Exception) {
                Timber.e(e, "Failed storing legacy SF2")
                null
            }
        }
    }

    /**
     * Finds an SF2 created by YamahaArranger in the public folder.
     * Returns the Uri and display name.
     */
    fun findSoundFont(displayName: String? = null): Pair<Uri, String>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.RELATIVE_PATH
            )
            val selection = buildString {
                append(MediaStore.Downloads.RELATIVE_PATH)
                append("=? AND ")
                append(MediaStore.Downloads.DISPLAY_NAME)
                append(" LIKE ?")
                if (displayName != null) {
                    append(" AND ")
                    append(MediaStore.Downloads.DISPLAY_NAME)
                    append("=?")
                }
            }
            val args = if (displayName != null) {
                arrayOf(sf2RelativePath, "%.sf2", displayName)
            } else {
                arrayOf(sf2RelativePath, "%.sf2")
            }
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                MediaStore.Downloads.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    return MediaStore.Downloads.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        id
                    ) to name
                }
            }
            return null
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YamahaArranger/SF2"
        )
        val file = if (displayName != null) File(dir, displayName)
        else dir.listFiles { f -> f.isFile && f.extension.equals("sf2", true) }
            ?.sortedBy { it.name.lowercase() }?.firstOrNull()
        return file?.takeIf { it.exists() && it.length() > 0 }?.let {
            Uri.fromFile(it) to it.name
        }
    }

    fun copySoundFontToCache(uri: Uri, displayName: String): File? {
        val dir = File(context.cacheDir, "sf2").apply { mkdirs() }
        val dest = File(dir, displayName)
        return try {
            openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            Timber.e(e, "Failed caching SF2")
            null
        }
    }
}

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
                try {
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                        resolver.openOutputStream(uri)?.use {
                            it.write("Place your SoundFont .sf2 files in this folder.\n".toByteArray())
                        }
                        val done = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        resolver.update(uri, done, null, null)
                    }
                } catch (e: IllegalStateException) {
                    // Some Android/MediaStore builds can report an existing
                    // marker as absent and then reject a duplicate insert.
                    // The marker is only informational; never abort SF2 startup.
                    Timber.w(e, "SF2 folder marker already exists or cannot be created")
                } catch (e: Exception) {
                    Timber.w(e, "Could not create SF2 folder marker")
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

    /** True when the URI already points at Download/YamahaArranger/SF2. */
    fun isSoundFontInManagedFolder(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    cursor.moveToFirst() && index >= 0 &&
                        cursor.getString(index)?.equals(sf2RelativePath, ignoreCase = true) == true
                } ?: false
            } catch (e: Exception) {
                Timber.w(e, "Could not determine SF2 source folder")
                false
            }
        }
        val path = uri.path ?: return false
        val managed = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YamahaArranger/SF2"
        ).canonicalPath
        return try {
            File(path).canonicalPath.startsWith(managed + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    fun saveSoundFont(uri: Uri, displayName: String): Uri? {
        val safeName = displayName.substringAfterLast('/').ifBlank { "font.sf2" }
            .let { if (it.lowercase().endsWith(".sf2")) it else "$it.sf2" }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            findSoundFont(safeName)?.first?.let { existing ->
                Timber.i("SF2 already stored, reusing: $safeName")
                return existing
            }

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
        val wantedName = displayName?.trim()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver

            // First search the Downloads collection. This covers files created
            // through MediaStore as well as normal files indexed by Android.
            fun queryCollection(uri: Uri, projection: Array<String>, selection: String, args: Array<String>): Pair<Uri, String>? {
                return try {
                    resolver.query(uri, projection, selection, args, "date_modified DESC")?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.Downloads._ID)
                        val nameCol = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                        if (idCol >= 0 && nameCol >= 0 && cursor.moveToFirst()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol)
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id) to name
                        } else null
                    }
                } catch (e: Exception) {
                    Timber.w(e, "SF2 MediaStore Downloads query failed")
                    null
                }
            }

            val selection = buildString {
                append(MediaStore.Downloads.RELATIVE_PATH)
                append(" LIKE ? AND ")
                append(MediaStore.Downloads.DISPLAY_NAME)
                append(" LIKE ?")
                if (wantedName != null) {
                    append(" AND ")
                    append(MediaStore.Downloads.DISPLAY_NAME)
                    append("=?")
                }
            }
            val args = if (wantedName != null) {
                arrayOf("%/YamahaArranger/SF2/%", "%.sf2", wantedName)
            } else {
                arrayOf("%/YamahaArranger/SF2/%", "%.sf2")
            }
            queryCollection(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                selection,
                args
            )?.let { return it }

            // Fallback: some file managers place the file in Downloads but the
            // Downloads collection is not populated immediately. Search the
            // general MediaStore file index as well.
            try {
                val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME)
                val selectionFiles = buildString {
                    append(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    append(" LIKE ? AND ")
                    append(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    append(" LIKE ?")
                    if (wantedName != null) {
                        append(" AND ")
                        append(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        append("=?")
                    }
                }
                val argsFiles = if (wantedName != null) {
                    arrayOf("%/YamahaArranger/SF2/%", "%.sf2", wantedName)
                } else {
                    arrayOf("%/YamahaArranger/SF2/%", "%.sf2")
                }
                resolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    projection,
                    selectionFiles,
                    argsFiles,
                    MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id) to name
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "SF2 MediaStore Files fallback failed")
            }
            return null
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YamahaArranger/SF2"
        )
        val file = if (wantedName != null) File(dir, wantedName)
        else dir.listFiles { f -> f.isFile && f.extension.equals("sf2", true) }
            ?.sortedByDescending { it.lastModified() }?.firstOrNull()
        return file?.takeIf { it.exists() && it.length() > 0 }?.let {
            Uri.fromFile(it) to it.name
        }
    }

    fun listSoundFonts(): List<Pair<Uri, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YamahaArranger/SF2")
            return dir.listFiles { f -> f.isFile && f.extension.equals("sf2", true) }
                ?.sortedBy { it.name.lowercase() }
                ?.map { Uri.fromFile(it) to it.name }
                ?: emptyList()
        }
        return try {
            val resolver = context.contentResolver
            val result = mutableListOf<Pair<Uri, String>>()
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.RELATIVE_PATH
            )

            // MediaStore.Downloads is the reliable public-storage index for
            // Android 10+. Query it directly instead of relying on the broad
            // Files collection, which can be filtered on some Android builds.
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND " +
                    MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                arrayOf("%.sf2", "%/YamahaArranger/SF2/%"),
                MediaStore.Downloads.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    result += MediaStore.Downloads.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY, id
                    ) to name
                }
            }

            // Also auto-discover SF2 files placed directly in Download.
            if (result.isEmpty()) {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND " +
                        MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                    arrayOf("%.sf2", "%Download/%"),
                    MediaStore.Downloads.DISPLAY_NAME + " COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        result += MediaStore.Downloads.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY, id
                        ) to name
                    }
                }
            }

            // Final fallback for devices/file managers that expose SF2 only
            // through the generic Files index.
            if (result.isEmpty()) {
                val projectionFiles = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME
                )
                resolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    projectionFiles,
                    MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? AND " +
                        MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?",
                    arrayOf("%Download/%", "%.sf2"),
                    MediaStore.Files.FileColumns.DISPLAY_NAME + " COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        result += MediaStore.Files.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY, id
                        ) to name
                    }
                }
            }

            result
        } catch (e: Exception) {
            Timber.w(e, "SF2 list query failed")
            emptyList()
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

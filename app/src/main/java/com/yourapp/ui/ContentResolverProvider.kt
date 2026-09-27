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

data class StyleFolder(val uri: Uri, val name: String, val styleCount: Int)

@Singleton
class ContentResolverProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sf2RootDir: File
        get() = File(Environment.getExternalStorageDirectory(), "YamahaArranger/SF2")

    private val styleRootDir: File
        get() = File(Environment.getExternalStorageDirectory(), "YamahaArranger/Styles")

    fun ensureStyleFolder() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Timber.w("YamahaArranger Styles folder requires MANAGE_EXTERNAL_STORAGE")
                return
            }
            styleRootDir.mkdirs()
        } catch (e: Exception) {
            Timber.w(e, "Could not create YamahaArranger/Styles folder")
        }
    }

    /**
     * Scan YamahaArranger/Styles without FileTreeWalk's silent SKIP behavior.
     *
     * On Android 11+ a directory can be visible while child enumeration is
     * blocked by scoped-storage permissions. FileTreeWalk silently skips an
     * unreadable directory, which made the UI report "0" with no useful clue.
     * An explicit stack lets us log exactly where enumeration stops.
     */
    private fun styleFilesUnder(dir: File): List<File> {
        if (!dir.isDirectory) {
            Timber.w("🎼 Style scan: not a directory: \u0024{dir.absolutePath}")
            return emptyList()
        }

        val result = mutableListOf<File>()
        val pending = ArrayDeque<File>()
        pending.addLast(dir)

        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val children = try {
                current.listFiles()
            } catch (e: SecurityException) {
                Timber.e(e, "🎼 Style scan denied: \u0024{current.absolutePath}")
                null
            }

            if (children == null) {
                Timber.w(
                    "🎼 Style scan cannot enumerate: path=\u0024{current.absolutePath} " +
                        "exists=\u0024{current.exists()} dir=\u0024{current.isDirectory} canRead=\u0024{current.canRead()}"
                )
                continue
            }

            for (child in children) {
                if (child.isDirectory) {
                    pending.addLast(child)
                } else if (child.isFile && child.name.endsWith(".sty", ignoreCase = true)) {
                    result += child
                }
            }
        }

        if (result.isNotEmpty()) return result

        // Fallback for devices where direct File.listFiles() cannot enumerate
        // shared-storage children reliably. MANAGE_EXTERNAL_STORAGE also
        // permits access to MediaStore.Files.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            Environment.isExternalStorageManager()
        ) {
            val prefix = styleRootDir.absolutePath.trimEnd('/') + "/"
            val mediaUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val mediaResult = mutableListOf<File>()

            try {
                context.contentResolver.query(
                    mediaUri,
                    projection,
                    "\u0024{MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("%.sty"),
                    null
                )?.use { cursor ->
                    val dataIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                    while (cursor.moveToNext()) {
                        if (dataIndex < 0 || cursor.isNull(dataIndex)) continue
                        val path = cursor.getString(dataIndex)
                        val file = File(path)
                        if (path.startsWith(prefix) &&
                            file.absolutePath.startsWith(dir.absolutePath.trimEnd('/') + "/") &&
                            file.isFile &&
                            file.name.endsWith(".sty", ignoreCase = true)
                        ) {
                            mediaResult += file
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "🎼 MediaStore Style fallback failed for \u0024{dir.absolutePath}")
            }

            if (mediaResult.isNotEmpty()) {
                Timber.i(
                    "🎼 MediaStore Style fallback found \u0024{mediaResult.size} files in \u0024{dir.absolutePath}"
                )
                return mediaResult.distinctBy { it.absolutePath }
            }
        }

        return result
    }

    fun listStyleFolders(): List<StyleFolder> {
        ensureStyleFolder()
        val allFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val children = styleRootDir.listFiles()
        Timber.i(
            "🎼 Style root: path=\u0024{styleRootDir.absolutePath} exists=\u0024{styleRootDir.exists()} " +
                "dir=\u0024{styleRootDir.isDirectory} canRead=\u0024{styleRootDir.canRead()} " +
                "allFiles=\u0024allFiles children=\u0024{children?.size ?: -1}"
        )
        return children
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.map { dir ->
                val count = styleFilesUnder(dir).size
                Timber.i(
                    "🎼 Style category: \u0024{dir.name} path=\u0024{dir.absolutePath} " +
                        "canRead=\u0024{dir.canRead()} styCount=\u0024count"
                )
                StyleFolder(
                    uri = Uri.fromFile(dir),
                    name = dir.name,
                    styleCount = count
                )
            }
            ?: emptyList()
    }

    fun listStyles(): List<Pair<Uri, String>> {
        ensureStyleFolder()
        return styleRootDir.listFiles { f -> f.isFile && f.extension.equals("sty", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Uri.fromFile(it) to it.name }
            ?: emptyList()
    }

    fun listStylesInFolder(folderUri: Uri): List<Pair<Uri, String>> {
        return try {
            val path = folderUri.path ?: return emptyList()
            val dir = File(path)
            val files = styleFilesUnder(dir)
                .sortedBy { it.name.lowercase() }
            Timber.i(
                "🎼 Style folder open: uri=\u0024folderUri path=\u0024{dir.absolutePath} " +
                    "exists=\u0024{dir.exists()} dir=\u0024{dir.isDirectory} canRead=\u0024{dir.canRead()} " +
                    "styCount=\u0024{files.size}"
            )
            files.map { Uri.fromFile(it) to it.name }
        } catch (e: Exception) {
            Timber.e(e, "Failed listing styles in $folderUri")
            emptyList()
        }
    }

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
        "/storage/emulated/0/YamahaArranger/SF2"

    /**
     * Shared, user-visible SF2 storage.
     *
     * Android 10+ uses MediaStore.Downloads so the file appears in the normal
     * Download folder without broad storage permission. Older Android versions
     * fall back to the public Download directory.
     */
    fun ensureSoundFontFolder() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Timber.w("YamahaArranger root folder requires MANAGE_EXTERNAL_STORAGE")
                return
            }
            sf2RootDir.mkdirs()
            File(sf2RootDir, "PUT_SF2_FILES_HERE.txt").takeIf { !it.exists() }?.writeText(
                "Place your SoundFont .sf2 files in this folder.\n"
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not create YamahaArranger/SF2 folder")
        }
    }

    /** True when the URI already points at /storage/emulated/0/YamahaArranger/SF2. */
    fun isSoundFontInManagedFolder(uri: Uri): Boolean {
        return try {
            val target = when (uri.scheme) {
                "file" -> File(uri.path ?: return false).canonicalPath
                else -> uri.path ?: return false
            }
            target.contains("/YamahaArranger/SF2", ignoreCase = true)
        } catch (_: Exception) { false }
    }
    @Synchronized
    fun saveSoundFont(uri: Uri, displayName: String): Uri? {
        val safeName = displayName.substringAfterLast('/').ifBlank { "font.sf2" }
            .let { if (it.lowercase().endsWith(".sf2")) it else it + ".sf2" }
        return try {
            ensureSoundFontFolder()
            if (!sf2RootDir.exists()) return null
            val dest = File(sf2RootDir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (dest.length() > 0) Uri.fromFile(dest) else null
        } catch (e: Exception) {
            Timber.e(e, "Failed storing SF2 in /storage/emulated/0/YamahaArranger/SF2")
            null
        }
    }
    /**
     * Finds an SF2 created by YamahaArranger in the public folder.
     * Returns the Uri and display name.
     */
    fun findSoundFont(displayName: String? = null): Pair<Uri, String>? {
        ensureSoundFontFolder()
        val wantedName = displayName?.trim()
        val file = if (wantedName != null) File(sf2RootDir, wantedName)
        else sf2RootDir.listFiles { f -> f.isFile && f.extension.equals("sf2", true) }
            ?.sortedByDescending { it.lastModified() }?.firstOrNull()
        return file?.takeIf { it.exists() && it.length() > 0 }?.let { Uri.fromFile(it) to it.name }
    }

    fun listSoundFonts(): List<Pair<Uri, String>> {
        ensureSoundFontFolder()
        return sf2RootDir.listFiles { f -> f.isFile && f.extension.equals("sf2", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Uri.fromFile(it) to it.name }
            ?: emptyList()
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

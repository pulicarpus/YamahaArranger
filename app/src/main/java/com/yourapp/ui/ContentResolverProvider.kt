package com.yourapp.yamahaarranger.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
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

    /**
     * App-owned SF2 directory. Android creates the parent app-specific external
     * storage area as needed; no broad storage permission is required.
     */
    fun getSoundFontDir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(base, "YamahaArranger/SF2").apply { mkdirs() }
    }

    fun firstSoundFontFile(): File? {
        val dir = getSoundFontDir()
        return dir.listFiles { file ->
            file.isFile && file.extension.equals("sf2", ignoreCase = true) && file.length() > 0
        }?.sortedBy { it.name.lowercase() }?.firstOrNull()
    }
}
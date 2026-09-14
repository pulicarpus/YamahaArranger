package com.yourapp.yamahaarranger.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around ContentResolver so ViewModels don't need a raw
 * Context injected (keeps them off Android framework classes for testing)
 * — used by the "Import style from storage" flow (Storage Access
 * Framework document picker).
 */
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
        Timber.e(e, "Failed querying display name for $uri")
        null
    }
}

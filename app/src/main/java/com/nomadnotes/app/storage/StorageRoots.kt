package com.nomadnotes.app.storage

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Resolves the directory that holds every NomadNotes notebook, trading reach for reliability.
 *
 * Two roots are possible and the choice is made from the current permission state:
 *  - **All-Files granted** ([Environment.isExternalStorageManager] is true): a top-level
 *    `NomadNotes/` folder on shared storage. Notebooks are then visible to file managers, survive
 *    an app uninstall, and the user can back them up or sync them.
 *  - **Not granted**: the app's own external files directory. This always works without any
 *    permission, so the app stays functional; the cost is that notebooks live in app-private
 *    storage and are removed when the app is uninstalled.
 *
 * The app is expected to keep asking for All-Files access, but must never depend on it: this
 * fallback is what lets the editor open and save on the very first run, before the user has
 * granted anything. Switching a notebook's storage between the two roots when the permission later
 * changes is deliberately out of scope here.
 */
fun notebooksRoot(context: Context): File {
    val base = if (Environment.isExternalStorageManager()) {
        // Deprecated, but still the correct base for a MANAGE_EXTERNAL_STORAGE app addressing
        // shared storage by absolute path.
        @Suppress("DEPRECATION")
        Environment.getExternalStorageDirectory()
    } else {
        // Null only when external storage is unavailable (e.g. a removed SD card); fall back to
        // internal storage so a root can always be resolved.
        context.getExternalFilesDir(null) ?: context.filesDir
    }
    return File(base, "NomadNotes")
}

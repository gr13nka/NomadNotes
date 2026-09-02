package com.nomadnotes.app.storage

import android.content.Context
import com.nomadnotes.core.ink.SmoothingLevel

/**
 * The editor's persistent settings — the few choices that should outlive a session, as opposed to the
 * per-session tool state the editor keeps in memory.
 *
 * Deliberately small and typed: callers read and write ordinary Kotlin values and never learn the
 * preferences file, the key names, or how a value is encoded. A value that cannot be understood —
 * absent, or written by a build that spelled it differently — reads back as the documented default
 * rather than failing, so a settings file can never stop the editor from opening.
 */
class EditorPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * How much a finished stroke is smoothed before it becomes ink.
     * Defaults to [SmoothingLevel.OFF], so the app inks exactly what was drawn until asked otherwise.
     */
    var smoothing: SmoothingLevel
        get() {
            val stored = prefs.getString(KEY_SMOOTHING, null) ?: return SmoothingLevel.OFF
            return SmoothingLevel.entries.firstOrNull { it.name == stored } ?: SmoothingLevel.OFF
        }
        set(value) {
            prefs.edit().putString(KEY_SMOOTHING, value.name).apply()
        }

    private companion object {
        const val FILE_NAME = "editor-prefs"
        const val KEY_SMOOTHING = "smoothing"
    }
}

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
     * Defaults to [SmoothingLevel.AUTO], which derives a strength from each stroke, so there is
     * nothing left to tune by hand.
     *
     * Read back by name, with an unreadable value falling back to the default: a file written by an
     * older build (e.g. [SmoothingLevel.LIGHT] or [SmoothingLevel.STRONG]) still loads as itself, and
     * a name no build of this app ever wrote degrades to the default instead of failing.
     */
    var smoothing: SmoothingLevel
        get() {
            val stored = prefs.getString(KEY_SMOOTHING, null) ?: return SmoothingLevel.AUTO
            return SmoothingLevel.entries.firstOrNull { it.name == stored } ?: SmoothingLevel.AUTO
        }
        set(value) {
            prefs.edit().putString(KEY_SMOOTHING, value.name).apply()
        }

    private companion object {
        const val FILE_NAME = "editor-prefs"
        const val KEY_SMOOTHING = "smoothing"
    }
}

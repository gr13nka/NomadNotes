package com.nomadnotes.app.render

/**
 * A parsed [com.nomadnotes.core.Page.templateRef]: which background stationery a page wants.
 *
 * The stored ref is a short string so it survives a JSON roundtrip and stays human-readable
 * ("builtin:grid", "user:sketch.png"). This type is the parsed form of that string, kept pure
 * (no Android types) so the parsing is unit-testable on the JVM; turning a ref into actual pixels
 * is [TemplateResolver]'s job.
 *
 * The three built-ins are drawn in code; [UserImage] names a file in the notebooks' `templates/`
 * directory. A `null` ref (a fresh page) and the explicit "builtin:blank" both mean [Blank].
 */
sealed interface TemplateRef {

    /** No stationery: the page is plain white. Also the meaning of a null stored ref. */
    data object Blank : TemplateRef

    /** Evenly spaced horizontal rules, drawn in code. */
    data object Lines : TemplateRef

    /** A square grid, drawn in code. */
    data object Grid : TemplateRef

    /** A user-supplied image [filename] in the `templates/` directory (extension included). */
    data class UserImage(val filename: String) : TemplateRef

    companion object {
        const val BLANK = "builtin:blank"
        const val LINES = "builtin:lines"
        const val GRID = "builtin:grid"
        const val USER_PREFIX = "user:"

        /** The stored-string form of this ref, the inverse of [parse] for every non-null result. */
        fun toRef(template: TemplateRef): String = when (template) {
            Blank -> BLANK
            Lines -> LINES
            Grid -> GRID
            is UserImage -> USER_PREFIX + template.filename
        }

        /**
         * Parses a stored [ref] into a [TemplateRef], or returns null when the string is not one
         * this version understands (so the caller can fall back to a blank page and log it). A null
         * [ref] is a blank page and parses to [Blank]; a `user:` ref with an empty filename is
         * unknown (null).
         */
        fun parse(ref: String?): TemplateRef? = when {
            ref == null -> Blank
            ref == BLANK -> Blank
            ref == LINES -> Lines
            ref == GRID -> Grid
            ref.startsWith(USER_PREFIX) -> {
                val filename = ref.removePrefix(USER_PREFIX)
                if (filename.isEmpty()) null else UserImage(filename)
            }
            else -> null
        }
    }
}

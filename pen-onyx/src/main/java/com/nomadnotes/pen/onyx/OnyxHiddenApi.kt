package com.nomadnotes.pen.onyx

import android.util.Log
import java.lang.reflect.Method

/**
 * Lifts Android's hidden-API enforcement off the device's `android.onyx.*` system handwriting
 * classes for this process.
 *
 * onyxsdk-pen reaches those classes reflectively — `EpdController` calls into
 * `android.onyx.handwriting.*` and `android.onyx.ViewUpdateHelper` to map the pen's digitizer
 * region onto the surface. Under a modern `targetSdk` those reflective calls are denied
 * ("blocked, reflection, denied"), the region maps to empty ("RawInputReader: Empty region
 * detected"), and the panel never captures any pen input — so no ink appears at all. Boox's own
 * apps are exempt via the platform; a third-party app must exempt itself.
 *
 * This must run once, at process start, before any Onyx SDK class is used. The call to
 * `VMRuntime.setHiddenApiExemptions` is itself a restricted API, so it is reached through double
 * reflection: the `Method` objects are looked up via a reflective `Class.getDeclaredMethod`, whose
 * immediate caller is the platform's reflection machinery rather than this app, so the lookup is
 * not subject to the hidden-API check. Deliberately references no Onyx type, so loading it cannot
 * trigger `EpdController`'s static initializer before the exemption is in place.
 *
 * NOTE: this pure-reflection bypass only works up to Android 11 (API 30). Android 12 (API 31)
 * closed the `Method.invoke` loophole, so on API 31+ the lookup throws and this is a no-op — a
 * device on that API needs a native trampoline (e.g. the FreeReflection library), a newer
 * onyxsdk-pen that self-exempts, or a device-side exemption. See the spike notes.
 */
object OnyxHiddenApi {

    private const val TAG = "OnyxHiddenApi"

    /** Descriptor prefix of the Onyx system namespace the pen SDK reflects into. */
    private val EXEMPT_PREFIXES = arrayOf("Landroid/onyx/")

    fun exemptOnyxSystemClasses() {
        try {
            val classArrayType = arrayOfNulls<Class<*>>(0).javaClass
            val stringArrayType = arrayOfNulls<String>(0).javaClass

            val getDeclaredMethod = Class::class.java
                .getDeclaredMethod("getDeclaredMethod", String::class.java, classArrayType)
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)

            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod
                .invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setHiddenApiExemptions = getDeclaredMethod
                .invoke(vmRuntimeClass, "setHiddenApiExemptions", arrayOf(stringArrayType)) as Method

            val vmRuntime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(vmRuntime, EXEMPT_PREFIXES)
        } catch (t: Throwable) {
            // Expected on API 31+ (the bypass is closed there); raw drawing then stays blocked
            // until a native/newer-SDK exemption is in place. Must never crash the app.
            Log.w(TAG, "Onyx hidden-API exemption unavailable on this OS: ${t.message}")
        }
    }
}

package com.amar.vault.agent

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Global holder for the currently-resumed Activity.
 *
 * Why this exists:
 *   On Android 10+, startActivity() called from a non-Activity context (e.g.,
 *   Application context, Service context) triggers Background Activity Launch
 *   (BAL) restrictions. Android silently drops the intent — no exception,
 *   no error — and the target app never appears.
 *
 *   OpenAppExecutor is injected with @ApplicationContext. That's the correct
 *   context for most operations (package queries, resource access), but NOT
 *   for launching user-visible activities on modern Android.
 *
 *   This holder lets the executor fall back to the current foreground activity
 *   when one is available. Since the user is literally in the app when they
 *   tap Submit, a visible-window launch from the activity context is properly
 *   treated as user-initiated and sails through BAL checks.
 *
 * Threading:
 *   set() / clear() are called from Activity lifecycle callbacks on the main
 *   thread. get() can be called from any thread. The WeakReference swap is
 *   atomic enough for our purposes — we don't care about narrow races because
 *   the fallback (ApplicationContext) still works for pre-Android-10 devices
 *   and fails the same silent way on modern devices with or without this.
 *
 * Memory safety:
 *   WeakReference prevents the holder from pinning a killed Activity.
 *   clear() additionally wipes the ref when the activity pauses, so we don't
 *   leave a stale reference pointing at a destroyed activity.
 */
object CurrentActivityHolder {

    @Volatile
    private var ref: WeakReference<Activity>? = null

    /** Called from MainActivity.onResume(). */
    fun set(activity: Activity) {
        ref = WeakReference(activity)
    }

    /**
     * Called from MainActivity.onPause(). Only clears if the paused activity
     * matches the one we have — guards against clearing a ref that a newer
     * onResume already replaced (cross-activity transitions).
     */
    fun clear(activity: Activity) {
        val current = ref?.get()
        if (current === activity) {
            ref = null
        }
    }

    /**
     * Returns the currently-resumed activity, or null if the app is in the
     * background / no activity has claimed the slot yet.
     */
    fun get(): Activity? = ref?.get()
}
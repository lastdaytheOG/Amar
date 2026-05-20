package com.amar.vault.agent.runtime.replay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Debug-only receiver to dump or replay the recorder buffer via ADB.
 *
 * # Triggers
 *
 *   Summary log:
 *     adb shell am broadcast -a com.amar.vault.DEBUG_EVENT_HISTORY \
 *         --es action summary
 *
 *   Dump last N events (default 50):
 *     adb shell am broadcast -a com.amar.vault.DEBUG_EVENT_HISTORY \
 *         --es action dump --ei count 100
 *
 *   Dump events from past windowMs:
 *     adb shell am broadcast -a com.amar.vault.DEBUG_EVENT_HISTORY \
 *         --es action window --ei windowMs 5000
 *
 *   Replay last N events (DANGEROUS — corrupts WorldState):
 *     adb shell am broadcast -a com.amar.vault.DEBUG_EVENT_HISTORY \
 *         --es action replay --ei count 20
 *
 * # Why a BroadcastReceiver
 * Triggerable from adb without launching the app's UI or modifying
 * source. Lives only in debug builds. Never invoked at runtime by the
 * agent itself.
 *
 * # Manifest
 * Must be declared in AndroidManifest.xml under <application>:
 *   <receiver
 *       android:name=".agent.runtime.replay.EventHistoryDebugReceiver"
 *       android:exported="true">
 *       <intent-filter>
 *           <action android:name="com.amar.vault.DEBUG_EVENT_HISTORY" />
 *       </intent-filter>
 *   </receiver>
 */
@AndroidEntryPoint
class EventHistoryDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var recorder: EventHistoryRecorder

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "summary"
        Log.i(TAG, "RECEIVED action=$action extras=${intent.extras?.keySet()}")

        when (action) {
            "summary" -> recorder.logSummary()

            "dump" -> {
                val count = intent.getIntExtra("count", 50)
                val events = recorder.snapshotLastN(count)
                Log.i(TAG, "DUMP n=${events.size}")
                events.forEachIndexed { i, ev ->
                    Log.i(TAG, "  [$i] ts=${ev.atMillis} ${ev::class.simpleName} $ev")
                }
            }

            "window" -> {
                val windowMs = intent.getIntExtra("windowMs", 5000).toLong()
                val events = recorder.snapshotLastWindow(windowMs)
                Log.i(TAG, "WINDOW ms=$windowMs n=${events.size}")
                events.forEachIndexed { i, ev ->
                    Log.i(TAG, "  [$i] ts=${ev.atMillis} ${ev::class.simpleName} $ev")
                }
            }

            "replay" -> {
                val count = intent.getIntExtra("count", 20)
                val events = recorder.snapshotLastN(count)
                Log.i(TAG, "REPLAY_REQUESTED count=${events.size}")
                // Replay is suspending; run in a coroutine so the
                // BroadcastReceiver returns quickly.
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    recorder.replay(events)
                }
            }

            else -> Log.w(TAG, "Unknown action=$action; valid: summary|dump|window|replay")
        }
    }

    companion object {
        private const val TAG = "EventHistoryDbg"
    }
}
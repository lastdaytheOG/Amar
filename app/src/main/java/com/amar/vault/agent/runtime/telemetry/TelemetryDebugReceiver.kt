package com.amar.vault.agent.runtime.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug receiver to force a telemetry export on demand.
 *
 * # Usage
 *   adb shell am broadcast -a com.amar.vault.DEBUG_TELEMETRY -p com.amar.vault \
 *       --es action export
 *
 *   adb shell am broadcast -a com.amar.vault.DEBUG_TELEMETRY -p com.amar.vault \
 *       --es action where
 *
 * # Manifest registration required
 *   <receiver
 *       android:name=".agent.runtime.telemetry.TelemetryDebugReceiver"
 *       android:exported="true">
 *       <intent-filter>
 *           <action android:name="com.amar.vault.DEBUG_TELEMETRY" />
 *       </intent-filter>
 *   </receiver>
 *
 * # Pulling the file
 *   adb pull /sdcard/Android/data/com.amar.vault/files/telemetry/latest.json
 */
@AndroidEntryPoint
class TelemetryDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var exporter: TelemetryExporter

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "export"
        Log.i(TAG, "RECEIVED action=$action")
        when (action) {
            "export" -> {
                val path = exporter.export()
                Log.i(TAG, "DONE path=$path")
            }
            "where" -> {
                Log.i(TAG, "WHERE dir=${exporter.dir().absolutePath}")
            }
            else -> Log.w(TAG, "Unknown action=$action; valid: export|where")
        }
    }

    companion object {
        private const val TAG = "TelemetryDbg"
    }
}
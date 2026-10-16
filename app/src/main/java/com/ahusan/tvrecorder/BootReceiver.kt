package com.ahusan.tvrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Turns phone access back on after the TV restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            ServerService.start(context)
        }
    }
}

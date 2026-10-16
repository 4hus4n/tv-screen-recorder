package com.ahusan.tvrecorder

import android.os.SystemClock

/** Shared recording state, read by the TV screen and the phone web page. */
object RecState {
    @Volatile var recording = false
    @Volatile var saving = false
    @Volatile var startedAt = 0L          // elapsedRealtime
    @Volatile var stopAt = 0L             // elapsedRealtime, 0 = no auto-stop
    @Volatile var fileName: String? = null
    @Volatile var withAudio = false
    @Volatile var lastError: String? = null

    fun elapsedSec(): Long =
        if (recording) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0

    fun remainingSec(): Long =
        if (recording && stopAt > 0) maxOf(0, (stopAt - SystemClock.elapsedRealtime()) / 1000) else -1
}

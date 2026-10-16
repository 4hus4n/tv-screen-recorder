package com.ahusan.tvrecorder

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Recording height: 720 or 1080. */
    fun quality(c: Context) = sp(c).getInt("quality", 1080)
    fun setQuality(c: Context, v: Int) = sp(c).edit().putInt("quality", v).apply()

    /** Record the TV's own sound (Android 10+). */
    fun audio(c: Context) = sp(c).getBoolean("audio", true)
    fun setAudio(c: Context, v: Boolean) = sp(c).edit().putBoolean("audio", v).apply()

    /** Auto-stop after N minutes, 0 = off. */
    fun autoStop(c: Context) = sp(c).getInt("autostop", 0)
    fun setAutoStop(c: Context, v: Int) = sp(c).edit().putInt("autostop", v).apply()

    /** Access key put in the QR link so only people who scanned it can open the page. */
    fun key(c: Context): String {
        val s = sp(c)
        var k = s.getString("key", null)
        if (k == null) {
            k = (100000..999999).random().toString()
            s.edit().putString("key", k).apply()
        }
        return k
    }
}

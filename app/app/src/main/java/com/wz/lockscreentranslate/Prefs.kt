package com.wz.lockscreentranslate

import android.content.Context

/** Tiny SharedPreferences wrapper for proxy config. */
object Prefs {
    private const val FILE = "lt_prefs"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun proxyUrl(c: Context): String =
        sp(c).getString("proxyUrl", "https://us-east4-wz-cloud-claude.cloudfunctions.net/translate")!!
    fun authToken(c: Context): String = sp(c).getString("authToken", "")!!
    fun renderMode(c: Context): String = sp(c).getString("renderMode", "spans")!!

    fun save(c: Context, proxyUrl: String, authToken: String) {
        sp(c).edit().putString("proxyUrl", proxyUrl.trim()).putString("authToken", authToken.trim()).apply()
    }

    // Lock-screen launch notification (one-tap launcher on the lock screen)
    fun launchNotif(c: Context): Boolean = sp(c).getBoolean("launchNotif", true)
    fun setLaunchNotif(c: Context, on: Boolean) = sp(c).edit().putBoolean("launchNotif", on).apply()

    // Lock-screen volume-chord trigger (shelved)
    fun chordEnabled(c: Context): Boolean = sp(c).getBoolean("chordEnabled", false)
    fun setChordEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("chordEnabled", on).apply()
    fun chordKey(c: Context): String = sp(c).getString("chordKey", "down")!!
    fun setChordKey(c: Context, key: String) = sp(c).edit().putString("chordKey", key).apply()
}

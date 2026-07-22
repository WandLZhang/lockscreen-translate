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
}

package com.qrfloat.app

import android.content.Context

object Prefs {
    private const val NAME = "qrfloat"
    const val DEFAULT_BROKER = "tcp://broker.emqx.io:1883"

    private fun sp() = App.instance.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getBroker(): String = sp().getString("broker", DEFAULT_BROKER) ?: DEFAULT_BROKER
    fun setBroker(url: String) { sp().edit().putString("broker", url.trim()).apply() }

    fun getLastReceived(): String? = sp().getString("last_received", null)
    fun setLastReceived(text: String) { sp().edit().putString("last_received", text).apply() }
}

package de.flkaiser.paanalyzer.settings

import android.content.Context
import androidx.core.content.edit

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val values = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        return SettingsCodec.decode(values)
    }

    fun save(settings: AppSettings) {
        prefs.edit {
            for ((k, v) in SettingsCodec.encode(settings)) putString(k, v)
        }
    }
}

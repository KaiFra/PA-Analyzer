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

    /** Ids of the curves shown in the results chart, or null if never changed. */
    fun loadCurves(): Set<String>? = prefs.getStringSet(KEY_CURVES, null)?.toSet()

    fun saveCurves(ids: Set<String>) = prefs.edit { putStringSet(KEY_CURVES, ids) }

    private companion object {
        const val KEY_CURVES = "visibleCurves"
    }
}

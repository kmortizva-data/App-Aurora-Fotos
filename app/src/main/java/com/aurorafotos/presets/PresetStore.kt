package com.aurorafotos.presets

import android.content.Context

/** Persists user overrides of the built-in presets in SharedPreferences. */
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)

    fun get(id: String): Preset {
        val base = Presets.byId(id)
        val raw = prefs.getString(id, null) ?: return base
        return runCatching { Preset.deserialize(raw, base) }.getOrDefault(base)
    }

    fun all(): List<Preset> = Presets.ALL.map { get(it.id) }

    fun save(preset: Preset) {
        prefs.edit().putString(preset.id, preset.serialize()).apply()
    }

    fun reset(id: String) {
        prefs.edit().remove(id).apply()
    }

    var lastSelectedId: String
        get() = prefs.getString("last_selected", Presets.AURORA_PHOTO.id) ?: Presets.AURORA_PHOTO.id
        set(value) = prefs.edit().putString("last_selected", value).apply()
}

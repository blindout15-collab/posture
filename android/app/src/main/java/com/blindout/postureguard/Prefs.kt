package com.blindout.postureguard

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("posture", Context.MODE_PRIVATE)

    var baseDistCm: Int
        get() = sp.getInt("baseDist", 40)
        set(v) = sp.edit { putInt("baseDist", v) }
    var minDistCm: Int
        get() = sp.getInt("minDist", 30)
        set(v) = sp.edit { putInt("minDist", v) }
    var pitchTolDeg: Int
        get() = sp.getInt("pitchTol", 15)
        set(v) = sp.edit { putInt("pitchTol", v) }
    var rollTolDeg: Int
        get() = sp.getInt("rollTol", 12)
        set(v) = sp.edit { putInt("rollTol", v) }
    var holdSec: Int
        get() = sp.getInt("hold", 5)
        set(v) = sp.edit { putInt("hold", v) }
    var cooldownSec: Int
        get() = sp.getInt("cooldown", 30)
        set(v) = sp.edit { putInt("cooldown", v) }
    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit { putBoolean("vibrate", v) }
    var overlay: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit { putBoolean("overlay", v) }
    var breakReminder: Boolean
        get() = sp.getBoolean("breakReminder", true)
        set(v) = sp.edit { putBoolean("breakReminder", v) }

    var baseline: Sample?
        get() = if (sp.contains("b_width")) {
            Sample(sp.getFloat("b_width", 0f), sp.getFloat("b_pitch", 0f), sp.getFloat("b_roll", 0f))
        } else {
            null
        }
        set(v) = sp.edit {
            if (v == null) {
                remove("b_width"); remove("b_pitch"); remove("b_roll")
            } else {
                putFloat("b_width", v.width); putFloat("b_pitch", v.pitch); putFloat("b_roll", v.roll)
            }
        }

    val thresholds: Thresholds
        get() = Thresholds(baseDistCm, minDistCm, pitchTolDeg, rollTolDeg)
}

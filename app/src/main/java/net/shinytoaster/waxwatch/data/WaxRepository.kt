package net.shinytoaster.waxwatch.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Locale

enum class DistanceUnit { SYSTEM, KILOMETERS, MILES }

class WaxRepository(private val context: Context) {
    
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("wax_watch_prefs", Context.MODE_PRIVATE)

    fun getRiderWeight(): Double {
        return prefs.getFloat("rider_weight_kg", 75.0f).toDouble()
    }

    fun setRiderWeight(weightKg: Double) {
        prefs.edit { putFloat("rider_weight_kg", weightKg.toFloat()) }
    }

    fun getWaxType(): WaxType {
        val name = prefs.getString("wax_type", WaxType.GENERIC_PARAFFIN.name) ?: WaxType.GENERIC_PARAFFIN.name
        return try {
            WaxType.valueOf(name)
        } catch (_: Exception) {
            WaxType.GENERIC_PARAFFIN
        }
    }

    fun setWaxType(type: WaxType) {
        prefs.edit { putString("wax_type", type.name) }
    }

    fun getDistanceUnit(): DistanceUnit {
        val name = prefs.getString("distance_unit", DistanceUnit.SYSTEM.name) ?: DistanceUnit.SYSTEM.name
        return try {
            DistanceUnit.valueOf(name)
        } catch (_: Exception) {
            DistanceUnit.SYSTEM
        }
    }

    fun setDistanceUnit(unit: DistanceUnit) {
        prefs.edit { putString("distance_unit", unit.name) }
    }

    fun resolveDistanceUnit(): DistanceUnit {
        val unit = getDistanceUnit()
        if (unit != DistanceUnit.SYSTEM) return unit
        
        val country = Locale.getDefault().country
        return if (country == "US" || country == "LR" || country == "MM" || country == "GB") {
            DistanceUnit.MILES
        } else {
            DistanceUnit.KILOMETERS
        }
    }

    fun getBaseWaxLifeMeters(): Double {
        return prefs.getFloat("base_wax_life_meters", 350000f).toDouble()
    }

    fun setBaseWaxLifeMeters(meters: Double) {
        prefs.edit { putFloat("base_wax_life_meters", meters.toFloat()) }
    }

    fun getAlertThresholdPercent(): Int {
        return prefs.getInt("alert_threshold_percent", 20)
    }

    fun setAlertThresholdPercent(percent: Int) {
        prefs.edit { putInt("alert_threshold_percent", percent) }
    }

    private fun findCaseInsensitiveKey(profileId: String): String? {
        val trimmedProfileId = profileId.trim().lowercase()
        val keys = prefs.all.keys
        return keys.find { key ->
            if (key.startsWith("wax_state_")) {
                key.substring("wax_state_".length).trim().lowercase() == trimmedProfileId
            } else false
        }
    }

    fun getWaxState(profileId: String): WaxState? {
        val actualKey = findCaseInsensitiveKey(profileId) ?: "wax_state_$profileId"
        val jsonString = prefs.getString(actualKey, null) ?: return null
        return try {
            val json = JSONObject(jsonString)
            val maxLife = json.optDouble("maxLifeMeters", getBaseWaxLifeMeters())
            val remaining = json.optDouble("remainingDistanceMeters", maxLife)

            WaxState(
                profileId = profileId,
                remainingDistanceMeters = remaining,
                maxLifeMeters = maxLife,
                surfaceType = SurfaceType.valueOf(json.optString("surfaceType", SurfaceType.PAVEMENT.name)),
                alertTriggered = json.optBoolean("alertTriggered", false)
            )
        } catch (e: Exception) {
            Log.e("WaxWatch", "Error parsing wax state for $profileId", e)
            null
        }
    }

    fun saveWaxState(state: WaxState) {
        val json = JSONObject().apply {
            put("remainingDistanceMeters", state.remainingDistanceMeters)
            put("maxLifeMeters", state.maxLifeMeters)
            put("surfaceType", state.surfaceType.name)
            put("alertTriggered", state.alertTriggered)
        }
        // Save using trimmed profile ID to prevent spacing issues
        val key = "wax_state_${state.profileId.trim()}"
        prefs.edit { putString(key, json.toString()) }

        val profileIds = getSavedProfileIds().toMutableSet()
        if (profileIds.add(state.profileId.trim())) {
            prefs.edit { putStringSet("all_profile_ids", profileIds) }
        }
    }

    fun deleteWaxState(profileId: String) {
        val actualKey = findCaseInsensitiveKey(profileId) ?: "wax_state_$profileId"
        prefs.edit { remove(actualKey) }
        val profileIds = getSavedProfileIds().toMutableSet()
        val toRemove = profileIds.find { it.trim().lowercase() == profileId.trim().lowercase() } ?: profileId
        if (profileIds.remove(toRemove)) {
            prefs.edit { putStringSet("all_profile_ids", profileIds) }
        }
    }

    fun getSavedProfileIds(): Set<String> {
        return prefs.getStringSet("all_profile_ids", emptySet()) ?: emptySet()
    }

    fun getAllWaxStates(): Map<String, WaxState> {
        val res = mutableMapOf<String, WaxState>()
        for (id in getSavedProfileIds()) {
            val state = getWaxState(id)
            if (state != null) {
                res[id] = state
            }
        }
        return res
    }
}

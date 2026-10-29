package com.aura.music.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.Serializable

/** A preference value that survives JSON with its type ([t]: b, i, l, f, d, s or ss). */
@Serializable
data class TypedValue(val t: String, val v: String? = null, val set: List<String>? = null)

/** Converts DataStore preferences to and from [TypedValue]s, for backups. */
object PreferenceValues {

    fun encode(values: Map<String, Any>): Map<String, TypedValue> = values.mapNotNull { (key, value) ->
        val typed = when (value) {
            is Boolean -> TypedValue("b", value.toString())
            is Int -> TypedValue("i", value.toString())
            is Long -> TypedValue("l", value.toString())
            is Float -> TypedValue("f", value.toString())
            is Double -> TypedValue("d", value.toString())
            is String -> TypedValue("s", value)
            is Set<*> -> TypedValue("ss", set = value.filterIsInstance<String>().sorted())
            else -> null
        }
        typed?.let { key to it }
    }.toMap()

    fun decode(values: Map<String, TypedValue>): Map<String, Any> = values.mapNotNull { (key, typed) ->
        val value: Any? = when (typed.t) {
            "b" -> typed.v?.toBooleanStrictOrNull()
            "i" -> typed.v?.toIntOrNull()
            "l" -> typed.v?.toLongOrNull()
            "f" -> typed.v?.toFloatOrNull()
            "d" -> typed.v?.toDoubleOrNull()
            "s" -> typed.v
            "ss" -> typed.set?.toSet()
            else -> null
        }
        value?.let { key to it }
    }.toMap()

    fun write(prefs: MutablePreferences, values: Map<String, Any>) {
        for ((key, value) in values) {
            when (value) {
                is Boolean -> prefs[booleanPreferencesKey(key)] = value
                is Int -> prefs[intPreferencesKey(key)] = value
                is Long -> prefs[longPreferencesKey(key)] = value
                is Float -> prefs[floatPreferencesKey(key)] = value
                is Double -> prefs[doublePreferencesKey(key)] = value
                is String -> prefs[stringPreferencesKey(key)] = value
                is Set<*> -> prefs[stringSetPreferencesKey(key)] = value.filterIsInstance<String>().toSet()
            }
        }
    }
}

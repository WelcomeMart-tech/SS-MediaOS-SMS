
package com.ssmediaos.smsgateway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object IncomingSmsStore {

    private const val PREFS_NAME = "incoming_sms_store"
    private const val KEY_MESSAGES = "messages"
    private const val MAX_MESSAGES = 200

    @Synchronized
    fun save(
        context: Context,
        sender: String,
        message: String,
        timestamp: Long
    ) {
        val preferences = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val oldMessages = try {
            JSONArray(preferences.getString(KEY_MESSAGES, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }

        val updatedMessages = JSONArray()

        updatedMessages.put(
            JSONObject().apply {
                put("sender", sender)
                put("message", message)
                put("timestamp", timestamp)
            }
        )

        for (index in 0 until oldMessages.length()) {
            if (updatedMessages.length() >= MAX_MESSAGES) {
                break
            }

            val item = oldMessages.optJSONObject(index) ?: continue
            updatedMessages.put(item)
        }

        preferences.edit()
            .putString(KEY_MESSAGES, updatedMessages.toString())
            .apply()
    }

    @Synchronized
    fun getAll(context: Context): List<JSONObject> {
        val preferences = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val messages = try {
            JSONArray(preferences.getString(KEY_MESSAGES, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }

        return buildList {
            for (index in 0 until messages.length()) {
                val item = messages.optJSONObject(index)
                if (item != null) {
                    add(item)
                }
            }
        }
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .remove(KEY_MESSAGES)
            .apply()
    }
}

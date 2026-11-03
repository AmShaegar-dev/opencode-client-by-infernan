package ru.opencode.mobile.data

import android.content.Context
import org.json.JSONArray

/** Локальное хранилище серверов. Данные шифруются ключом Android Keystore. */
class ServersStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("opencode_mobile_servers", Context.MODE_PRIVATE)

    fun read(): List<ServerProfile> {
        val encrypted = prefs.getString(KEY_ENCRYPTED, null)
        val raw = if (encrypted != null) {
            runCatching { SecretBox.decrypt(encrypted) }.getOrNull()
        } else {
            // Совместимость с самыми первыми сборками без шифрования.
            prefs.getString(KEY_LEGACY, null)
        } ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { ServerProfile.fromJson(it)?.let(::add) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun write(servers: List<ServerProfile>) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson()) }
        val raw = array.toString()
        val editor = prefs.edit()
        val encrypted = runCatching { SecretBox.encrypt(raw) }.getOrNull()
        if (encrypted != null) {
            editor.putString(KEY_ENCRYPTED, encrypted).remove(KEY_LEGACY)
        } else {
            // Крайне редко: Keystore недоступен. Храним приватно, но без шифрования.
            editor.putString(KEY_LEGACY, raw)
        }
        editor.apply()
    }

    private companion object {
        const val KEY_ENCRYPTED = "servers_enc"
        const val KEY_LEGACY = "servers_json"
    }
}

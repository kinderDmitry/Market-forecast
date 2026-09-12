package com.marketforecast.prox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Apps Script client for the PRO X personal account.
 * The Google Sheet itself remains private; only the Apps Script web app is exposed.
 */
class AccountClient(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mfprefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("account_server_url", "")?.trim()?.trimEnd('/') ?: ""
        set(v) { prefs.edit().putString("account_server_url", v.trim().trimEnd('/')).apply() }

    private fun request(action: String, payload: JSONObject = JSONObject()): JSONObject {
        require(baseUrl.isNotBlank()) { "Сначала укажите URL Google Apps Script" }
        val body = JSONObject(payload.toString()).put("action", action)
        prefs.getString("account_session", "")?.takeIf { it.isNotBlank() }?.let { body.put("token", it) }

        val c = (URL(baseUrl).openConnection() as HttpURLConnection)
        c.requestMethod = "POST"
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "{}"
        val result = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false).put("error", "Некорректный ответ сервера") }
        if (result.optBoolean("ok", true).not()) {
            throw IllegalStateException(result.optString("error", "Ошибка сервера"))
        }
        if (code !in 200..299) throw IllegalStateException("Ошибка сервера ($code)")
        return result
    }

    fun register(email: String, password: String, name: String): JSONObject =
        request("register", JSONObject().put("email", email).put("password", password).put("name", name))

    fun login(email: String, password: String): JSONObject =
        request("login", JSONObject().put("email", email).put("password", password))

    fun me(): JSONObject = request("me")

    fun logout() {
        runCatching { request("logout") }
        clearSession()
    }

    fun saveSession(result: JSONObject) {
        result.optString("token").takeIf { it.isNotBlank() }?.let {
            prefs.edit().putString("account_session", it).apply()
        }
        result.optJSONObject("user")?.let {
            prefs.edit().putString("account_user", it.toString()).apply()
        }
    }

    fun clearSession() {
        prefs.edit().remove("account_session").remove("account_user").apply()
    }

    fun cachedUser(): JSONObject? = prefs.getString("account_user", null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun hasSession(): Boolean = prefs.getString("account_session", "").orEmpty().isNotBlank()
}

package com.marketforecast.prox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AccountClient(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mfprefs", Context.MODE_PRIVATE)
    var baseUrl: String
        get() = prefs.getString("account_server_url", "")?.trim()?.trimEnd('/') ?: ""
        set(v) { prefs.edit().putString("account_server_url", v.trim().trimEnd('/')).apply() }

    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        require(baseUrl.isNotBlank()) { "Сначала укажите адрес сервера" }
        val c = (URL(baseUrl + path).openConnection() as HttpURLConnection)
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json")
        prefs.getString("account_session", "")?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        if (c.responseCode !in 200..299) throw IllegalStateException(JSONObject(text).optString("error", "Ошибка сервера (${c.responseCode})"))
        return JSONObject(text)
    }

    fun register(email: String, password: String, name: String): JSONObject = request("/api/auth/register", "POST", JSONObject().put("email", email).put("password", password).put("name", name))
    fun login(email: String, password: String): JSONObject = request("/api/auth/login", "POST", JSONObject().put("email", email).put("password", password))
    fun me(): JSONObject = request("/api/auth/me", "GET")
    fun logout() { runCatching { request("/api/auth/logout", "POST") }; prefs.edit().remove("account_session").remove("account_user").apply() }
    fun adminUsers(): JSONArray = request("/api/admin/users", "GET").optJSONArray("users") ?: JSONArray()
    fun setUserStatus(id: String, enabled: Boolean): JSONObject = request("/api/admin/users/$id/status", "POST", JSONObject().put("enabled", enabled))

    fun saveSession(result: JSONObject) {
        result.optString("token").takeIf { it.isNotBlank() }?.let { prefs.edit().putString("account_session", it).apply() }
        result.optJSONObject("user")?.let { prefs.edit().putString("account_user", it.toString()).apply() }
    }
    fun clearSession() { prefs.edit().remove("account_session").remove("account_user").apply() }
    fun cachedUser(): JSONObject? = prefs.getString("account_user", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
}

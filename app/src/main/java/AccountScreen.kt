package com.marketforecast.prox

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AccountScreen(ru: Boolean, ctx: Context, onBack: () -> Unit) {
    val client = remember { AccountClient(ctx) }
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(client.baseUrl) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(client.cachedUser()) }
    var message by remember { mutableStateOf<String?>(null) }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun call(block: () -> JSONObject) {
        busy = true
        message = null
        scope.launch(Dispatchers.IO) {
            runCatching { block() }.onSuccess { r ->
                client.saveSession(r)
                user = r.optJSONObject("user") ?: user
                message = if (ru) "Готово" else "Done"
            }.onFailure { message = it.message ?: if (ru) "Ошибка" else "Error" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (client.hasSession()) {
            runCatching { client.me() }.onSuccess { r ->
                client.saveSession(r)
                user = r.optJSONObject("user") ?: user
            }.onFailure {
                client.clearSession()
                user = null
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(if (ru) "Личный кабинет" else "Personal account", fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
        item {
            Text(
                if (ru) "Регистрация автоматически добавляет пользователя в вашу закрытую Google Таблицу. Роль, доступ и дату окончания доступа вы меняете вручную в таблице."
                else "Registration automatically adds the user to your private Google Sheet. You manage role, access and expiry date in the sheet.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it; client.baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (ru) "URL Google Apps Script" else "Google Apps Script URL") },
                supportingText = { Text(if (ru) "Вставьте URL развёрнутого веб-приложения Apps Script" else "Paste the deployed Apps Script web app URL") }
            )
        }

        if (user == null) {
            if (register) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (ru) "Имя" else "Name") }
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Email") }
                )
            }
            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (ru) "Пароль" else "Password") }
                )
            }
            item {
                Button(
                    onClick = {
                        call {
                            if (register) client.register(email.trim(), password, name.trim())
                            else client.login(email.trim(), password)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && email.isNotBlank() && password.length >= 8
                ) {
                    Text(
                        if (register) if (ru) "Зарегистрироваться" else "Register"
                        else if (ru) "Войти" else "Sign in"
                    )
                }
            }
            item {
                TextButton(onClick = { register = !register; message = null }) {
                    Text(
                        if (register) if (ru) "Уже есть аккаунт — войти" else "Already registered — sign in"
                        else if (ru) "Нет аккаунта — зарегистрироваться" else "No account — register"
                    )
                }
            }
        } else {
            item { AccountUserCard(user!!, ru) }
            item {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            client.logout()
                            user = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (ru) "Выйти" else "Sign out")
                }
            }
        }

        message?.let {
            item {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        item { TextButton(onClick = onBack) { Text(if (ru) "Назад" else "Back") } }
    }
}

@Composable
private fun AccountUserCard(user: JSONObject, ru: Boolean) {
    val enabled = user.optBoolean("enabled", false)
    val role = user.optString("role", "user")
    val expires = user.optString("expires_at", "").trim()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Icon(Icons.Default.Person, null)
                Spacer(Modifier.width(8.dp))
                Text(user.optString("name").ifBlank { user.optString("email") }, fontSize = 19.sp, fontWeight = FontWeight.Black)
            }
            Text(user.optString("email"), fontSize = 11.sp)
            Text(
                if (role == "admin") if (ru) "Администратор" else "Administrator" else if (ru) "Пользователь" else "User",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Row {
                Icon(
                    if (enabled) Icons.Default.CheckCircle else Icons.Default.Lock,
                    null,
                    tint = if (enabled) Color(0xFF00D084) else Color(0xFFFF4757)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (enabled) if (ru) "Доступ разрешён" else "Access allowed" else if (ru) "Ожидает разрешения" else "Waiting for approval",
                    fontWeight = FontWeight.Bold
                )
            }
            if (expires.isNotBlank()) {
                Text(if (ru) "Доступ до: $expires" else "Access until: $expires", fontSize = 11.sp)
            } else {
                Text(if (ru) "Доступ: без указанной даты" else "Access: no expiry date set", fontSize = 11.sp)
            }
        }
    }
}

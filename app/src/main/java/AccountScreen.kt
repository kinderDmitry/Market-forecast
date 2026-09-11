package com.marketforecast.prox

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var users by remember { mutableStateOf(emptyList<JSONObject>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun call(block: () -> JSONObject) {
        busy = true
        scope.launch(Dispatchers.IO) {
            runCatching { block() }.onSuccess { r ->
                client.saveSession(r); user = r.optJSONObject("user") ?: user; message = if (ru) "Готово" else "Done"
            }.onFailure { message = it.message }
            busy = false
        }
    }
    LaunchedEffect(user?.optString("role"), server) {
        if (user?.optString("role") == "admin") runCatching { users = (0 until client.adminUsers().length()).map { client.adminUsers().getJSONObject(it) } }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(if(ru) "Личный кабинет" else "Account", fontSize=28.sp, fontWeight=FontWeight.Black) }
        item { Text(if(ru) "Доступ к приложению управляется сервером. Сервер можно разместить бесплатно через Cloudflare Workers + D1." else "App access is controlled by the server. The server can be deployed free with Cloudflare Workers + D1.", fontSize=10.sp, color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(server,{server=it;client.baseUrl=it},Modifier.fillMaxWidth(),singleLine=true,label={Text(if(ru)"Адрес сервера" else "Server URL")}) }
        if (user == null) {
            item { if(register) OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),singleLine=true,label={Text(if(ru)"Имя" else "Name")}) }
            item { OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Email")}) }
            item { OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),singleLine=true,label={Text(if(ru)"Пароль" else "Password")}) }
            item { Button({ call { if(register) client.register(email.trim(),password,name.trim()) else client.login(email.trim(),password) } },Modifier.fillMaxWidth(),enabled=!busy) { Text(if(register) if(ru)"Создать аккаунт" else "Create account" else if(ru)"Войти" else "Sign in") } }
            item { TextButton({register=!register}) { Text(if(register) if(ru)"Уже есть аккаунт — войти" else "Already registered — sign in" else if(ru)"Нет аккаунта — зарегистрироваться" else "No account — register") } }
        } else {
            item { AccountUserCard(user!!,ru) }
            item { OutlinedButton({ scope.launch(Dispatchers.IO) { client.logout(); user=null } },Modifier.fillMaxWidth()) { Icon(Icons.Default.Lock,null);Spacer(Modifier.width(6.dp));Text(if(ru)"Выйти" else "Sign out") } }
            if (user!!.optString("role") == "admin") {
                item { Text(if(ru)"Управление доступом" else "Access management",fontSize=18.sp,fontWeight=FontWeight.Black) }
                items(users) { u ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(u.optString("email"),fontWeight=FontWeight.Bold); Text(if(u.optBoolean("enabled",false)) if(ru)"Доступ разрешён" else "Access allowed" else if(ru)"Доступ закрыт" else "Access blocked",fontSize=9.sp,color=if(u.optBoolean("enabled")) Color(0xFF00D084) else Color(0xFFFF4757)) }
                        Switch(checked=u.optBoolean("enabled"),onCheckedChange={ enabled -> scope.launch(Dispatchers.IO) { runCatching { client.setUserStatus(u.optString("id"),enabled) }.onSuccess { users = users.map { x -> if(x.optString("id")==u.optString("id")) JSONObject(x.toString()).put("enabled",enabled) else x } } } })
                    }
                }
            }
        }
        message?.let { item { Text(it,fontSize=10.sp,color=MaterialTheme.colorScheme.primary) } }
        item { TextButton(onBack) { Text(if(ru)"Назад" else "Back") } }
    }
}

@Composable private fun AccountUserCard(user: JSONObject, ru: Boolean) {
    val enabled=user.optBoolean("enabled",false)
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(user.optString("name").ifBlank{user.optString("email")},fontSize=18.sp,fontWeight=FontWeight.Black);Text(user.optString("email"),fontSize=10.sp);Spacer(Modifier.height(8.dp));Row{Icon(if(enabled) Icons.Default.CheckCircle else Icons.Default.Lock,null,tint=if(enabled) Color(0xFF00D084) else Color(0xFFFF4757));Spacer(Modifier.width(6.dp));Text(if(enabled) if(ru)"Доступ разрешён" else "Access allowed" else if(ru)"Доступ закрыт" else "Access blocked",fontWeight=FontWeight.Bold)}}}
}

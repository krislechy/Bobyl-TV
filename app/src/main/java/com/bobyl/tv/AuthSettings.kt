package com.bobyl.tv

import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.bobyl.tv.data.AuthRepository
import com.bobyl.tv.data.CatalogUrls
import com.bobyl.tv.data.networkErrorMessage
import com.bobyl.tv.data.AccountProfile
import androidx.compose.ui.platform.LocalContext
import com.bobyl.tv.SkeletonAsyncImage as AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AuthSettings(provider: String, auth: AuthRepository, changed: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var registration by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<AccountProfile?>(null) }
    var web by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    // Never rememberSaveable: plaintext passwords must not enter saved-instance-state.
    var password by remember { mutableStateOf("") }
    var account by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    web?.let { AccountWebDialog(it) { web = null; revision++ } }
    LaunchedEffect(provider, revision) {
        busy = true
        checking = true
        try {
            account = auth.restore(provider)
            if (account != null) {
                profile = auth.profile(provider)
                message = when (profile?.authenticated) {
                    true -> "Вход подтверждён сервером"
                    false -> { account = null; "Сессия истекла. Войдите заново." }
                    null -> "Не удалось определить состояние входа. Повторите проверку."
                }
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Не удалось восстановить сессию. Войдите заново." }
        finally { busy = false; checking = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(CatalogUrls.provider(provider), color = Color(0xFFACADB2), fontSize = 13.sp)
        if (checking && profile == null) SkeletonLoading(SkeletonLayout.Profile)
        else if (account == null) {
            CinematicButton(if (registration) "Уже есть аккаунт — войти" else "Регистрация", false) { if (!busy) registration = !registration }
            if (registration) {
                Input(email, "Email", { if (!busy) email = it })
                CinematicButton((if (accepted) "✓ " else "") + "Принимаю правила сайта", accepted) { accepted = !accepted }
                CinematicButton("Прочитать правила", false) { web = CatalogUrls.provider(provider) + "rules.html" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Input(username, "Логин", { if (!busy) username = it }) }
                Box(Modifier.weight(1f)) { Input(password, "Пароль", { if (!busy) password = it }, password = true) }
            }
            CinematicButton(if (busy) "Подождите…" else if (registration) "Зарегистрироваться" else "Войти", true) {
                if (!busy && (!registration || accepted)) {
                    busy = true
                    message = ""
                    val submittedName = username
                    val submittedPassword = password
                    scope.launch {
                        try {
                            account = if (registration) auth.register(provider, email, submittedName, submittedPassword) else auth.login(provider, submittedName, submittedPassword)
                            password = ""
                            message = "Вход подтверждён сервером. Сессия сохранена."
                            changed()
                            revision++
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { message = networkErrorMessage(error) }
                        finally { busy = false }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                profile?.avatar?.takeIf { it.isNotBlank() }?.let { avatar ->
                    AsyncImage(avatar, "Аватар", Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(account.orEmpty(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    profile?.let { data ->
                        Text(if (data.premium) "Premium • ${data.premiumInfo}" else "Premium не активен", color = Color(0xFFACADB2), fontSize = 14.sp)
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
            item { FieldAction(FieldGlyph.Refresh, "Проверить сессию и профиль", enabled = !busy) { revision++ } }
            item { CinematicButton("Premium / продление", false) { web = CatalogUrls.provider(provider) + "payments" } }
            item { CinematicButton("Выйти", false) {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try { auth.logout(provider); account = null; password = ""; message = "Вы вышли"; changed() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { message = networkErrorMessage(error) }
                        finally { busy = false }
                    }
                }
            } }
            }
        }
        if (busy && !checking) LoadingIndicator("Сохраняем…")
        if (message.isNotBlank()) Text(message, color = Color(0xFFD7D7DD), fontSize = 14.sp)
        Text("Пароль не сохраняется • сессия защищена Android Keystore", color = Color(0xFFACADB2), fontSize = 12.sp)
    }
}

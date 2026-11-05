package com.bobyl.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

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
import com.bobyl.tv.data.AccountSession
import com.bobyl.tv.data.AccountSessionGuard
import com.bobyl.tv.data.CatalogUrls
import com.bobyl.tv.data.networkErrorMessage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bobyl.tv.SkeletonAsyncImage as AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AccountDialog(provider: String, auth: AuthRepository, accountState: HomeDashboardState,
    retryAccount: () -> Unit, changed: () -> Unit, dismiss: () -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaFocusFrameHost {
            val backFocus = remember { FocusRequester() }
            var logoutRequest by remember(provider) { mutableIntStateOf(0) }
            var logoutOwner by remember(provider) { mutableStateOf<AccountSession?>(null) }
            var safeFocusReady by remember { mutableStateOf(false) }
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(windowFocused) {
                safeFocusReady = false
                if (windowFocused) {
                    withFrameNanos { }
                    safeFocusReady = backFocus.requestFocus()
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .cinemaSurface(false, shape = RoundedCornerShape(CinemaStyle.PanelCorner)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Аккаунт", Modifier.weight(1f), color = CinemaStyle.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        if (accountState.profile?.authenticated == true) FieldAction(FieldGlyph.Logout, "Выйти",
                            modifier = Modifier.focusProperties { canFocus = safeFocusReady },
                            enabled = !accountState.accountLoading) {
                            val owner = accountState.accountSession
                            if (owner != null && AccountSessionGuard.shared.current(provider) == owner) {
                                logoutOwner = owner
                                logoutRequest++
                            }
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().focusGroup()
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        key(provider) { AuthSettings(provider, auth, accountState, retryAccount, changed, logoutRequest, logoutOwner) }
                    }
                    CinematicButton("Назад", false, Modifier.focusRequester(backFocus), onClick = dismiss)
                }
            }
        }
    }
}

@Composable
internal fun AuthSettings(provider: String, auth: AuthRepository, accountState: HomeDashboardState,
    retryAccount: () -> Unit, changed: () -> Unit, logoutRequest: Int = 0, logoutOwner: AccountSession? = null) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var registration by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    val profile = accountState.profile
    var web by remember { mutableStateOf<String?>(null) }
    // Never rememberSaveable: plaintext passwords must not enter saved-instance-state.
    var password by remember { mutableStateOf("") }
    val account = profile?.takeIf { it.authenticated == true }
    var busy by remember { mutableStateOf(false) }
    val checking = accountState.accountLoading
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var pendingLogout by remember(provider) { mutableStateOf<AccountSession?>(null) }
    LaunchedEffect(logoutRequest) {
        if (logoutRequest > 0 && account != null && !busy && !checking) {
            val owner = logoutOwner
            if (owner != null && accountState.accountSession == owner && AccountSessionGuard.shared.current(provider) == owner) {
                pendingLogout = owner
            }
        }
    }
    LaunchedEffect(accountState.accountSession, account?.authenticated) {
        if (account == null || pendingLogout?.let { it != accountState.accountSession } == true) pendingLogout = null
    }
    pendingLogout?.let { expectedSession ->
        LogoutConfirmation(dismiss = { pendingLogout = null }) {
            pendingLogout = null
            if (!busy && !checking && account != null && accountState.accountSession == expectedSession &&
                AccountSessionGuard.shared.current(provider) == expectedSession) {
                busy = true
                scope.launch {
                    try { auth.logout(provider, expectedSession); password = ""; message = ""; changed() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = networkErrorMessage(error) }
                    finally { busy = false }
                }
            }
        }
    }
    val websiteSession = remember(web, provider) {
        if (web != null) com.bobyl.tv.data.AccountSessionGuard.shared.current(provider) else null
    }
    web?.let { AccountWebDialog(it) {
        web = null
        if (websiteSession != com.bobyl.tv.data.AccountSessionGuard.shared.current(provider)) changed()
    } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(CatalogUrls.provider(provider), color = CinemaStyle.Muted, fontSize = 13.sp)
        if (checking && profile == null) SkeletonLoading(SkeletonLayout.Profile)
        // A network/challenge failure is not evidence that the saved account is signed out.
        else if (account == null && accountState.accountError.isBlank()) {
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
                            if (registration) auth.register(provider, email, submittedName, submittedPassword)
                            else auth.login(provider, submittedName, submittedPassword)
                            password = ""
                            message = ""
                            changed()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { message = networkErrorMessage(error) }
                        finally { busy = false }
                    }
                }
            }
        } else if (account != null) {
            AccountPremiumSummary(profile, checking, accountState.premiumFraction, Modifier.fillMaxWidth()) {
                profile?.avatar?.takeIf { it.isNotBlank() }?.let { avatar ->
                    AsyncImage(avatar, "Аватар", Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(accountDisplayNickname(profile).ifBlank { "Аккаунт" }, color = CinemaStyle.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (accountState.accountError.isNotBlank()) {
            Text(accountState.accountError, color = CinemaStyle.Error, fontSize = 14.sp)
            FieldAction(FieldGlyph.Refresh, "Повторить", enabled = !busy && !checking, onClick = retryAccount)
        }
        if (profile?.authenticated == false) Text("Войдите в аккаунт заново.", color = CinemaStyle.Muted, fontSize = 14.sp)
        if (busy && !checking) LoadingIndicator("Сохраняем…")
        if (message.isNotBlank()) Text(message, color = CinemaStyle.Text, fontSize = 14.sp)
    }
}

@Composable
private fun LogoutConfirmation(dismiss: () -> Unit, confirm: () -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaFocusFrameHost {
            val cancelFocus = remember { FocusRequester() }
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(windowFocused) {
                if (windowFocused) {
                    withFrameNanos { }
                    cancelFocus.requestFocus()
                }
            }
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()
                    .cinemaSurface(false, shape = RoundedCornerShape(CinemaStyle.PanelCorner)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Выйти из аккаунта?", color = CinemaStyle.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    CinemaButtonFocusGroup {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                            CinematicButton("Отмена", false, Modifier.focusRequester(cancelFocus), onClick = dismiss)
                            CinematicButton("Выйти", true, onClick = confirm)
                        }
                    }
                }
            }
        }
    }
}

package com.bobyl.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CommentsDialog(provider: String, filmId: String, dismiss: () -> Unit) {
    val repository = remember { CommentsRepository() }
    var rows by remember { mutableStateOf<List<FilmComment>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<FilmComment?>(null) }
    var deleting by remember { mutableStateOf<FilmComment?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun mutate(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { action(); page = 1; retry++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = networkErrorMessage(failure) }
            finally { busy = false }
        }
    }
    LaunchedEffect(provider, filmId, page, retry) {
        busy = true; error = ""
        try {
            val result = repository.load(provider, filmId, page)
            val combined = (if (page == 1) result else rows + result).distinctBy { it.id }
            more = result.isNotEmpty() && (page == 1 || combined.size > rows.size); rows = combined
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = networkErrorMessage(failure) }
        finally { busy = false }
    }
    deleting?.let { comment -> ConfirmAction("Удалить ваш комментарий?", { deleting = null }) { mutate { repository.delete(provider, comment) } } }
    if (sending) ConfirmAction("Опубликовать комментарий${reply?.let { " в ответ ${it.author}" }.orEmpty()}?", { sending = false }) {
        mutate { notice = repository.post(provider, filmId, text, reply?.id ?: "0"); text = ""; reply = null }
    }
    CinemaModal("Комментарии", dismiss, backOnRight = true) {
        val listState = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().popupScrollIndicator(listState).padding(end = 10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    reply?.let { target ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ответ: ${target.author}", color = Color.LightGray, fontSize = 14.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            FieldAction(FieldGlyph.Clear, "Отменить ответ", enabled = !busy) { reply = null }
                        }
                    }
                    Input(text, "Ваш комментарий", { if (!busy) text = it }) {
                        FieldAction(FieldGlyph.Send, "Отправить комментарий", primary = true, enabled = !busy && text.isNotBlank()) { sending = true }
                    }
                    Text("Для публикации нужен вход • возможна проверка на сайте", color = Color.Gray, fontSize = 12.sp)
                }
            }
            if (notice.isNotBlank()) item { Text(notice, color = Color.White) }
            items(rows, key = { it.id }) { comment -> Column(Modifier.padding(start = (comment.depth.coerceIn(0, 3) * 10).dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(comment.author, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(comment.date, color = Color.Gray, fontSize = 12.sp)
                }
                Text(comment.text, color = Color(0xFFE0E0E3), fontSize = 16.sp, lineHeight = 22.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FieldAction(FieldGlyph.Reply, "Ответить ${comment.author}", enabled = !busy) { reply = comment }
                    if (!comment.self) FieldAction(FieldGlyph.Like, if (comment.liked) "Убрать лайк" else "Нравится", primary = comment.liked, enabled = !busy) { mutate { repository.like(provider, comment) } }
                    Text(comment.likes.toString(), color = Color.LightGray, fontSize = 14.sp)
                    if (comment.deleteHash.isNotBlank()) FieldAction(FieldGlyph.Delete, "Удалить комментарий", enabled = !busy) { deleting = comment }
                }
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF25262B)))
            } }
            if (busy) item { SkeletonLoading(SkeletonLayout.Text, rows = if (rows.isEmpty()) 3 else 1) }
            if (error.isNotBlank()) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, color = Color(0xFFFFB5B9), modifier = Modifier.weight(1f))
                FieldAction(FieldGlyph.Refresh, "Повторить загрузку", enabled = !busy) { retry++ }
            } }
            if (!busy && error.isBlank() && more) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FieldAction(FieldGlyph.More, "Ещё комментарии") { page++ }
            } }
            if (!busy && error.isBlank() && rows.isEmpty()) item { Text("Комментариев пока нет", color = Color.LightGray) }
        }
    }
}

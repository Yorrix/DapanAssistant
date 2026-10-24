package cn.dapan.assistant

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CalendarListSheet(
    s: AppStore,
    kind: String,
    dismiss: () -> Unit,
    edit: (CalendarEntry) -> Unit
) {
    val eventKind = if (kind == "TDL") EventKind.TODO else EventKind.DEADLINE
    val remaining = s.calendarEntries.count { it.kind == eventKind && !it.completed }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper,
        dragHandle = null
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.75f).padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kind, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("$remaining 待完成", Modifier.weight(1f).padding(start = 9.dp), fontSize = 11.sp, color = Muted)
                QuietIconButton("新增 $kind", AppGlyph.Add, {
                    edit(newEntry().copy(kind = eventKind).normalized())
                }, color = DutBlue)
                QuietIconButton("关闭列表", AppGlyph.Close, dismiss)
            }
            HorizontalDivider(thickness = .5.dp, color = Hairline)
            CalendarTaskList(s, kind, edit, Modifier.weight(1f))
        }
    }
}

@Composable fun CalendarTodoScreen(s: AppStore, edit: (CalendarEntry) -> Unit) {
    val remaining = s.calendarEntries.count { it.kind == EventKind.TODO && !it.completed }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$remaining 待完成", Modifier.weight(1f), fontSize = 12.sp, color = Muted)
            TextButton(onClick = { edit(newEntry().copy(kind = EventKind.TODO).normalized()) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                LineIcon(AppGlyph.Add, Modifier.size(14.dp), DutBlue)
                Spacer(Modifier.width(4.dp))
                Text("新增", fontSize = 13.sp)
            }
        }
        CalendarTaskList(s, "TDL", edit, Modifier.weight(1f), LocalFloatingAiHeight.current)
    }
}

private data class CalendarTaskSection(val label: String, val entries: List<CalendarEntry>)

@Composable private fun CalendarTaskList(
    s: AppStore,
    kind: String,
    edit: (CalendarEntry) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp
) {
    val todo = kind == "TDL"
    val now by rememberDashboardTime(active = !todo)
    val all = s.calendarEntries.filter { it.kind == if (todo) EventKind.TODO else EventKind.DEADLINE }
    val pending = all.filterNot { it.completed }.sortedWith(compareBy<CalendarEntry> { it.start }.thenBy { it.id })
    val labels = if (todo) listOf("紧急", "一般") else listOf("已逾期", "今天", "七天内", "更晚")
    val grouped = pending.groupBy { if (todo) (if (it.urgent) "紧急" else "一般") else Rules.deadlineBucket(it.start, now) }
    val sections = labels.mapNotNull { label -> grouped[label]?.let { CalendarTaskSection(label, it) } }
    val doneIds = all.filter { it.completed }.map { it.id }
    // Keep newly completed records at the end, including changes made by the AI.
    // This is presentation order only; completing never removes a stored record.
    var completedOrder by rememberSaveable(kind) { mutableStateOf(doneIds) }
    LaunchedEffect(doneIds) {
        val current = doneIds.toSet()
        completedOrder = completedOrder.filter { it in current } + doneIds.filterNot { it in completedOrder }
    }
    val positions = completedOrder.withIndex().associate { it.value to it.index }
    val done = all.filter { it.completed }.sortedWith(compareBy<CalendarEntry> { positions[it.id] ?: Int.MAX_VALUE }.thenBy { it.start }.thenBy { it.id })
    var error by remember(kind) { mutableStateOf<String?>(null) }
    fun toggle(entry: CalendarEntry) {
        runCatching {
            val current = s.calendarEntries.firstOrNull { it.id == entry.id } ?: error("这条事项已不存在")
            s.put(current.copy(completed = !current.completed))
        }.onSuccess { error = null }.onFailure { error = it.message ?: "未能保存，请重试" }
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(bottom = bottomInset + 14.dp)
    ) {
        if (error != null) item(key = "error") {
            Text(error.orEmpty(), Modifier.padding(vertical = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        if (pending.isEmpty()) item(key = "empty-pending") {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (done.isNotEmpty()) {
                    LineIcon(AppGlyph.Check, Modifier.size(16.dp), DutBlue)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (done.isNotEmpty()) "全部完成" else "还没有 $kind", fontSize = 13.sp, color = Muted)
            }
        }
        sections.forEach { section ->
            item(key = "section:${section.label}", contentType = "section") {
                CalendarTaskHeading(section.label, section.entries.size, section.label == "已逾期" || section.label == "紧急")
            }
            items(section.entries, key = { "entry:${it.id}" }, contentType = { "entry" }) { entry ->
                CalendarTaskRow(entry, now, { edit(entry) }, { toggle(entry) },
                    Modifier.animateItem(fadeInSpec = tween(150), placementSpec = spring(dampingRatio = 1f, stiffness = 420f), fadeOutSpec = tween(120)))
            }
        }
        if (done.isNotEmpty()) {
            item(key = "section:completed", contentType = "section") {
                CalendarTaskHeading("已完成", done.size)
            }
            items(done, key = { "entry:${it.id}" }, contentType = { "entry" }) { entry ->
                CalendarTaskRow(entry, now, { edit(entry) }, { toggle(entry) },
                    Modifier.animateItem(fadeInSpec = tween(150), placementSpec = spring(dampingRatio = 1f, stiffness = 420f), fadeOutSpec = tween(120)))
            }
        }
    }
}

@Composable private fun CalendarTaskHeading(label: String, count: Int, important: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (important) MaterialTheme.colorScheme.error else Muted)
        Text("$count", Modifier.padding(start = 5.dp, end = 10.dp), fontSize = 10.sp, color = Muted)
        HorizontalDivider(Modifier.weight(1f), thickness = .5.dp, color = Hairline)
    }
}

@Composable private fun CalendarTaskRow(
    entry: CalendarEntry,
    now: LocalDateTime,
    edit: () -> Unit,
    toggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deadline = entry.kind == EventKind.DEADLINE
    val overdue = deadline && !entry.completed && entry.start.isBefore(now)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = edit).padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = if (entry.completed) FontWeight.Normal else FontWeight.Medium,
                    color = if (entry.completed) Muted else Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (entry.completed) TextDecoration.LineThrough else null)
                if (deadline) Text(entry.start.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), fontSize = 11.sp, lineHeight = 15.sp, color = if (overdue) MaterialTheme.colorScheme.error else Muted)
                val detail = listOf(entry.location.trim(), entry.category.trim()).filter { it.isNotEmpty() && it != "无分类" }.joinToString(" · ")
                if (detail.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.location.isNotBlank()) LineIcon(AppGlyph.Pin, Modifier.size(11.dp), Muted)
                    Text(detail, fontSize = 11.sp, lineHeight = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (entry.notes.isNotBlank()) Text(entry.notes, fontSize = 11.sp, lineHeight = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                onClick = toggle,
                modifier = Modifier.height(36.dp).widthIn(min = 58.dp).semantics { contentDescription = "${if (entry.completed) "恢复" else "完成"}：${entry.title}" },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = if (entry.completed) SoftSurface else AccentSurface, contentColor = if (entry.completed) Muted else DutBlue)
            ) { Text(if (entry.completed) "恢复" else "完成", fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        }
        HorizontalDivider(thickness = .5.dp, color = Hairline)
    }
}

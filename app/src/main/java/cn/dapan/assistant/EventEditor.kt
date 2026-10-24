package cn.dapan.assistant

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun EventKind.editorLabel() = when (this) {
    EventKind.TIMED -> "日程"
    EventKind.DEADLINE -> "DDL"
    EventKind.TODO -> "TDL"
    EventKind.ALL_DAY -> "全天"
}

internal fun eventEditorTimedEnd(start: LocalDateTime, end: LocalDateTime): LocalDateTime =
    if (end.toLocalDate() == start.toLocalDate() && end.isAfter(start)) end
    else minOf(start.plusHours(1), start.toLocalDate().atTime(23, 59))

/** A single, stable sheet for all four kinds. Only the scrollable form changes. */
@Composable
fun EventEditor(
    initial: CalendarEntry,
    store: AppStore,
    onDismiss: () -> Unit,
    onSave: (CalendarEntry) -> Unit,
    onDelete: () -> Unit,
    onMap: (String) -> Unit = {}
) {
    var entry by remember(initial.id) { mutableStateOf(initial) }
    var start by remember(initial.id) { mutableStateOf(initial.start) }
    var end by remember(initial.id) { mutableStateOf(initial.end) }
    var absolute by remember(initial.id) { mutableStateOf(initial.absoluteReminder) }
    var travel by remember(initial.id) { mutableStateOf(initial.travelMinutes.toString()) }
    var before by remember(initial.id) { mutableStateOf(initial.beforeMinutes.toString()) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var deleting by remember(initial.id) { mutableStateOf(false) }
    val existing = store.calendarEntries.any { it.id == initial.id }
    val focus = LocalFocusManager.current
    val errorColor = MaterialTheme.colorScheme.error

    fun changeStart(next: LocalDateTime) {
        val duration = Duration.between(start, end)
        start = next
        end = minOf(next.plus(duration), next.toLocalDate().atTime(23, 59))
        error = null
    }

    fun save() {
        runCatching {
            val travelMinutes = if (entry.kind == EventKind.TIMED) {
                travel.ifBlank { "0" }.toIntOrNull()
                    ?: throw IllegalArgumentException("路程时间请输入 0～1440 分钟")
            } else 0
            val beforeMinutes = if (entry.reminderMode == ReminderMode.BEFORE) {
                before.toIntOrNull() ?: throw IllegalArgumentException("请填写提前提醒的分钟数")
            } else initial.beforeMinutes
            val value = entry.copy(
                start = start,
                end = end,
                category = category.trim(),
                travelMinutes = travelMinutes,
                beforeMinutes = beforeMinutes,
                absoluteReminder = if (entry.reminderMode == ReminderMode.ABSOLUTE) absolute else null
            ).normalized()
            val problem = value.error() ?: if (
                value.start > LocalDateTime.now() &&
                value.reminderTime()?.isBefore(LocalDateTime.now()) == true
            ) "提醒时间已过去，请修改或关闭提醒" else null
            if (problem != null) error = problem
            else {
                focus.clearFocus()
                onSave(value)
                store.addCategory(category)
            }
        }.onFailure { error = it.message ?: "保存失败，请重试" }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().height(minOf(maxHeight, 660.dp)),
                shape = AppShape(22.dp),
                color = Paper,
                tonalElevation = 0.dp
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("取消", fontSize = 14.sp, color = Muted)
                        }
                        Text(
                            if (existing) "编辑事项" else "新增事项",
                            Modifier.weight(1f), textAlign = TextAlign.Center,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink
                        )
                        Button(
                            onClick = ::save,
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DutBlue, contentColor = OnAccent),
                            shape = AppShape(18.dp)
                        ) { Text("保存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    HorizontalDivider(color = Hairline, thickness = .5.dp)
                    // The action and validation area never scroll behind the keyboard.
                    error?.let {
                        Text(
                            it, Modifier.fillMaxWidth().background(errorColor.copy(alpha = .08f))
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            color = errorColor, fontSize = 12.sp, lineHeight = 17.sp
                        )
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (existing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(AppShape(3.dp)).background(DutBlue))
                                Text(entry.kind.editorLabel(), Modifier.padding(start = 7.dp), color = Muted, fontSize = 12.sp)
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth().clip(AppShape(12.dp)).background(SoftSurface).padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                EventKind.entries.forEach { kind ->
                                    EditorChoice(kind.editorLabel(), entry.kind == kind, Modifier.weight(1f)) {
                                        if (entry.kind != kind) {
                                            val course = kind == EventKind.TIMED && category == "课程"
                                            entry = entry.copy(kind = kind, reminderMode = if (course) ReminderMode.DEPARTURE else ReminderMode.OFF)
                                            travel = if (course) "20" else "0"
                                            if (kind == EventKind.ALL_DAY) changeStart(start.toLocalDate().atStartOfDay())
                                            if (kind == EventKind.TIMED) end = eventEditorTimedEnd(start, end)
                                            error = null
                                        }
                                    }
                                }
                            }
                        }

                        EditorTextInput(
                            entry.title, { entry = entry.copy(title = it); error = null },
                            "事项名称", Modifier.fillMaxWidth().heightIn(min = 40.dp),
                            fontSize = 21, singleLine = false, maxLines = 2,
                            fontWeight = FontWeight.SemiBold, imeAction = ImeAction.Next
                        )

                        CategoryPicker(store, category) { selected ->
                            category = selected
                            error = null
                            if (selected == "课程" && entry.kind == EventKind.TIMED) {
                                travel = "20"
                                entry = entry.copy(reminderMode = ReminderMode.DEPARTURE)
                            }
                        }

                        if (entry.kind == EventKind.TODO) {
                            EditorGroup {
                                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                                    EditorLabel("优先级")
                                    Spacer(Modifier.weight(1f))
                                    Row(Modifier.width(174.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        EditorChoice("一般", !entry.urgent, Modifier.weight(1f)) { entry = entry.copy(urgent = false) }
                                        EditorChoice("紧急", entry.urgent, Modifier.weight(1f)) { entry = entry.copy(urgent = true) }
                                    }
                                }
                            }
                        } else {
                            EditorGroup {
                                EditorDateRow(start, if (entry.kind == EventKind.DEADLINE) "截止日期" else "日期") { date ->
                                    changeStart(if (entry.kind == EventKind.ALL_DAY) date.toLocalDate().atStartOfDay() else date)
                                }
                                if (entry.kind != EventKind.ALL_DAY) {
                                    EditorDivider()
                                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        EditorTimeButton(
                                            if (entry.kind == EventKind.DEADLINE) "截止时间" else "开始",
                                            start, Modifier.weight(1f), ::changeStart
                                        )
                                        if (entry.kind == EventKind.TIMED) {
                                            Text("—", Modifier.padding(horizontal = 8.dp), color = Muted, fontSize = 17.sp)
                                            EditorTimeButton("结束", end, Modifier.weight(1f)) {
                                                end = start.toLocalDate().atTime(it.toLocalTime())
                                                error = null
                                            }
                                        }
                                    }
                                }
                            }

                            EditorGroup {
                                if (entry.kind == EventKind.TIMED) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                        LineIcon(AppGlyph.Walk, Modifier.size(18.dp), Muted)
                                        Text("路程", Modifier.padding(start = 8.dp), fontSize = 13.sp, color = Ink)
                                        Spacer(Modifier.weight(1f))
                                        EditorNumber(travel, "路程分钟数") { travel = it; error = null }
                                        Text("分钟", Modifier.padding(start = 7.dp), color = Muted, fontSize = 12.sp)
                                    }
                                    EditorDivider()
                                }
                                Row(Modifier.fillMaxWidth().heightIn(min = 47.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("提醒", Modifier.padding(end = 10.dp), fontSize = 13.sp, color = Ink)
                                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        ReminderMode.entries.filter { it != ReminderMode.DEPARTURE || entry.kind == EventKind.TIMED }.forEach { mode ->
                                            val label = when (mode) {
                                                ReminderMode.OFF -> "不提醒"
                                                ReminderMode.DEPARTURE -> "出发时"
                                                ReminderMode.BEFORE -> "提前"
                                                ReminderMode.ABSOLUTE -> "指定"
                                            }
                                            EditorChoice(label, entry.reminderMode == mode, Modifier.weight(1f), small = true) {
                                                entry = entry.copy(reminderMode = mode)
                                                if (mode == ReminderMode.ABSOLUTE && absolute == null) absolute = start.minusMinutes(10)
                                                error = null
                                            }
                                        }
                                    }
                                }
                                when (entry.reminderMode) {
                                    ReminderMode.BEFORE -> {
                                        EditorDivider()
                                        Row(Modifier.fillMaxWidth().heightIn(min = 43.dp), verticalAlignment = Alignment.CenterVertically) {
                                            EditorLabel("提前")
                                            Spacer(Modifier.weight(1f))
                                            EditorNumber(before, "提前提醒分钟数") { before = it; error = null }
                                            Text("分钟", Modifier.padding(start = 7.dp), color = Muted, fontSize = 12.sp)
                                        }
                                    }
                                    ReminderMode.ABSOLUTE -> {
                                        EditorDivider()
                                        DateTimeField("提醒时间", absolute ?: start.minusMinutes(10)) { absolute = it; error = null }
                                    }
                                    ReminderMode.DEPARTURE -> {
                                        val minutes = travel.toIntOrNull() ?: 0
                                        if (minutes > 0) {
                                            Text(
                                                "${start.minusMinutes(minutes.toLong()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} 出发",
                                                Modifier.padding(bottom = 5.dp), fontSize = 11.sp, color = Muted
                                            )
                                        }
                                    }
                                    ReminderMode.OFF -> Unit
                                }
                            }
                        }

                        EditorGroup {
                            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon(AppGlyph.Pin, Modifier.size(18.dp), Muted)
                                EditorTextInput(
                                    entry.location, { entry = entry.copy(location = it) }, "添加地点",
                                    Modifier.weight(1f).padding(start = 9.dp, end = 4.dp), imeAction = ImeAction.Next
                                )
                                if (existing && initial.location.isNotBlank()) {
                                    IconButton(
                                        onClick = { onMap(initial.location) },
                                        modifier = Modifier.size(36.dp).semantics { contentDescription = "查看已保存地点" }
                                    ) { LineIcon(AppGlyph.Next, Modifier.size(16.dp), DutBlue) }
                                }
                            }
                            EditorDivider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.Top) {
                                LineIcon(AppGlyph.File, Modifier.padding(top = 2.dp).size(18.dp), Muted)
                                EditorTextInput(
                                    entry.notes, { entry = entry.copy(notes = it) }, "添加备注",
                                    Modifier.weight(1f).padding(start = 9.dp).heightIn(min = 62.dp),
                                    singleLine = false, maxLines = 8, imeAction = ImeAction.Default
                                )
                            }
                        }

                        if (entry.kind == EventKind.DEADLINE || entry.kind == EventKind.TODO) {
                            Row(
                                Modifier.fillMaxWidth().clip(AppShape(10.dp))
                                    .clickable { entry = entry.copy(completed = !entry.completed) }
                                    .heightIn(min = 42.dp).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = entry.completed,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = DutBlue, checkmarkColor = OnAccent),
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                                Text("已完成", fontSize = 13.sp, color = Ink)
                            }
                        }
                        if (existing) {
                            TextButton(
                                onClick = { deleting = true },
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.align(Alignment.Start)
                            ) {
                                LineIcon(AppGlyph.Trash, Modifier.size(17.dp), errorColor)
                                Text("删除事项", Modifier.padding(start = 7.dp), color = errorColor, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除这个事项？") },
            text = { Text(entry.title) },
            confirmButton = { TextButton(onClick = onDelete) { Text("删除", color = errorColor) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("保留") } }
        )
    }
}

@Composable
private fun EditorGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = CardSurface, shape = AppShape(13.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp), content = content)
    }
}

@Composable
private fun EditorDivider() = HorizontalDivider(color = Hairline.copy(alpha = .65f), thickness = .5.dp)

@Composable
private fun EditorLabel(text: String) = Text(text, color = Ink, fontSize = 13.sp)

@Composable
private fun EditorChoice(
    label: String, selected: Boolean, modifier: Modifier = Modifier,
    small: Boolean = false, onClick: () -> Unit
) {
    Box(
        modifier.height(if (small) 38.dp else 40.dp).clip(AppShape(if (small) 8.dp else 10.dp))
            .background(if (selected) AccentSurface else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(horizontal = if (small) 2.dp else 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) DutBlue else Muted,
            fontSize = if (small) 12.sp else 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1)
    }
}

@Composable
private fun EditorTextInput(
    value: String, onChange: (String) -> Unit, hint: String,
    modifier: Modifier = Modifier, fontSize: Int = 14, singleLine: Boolean = true,
    maxLines: Int = 1, fontWeight: FontWeight = FontWeight.Normal,
    imeAction: ImeAction = ImeAction.Done
) {
    val focus = LocalFocusManager.current
    BasicTextField(
        value, onChange, modifier.semantics { contentDescription = hint },
        textStyle = TextStyle(color = Ink, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp, fontWeight = fontWeight),
        cursorBrush = SolidColor(DutBlue), singleLine = singleLine,
        maxLines = if (singleLine) 1 else maxLines,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { inner ->
            Box(contentAlignment = if (singleLine || maxLines == 2) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Text(hint, color = Muted, fontSize = fontSize.sp, fontWeight = fontWeight)
                inner()
            }
        }
    )
}

@Composable
private fun EditorNumber(value: String, label: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    BasicTextField(
        value, { next -> if (next.length <= 4 && next.all(Char::isDigit)) onChange(next) },
        Modifier.width(64.dp).height(38.dp).clip(AppShape(8.dp)).background(SoftSurface)
            .semantics { contentDescription = label },
        singleLine = true,
        textStyle = TextStyle(color = DutBlue, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(DutBlue),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { inner -> Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) { inner() } }
    )
}

@Composable
private fun EditorDateRow(value: LocalDateTime, label: String, onChange: (LocalDateTime) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(AppShape(8.dp)).clickable {
            DatePickerDialog(context, platformDialogTheme, { _, year, month, day ->
                onChange(value.withDayOfMonth(1).withYear(year).withMonth(month + 1).withDayOfMonth(day))
            }, value.year, value.monthValue - 1, value.dayOfMonth).show()
        }.semantics { contentDescription = "$label，${value.toLocalDate()}"; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorLabel(label)
        Spacer(Modifier.weight(1f))
        Text(value.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA)), fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
        LineIcon(AppGlyph.Next, Modifier.padding(start = 5.dp).size(15.dp), Muted)
    }
}

@Composable
private fun EditorTimeButton(label: String, value: LocalDateTime, modifier: Modifier, onChange: (LocalDateTime) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier.clip(AppShape(10.dp)).clickable {
            TimePickerDialog(context, platformDialogTheme, { _, hour, minute ->
                onChange(value.withHour(hour).withMinute(minute).withSecond(0).withNano(0))
            }, value.hour, value.minute, true).show()
        }.semantics { contentDescription = "$label，${value.toLocalTime()}"; role = Role.Button }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value.format(DateTimeFormatter.ofPattern("HH:mm")), color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DateTimeField(label: String, value: LocalDateTime, onChange: (LocalDateTime) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, color = Muted)
        TextButton(onClick = {
            DatePickerDialog(context, platformDialogTheme, { _, year, month, day ->
                onChange(value.withDayOfMonth(1).withYear(year).withMonth(month + 1).withDayOfMonth(day))
            }, value.year, value.monthValue - 1, value.dayOfMonth).show()
        }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(value.format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 13.sp, color = DutBlue)
        }
        TextButton(onClick = {
            TimePickerDialog(context, platformDialogTheme, { _, hour, minute ->
                onChange(value.withHour(hour).withMinute(minute).withSecond(0).withNano(0))
            }, value.hour, value.minute, true).show()
        }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(value.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 13.sp, color = DutBlue)
        }
    }
}

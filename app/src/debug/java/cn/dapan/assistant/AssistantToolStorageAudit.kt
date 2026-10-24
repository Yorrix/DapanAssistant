package cn.dapan.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Uses only the isolated audit Context. Never starts recording, network calls or notifications. */
internal fun runToolStorageAudit(c: Context, phase: String, verify: (String, () -> Unit) -> Unit) {
 val stateFile = File(c.filesDir, "tool-audit-expected.json")
 val wheelStore = WheelStore(c)
 val meetings = MeetingLibrary(c)
 if (phase == "write") {
  val wheel = SavedWheel("wheel-audit", "本周谁打扫", listOf(
   WheelOption("a", "甲同学", 1.0), WheelOption("b", "乙同学", 1.0), WheelOption("c", "丙同学", 2.0)))
  verify("Wheel save preserves 1:1:2 weights and selection") {
   wheelStore.save(wheel)
   check(wheelStore.selected == wheel.id && wheelStore.wheels.single { it.id == wheel.id } == wheel)
   check(WheelMath.sectors(wheel).map { it.sweep } == listOf(90.0, 90.0, 180.0))
  }
  verify("Wheel edit replaces same ID without duplicates") {
   wheelStore.save(wheel.copy(name = "宿舍值日", options = wheel.options.map { if (it.id == "c") it.copy(name = "丙", weight = 0.0) else it }))
   val edited = wheelStore.wheels.single { it.id == wheel.id }
   check(edited.name == "宿舍值日" && edited.options.last().weight == 0.0)
   repeat(300) { check(WheelMath.pick(edited).option.id != "c") }
  }
  verify("Invalid zero-weight wheel does not change persisted data") {
   val file = File(c.filesDir, "decision-wheels-v1.json"); val before = file.readText()
   check(runCatching { wheelStore.save(wheel.copy(options = wheel.options.map { it.copy(weight = 0.0) })) }.isFailure)
   check(file.readText() == before)
  }
  verify("Wheel select and delete maintain valid active wheel") {
   val temporary = wheel.copy(id = "wheel-delete", name = "删除这个")
   wheelStore.save(temporary); wheelStore.select(wheel.id); wheelStore.delete(temporary.id)
   check(wheelStore.selected == wheel.id && wheelStore.wheels.none { it.id == temporary.id })
  }
  verify("Wheel timing and pointer settings persist exact values") {
   wheelStore.configure(WheelSettings(true, "短", 3000)); check(wheelStore.settings.milliseconds == 500)
   wheelStore.configure(WheelSettings(true, "自定义", 1750)); check(wheelStore.settings.milliseconds == 1750)
   check(WheelStore(c).settings == wheelStore.settings)
  }
  verify("Invalid wheel timing cannot overwrite settings") {
   val before = wheelStore.settings
   check(runCatching { wheelStore.configure(WheelSettings(true, "自定义", 99)) }.isFailure)
   check(wheelStore.settings == before && WheelStore(c).settings == before)
  }
  val folderId = meetings.create("数学课程", "课程")
  val otherId = meetings.create("临时事项", "其他")
  fun folder(id: String) = meetings.folders(Recordings.clips(c)).single { it.id == id }
  verify("Recording folders support course/meeting/other and rename") {
   check(folder(folderId).kind == "课程" && folder(otherId).kind == "其他")
   meetings.rename(folder(folderId), "微积分复习", "课程")
   check(meetings.title(folderId) == "微积分复习")
  }
  fun fixtureClip(n: Long, session: String, name: String, text: String): Clip {
   val id = UUID(0, n).toString(); val dir = Recordings.directory(c)
   File(dir, "$id.wav").writeBytes(ByteArray(44)) // Metadata fixture, never played or recorded.
   File(dir, "$id.json").writeText(JSONObject().put("id", id).put("session", session).put("name", name)
    .put("created", 1000 + n).put("lines", JSONArray().put(JSONObject().put("text", text).put("start", n * 1000).put("end", n * 1000 + 1500))).toString())
   return Recordings.clips(c).single { it.id == id }
  }
  val first = fixtureClip(1, folderId, "第一段", "极限的定义")
  val second = fixtureClip(2, folderId, "第二段", "导数和积分")
  val other = fixtureClip(3, otherId, "无关记录", "采购清单")
  verify("Recording folder order filters other sessions") {
   check(meetings.order(folderId, Recordings.clips(c)).map { it.id } == listOf(first.id, second.id))
   meetings.saveOrder(folder(folderId), listOf(second.id, first.id))
   check(meetings.order(folderId, Recordings.clips(c)).map { it.id } == listOf(second.id, first.id))
  }
  verify("Combined transcript labels each recording and keeps order") {
   val ordered = meetings.order(folderId, Recordings.clips(c)); val source = meetingSource(ordered)
   check(source.contains("录音 R1：第二段") && source.contains("【R1 00:00:02】 导数和积分"))
   check(source.contains("录音 R2：第一段") && !source.contains("采购清单"))
  }
  verify("Summary keeps Markdown and exact clip IDs") {
   val clips = meetings.order(folderId, Recordings.clips(c))
   meetings.saveSummary(folder(folderId), "# 复习提纲\n- 极限\n- **导数**", meetingSignature(clips, "课程", "保留公式"), clips.map { it.id })
   val summary = meetings.summary(folderId)!!
   check(summary.text.startsWith("# 复习提纲") && summary.clipIds == clips.map { it.id })
   check(summary.isCurrent(clips, "课程", "保留公式"))
   check(!summary.isCurrent(clips.reversed(), "课程", "保留公式") && !summary.isCurrent(clips, "课程", "另外的提示"))
  }
  verify("Transcript correction updates source and invalidates old summary") {
   Recordings.replaceLines(c, first.id, listOf(TranscriptLine("修正：函数极限", 1000, 2800)))
   val updated = Recordings.clips(c).single { it.id == first.id }
   check(updated.lines.single().text == "修正：函数极限")
   check(!meetings.summary(folderId)!!.isCurrent(meetings.order(folderId, Recordings.clips(c)), "课程", "保留公式"))
  }
  verify("Restore previous transcript restores summary signature") {
   Recordings.restorePrevious(c, first.id)
   check(Recordings.clips(c).single { it.id == first.id }.lines == first.lines)
   check(meetings.summary(folderId)!!.isCurrent(meetings.order(folderId, Recordings.clips(c)), "课程", "保留公式"))
  }
  verify("Invalid transcript timestamps preserve prior text") {
   check(runCatching { Recordings.replaceLines(c, first.id, listOf(TranscriptLine("非法", 200, 100))) }.isFailure)
   check(Recordings.clips(c).single { it.id == first.id }.lines == first.lines)
  }
  verify("Recording rename keeps identity and old audio") {
   Recordings.rename(c, second, "导数专题")
   val renamed = Recordings.clips(c).single { it.id == second.id }
   check(renamed.name == "导数专题" && renamed.audio == second.audio && renamed.audio.exists())
  }
  verify("Recording deletion and folder removal are scoped") {
   Recordings.delete(c, other); meetings.remove(folder(otherId))
   check(Recordings.clips(c).none { it.id == other.id } && !other.audio.exists())
   check(meetings.folders(Recordings.clips(c)).none { it.id == otherId })
   check(Recordings.clips(c).size == 2 && meetings.title(folderId) == "微积分复习")
  }
  val current = meetings.order(folderId, Recordings.clips(c))
  meetings.saveSummary(folder(folderId), "# 复习提纲\n- 极限\n- **导数**", meetingSignature(current, "课程", "保留公式"), current.map { it.id })
  VocabularyStore(c).use { words ->
   val a = VocabularyWord("eigenvalue", "/ˈaɪɡənvæljuː/", "特征值", 0)
   val b = VocabularyWord("differentiable", "", "可微的", 0)
   val basic = VocabularyWord("hello", "", "你好", 1)
   val cet4 = VocabularyWord("procedure", "", "过程", 4)
   verify("Vocabulary stores new unfamiliar words newest first") {
    words.add(listOf(VocabularyHit(a, 1), VocabularyHit(b, 1), VocabularyHit(basic, 1), VocabularyHit(cet4, 1)))
    check(words.list(VocabularyLevel.HIGH_SCHOOL).map { it.entry.word } == listOf("procedure", "differentiable", "eigenvalue"))
   }
   verify("Vocabulary repeat increments occurrence count without duplicates") {
    words.add(listOf(VocabularyHit(a, 2)))
    check(words.list(VocabularyLevel.HIGH_SCHOOL).single { it.entry.word == a.word }.count == 3)
    check(words.list(VocabularyLevel.HIGH_SCHOOL).size == 3)
   }
   verify("Vocabulary level excludes mastered wordlists and basic words") {
    check(words.list(VocabularyLevel.CET4).map { it.entry.word } == listOf("differentiable", "eigenvalue"))
    check(words.list(VocabularyLevel.BASIC).none { it.entry.word == "hello" })
   }
   verify("Mark known and undo known affect only target word") {
    words.mark(a.word, true); check(a.word in words.known() && words.list(VocabularyLevel.HIGH_SCHOOL).none { it.entry.word == a.word })
    words.mark(a.word, false); check(words.list(VocabularyLevel.HIGH_SCHOOL).any { it.entry.word == a.word })
    words.mark(b.word, true)
   }
   verify("Vocabulary English and Chinese search") {
    check(words.list(VocabularyLevel.CET6, "特征").single().entry.word == a.word)
    check(words.list(VocabularyLevel.CET6, "EIGEN").single().entry.word == a.word)
   }
  }
  verify("Vocabulary preferences persist separately from heard-word data") {
   VocabularyPreferences(c).apply { level = VocabularyLevel.CET6; context = "Mathematics lecture" }
   check(VocabularyPreferences(c).level == VocabularyLevel.CET6 && VocabularyPreferences(c).context == "Mathematics lecture")
  }
  val banner = BannerOptions("大工加油", 128f, 72f, 0xFF11AA22.toInt(), 0xFF101010.toInt(), false, true, false, true, 3f)
  verify("Banner configuration preserves text, colors, dimensions and motion") {
   c.getSharedPreferences("handheld-banner", 0).edit().putString("options", banner.json()).commit()
   check(BannerOptions.load(c) == banner)
  }
  verify("Banner malformed/oversized settings use safe bounds") {
   check(BannerOptions.parse("{") == BannerOptions())
   val bounds = BannerOptions.parse("{\"text\":\"测试\",\"size\":900,\"speed\":-1,\"shake\":80}")
   check(bounds.size == 240f && bounds.speed == 20f && bounds.shake == 12f)
  }
  verify("Campus hotword default includes 玉兰卡 and custom words survive toggle") {
   val prefs = AsrHotwordPreferences(c)
   check("玉兰卡" in prefs.active())
   prefs.save(false, listOf("玉兰卡", "偏微分方程")); check(prefs.active().isEmpty())
   prefs.save(true, prefs.words()); check(prefs.active() == listOf("玉兰卡", "偏微分方程"))
  }
  verify("Invalid hotword list cannot overwrite saved words") {
   val prefs = AsrHotwordPreferences(c); val before = prefs.words()
   check(runCatching { prefs.save(true, listOf("长".repeat(41))) }.isFailure)
   check(prefs.words() == before)
  }
  // Force any queued SharedPreferences writes to disk before the caller kills this process.
  listOf("classroom-vocabulary", "handheld-banner", "asr-hotwords").forEach { c.getSharedPreferences(it, 0).edit().putBoolean("audit_flush", true).commit() }
  stateFile.writeText(JSONObject().put("meetingId", folderId).put("clipIds", JSONArray(current.map { it.id })).toString())
 } else {
  val state = JSONObject(stateFile.readText()); val folderId = state.getString("meetingId")
  verify("Wheel state survives process restart") {
   check(wheelStore.selected == "wheel-audit" && wheelStore.wheels.single { it.id == "wheel-audit" }.name == "宿舍值日")
   check(wheelStore.settings == WheelSettings(true, "自定义", 1750))
  }
  verify("Recording folders, clip order and summary survive process restart") {
   val clips = meetings.order(folderId, Recordings.clips(c))
   check(meetings.title(folderId) == "微积分复习" && clips.map { it.name } == listOf("导数专题", "第一段"))
   check(meetings.summary(folderId)!!.isCurrent(clips, "课程", "保留公式"))
  }
  verify("Vocabulary known status and occurrence counts survive process restart") {
   VocabularyStore(c).use { words ->
    check("differentiable" in words.known())
    check(words.list(VocabularyLevel.CET6).single().count == 3)
    words.clear(); check(words.list(VocabularyLevel.BASIC).isEmpty() && "differentiable" in words.known())
   }
  }
  verify("Vocabulary and banner preferences survive process restart") {
   check(VocabularyPreferences(c).level == VocabularyLevel.CET6)
   check(BannerOptions.load(c).text == "大工加油" && BannerOptions.load(c).speed == 72f)
  }
  verify("ASR hotwords survive process restart") {
   check(AsrHotwordPreferences(c).active() == listOf("玉兰卡", "偏微分方程"))
  }
 }
}

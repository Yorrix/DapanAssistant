package cn.dapan.assistant

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import org.json.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun ClassroomScreen(onBack:()->Unit){
 val c=LocalContext.current;val state=ClassroomSession
 state.initialize(c)
 var settings by remember{mutableStateOf(false)};var history by remember{mutableStateOf(false)}
 var tab by rememberSaveable{mutableStateOf("发言")};var message by remember{mutableStateOf<String?>(null)}
 var adding by remember{mutableStateOf(false)};var editing by remember{mutableStateOf<ClassroomLine?>(null)}
 var topic by rememberSaveable(state.selected){mutableStateOf(state.lesson.topic)}
 LaunchedEffect(state.lesson.topic){topic=state.lesson.topic}
 LaunchedEffect(topic,state.selected){val id=state.selected;delay(500);if(state.ready&&state.selected==id&&state.lesson.topic!=topic)state.topic(topic)}
 fun act(block:()->Unit){runCatching(block).onFailure{message=it.message?:"操作未完成，请重试"}}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)act{state.start()}else message="未允许麦克风，可以直接补充文字"}
 fun listen(){act{if(state.listening)state.stop()else if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.RECORD_AUDIO)else state.start()}}
 fun generate(){act{state.topic(topic);state.generate();tab="发言"}}
 fun copy(text:String){c.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("课堂助手",text));android.widget.Toast.makeText(c,"已复制",android.widget.Toast.LENGTH_SHORT).show()}
 AssistantSurface("课堂助手",JSONObject().put("ready",state.ready).put("selectedId",state.selected).put("mode",state.mode.label).put("prompt",state.prompt).put("automatic",state.automatic).put("topic",state.lesson.topic).put("listening",state.listening).put("generating",state.generating).put("drafts",JSONArray(state.lesson.drafts.takeLast(3).map{it.text})).put("recent",state.lesson.raw().takeLast(5000)),listOf(
  surfaceTool("configure","mode选择提示词预设：讨论发言/思政课堂/通用课堂/自定义；prompt修改后自动成为自定义，不覆盖预设；topic主题，level=1..7；automatic开启或关闭意图检测自动生成，各项可单独修改","mode,topic,prompt,level,automatic"){a->val mode=if(a.has("mode"))ClassroomMode.entries.singleOrNull{it.label==a.getString("mode")}?:error("预设不存在")else state.mode;val prompt=a.optString("prompt",state.promptFor(mode));val level=a.optInt("level",state.level);val automatic=if(a.has("automatic"))a.getBoolean("automatic")else state.automatic;require(level in 1..7&&prompt.length<=4000&&a.optString("topic").length<=2000);prepared("设置课堂助手",a){state.configure(mode,prompt,level,newAutomatic=automatic);if(a.has("topic")){state.topic(a.getString("topic"));topic=state.lesson.topic};"提示词与自动生成设置已保存"}},
  surfaceTool("generate","根据当前讨论记录准备一段发言；question可补充本次问题或想强调的观点，生成草稿不代表已经发言","question"){a->val question=a.optString("question");require(question.length<=2000);prepared("生成课堂发言草稿",a){state.generate(question);tab="发言";"已开始生成，完整结果会保存在课堂发言草稿中"}},
  surfaceTool("add","补充真实课堂文字记录，text内容，speaker可选发言人；不得把AI草稿直接当成已说过的话","text,speaker"){a->val text=a.text("text",12000);val speaker=a.optString("speaker","未标注");require(speaker.isNotBlank()&&speaker.length<=30);prepared("补充课堂记录",a){state.add(text,speaker);"已保存课堂文字记录"}},
  surfaceTool("start","持续收听课堂并记录转写，需要麦克风权限及当前选用的语音服务","",true){a->require(!AudioState.running);prepared("开始课堂收听",a){state.start();"已请求开始收听，状态见课堂页面"}},
  surfaceTool("stop","停止课堂收听，保留已有转写和草稿","",true){a->prepared("暂停课堂收听",a){state.stop();"已请求暂停课堂收听"}}
 ))
 Column(Modifier.fillMaxSize()){
  Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){QuietIconButton("返回",AppGlyph.Back,onBack);Text("课堂助手",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold);TextButton(onClick={history=true}){Text("记录",fontSize=12.sp)};QuietIconButton("课堂设置",AppGlyph.Settings,{settings=true})}
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
   TextButton(onClick={settings=true},contentPadding=PaddingValues(horizontal=4.dp)){Text("提示词 · ${state.mode.label}",fontSize=12.sp)}
   Spacer(Modifier.weight(1f));Text("自动生成",fontSize=12.sp,color=Muted);Spacer(Modifier.width(8.dp));Switch(state.automatic,{act{state.toggleAutomatic(it)}},modifier=Modifier.semantics{contentDescription="自动生成"})
  }
  OutlinedTextField(topic,{topic=it.take(2000)},Modifier.fillMaxWidth().padding(horizontal=12.dp),placeholder={Text("讨论主题、老师的问题或你的想法",fontSize=13.sp)},maxLines=3,shape=AppShape(12.dp),textStyle=LocalTextStyle.current.copy(fontSize=14.sp))
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedButton(onClick={listen()},enabled=state.ready&&(!AudioState.running||state.listening),contentPadding=PaddingValues(horizontal=12.dp),modifier=Modifier.height(38.dp)){
    LineIcon(if(state.listening)AppGlyph.Stop else AppGlyph.Mic,Modifier.size(16.dp),DutBlue);Spacer(Modifier.width(5.dp));Text(if(state.listening)"暂停收听"else"开始收听",fontSize=13.sp)}
   Button(onClick={if(state.generating)state.cancel()else generate()},enabled=state.ready,modifier=Modifier.weight(1f).height(38.dp)){Text(if(state.generating)"停止生成"else"生成发言",fontSize=14.sp)}
   TextButton(onClick={adding=true},enabled=state.ready,contentPadding=PaddingValues(0.dp)){Text("补文字",fontSize=12.sp)}
  }
  Row(Modifier.fillMaxWidth().padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){
   listOf("发言","转写").forEach{label->TextButton(onClick={tab=label},contentPadding=PaddingValues(horizontal=8.dp)){Text(if(label=="转写")"转写 ${state.lesson.lines.size}"else"发言 ${state.lesson.drafts.size}",fontSize=13.sp,color=if(tab==label)DutBlue else Muted,fontWeight=if(tab==label)FontWeight.SemiBold else FontWeight.Normal)}}
   Spacer(Modifier.weight(1f));Text(if(state.listening)if(AudioState.connectionReady)"正在听"else"连接中…"else if(state.compressing)"整理背景中"else"已保存在本机",fontSize=11.sp,color=if(state.listening)DutBlue else Muted)
  }
  HorizontalDivider(color=Hairline,thickness=.5.dp)
  if(state.partial.isNotBlank())Text(state.partial,Modifier.fillMaxWidth().background(SoftSurface).padding(horizontal=14.dp,vertical=6.dp),fontSize=12.sp,lineHeight=17.sp,color=Muted,maxLines=2)
  val audioError=if(AudioState.session==state.owner)AudioState.error else null
  (state.error?:audioError)?.let{Text(it,Modifier.padding(horizontal=14.dp,vertical=4.dp),fontSize=12.sp,color=Coral)}
  LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=14.dp,end=14.dp,top=8.dp,bottom=12.dp+LocalFloatingAiHeight.current),verticalArrangement=Arrangement.spacedBy(10.dp)){
   if(!state.ready)item{Text("正在读取课堂记录…",fontSize=13.sp,color=Muted)}
   if(tab=="发言"){
    if(state.generating||state.preview.isNotBlank())item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(state.status,fontSize=11.sp,color=Muted);SelectionContainer{Text(state.preview.ifBlank{"正在结合课堂内容…"},fontSize=18.sp,lineHeight=29.sp)};if(!state.generating)TextButton(onClick={copy(state.preview)}){Text("复制已接收文字")}}}
    if(state.lesson.drafts.isEmpty()&&!state.generating)item{Text(if(state.automatic)"开始收听后，识别提问或邀请发言，自动准备回答。也可随时手动生成。"else"写下主题或开始收听，轮到你时点“生成发言”。",fontSize=14.sp,lineHeight=22.sp,color=Muted)}
    items(state.lesson.drafts.asReversed(),key={it.id}){draft->Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){Text("${classroomTime(draft.at)} · ${draft.mode} · 草稿",Modifier.weight(1f),fontSize=11.sp,color=Muted);TextButton(onClick={copy(draft.text)},contentPadding=PaddingValues(0.dp)){Text("复制",fontSize=12.sp)}}
     SelectionContainer{Text(draft.text,fontSize=18.sp,lineHeight=29.sp)};HorizontalDivider(Modifier.padding(top=6.dp),color=Hairline,thickness=.5.dp)
    }}
   }else{
    if(state.lesson.lines.isEmpty())item{Text("收听后显示真实转写。点记录可修正文字、标注发言人。",fontSize=13.sp,color=Muted)}
    items(state.lesson.lines.asReversed(),key={it.id}){line->Column(Modifier.fillMaxWidth().clickable(enabled=!state.listening){editing=line}.padding(vertical=3.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
     Text("${classroomTime(line.at)} · ${line.speaker}",fontSize=11.sp,color=Muted);Text(line.text,fontSize=14.sp,lineHeight=22.sp);HorizontalDivider(color=Hairline,thickness=.5.dp)
    }}
   }
  }
 }
 if(settings)ClassroomSettings{settings=false}
 if(history)AlertDialog(onDismissRequest={history=false},title={Text("课堂记录")},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())){state.lessons.asReversed().forEach{lesson->TextButton(onClick={act{state.select(lesson.id);history=false}},enabled=!state.listening){Column(Modifier.fillMaxWidth()){Text(lesson.topic.ifBlank{"未命名课堂"},maxLines=1);Text("${classroomDate(lesson.created)} · ${lesson.lines.size} 段转写 / ${lesson.drafts.size} 份草稿",fontSize=11.sp,color=Muted)}}}}},confirmButton={TextButton(onClick={act{state.newLesson();history=false}},enabled=state.ready&&!state.listening){Text("新一课")}},dismissButton={TextButton(onClick={copy(state.lesson.lines.joinToString("\n"){"${classroomTime(it.at)} ${it.speaker}：${it.text}"})},enabled=state.lesson.lines.isNotEmpty()){Text("复制转写")}})
 if(adding||editing!=null){
  var text by remember(editing){mutableStateOf(editing?.text.orEmpty())};var speaker by remember(editing){mutableStateOf(editing?.speaker?:"我")}
  AlertDialog(onDismissRequest={adding=false;editing=null},title={Text(if(editing==null)"补充课堂记录"else"修正转写")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(speaker,{speaker=it.take(30)},label={Text("发言人")},singleLine=true);OutlinedTextField(text,{text=it.take(12000)},label={Text("实际发言或课堂材料")},maxLines=8)}},confirmButton={TextButton(onClick={act{val line=editing;if(line==null)state.add(text,speaker)else state.editLine(line.id,speaker,text);adding=false;editing=null}},enabled=text.isNotBlank()&&speaker.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={adding=false;editing=null}){Text("取消")}})
 }
 if(message!=null)AlertDialog(onDismissRequest={message=null},text={Text(message!!)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}})
}
private fun classroomTime(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
private fun classroomDate(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@Composable private fun ClassroomSettings(onClose:()->Unit){
 val state=ClassroomSession
 var editor by remember{mutableStateOf(ClassroomPromptEditor(ClassroomPromptChoice(state.mode,state.prompt),state.customPrompt))}
 var level by remember{mutableFloatStateOf(state.level.toFloat())}
 var asr by remember{mutableStateOf(state.asrContext)}
 var recent by remember{mutableStateOf(state.limits.recent.toString())};var total by remember{mutableStateOf(state.limits.total.toString())}
 var window by remember{mutableStateOf(state.detectionChars.toString())};var automatic by remember{mutableStateOf(state.automatic)}
 var error by remember{mutableStateOf<String?>(null)}
 AudioSettingsDialog("课堂设置",asr,{asr=it},editor.choice.text,{editor=editor.edit(it)},"发言","写下希望如何回答，或如何参与讨论",4000,{
  runCatching{state.configure(newMode=editor.choice.mode,prompt=editor.choice.text,newLevel=level.toInt(),recent=recent.toInt(),total=total.toInt(),asr=asr,windowChars=window.toInt(),newAutomatic=automatic,savedCustom=editor.custom);onClose()}.onFailure{error=it.message?:"请检查设置数值"}
 },before={
  Row(verticalAlignment=Alignment.CenterVertically){Text("自动生成",Modifier.weight(1f),fontSize=13.sp);Switch(automatic,{automatic=it})}
  Text("收听中识别提问或邀请发言；关闭后手动生成。",fontSize=11.sp,color=Muted)
  val style=ClassroomSpeechStyle.levels[level.toInt()-1]
  Text("表达：${style.name} · ${style.minChars}–${style.maxChars} 字",fontSize=13.sp)
  Slider(level,{level=it},valueRange=1f..7f,steps=5)
 },generationControls={
  Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
   ClassroomMode.entries.forEach{preset->FilterChip(selected=editor.choice.mode==preset,onClick={editor=editor.select(preset)},label={Text(preset.label,fontSize=12.sp)})}
  }
  Text("选择预设填入提示词；修改后成为自定义，原预设保留。",fontSize=11.sp,color=Muted)
 },after={
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(recent,{recent=it.filter(Char::isDigit).take(6)},Modifier.weight(1f),label={Text("保留原文字数",fontSize=11.sp)},singleLine=true);OutlinedTextField(total,{total=it.filter(Char::isDigit).take(6)},Modifier.weight(1f),label={Text("上下文总字数",fontSize=11.sp)},singleLine=true)}
  Text("较早内容压缩为背景；完整转写始终保留。",fontSize=11.sp,color=Muted)
  if(automatic)OutlinedTextField(window,{window=it.filter(Char::isDigit).take(4)},label={Text("意图检测窗口 · 20–2000 字",fontSize=12.sp)},singleLine=true)
  Text("转写发往阿里云，生成时发送所选课堂上下文给文字 AI。公用模式计入共享额度，自备模式使用个人 API。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
  error?.let{Text(it,fontSize=12.sp,color=Coral)}
 })
}

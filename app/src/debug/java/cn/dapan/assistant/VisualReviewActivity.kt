package cn.dapan.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/** Shell-only visual fixture. Use on the emulator; never seeds user calendar data. */
class VisualReviewActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  AppAppearance.setStyle(this,UiStyle.from(intent.getStringExtra("style")))
  AppAppearance.set(this,if(intent.getBooleanExtra("dark",false))"dark"else"light")
  if(intent.getBooleanExtra("translation",false)){
   setContent{DapanTheme{TranslationMotionReview()}}
   return
  }
  val sample=CalendarEntry(title="高等数学 · 极限与连续",start=LocalDateTime.of(2026,10,20,8,0),end=LocalDateTime.of(2026,10,20,9,35),
   location="A01 · 302 教室",notes="带教材和上次的习题。课前复习极限的定义，课后整理今天的错题。",category="课程")
  setContent{DapanTheme{
   Surface(Modifier.fillMaxSize(),color=Paper){
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
     Text("${AppAppearance.style.label} · ${if(AppAppearance.dark)"黑夜"else"白天"}",fontSize=20.sp)
     Text("缩小：时间与标题优先",fontSize=12.sp,color=Muted)
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
      listOf(68.dp to 68.dp,120.dp to 95.dp).forEach{(w,h)->
       Box(Modifier.width(w).height(h).clip(RoundedCornerShape(6.dp)).background(CardSurface).background(calendarEventTint(sample).copy(alpha=.14f))){CalendarEventLabel(sample,w,h.value)}
      }
     }
     Text("放大：时间、标题、地点与备注",fontSize=12.sp,color=Muted)
     Box(Modifier.width(300.dp).height(220.dp).clip(RoundedCornerShape(6.dp)).background(CardSurface).background(calendarEventTint(sample).copy(alpha=.14f))){CalendarEventLabel(sample,300.dp,220f)}
     StyleChooser()
    }
   }
  }}
 }
}

/** In-memory scripted ASR/translation: no mic, network, credentials or persisted history. */
@Composable
private fun TranslationMotionReview(){
 var turns by remember{mutableStateOf((1..8).map{
  TranslationTurn(id="sample-$it",speaker="讲话",from="英语",to="中文",
   text="We will discuss this question in the next class.",
   translated="我们会在下一节课讨论这个问题。")
 })}
 var draft by remember{mutableStateOf("draft-0")}
 var partial by remember{mutableStateOf("")}
 var original by remember{mutableStateOf(true)}
 var listening by remember{mutableStateOf(true)}
 LaunchedEffect(Unit){
  delay(1200)
  repeat(4){index->
   val words="Please remember to bring your notes to the next class and review the examples we discussed today.".split(" ")
   for(word in words){partial=if(partial.isEmpty())word else "$partial $word";delay(100)}
   val id=draft
   turns=turns+TranslationTurn(id=id,speaker="讲话",from="英语",to="中文",text=partial,pending=true)
   draft="draft-${index+1}";partial=""
   delay(700)
   turns=turns.map{if(it.id==id)it.copy(pending=false,translated="请记得在下次上课时带上笔记，并复习我们今天讨论的例题。尤其注意解题过程中每一步所用的条件，以及这些方法在其他问题中的应用。")else it}
   delay(1100)
  }
  listening=false
 }
 Surface(Modifier.fillMaxSize(),color=Paper){
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
   Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=16.dp)){
    Text("实时翻译 · 动效检查",Modifier.weight(1f),fontSize=18.sp)
    TextButton(onClick={original=!original}){Text(if(original)"隐藏原文"else"显示原文")}
   }
   TranslationTranscript(turns,draft,listening,partial,"英语","中文",original,19f,{},{},Modifier.weight(1f).fillMaxWidth())
  }
 }
}

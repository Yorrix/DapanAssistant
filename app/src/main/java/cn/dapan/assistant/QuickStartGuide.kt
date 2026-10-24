package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun QuickStartGuide(secret:Secrets,policy:AssistantPolicy,onFinish:(String,String?)->Unit){
 var step by rememberSaveable{mutableIntStateOf(0)}
 var error by remember{mutableStateOf<String?>(null)}
 var example by rememberSaveable{mutableStateOf<String?>(null)}
 var accountOpen by rememberSaveable{mutableStateOf(false)}
 val titles=listOf("先接通 AI","直接说你想做什么","操作权限，由你决定","换成你喜欢的样子","这些功能，都在这里")
 fun move(to:Int){step=to;error=null}
 Dialog(onDismissRequest={onFinish("",null)},properties=DialogProperties(usePlatformDefaultWidth=false)){
  BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),contentAlignment=Alignment.Center){
   Surface(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=maxHeight),shape=AppShape(20.dp),color=CardSurface,border=BorderStroke(.5.dp,Hairline)){
    Column{
     Column(Modifier.padding(start=16.dp,end=8.dp,top=8.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
      Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
       Text("快速入门",Modifier.weight(1f),fontSize=11.sp,color=Muted)
       Text("${step+1} / ${titles.size}",fontSize=11.sp,color=DutBlue)
       QuietIconButton("关闭指南",AppGlyph.Close,{onFinish("",null)})
      }
      Text(titles[step],fontSize=19.sp,lineHeight=25.sp,fontWeight=FontWeight.SemiBold,color=Ink)
      Row(Modifier.fillMaxWidth().padding(end=8.dp,top=3.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
       titles.indices.forEach{i->Box(Modifier.weight(1f).height(2.dp).background(if(i<=step)DutBlue else Hairline))}
      }
     }
     key(step){Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=10.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
      when(step){
       0->{
        GuideText("统筹课程、日程和生活琐事。大多数时候，把需求告诉大盘就行。")
        SharedAiSettings(secret,onLogin={accountOpen=true})
       }
       1->{
        GuideText("在任何页面，都能让 AI 调用工具。直接说需求，不必找按钮，也不用背口令。")
        listOf(
         "午饭18块，记到吃饭里。",
         "明天8点去B01体测，带玉兰卡，出发时提醒。",
         "下周五前交数学作业，帮我记个DDL。",
         "把下周的英语课都提前一小时。"
        ).forEach{text->
         Surface(color=if(example==text)AccentSurface else SoftSurface,shape=AppShape(10.dp),modifier=Modifier.fillMaxWidth().semantics{selected=example==text}.clickable{example=if(example==text)null else text}){
          Row(Modifier.padding(horizontal=10.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
           Text(text,Modifier.weight(1f),fontSize=13.sp,lineHeight=19.sp,color=if(example==text)DutBlue else Ink)
           if(example==text)LineIcon(AppGlyph.Check,Modifier.size(15.dp),DutBlue)
          }
         }
        }
        Text(if(example==null)"可选一句，完成指南后填入 AI 栏，不会自动发送。"else"已选好练习句，只填入输入框；由你决定是否发送。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
        HorizontalDivider(color=Hairline,thickness=.5.dp)
        GuideInstruction(AppGlyph.Chat,"接着聊","AI 问你几点，直接回“晚上八点”。要更正就说“地点改成 A01”。")
        GuideInstruction(AppGlyph.Add,"交给它资料","点 ＋ 添加图片或文件，再说“存到资料库”或“找一下这份文件里的某句话”。")
       }
       2->{
        GuideText("权限决定 AI 能否改动你的记录，不影响手动操作。")
        listOf(AssistantAccess.CONFIRM,AssistantAccess.FULL,AssistantAccess.READ_ONLY).forEach{mode->
         val description=when(mode){
          AssistantAccess.CONFIRM->"先看操作清单，点 ✓ 才执行；点 × 放弃。适合第一次用。"
          AssistantAccess.FULL->"你说记账、改日程或批量删除，直接执行，不再逐项确认。"
          AssistantAccess.READ_ONLY->"能查询、回答和打开工具；不会替你新增、修改或删除记录。"
         }
         Surface(color=if(policy.access==mode)AccentSurface else SoftSurface,shape=AppShape(10.dp),modifier=Modifier.fillMaxWidth().clickable(role=Role.RadioButton){runCatching{policy.set(mode)}.onFailure{error=it.message}}){
          Row(Modifier.padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(7.dp)){
           RadioButton(policy.access==mode,null,Modifier.size(24.dp))
           Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
            Text(mode.label,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            Text(description,fontSize=12.sp,lineHeight=18.sp,color=Muted)
           }
          }
         }
        }
        GuideInstruction(AppGlyph.Done,"看执行结果","“已执行”才表示完成。支持撤销的操作会提供撤销按钮；有误也可以接着说。")
        Text("点选即生效，随时可到 今天 → 设置 更改。",fontSize=11.sp,color=Muted)
       }
       3->{
        Text("形状和配色自由搭配，所有组合都支持白天与黑夜。",fontSize=12.sp,lineHeight=18.sp,color=Muted)
        ThemeChooser()
        StyleChooser()
        HorizontalDivider(color=Hairline,thickness=.5.dp)
        PetPreference()
        Text(if(AppAppearance.petEnabled)"单击小助手打开 AI 栏，长按说话；移出再松手取消。"else"保持关闭，使用底部 AI 栏；右下角箭头可收起。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
       }
       4->GuideFeatureOverview{destination->onFinish(destination,null)}
      }
      error?.let{Text(it,fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.error)}
     }}
     HorizontalDivider(color=Hairline,thickness=.5.dp)
     Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
      if(step>0)TextButton(onClick={move(step-1)}){Text("上一步",fontSize=12.sp)}
      else TextButton(onClick={move(1)}){Text("稍后配置",fontSize=12.sp)}
      Spacer(Modifier.weight(1f))
      if(step==titles.lastIndex){
       Button(onClick={onFinish("今天",example)},contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp)){Text(if(example==null)"开始使用"else"填入练习句",fontSize=13.sp)}
      }else Button(onClick={move(step+1)},contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp)){Text("下一步",fontSize=13.sp)}
     }
    }
   }
  }
 }
 if(accountOpen)SharedAiLoginDialog(onDismiss={accountOpen=false})
}

@Composable private fun GuideText(text:String){Text(text,fontSize=13.sp,lineHeight=19.sp,color=Ink)}

@Composable private fun GuideInstruction(glyph:AppGlyph,title:String,detail:String){
 Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top){
  LineIcon(glyph,Modifier.padding(top=2.dp).size(16.dp),DutBlue)
  Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
   Text(title,fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Ink)
   Text(detail,fontSize=12.sp,lineHeight=18.sp,color=Muted)
  }
 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun GuideFeatureOverview(open:(String)->Unit){
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("点任意功能直接体验。以后在“工具”页长按收藏，在今天页编辑小组件。",fontSize=12.sp,lineHeight=18.sp,color=Muted)
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
   GuideMainEntry("今天","安排与自选小组件",AppGlyph.Today,Modifier.weight(1f)){open("今天")}
   GuideMainEntry("日历","日程 · DDL · TDL",AppGlyph.Calendar,Modifier.weight(1f)){open("日历")}
  }
  Text("课表导入：今天 → 设置。日程可设路程、出发提醒。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
  // Same live catalogue as the toolbox: added/removed tools cannot drift from the guide.
  toolGroups.forEach{(group,names)->
   Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text(group,fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Muted)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
     names.forEach{name->
      Surface(color=SoftSurface,shape=ToolShape,border=BorderStroke(.5.dp,Hairline),modifier=Modifier.clip(ToolShape).clickable(role=Role.Button){open(toolDestination(name))}){
       Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
        LineIcon(guideToolGlyph(name),Modifier.size(14.dp),DutBlue)
        Text(name,fontSize=12.sp,lineHeight=17.sp,color=Ink)
       }
      }
     }
    }
   }
  }
 }
}

@Composable private fun GuideMainEntry(title:String,subtitle:String,glyph:AppGlyph,modifier:Modifier,onClick:()->Unit){
 Surface(modifier=modifier.clip(AppShape(10.dp)).clickable(onClick=onClick),shape=AppShape(10.dp),color=AccentSurface){
  Column(Modifier.padding(horizontal=10.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
    LineIcon(glyph,Modifier.size(16.dp),DutBlue)
    Text(title,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Ink)
   }
   Text(subtitle,fontSize=10.sp,lineHeight=15.sp,color=Muted)
  }
 }
}

private fun guideToolGlyph(name:String)=when(name){
 "晨签"->AppGlyph.Check
 "专注"->AppGlyph.Clock
 "导航","热力图"->AppGlyph.Pin
 "天气"->AppGlyph.Today
 "录音摘要","课堂生词"->AppGlyph.Mic
 "实时翻译"->AppGlyph.Chat
 "账单","二手信息"->AppGlyph.Wallet
 "官网通知"->AppGlyph.Gazette
 "抽签"->AppGlyph.Wheel
 "手持弹幕"->AppGlyph.Marquee
 "信息查询","资料库","扫描文件"->AppGlyph.File
 else->AppGlyph.Tools
}

@Composable fun QuickStartEntry(onOpen:()->Unit){
 Panel{Row(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
  Column(Modifier.weight(1f)){Text("使用指南",fontSize=14.sp,fontWeight=FontWeight.SemiBold);Text("接通 AI · 直接说需求 · 探索工具",fontSize=11.sp,color=Muted)}
  LineIcon(AppGlyph.Next,Modifier.size(15.dp),Muted)
 }}
}

package cn.dapan.assistant

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable fun CourseImportIntroduction(store:AppStore,enabled:Boolean=true,onSettings:()->Unit){
 val c=LocalContext.current
 val prefs=remember{c.getSharedPreferences("course-import-guide",0)}
 val imported=store.entries.any{it.id.startsWith(TimetableImport.PREFIX)}
 var show by rememberSaveable{mutableStateOf(!prefs.getBoolean("shown",false)&&!imported)}
 fun finish(open:Boolean){
  prefs.edit().putBoolean("shown",true).apply();show=false
  if(open){onSettings();c.startActivity(Intent(c,TimetableImportActivity::class.java))}
 }
 LaunchedEffect(imported,enabled){
  if(imported){prefs.edit().putBoolean("shown",true).apply();show=false}
  else if(enabled&&prefs.getBoolean("shown",false))show=false
 }
 if(show&&enabled)AlertDialog(onDismissRequest={finish(false)},containerColor=CardSurface,
  title={Text("先把课表带进来",fontSize=18.sp)},
  text={Text("登录你自己的教务账号，读取并确认后，课程会加入日历。\n\n现在导入或以后再导入，入口都在：今天 → 右上角设置 → 导入课表。",fontSize=14.sp)},
  confirmButton={TextButton(onClick={finish(true)}){Text("现在导入")}},
  dismissButton={TextButton(onClick={finish(false)}){Text("稍后导入")}})
}

/** Launch the real import flow without dismissing the guide or losing its current step. */
@Composable fun CourseImportGuideStep(){
 val c=LocalContext.current
 val store=remember(c){AppStore.get(c)}
 val imported=store.entries.count{it.id.startsWith(TimetableImport.PREFIX)}
 var error by remember{mutableStateOf<String?>(null)}
 val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){error=null}
 LaunchedEffect(Unit){c.getSharedPreferences("course-import-guide",0).edit().putBoolean("shown",true).apply()}
 Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
  Text("导入后，课程直接加入日历。AI 也能帮你查询、调整和设提醒。",fontSize=13.sp,lineHeight=19.sp,color=Ink)
  listOf("登录自己的学校教务账号","进入“我的课表”，选择学期","点右上角“读取”，确认后导入").forEachIndexed{i,text->
   Row(horizontalArrangement=Arrangement.spacedBy(9.dp),verticalAlignment=Alignment.Top){
    Text("${i+1}",fontSize=13.sp,color=DutBlue,fontWeight=FontWeight.SemiBold)
    Text(text,fontSize=13.sp,lineHeight=19.sp,color=Ink)
   }
  }
  Button(onClick={runCatching{launcher.launch(Intent(c,TimetableImportActivity::class.java))}.onFailure{error="暂时无法打开教务页面，请稍后重试。"}},contentPadding=PaddingValues(horizontal=14.dp,vertical=9.dp)){
   Text(if(imported>0)"再次导入课表"else"导入我的课表",fontSize=13.sp)
  }
  if(imported>0)Text("已导入 $imported 次课程，可继续设置。",fontSize=12.sp,color=DutBlue)
  Text("现在不导入也可以。以后在 今天 → 设置 → 导入课表 找到入口。",fontSize=11.sp,lineHeight=17.sp,color=Muted)
  Text("学校账号仅用于教务登录，课表在手机上读取。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
  error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
 }
}

@Composable fun CourseImportSettings(store:AppStore){
 val c=LocalContext.current
 val count=remember(store.entries){store.entries.count{it.id.startsWith(TimetableImport.PREFIX)}}
 Panel {
  Row(Modifier.fillMaxWidth().clickable{c.startActivity(Intent(c,TimetableImportActivity::class.java))}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
   LineIcon(AppGlyph.Calendar,Modifier.size(20.dp),DutBlue)
   Column(Modifier.weight(1f).padding(horizontal=10.dp)){
    Text("导入课表",fontSize=14.sp,fontWeight=FontWeight.SemiBold)
    Text(if(count>0)"已有 $count 次导入的课程 · 可重新读取"else"尚无导入的课程 · 登录自己的教务账号",fontSize=11.sp,color=Muted)
   }
   LineIcon(AppGlyph.Next,Modifier.size(15.dp),Muted)
  }
 }
}

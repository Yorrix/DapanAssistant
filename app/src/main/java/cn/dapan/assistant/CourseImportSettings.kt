package cn.dapan.assistant

import android.content.Intent
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
 LaunchedEffect(imported){if(imported){prefs.edit().putBoolean("shown",true).apply();show=false}}
 if(show&&enabled)AlertDialog(onDismissRequest={finish(false)},containerColor=CardSurface,
  title={Text("先把课表带进来",fontSize=18.sp)},
  text={Text("登录你自己的教务账号，读取并确认后，课程会加入日历。\n\n现在导入或以后再导入，入口都在：今天 → 右上角设置 → 导入课表。",fontSize=14.sp)},
  confirmButton={TextButton(onClick={finish(true)}){Text("现在导入")}},
  dismissButton={TextButton(onClick={finish(false)}){Text("稍后导入")}})
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

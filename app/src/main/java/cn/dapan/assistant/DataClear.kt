package cn.dapan.assistant

import android.app.ActivityManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*

enum class ClearDataKind(val label:String) {
 COURSES("仅导入的课表"),CALENDAR("全部日历事项（含课表、DDL、TDL）"),
 BILLS("账单"),PARCELS("取件码"),INFORMATION("信息查询"),MORNING("晨签记录与统计设置"),
 CHAT("AI 聊天记录"),KEYS("DeepSeek 与阿里云 API 密钥")
}
object ClearDataRules {
 fun entry(e:CalendarEntry,selected:Set<ClearDataKind>)=ClearDataKind.CALENDAR in selected ||
  (ClearDataKind.COURSES in selected && e.id.startsWith(TimetableImport.PREFIX))
 fun note(n:NoteItem,selected:Set<ClearDataKind>)=when(n.module){
  "待办"->ClearDataKind.CALENDAR;"账单"->ClearDataKind.BILLS;"快递"->ClearDataKind.PARCELS;"信息查询"->ClearDataKind.INFORMATION;else->null
 } in selected
}

@Composable fun DataClearControls(store:AppStore,enabled:Boolean,onClear:(Set<ClearDataKind>)->Unit){
 val c=LocalContext.current
 var choosing by remember{mutableStateOf(false)}
 var selected by remember{mutableStateOf(emptySet<ClearDataKind>())}
 var confirmation by remember{mutableStateOf(false)}
 var reset by remember{mutableStateOf(false)}
 var resetText by remember{mutableStateOf("")}
 var message by remember{mutableStateOf<String?>(null)}
 val available=enabled&&!AudioState.running&&TranscriptionState.runningId==null&&MeetingSummaryState.session==null&&store.focusEnd<=0L
 Panel {
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   Text("清除数据",Modifier.weight(1f),fontSize=14.sp)
   TextButton(enabled=available,onClick={selected=emptySet();choosing=true}){Text("选择内容",fontSize=12.sp)}
  }
  Text(if(available)"按内容清除，或重置本机全部数据。"else"请先结束 AI 请求、录音、转写、摘要或专注。",fontSize=11.sp,color=Muted)
 }
 if(choosing)AlertDialog(onDismissRequest={choosing=false},title={Text("清除数据",fontSize=18.sp)},text={
  Column(Modifier.verticalScroll(rememberScrollState())){
   ClearDataKind.entries.forEach{kind->Row(Modifier.fillMaxWidth().clickable{selected=if(kind in selected)selected-kind else selected+kind}.heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically){
    Checkbox(kind in selected,null,Modifier.size(32.dp));Text(kind.label,fontSize=13.sp)
   }}
   HorizontalDivider(Modifier.padding(vertical=6.dp),color=Hairline)
   TextButton(onClick={choosing=false;resetText="";reset=true}){Text("重置本机全部数据…",color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
  }
 },confirmButton={TextButton(enabled=selected.isNotEmpty()&&available,onClick={choosing=false;confirmation=true}){Text("下一步")}},dismissButton={TextButton(onClick={choosing=false}){Text("取消")}})
 if(confirmation)AlertDialog(onDismissRequest={confirmation=false},title={Text("确认清除所选内容？",fontSize=18.sp)},text={Column(Modifier.verticalScroll(rememberScrollState())){
  Text(selected.joinToString("\n"){"• ${it.label}"},fontSize=13.sp)
  Text("\n无法撤销。未选内容保留；本机自动恢复副本也会在清除记录时移除。二手平台账号、已发布信息及你导出到手机其他位置的文件不受影响。",fontSize=12.sp,color=Muted)
 }},confirmButton={TextButton(enabled=available,onClick={confirmation=false;runCatching{onClear(selected)}.onSuccess{message="所选数据已清除"}.onFailure{message="清理未完全完成：${it.message}，请检查后重试。"}}){Text("确认清除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmation=false}){Text("取消")}})
 if(reset)AlertDialog(onDismissRequest={reset=false},title={Text("重置本机全部数据",fontSize=18.sp)},text={Column{
  Text("删除此 App 内的所有记录、资料库文件、扫描件、录音、热力图、聊天、密钥、设置和登录状态。应用会关闭，再次打开将重新初始化。\n\n不会注销服务器账号、删除已发布二手信息，也不会删除已导出的文件。此操作无法撤销。",fontSize=13.sp)
  OutlinedTextField(resetText,{resetText=it},label={Text("输入“清除全部”确认")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=8.dp))
 }},confirmButton={TextButton(enabled=available&&resetText=="清除全部",onClick={runCatching{
  FocusControl.cleanup(c)
  check(c.getSystemService(ActivityManager::class.java).clearApplicationUserData()){"系统未能启动清理"}
 }.onFailure{reset=false;message=it.message}}){Text("清除并关闭",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={reset=false}){Text("取消")}})
 message?.let{AlertDialog(onDismissRequest={message=null},text={Text(it,fontSize=14.sp)},confirmButton={TextButton(onClick={message=null}){Text("完成")}})}
}

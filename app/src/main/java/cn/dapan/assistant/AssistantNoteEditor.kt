package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable fun AssistantNoteEditor(note:NoteItem,dismiss:()->Unit,save:(NoteItem)->Unit) {
 if(note.module=="信息查询"){InformationRecordEditor(note,onDismiss=dismiss,onSave=save);return}
 if(note.module=="账单"){BillEditor(note,onDismiss=dismiss,onSave=save);return}
 var title by remember(note){mutableStateOf(note.title)}
 var detail by remember(note){mutableStateOf(note.detail)}
 var group by remember(note){mutableStateOf(note.group)}
 var error by remember{mutableStateOf<String?>(null)}
 AlertDialog(onDismissRequest=dismiss,title={Text("确认${note.module}记录")},text={
  Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
   Text("可以修改识别结果，确认后才保存。")
   OutlinedTextField(title,{title=it},label={Text("名称")})
   OutlinedTextField(detail,{detail=it},label={Text(if(note.module=="账单")"第一行金额，其他行备注"else"内容")},minLines=3)
   OutlinedTextField(group,{group=it},label={Text("分组")})
   error?.let{Text(it,color=Color.Red)}
  }
 },confirmButton={TextButton(onClick={
  if(title.isBlank())error="请填写名称"
  else if(note.module=="账单"&&detail.substringBefore('\n').toBigDecimalOrNull()==null)error="金额需要是数字，例如 12.50"
  else try{save(note.copy(title=title.trim(),detail=detail,group=group.trim()))}catch(e:Exception){error=e.message}
 }){Text("确认保存")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}

package cn.dapan.assistant

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/** Secondary guidance stays available without displacing the tool's working area. */
@Composable fun ToolHelp(text:String) {
 var open by remember { mutableStateOf(false) }
 TextButton(onClick={open=true}) { Text("说明",fontSize=12.sp) }
 if(open) AlertDialog(onDismissRequest={open=false},title={Text("使用说明")},text={Text(text,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={open=false}){Text("知道了")}})
}

package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable fun AssistantOperationCard(operation:AssistantOperation,enabled:Boolean,onApprove:()->Unit,onReject:()->Unit,onUndo:()->Unit){
 var expanded by rememberSaveable{mutableStateOf(false)}
 val pending=operation.status==OperationStatus.PENDING
 val tint=when(operation.status){OperationStatus.FAILED,OperationStatus.PARTIAL->MaterialTheme.colorScheme.error;OperationStatus.DONE,OperationStatus.PENDING->DutBlue;else->Muted}
 Surface(shape=AppShape(11.dp),color=SoftSurface,border=BorderStroke(.5.dp,Hairline)){
  Column(Modifier.fillMaxWidth().padding(horizontal=11.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
    LineIcon(if(operation.status==OperationStatus.DONE)AppGlyph.Done else AppGlyph.File,Modifier.size(17.dp),tint)
    Text(operation.status.label,fontWeight=FontWeight.SemiBold,fontSize=12.sp,color=tint)
    Text(operation.summary,Modifier.weight(1f),fontSize=11.sp,lineHeight=16.sp,color=Muted)
   }
   val visible=if(expanded)operation.items else operation.items.take(3)
   visible.forEach{item->
    HorizontalDivider(color=Hairline,thickness=.5.dp)
    val rename=item.before.keys==setOf("名称")&&item.after.keys==setOf("名称")
    if(rename){
     Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
      Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
       Text(item.before.getValue("名称"),fontSize=11.sp,lineHeight=16.sp,color=Muted)
       Text("→ ${item.after.getValue("名称")}",fontSize=14.sp,lineHeight=19.sp,fontWeight=FontWeight.SemiBold,color=Ink)
      }
      if(item.count>1)Text("${item.count} 条",fontSize=11.sp,color=Muted)
     }
    }else{
     Text("${item.verb} · ${item.title}",fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,color=Ink)
     (item.before.keys+item.after.keys).distinct().filter{it!="名称"}.forEach{field->
      val before=item.before[field];val after=item.after[field]
      Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
       Text(field,Modifier.width(40.dp),fontSize=11.sp,lineHeight=16.sp,color=Muted)
       Text(if(before!=null&&after!=null)"$before → $after"else after?:before.orEmpty(),Modifier.weight(1f),fontSize=12.sp,lineHeight=17.sp,color=Ink)
      }
     }
     if(item.before["名称"]!=item.after["名称"]&&item.before.containsKey("名称")&&item.after.containsKey("名称"))Text("名称：${item.before["名称"]} → ${item.after["名称"]}",fontSize=12.sp,color=Ink)
    }
    val context=if(rename&&item.count>1)item.context.substringAfter(" · ",item.context).removeSuffix(" · 时间、地点和提醒保持不变")else item.context
    if(context.isNotBlank())Text(context,fontSize=11.sp,lineHeight=15.sp,color=Muted)
   }
   if(operation.items.any{it.context.endsWith(" · 时间、地点和提醒保持不变")})Text("仅改名称，时间、地点和提醒保持不变。",fontSize=11.sp,lineHeight=15.sp,color=Muted)
   if(operation.items.size>3)TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp),modifier=Modifier.height(30.dp)){Text(if(expanded)"收起明细"else"查看全部 ${operation.items.size} 组明细",fontSize=11.sp)}
   if(operation.detail.isNotBlank())Text(operation.detail,fontSize=11.sp,lineHeight=16.sp,color=if(operation.status==OperationStatus.FAILED)MaterialTheme.colorScheme.error else Muted)
   if(pending){
    HorizontalDivider(color=Hairline,thickness=.5.dp)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
     Text("尚未改动",Modifier.weight(1f),fontSize=10.sp,color=Muted)
     OutlinedButton(onClick=onReject,enabled=enabled,contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.height(36.dp),shape=AppShape(8.dp)){LineIcon(AppGlyph.Close,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("放弃",fontSize=12.sp)}
     Button(onClick=onApprove,enabled=enabled,contentPadding=PaddingValues(horizontal=12.dp),modifier=Modifier.height(36.dp),shape=AppShape(8.dp)){LineIcon(AppGlyph.Check,Modifier.size(16.dp),MaterialTheme.colorScheme.onPrimary);Spacer(Modifier.width(4.dp));Text("执行",fontSize=12.sp)}
    }
   }else if(operation.status==OperationStatus.DONE&&operation.undo!=null){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=onUndo,enabled=enabled,contentPadding=PaddingValues(horizontal=5.dp),modifier=Modifier.height(32.dp)){Text("↶ 撤销这次操作",fontSize=12.sp)}}
   }
  }
 }
}

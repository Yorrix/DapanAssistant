package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable fun AssistantAccessChooser(policy:AssistantPolicy){
 var error by remember{mutableStateOf<String?>(null)}
 Text("助手权限",fontWeight=FontWeight.SemiBold,fontSize=15.sp)
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
  AssistantAccess.entries.forEach{mode->CalendarAction(mode.label,{runCatching{policy.set(mode)}.onFailure{error=it.message}},policy.access==mode,Modifier.weight(1f))}
 }
 Text(policy.access.detail,fontSize=12.sp,color=Muted)
 Text("决定 AI 是否能直接替你记账、改日程或删记录；不影响手动编辑，也不会接管手机。",fontSize=11.sp,color=Muted)
 error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
}
@Composable fun AssistantPlanDialog(plan:AssistantPlan,onCancel:()->Unit,onConfirm:()->Unit){
 val maxHeight=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.82f
 androidx.compose.ui.window.Dialog(onDismissRequest=onCancel,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(max=maxHeight),shape=AppShape(18.dp),color=CardSurface,border=BorderStroke(.5.dp,Hairline)){
   Column(Modifier.padding(horizontal=14.dp)){
    Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
     Text(if(plan.writes)"确认操作"else"确认打开",Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=17.sp,lineHeight=22.sp)
     QuietIconButton("取消操作",AppGlyph.Close,onCancel)
    }
    Text(plan.summary,fontSize=12.sp,lineHeight=17.sp,color=DutBlue)
    if(plan.unmatched.isNotEmpty())Text("未匹配，不会修改：${plan.unmatched.joinToString("、")}",fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.error)
    Text(if(plan.writes)"尚未执行，确认后生效"else"确认后打开对应页面",fontSize=11.sp,lineHeight=16.sp,color=Muted,modifier=Modifier.padding(top=2.dp,bottom=10.dp))
    HorizontalDivider(color=Hairline,thickness=.5.dp)
    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(0.dp)){
     plan.items.forEachIndexed{index,item->
      Column(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
       Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
        val destructive=item.verb=="删除";val tint=if(destructive)MaterialTheme.colorScheme.error else DutBlue
        Surface(color=tint.copy(alpha=.09f),shape=AppShape(5.dp)){Text(item.verb,Modifier.padding(horizontal=7.dp,vertical=3.dp),fontSize=11.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium,color=tint)}
        Text(item.title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=14.sp,lineHeight=19.sp)
        if(plan.items.size>1)Text("${index+1}",fontSize=11.sp,lineHeight=16.sp,color=Muted)
       }
       if(item.context.isNotBlank())Text(item.context,fontSize=12.sp,lineHeight=17.sp,color=Muted)
       val changed=item.before.isNotEmpty()&&item.after.isNotEmpty()
       (item.before.keys+item.after.keys).distinct().filter{key->
        (!changed||item.before[key]!=item.after[key]) && !(!changed&&key=="名称"&&(item.after[key]?:item.before[key])==item.title)
       }.forEach{key->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
         Text(key,Modifier.width(54.dp).padding(top=1.dp),fontSize=12.sp,lineHeight=18.sp,color=Muted)
         Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
          if(changed){
           Text("原 · ${item.before[key].orEmpty().ifBlank{"未设置"}}",fontSize=12.sp,lineHeight=17.sp,color=Muted)
           Text("改为 · ${item.after[key].orEmpty().ifBlank{"未设置"}}",fontSize=13.sp,lineHeight=19.sp,color=Ink,fontWeight=FontWeight.Medium)
          }else Text((item.after[key]?:item.before[key]).orEmpty().ifBlank{"未设置"},fontSize=13.sp,lineHeight=19.sp,color=Ink)
         }
        }
       }
      }
      if(index<plan.items.lastIndex)HorizontalDivider(color=Hairline,thickness=.5.dp)
     }
    }
    HorizontalDivider(color=Hairline,thickness=.5.dp)
    Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
     TextButton(onClick=onCancel,modifier=Modifier.weight(1f)){Text("取消",fontSize=13.sp)}
     Button(onClick=onConfirm,modifier=Modifier.weight(2f).height(42.dp),shape=AppShape(10.dp)){
      Text(if(plan.operationCount>1)"确认 ${plan.operationCount} 项操作"else"确认执行",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
     }
    }
   }
  }
 }
}

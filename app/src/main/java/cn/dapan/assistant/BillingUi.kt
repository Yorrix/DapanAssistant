package cn.dapan.assistant

import org.json.*

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*

private val incomeInk:Color @Composable get()=if(AppAppearance.dark)Color(0xFF78D8B7)else Color(0xFF167A5A)

@Composable fun BillingScreen(store:AppStore,onBack:()->Unit){
 val c=LocalContext.current
 var monthText by rememberSaveable{mutableStateOf(YearMonth.now(Billing.zone).toString())}
 val month=YearMonth.parse(monthText)
 var edit by remember{mutableStateOf<NoteItem?>(null)}
 val monthly=remember(store.notes,month){store.notes.filter{it.module=="账单"&&YearMonth.from(Billing.day(it))==month}.sortedByDescending{it.created}}
 val expense=Billing.sum(monthly,false)
 val food=Billing.sum(monthly.filter{Billing.category(it)=="吃饭"},false)
 val other=expense-food
 val income=Billing.sum(monthly,true)
 val unknown=monthly.count{Billing.amount(it.detail)==null}
 AssistantSurface("账单",JSONObject().put("month",monthText).put("selectedId",edit?.id).put("visible",JSONArray(monthly.take(40).map{JSONObject().put("id",it.id).put("title",it.title).put("detail",it.detail)})),listOf(surfaceTool("month","切换账单月份 month=YYYY-MM","month",false){a->val m=YearMonth.parse(a.text("month")).toString();prepared("查看 $m 账单",a){monthText=m;"已显示 $m 账单"}}))
 Column(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
  Row(Modifier.fillMaxWidth().height(46.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack)
   Text("账单",Modifier.weight(1f),fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold)
   TextButton(onClick={edit=NoteItem(module="账单",title="",group="吃饭")}){Text("＋记一笔",fontSize=13.sp)}
  }
  Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically){
   TextButton(onClick={monthText=month.minusMonths(1).toString()}){Text("‹",fontSize=23.sp)}
   Text("${month.year}年${month.monthValue}月",Modifier.weight(1f).clickable{DatePickerDialog(c,platformDialogTheme,{_,y,m,_->monthText=YearMonth.of(y,m+1).toString()},month.year,month.monthValue-1,1).show()},fontSize=15.sp,fontWeight=FontWeight.Medium)
   if(month!=YearMonth.now(Billing.zone))TextButton(onClick={monthText=YearMonth.now(Billing.zone).toString()}){Text("本月",fontSize=11.sp)}
   TextButton(onClick={monthText=month.plusMonths(1).toString()}){Text("›",fontSize=23.sp)}
  }
  Row(Modifier.fillMaxWidth().clip(AppShape(12.dp)).background(AccentSurface).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   listOf("本月支出" to expense,"吃饭" to food,"其他" to other).forEach{(label,value)->Column(Modifier.weight(1f)){
    Text(label,fontSize=11.sp,color=Muted)
    Text("¥"+Billing.money(value),Modifier.horizontalScroll(rememberScrollState()),fontSize=if(Billing.money(value).length>9)13.sp else 17.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
   }}
  }
  if(income.signum()>0)Text("另有收入 ¥${Billing.money(income)}，未计入支出",fontSize=11.sp,color=incomeInk)
  if(unknown>0)Text("$unknown 笔旧记录金额待核对，未计入汇总",fontSize=11.sp,color=MaterialTheme.colorScheme.error)
  LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=10.dp+LocalFloatingAiHeight.current)){
   if(monthly.isEmpty())item{Text("这个月还没有记录，点右上角记一笔。",Modifier.padding(vertical=16.dp),fontSize=13.sp,color=Muted)}
   monthly.groupBy{Billing.day(it)}.forEach{(date,rows)->
    item(key="day-$date"){Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
     Text("${date.monthValue}月${date.dayOfMonth}日 · ${listOf("一","二","三","四","五","六","日")[date.dayOfWeek.value-1]}",Modifier.weight(1f),fontSize=11.sp,color=Muted)
     Text("支出 ¥${Billing.money(Billing.sum(rows,false))}",fontSize=11.sp,color=Muted)
    }}
    items(rows,key={it.id}){n->
     val amount=Billing.amount(n.detail);val isIncome=amount?.signum()==-1;val category=Billing.category(n)
     Row(Modifier.fillMaxWidth().clickable{edit=n}.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
      Box(Modifier.size(32.dp).clip(AppShape(10.dp)).background(AccentSurface),contentAlignment=Alignment.Center){Text(if(isIncome)"收"else if(category=="吃饭")"食"else"其",fontSize=13.sp,color=DutBlue)}
      Column(Modifier.weight(1f)){
       Text(n.title.ifBlank{category},fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
       val detail=listOfNotNull((if(isIncome)"收入"else category).takeUnless{it==n.title},Billing.memo(n).takeIf{it.isNotBlank()}).joinToString(" · ")
       if(detail.isNotBlank())Text(detail,fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
      }
      Text(if(amount==null)"待核对"else (if(isIncome)"+"else"−")+Billing.money(amount.abs()),fontSize=16.sp,fontWeight=FontWeight.Medium,color=if(isIncome)incomeInk else Ink)
     }
     HorizontalDivider(color=Muted.copy(alpha=.1f))
    }
   }
  }
 }
 edit?.let{note->BillEditor(note,onDismiss={edit=null},onSave={store.put(it);monthText=YearMonth.from(Billing.day(it)).toString();edit=null},onDelete=if(store.notes.any{it.id==note.id})({store.remove(note);edit=null})else null)}
}

@Composable fun BillEditor(note:NoteItem,onDismiss:()->Unit,onSave:(NoteItem)->Unit,onDelete:(()->Unit)?=null){
 val c=LocalContext.current
 val initial=remember(note){Billing.amount(note.detail)}
 val income=initial?.signum()==-1
 var amount by rememberSaveable(note.id){mutableStateOf(initial?.abs()?.let(Billing::money)?:"")}
 var category by rememberSaveable(note.id){mutableStateOf(Billing.category(note))}
 var title by rememberSaveable(note.id){mutableStateOf(note.title.takeUnless{it==note.group||it==Billing.category(note)}?:"")}
 var memo by rememberSaveable(note.id){mutableStateOf(Billing.memo(note))}
 var dateText by rememberSaveable(note.id){mutableStateOf(Billing.day(note).toString())}
 var error by remember{mutableStateOf("")};var deleting by remember{mutableStateOf(false)}
 val date=LocalDate.parse(dateText);val focus=remember{FocusRequester()}
 fun save(){runCatching{onSave(Billing.saved(note,income,amount,category,title,memo,date))}.onFailure{error=it.message?:"保存失败"}}
 Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth(.94f).heightIn(max=580.dp),shape=AppShape(20.dp),color=MaterialTheme.colorScheme.surface){
   Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal=14.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically){
     QuietIconButton("返回账单",AppGlyph.Back,onDismiss)
     Text(if(income)"编辑收入"else if(onDelete==null)"记一笔"else"编辑账单",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
     if(onDelete!=null)QuietIconButton("删除账单",AppGlyph.Trash,{deleting=true},color=Muted)
    }
    if(!income)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
     Billing.expenseCategories.forEach{label->FilterChip(category==label,{category=label},label={Text(label)},modifier=Modifier.weight(1f))}
    }
    OutlinedTextField(amount,{value->if(value.matches(Regex("[0-9]{0,9}(\\.[0-9]{0,2})?"))){amount=value;error=""}},Modifier.fillMaxWidth().focusRequester(focus),label={Text("金额")},prefix={Text("¥")},singleLine=true,shape=AppShape(12.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={save()}))
    OutlinedTextField(title,{title=it.take(100)},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("说明（选填）",fontSize=13.sp)},shape=AppShape(12.dp))
    // Keep previously written long notes editable without adding another field to everyday entry.
    if(Billing.memo(note).isNotBlank())OutlinedTextField(memo,{memo=it.take(2000)},Modifier.fillMaxWidth(),label={Text("原有备注")},maxLines=3,shape=AppShape(12.dp))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
     Text("日期",Modifier.weight(1f),fontSize=12.sp,color=Muted)
     TextButton(onClick={DatePickerDialog(c,platformDialogTheme,{_,y,m,d->dateText=LocalDate.of(y,m+1,d).toString()},date.year,date.monthValue-1,date.dayOfMonth).show()}){Text(if(date==LocalDate.now(Billing.zone))"今天"else dateText,fontSize=12.sp)}
    }
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
    Button(onClick={save()},modifier=Modifier.fillMaxWidth().height(44.dp),shape=AppShape(12.dp),colors=ButtonDefaults.buttonColors(containerColor=DutBlue)){Text("保存")}
   }
  }
  LaunchedEffect(note.id){if(initial==null)focus.requestFocus()}
 }
 if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除这笔账单？")},text={Text("${note.title} · ¥${initial?.abs()?.let(Billing::money)?:"金额待核对"}")},confirmButton={TextButton(onClick={runCatching{onDelete?.invoke()}.onFailure{error="删除失败，请重试"};deleting=false}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}

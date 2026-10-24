package cn.dapan.assistant

import org.json.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

object ParcelCodes {
 fun suggestion(raw:String):String?{
  if(raw.isBlank()||raw.length>160)return null
  var text=raw.trim().uppercase().replace('Ａ','A').replace('Ｓ','S').replace('Ｆ','F')
  text=text.replace(Regex("^(我的|这个)?(快递)?(取件码|取货码|提货码|运单尾号|尾号)(是|为)?[：:，, ]*"),"")
  text=text.replace(Regex("^顺丰(快递)?[：:，, ]*"),"SF")
  text=text.replace(Regex("^京东(快递)?[：:，, ]*"),"A")
  text=text.replace(Regex("^(SF|A)(取件码|取货码|提货码)(是|为)?[：:，, ]*")){it.groupValues[1]}
  text=text.replace(Regex("^S[ -]+F"),"SF")
  text=text.replace(Regex("^(诶|哎|欸)"),"A")
  text=text.replace(Regex("横杠|短横线|横线|杠|减号|减|破折号|空格"),"-")
  val digits=mapOf('零' to '0','〇' to '0','洞' to '0','一' to '1','幺' to '1','二' to '2','两' to '2','三' to '3','四' to '4','五' to '5','六' to '6','七' to '7','八' to '8','九' to '9')
  text=text.replace(Regex("[一二两三四五六七八九]?十[一二三四五六七八九]?")){match->val parts=match.value.split('十');((parts[0].firstOrNull()?.let{digits[it]?.digitToInt()}?:1)*10+(parts[1].firstOrNull()?.let{digits[it]?.digitToInt()}?:0)).toString()}
  text=text.map{digits[it]?:if(it in '０'..'９')('0'.code+it.code-'０'.code).toChar()else it}.joinToString("")
   .replace(Regex("[—–－_]"),"-").replace(Regex("[，,、\\s]+"),"-").trim('-',':','：','。','.')
   .replace(Regex("-+"),"-")
  val compact=text.replace("-","")
  val answer=when{
   Regex("SF[0-9]{5}").matches(compact)->"Sf-"+compact.drop(2)
   Regex("A[0-9]{6,7}").matches(compact)->{val n=compact.drop(1);"A${n.dropLast(5)}-${n.takeLast(5).take(1)}-${n.takeLast(4)}"}
   Regex("[0-9]{5}").matches(compact)->compact
   Regex("[0-9]{6,7}").matches(compact)->"${compact.dropLast(5)}-${compact.takeLast(5).take(1)}-${compact.takeLast(4)}"
   else->return null
  }
  // Explicit separators must match a known format; do not silently rearrange malformed groups.
  if(text.contains('-')&&!Regex("(SF-?[0-9]{5}|A-?[0-9]{1,2}-[0-9]-[0-9]{4}|[0-9]{1,2}-[0-9]-[0-9]{4}|[0-9](?:-[0-9]){4,6}|(?:SF|A)-?[0-9]{5,7})").matches(text))return null
  return answer.takeIf{it!=raw.trim()}
 }
 fun display(n:NoteItem):String{
  // Retain old records intact; prefer an identifiable code in the old detail field.
  val candidates=listOf(n.title)+n.detail.lines()
  return candidates.firstOrNull{Regex("(?i)(Sf-[0-9]{5}|A[0-9]{1,2}-[0-9]-[0-9]{4}|[0-9]{1,2}-[0-9]-[0-9]{4}|[0-9]{5})").matches(it.trim())}?.trim()?:n.title
 }
}

@Composable fun ParcelScreen(s:AppStore,onBack:()->Unit){
 var input by rememberSaveable(stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue())}
 var error by remember{mutableStateOf("")}
 var armed by remember{mutableStateOf<String?>(null)};var allClicks by remember{mutableIntStateOf(0)}
 val records=s.notes.filter{it.module=="快递"}.sortedByDescending{it.created}
 val ids=records.map{it.id}.toSet()
 val suggestion=remember(input.text){ParcelCodes.suggestion(input.text)}
 LaunchedEffect(armed){if(armed!=null){delay(5000);armed=null}}
 LaunchedEffect(allClicks){if(allClicks>0){delay(5000);allClicks=0}}
 LaunchedEffect(ids){armed=null;allClicks=0}
 fun addCode(raw:String){runCatching{
  val code=raw.trim();require(code.isNotBlank()&&code.length<=80){"请输入1至80个字符的取件码"}
  s.put(NoteItem(module="快递",title=code,detail=""));input=TextFieldValue();error=""
 }.onFailure{error=it.message?:"保存失败"}}
 AssistantSurface("快递",JSONObject().put("draft",input.text).put("items",JSONArray(records.map{JSONObject().put("id",it.id).put("code",ParcelCodes.display(it))})))
 Column(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
  Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack)
   Text("取件码",Modifier.weight(1f),fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,color=Ink)
   TextButton(onClick={armed=null;if(allClicks<2)allClicks++ else runCatching{s.removeParcelCodes(ids);allClicks=0}.onFailure{allClicks=0;error="删除失败，请重试"}},enabled=records.isNotEmpty()){
    Text(when(allClicks){1->"再点2次全部删除";2->"再点1次全部删除";else->"全部删除"},fontSize=11.sp,color=if(allClicks>0)MaterialTheme.colorScheme.error else Muted)
   }
  }
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
   OutlinedTextField(input,{input=it;error=""},Modifier.weight(1f),singleLine=true,placeholder={Text("输入取件码",fontSize=14.sp)},textStyle=TextStyle(fontSize=15.sp),shape=AppShape(12.dp))
   TextButton(onClick={addCode(input.text)},enabled=input.text.isNotBlank()){Text("添加")}
  }
  suggestion?.let{code->Box(Modifier.clip(CircleShape).background(AccentSurface).clickable{addCode(code)}.padding(horizontal=12.dp,vertical=7.dp)){Text("你是不是指 $code",fontSize=12.sp,color=DutBlue)}}
  if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
  LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=8.dp+LocalFloatingAiHeight.current)){
   if(records.isEmpty())item{Text("还没有取件码",fontSize=13.sp,color=Muted,modifier=Modifier.padding(vertical=12.dp))}
   items(records,key={it.id}){n->
    Row(Modifier.fillMaxWidth().clip(AppShape(12.dp)).background(CardSurface).padding(start=13.dp,end=3.dp),verticalAlignment=Alignment.CenterVertically){
     Text(ParcelCodes.display(n),Modifier.weight(1f).padding(vertical=10.dp),fontSize=20.sp,fontWeight=FontWeight.Medium)
     if(armed==n.id)Text("再点删除",fontSize=10.sp,color=MaterialTheme.colorScheme.error)
     QuietIconButton(if(armed==n.id)"确认删除 ${ParcelCodes.display(n)}"else"删除 ${ParcelCodes.display(n)}",AppGlyph.Trash,{
      allClicks=0
      if(armed!=n.id)armed=n.id else runCatching{s.remove(n);armed=null}.onFailure{armed=null;error="删除失败，请重试"}
     },color=if(armed==n.id)MaterialTheme.colorScheme.error else Muted)
    }
   }
  }
 }
}

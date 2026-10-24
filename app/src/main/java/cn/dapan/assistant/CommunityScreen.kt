package cn.dapan.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
import java.net.URLEncoder


@Composable internal fun AccountForm(api:CommunityClient,page:String,busy:Boolean,navigate:(String)->Unit,task:(suspend ()->Unit)->Unit,onSuccess:()->Unit,onMessage:(String)->Unit) {
 var prefix by remember{mutableStateOf("")};var domain by remember{mutableStateOf(CampusEmail.domains.first())}
 val address=CampusEmail.address(prefix,domain);val email=address.orEmpty()
 var password by remember{mutableStateOf("")};var code by remember{mutableStateOf("")};var nickname by remember{mutableStateOf("")};var visible by remember{mutableStateOf(false)};var agreed by remember{mutableStateOf(false)}
 var cooldown by remember{mutableIntStateOf(0)}
 LaunchedEffect(cooldown){if(cooldown>0){delay(1000);cooldown--}}
 val registering=page=="register";val resetting=page=="reset"
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=12.dp).padding(bottom=LocalFloatingAiHeight.current+10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  TextButton(onClick={navigate("browse")}){Text("← 先逛逛")}
  Text(if(registering)"注册验证一次邮箱，之后用密码登录。"else if(resetting)"验证邮箱后设置新密码。"else"用大工邮箱和大盘助手账号密码登录。",fontSize=13.sp,color=Muted)
  OutlinedTextField(prefix,{val parts=CampusEmail.split(it,domain);prefix=parts.first;domain=parts.second},label={Text("邮箱前缀")},placeholder={Text("填写 @ 前的账号，不限学号")},isError=prefix.isNotBlank()&&address==null,modifier=Modifier.fillMaxWidth(),singleLine=true,shape=AppShape(14.dp),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Email))
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
   CampusEmail.domains.forEach{suffix->FilterChip(domain==suffix,{domain=suffix},label={Text("@$suffix",fontSize=12.sp)},enabled=!busy)}
  }
  if(registering)OutlinedTextField(nickname,{nickname=it.take(24)},label={Text("公开昵称")},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=AppShape(14.dp))
  if(registering||resetting)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text("邮箱验证码")},modifier=Modifier.weight(1f),singleLine=true,shape=AppShape(14.dp),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Number))
   TextButton(onClick={task{val result=api.request("/auth/code","POST",JSONObject().put("email",email).put("purpose",if(resetting)"reset"else"register"));cooldown=60;onMessage(result.getString("message"))}},enabled=!busy&&cooldown==0&&email.isNotBlank()){Text(if(cooldown>0)"${cooldown}s"else"获取验证码")}
  }
  OutlinedTextField(password,{password=it},label={Text(if(resetting)"新密码"else"密码")},supportingText={if(registering||resetting)Text("至少 10 个字符，不要使用校园统一认证密码")},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=AppShape(14.dp),visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={TextButton(onClick={visible=!visible}){Text(if(visible)"隐藏"else"显示",fontSize=11.sp)}})
  if(registering)Row(verticalAlignment=Alignment.Top){Checkbox(agreed,{agreed=it});Text("我同意仅发布合法、真实的闲置信息。昵称对外展示，邮箱用于登录；发布时可自行选择公开联系方式。",Modifier.padding(top=10.dp),fontSize=12.sp,lineHeight=18.sp,color=Muted)}
  Button(onClick={task{
   val body=JSONObject().put("email",email).put("password",password)
   if(registering){body.put("code",code).put("nickname",nickname);api.session(api.request("/auth/register","POST",body));onSuccess()}
   else if(resetting){body.put("code",code);api.request("/auth/reset","POST",body);navigate("login");onMessage("密码已重设，请登录")}
   else{api.session(api.request("/auth/login","POST",body));onSuccess()}
  }},enabled=!busy&&email.isNotBlank()&&password.isNotBlank()&&(!registering||agreed&&nickname.isNotBlank()&&code.length==6)&&(!resetting||code.length==6),modifier=Modifier.fillMaxWidth().height(44.dp),shape=AppShape(15.dp),colors=ButtonDefaults.buttonColors(containerColor=Pine)){Text(if(busy)"处理中…"else if(registering)"验证邮箱并注册"else if(resetting)"重设密码"else"登录")}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={navigate(if(registering||resetting)"login"else"register")}){Text(if(registering||resetting)"已有账号，登录"else"第一次来？注册")};if(!registering&&!resetting)TextButton(onClick={navigate("reset")}){Text("忘记密码")}}
  Text("支持 @mail.dlut.edu.cn / @dlut.edu.cn。邮箱验证不代表学籍认证，平台不读取校园邮箱密码。",fontSize=11.sp,color=Muted,lineHeight=18.sp)
  Spacer(Modifier.height(20.dp))
 }
}

@Composable fun CommunitySettings() {
 val c=LocalContext.current;val api=remember{CommunityClient(c)};var endpoint by remember{mutableStateOf(api.endpoint)};var message by remember{mutableStateOf("")}
 Panel{Text("通知与二手平台",fontSize=14.sp,fontWeight=FontWeight.SemiBold);Text("默认地址通常无需修改。",fontSize=12.sp,lineHeight=17.sp,color=Muted);OutlinedTextField(endpoint,{endpoint=it},label={Text("HTTPS 服务地址")},singleLine=true,modifier=Modifier.fillMaxWidth());TextButton(onClick={runCatching{api.endpoint=endpoint;message="已保存"}.onFailure{message=it.message?:"地址无效"}}){Text("保存")};if(message.isNotBlank())Text(message,fontSize=12.sp,color=Pine)}
}



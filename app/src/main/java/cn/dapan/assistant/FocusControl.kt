package cn.dapan.assistant

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.service.notification.*
import java.time.*

object FocusControl {
 private fun prefs(c:Context)=c.getSharedPreferences("focus-options",0)
 fun allowNotifications(c:Context)=prefs(c).getBoolean("notifications",Build.VERSION.SDK_INT<29)
 fun allowCalls(c:Context)=prefs(c).getBoolean("calls",true)
 fun minutes(c:Context)=prefs(c).getInt("minutes",25).coerceIn(1,480)
 fun setMinutes(c:Context,value:Int){require(value in 1..480);check(prefs(c).edit().putInt("minutes",value).commit()){"专注时长保存失败"}}
 fun option(c:Context,key:String,value:Boolean){prefs(c).edit().putBoolean(key,value).apply()}
 fun access(c:Context)=c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
 fun needsDnd(c:Context)=!allowNotifications(c)
 fun openAccess(c:Context){c.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))}
 fun ruleActive(c:Context):Boolean {
  if(!access(c))return false
  val id=prefs(c).getString("rule",null)?:return false
  return runCatching{
   val manager=c.getSystemService(NotificationManager::class.java)
   if(Build.VERSION.SDK_INT>=35)manager.getAutomaticZenRuleState(id)==Condition.STATE_TRUE
   else manager.getAutomaticZenRule(id)?.isEnabled==true
  }.getOrDefault(false)
 }
 fun start(c:Context,s:AppStore,minutes:Int){
  require(minutes in 1..480){"请选择1分钟至8小时"}
  if(needsDnd(c)){check(Build.VERSION.SDK_INT>=29){"此系统不支持独立专注免打扰"};check(access(c)){"请先允许免打扰权限"}}
  cleanup(c)
  val end=System.currentTimeMillis()+minutes*60000L
  prefs(c).edit().putInt("minutes",minutes).putLong("duration",minutes*60000L).putLong("session",end).apply()
  s.updateFocusEnd(end)
  try{apply(c,end);Reminders.focus(c,end)}catch(e:Exception){finish(c,s,false);throw e}
 }
 fun duration(c:Context,end:Long):Long {
  val p=prefs(c)
  if(p.getLong("session",0)!=end)p.edit().putLong("session",end).putLong("duration",(end-System.currentTimeMillis()).coerceAtLeast(1000)).apply()
  return p.getLong("duration",25*60000L).coerceAtLeast(1000)
 }
 fun apply(c:Context,end:Long){
  if(end<=System.currentTimeMillis())return
  val p=prefs(c)
  if(p.getLong("applied",0)==end)return
  cleanup(c)
  if(!needsDnd(c)){p.edit().putLong("applied",end).apply();return}
  check(Build.VERSION.SDK_INT>=29&&access(c)){"免打扰未授权，通知限制未启用"}
  if(Build.VERSION.SDK_INT>=29){
   val manager=c.getSystemService(NotificationManager::class.java)
   val policy=ZenPolicy.Builder().disallowAllSounds().hideAllVisualEffects()
    .allowAlarms(true).allowCalls(if(allowCalls(c))ZenPolicy.PEOPLE_TYPE_ANYONE else ZenPolicy.PEOPLE_TYPE_NONE)
    .allowRepeatCallers(false).build()
   val uri=Uri.parse("condition://${c.packageName}/focus/$end")
   val rule=AutomaticZenRule("大盘助手 · 专注",ComponentName(c,FocusConditionProvider::class.java),uri,NotificationManager.INTERRUPTION_FILTER_PRIORITY,true)
   rule.zenPolicy=policy
   val id=manager.addAutomaticZenRule(rule)?:error("系统未能启用专注免打扰")
   p.edit().putString("rule",id).putLong("applied",end).apply()
   // The system also subscribes to the provider for expiry and process recovery.
   manager.setAutomaticZenRuleState(id,Condition(uri,"正在专注",Condition.STATE_TRUE))
  }
 }
 fun cleanup(c:Context){
  val p=prefs(c);val id=p.getString("rule",null)
  if(id!=null){
   val manager=c.getSystemService(NotificationManager::class.java)
   check(access(c)){"免打扰权限已关闭，请检查系统中的大盘专注规则"}
   if(access(c)){
    val rule=manager.getAutomaticZenRule(id)
    if(rule!=null&&rule.owner==ComponentName(c,FocusConditionProvider::class.java)){
     if(Build.VERSION.SDK_INT>=29)manager.setAutomaticZenRuleState(id,Condition(rule.conditionId,"专注已结束",Condition.STATE_FALSE))
     check(manager.removeAutomaticZenRule(id)){"请在系统免打扰设置关闭大盘专注规则"}
    }
   }
  }
  p.edit().remove("rule").remove("applied").apply()
 }
 fun finish(c:Context,s:AppStore,completed:Boolean){
  val hadSession=s.focusEnd>0
  val saved=runCatching{if(hadSession)s.updateFocusEnd(0)}.isSuccess
  // An unavailable data file must never prevent leaving the focus screen.
  if(!saved)s.focusEnd=0
  runCatching{Reminders.focus(c,0)}
  val restored=runCatching{cleanup(c)}.isSuccess
  if(!restored||!saved)android.widget.Toast.makeText(c,if(!restored)"专注已结束，请在系统免打扰设置检查大盘专注规则"else"专注已退出，但结束状态未能保存",android.widget.Toast.LENGTH_LONG).show()
  if(hadSession&&completed)Reminders.notify(c,2500,"专注完成",if(restored)"这一段时间，留给了你想做的事。"else"专注已结束，请检查系统免打扰设置。")
 }
 fun reconcile(c:Context,s:AppStore){
  if(s.loadError!=null)return
  if(s.focusEnd>0&&s.focusEnd<=System.currentTimeMillis())finish(c,s,true)
  else if(s.focusEnd==0L)runCatching{cleanup(c)}
 }
}

/** Only the system may bind this provider. Its condition follows the durable session deadline. */
class FocusConditionProvider:ConditionProviderService(){
 private val handler=Handler(Looper.getMainLooper())
 private val pending=mutableMapOf<Uri,Runnable>()
 override fun onConnected(){runCatching{FocusControl.reconcile(this,AppStore.get(this))}}
 override fun onSubscribe(conditionId:Uri){
  onUnsubscribe(conditionId)
  if(conditionId.authority!=packageName||conditionId.pathSegments.firstOrNull()!="focus")return
  val end=conditionId.lastPathSegment?.toLongOrNull()?:return
  val s=AppStore.get(this)
  val active=s.loadError==null&&s.focusEnd==end&&end>System.currentTimeMillis()
  notifyCondition(Condition(conditionId,if(active)"正在专注"else"专注已结束",if(active)Condition.STATE_TRUE else Condition.STATE_FALSE))
  if(active){
   val task=Runnable{onSubscribe(conditionId)}
   pending[conditionId]=task
   handler.postDelayed(task,(end-System.currentTimeMillis()).coerceIn(1,60000))
  }else if(s.focusEnd==end)runCatching{FocusControl.finish(this,s,true)}
 }
 override fun onUnsubscribe(conditionId:Uri){pending.remove(conditionId)?.let{handler.removeCallbacks(it)}}
 override fun onDestroy(){handler.removeCallbacksAndMessages(null);pending.clear();super.onDestroy()}
}

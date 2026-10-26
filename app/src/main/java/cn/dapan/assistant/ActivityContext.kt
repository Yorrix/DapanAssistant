package cn.dapan.assistant

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Activity Compose 1.8 has no LocalActivity; safely unwrap themed contexts instead. */
internal fun Context.hostActivity():Activity? {
 var current:Context=this
 val visited=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context,Boolean>())
 while(visited.add(current)){
  if(current is Activity)return current
  current=(current as? ContextWrapper)?.baseContext?:return null
 }
 return null
}

package cn.dapan.assistant

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.Locale

/** Names are retained only to discard obsolete saved routes and preferences. */
internal object RetiredFeatures {
 fun removed(name:String)=name.filterNot(Char::isWhitespace).lowercase(Locale.ROOT) in setOf("tibo雷达","tibo重置雷达")

 /** Upgrade cleanup is idempotent and never cancels unrelated reminders. */
 @Synchronized fun clean(context:Context){
  val c=context.applicationContext
  val migration=c.getSharedPreferences("feature-migrations",Context.MODE_PRIVATE)
  if(migration.getBoolean("radarRemoved",false))return
  runCatching{
   c.getSystemService(JobScheduler::class.java)?.let{it.cancel(7420);it.cancel(7421)}
   c.getSystemService(NotificationManager::class.java)?.let{manager->
    manager.activeNotifications.filter{it.tag?.startsWith("radar:")==true}.forEach{notification->
     notification.notification.contentIntent?.cancel()
     manager.cancel(notification.tag,notification.id)
    }
    manager.deleteNotificationChannel("reset-radar")
   }
   check(c.getSharedPreferences("reset-radar",Context.MODE_PRIVATE).edit().clear().commit())
   c.deleteSharedPreferences("reset-radar")
   AtomicFile(File(c.filesDir,"reset-radar-cache.json")).delete()
   val favorites=c.getSharedPreferences("tool-favorites",Context.MODE_PRIVATE)
   favorites.getStringSet("names",null)?.let{names->
    check(favorites.edit().putStringSet("names",names.filterNot(::removed).toSet()).commit())
   }
   val home=c.getSharedPreferences("home-layout",Context.MODE_PRIVATE)
   val edit=home.edit()
   home.getString("order",null)?.let{edit.putString("order",it.split(',').filterNot{key->key=="RADAR"}.joinToString(","))}
   home.getStringSet("enabled",null)?.let{edit.putStringSet("enabled",it-"RADAR")}
   check(edit.commit())
   check(migration.edit().putBoolean("radarRemoved",true).commit())
  }
 }
}

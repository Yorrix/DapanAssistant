package cn.dapan.assistant

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.time.*
import java.time.format.DateTimeFormatter

/** Cancel the PendingIntent and notification left by installations before 1.0.9. */
internal object MorningLastCall {
 const val ACTION="morning-last-call"
 const val PASS_ACTION="morning-last-call-pass"
 const val NOTIFICATION_ID=733
 private fun prefs(c:Context)=c.applicationContext.getSharedPreferences("morning-last-call",0)
 private fun operation(c:Context)=PendingIntent.getBroadcast(c,0,
  Intent(c,ReminderReceiver::class.java).setAction(ACTION).setData(Uri.parse("dapan://morning/last-minute"))
   ,
  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 fun cancelNotification(c:Context)=c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
 fun clear(c:Context){c.getSystemService(AlarmManager::class.java).cancel(operation(c));prefs(c).edit().clear().commit();cancelNotification(c)}

}

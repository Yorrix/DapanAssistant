package cn.dapan.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Test the real queue without opening an Activity or editing user events. */
class ReminderProbeReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) {
  when(intent.action) {
   "cn.dapan.assistant.TEST_REMINDER" ->
    Reminders.diagnostic(context,intent.getIntExtra("seconds",60))
   "cn.dapan.assistant.PREVIEW_MORNING" ->
    Reminders.notify(context,730,"今天晨签合格了吗？","选择合格或不合格；也可在月历中修改。",
     MorningCalendar.now().toLocalDate().toString())
  }
 }
}

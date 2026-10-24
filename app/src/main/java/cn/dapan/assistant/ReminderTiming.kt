package cn.dapan.assistant

/** A pending reminder must survive a resume that occurs after its original due time. */
internal object ReminderTiming {
 fun target(due:Long,now:Long,previous:Long,finished:Long,expires:Long):Long? = when {
  finished==due -> null
  due>now -> due
  previous==due && now<expires -> now+1000
  else -> null
 }
}

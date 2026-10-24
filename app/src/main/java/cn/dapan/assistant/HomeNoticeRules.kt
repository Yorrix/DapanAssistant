package cn.dapan.assistant

import org.json.JSONObject

enum class HomeNoticeScope(val label: String) {
 ALL("全部通知"), FOLLOWED("只显示关注来源");

 companion object {
  fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: ALL
 }
}

internal object HomeNoticeItems {
 fun select(items: List<JSONObject>, scope: HomeNoticeScope, followed: Set<String>): List<JSONObject> =
  items.asSequence()
   .filter { scope == HomeNoticeScope.ALL || NoticeDelta.host(it) in followed }
   .sortedByDescending { it.optString("date") }
   .take(3)
   .toList()
}

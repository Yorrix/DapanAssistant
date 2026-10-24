package cn.dapan.assistant

/** Owns one instance. Renderer exit, Compose disposal and Activity disposal may all release it. */
internal class TimetableWebLease<T : Any>(value: T) {
 private var held: T? = value
 val value: T? get() = held
 fun takeForRelease(): T? = held.also { held = null }
}

internal object TimetableWebRecovery {
 // Never replay an authentication redirect, ticket, query string or form submission after a crash.
 fun retryUrl(previous: String?): String {
  if (previous == null || !TimetableImport.courseUrl(previous)) return TimetableImport.ENTRY
  // Keep the school's current transport; do not automatically upgrade or downgrade it.
  return if (previous.startsWith("https://")) TimetablePage.COURSE_URL.replaceFirst("http://", "https://") else TimetablePage.COURSE_URL
 }
}

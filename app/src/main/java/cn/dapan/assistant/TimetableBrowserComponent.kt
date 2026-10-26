package cn.dapan.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.InputStream

internal object TimetableComponentResult {
 const val PACKAGE="cn.dapan.assistant.timetable"
 const val MAX_BYTES=600_000
 fun allowedUri(uri:String):Boolean=runCatching{
  val u=java.net.URI(uri)
  u.scheme=="content"&&u.rawAuthority=="$PACKAGE.results"&&u.rawQuery==null&&u.rawFragment==null&&
   u.path.matches(Regex("/timetable/[a-fA-F0-9-]{36}\\.json"))
 }.getOrDefault(false)
 fun read(input:InputStream):JSONObject {
  val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
  while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=MAX_BYTES){"返回的课表过大，请重新读取"};output.write(buffer,0,n)}
  val bytes=output.toByteArray()
  require(bytes.isNotEmpty()&&bytes.size<=MAX_BYTES){"返回的课表过大或为空，请重新读取"}
  val payload=JSONObject(bytes.toString(Charsets.UTF_8))
  require(payload.optInt("schema")==1){"课表组件版本不兼容，请更新组件"}
  require(payload.optJSONObject("grid")!=null&&payload.optJSONObject("courses")!=null){"课表内容不完整，请重新读取"}
  return payload
 }
}

/** A separately installed, same-signed browser. Never loads remote code into the host process. */
internal object TimetableBrowserComponent {
 fun installed(context:Context):Boolean=runCatching{
  val info=context.packageManager.getPackageInfo(TimetableComponentResult.PACKAGE,0)
  @Suppress("DEPRECATION") val version=if(android.os.Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
  version>=1&&context.packageManager.checkSignatures(context.packageName,TimetableComponentResult.PACKAGE)==PackageManager.SIGNATURE_MATCH
 }.getOrDefault(false)
 fun launch(context:Context):Intent {
  check(installed(context)){"课表组件未安装或签名不匹配，请重新安装"}
  return Intent().setComponent(ComponentName(TimetableComponentResult.PACKAGE,"${TimetableComponentResult.PACKAGE}.ImportActivity"))
 }
 @Suppress("DEPRECATION") fun verifyArchive(context:Context,file:File){
  val pm=context.packageManager
  val apk=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNATURES)?:error("组件安装包不可用，请重新下载")
  val own=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNATURES)
  require(apk.packageName==TimetableComponentResult.PACKAGE&&apk.versionCode>=1){"组件安装包不匹配，已停止安装"}
  val a=apk.signatures?.toList().orEmpty();val b=own.signatures?.toList().orEmpty()
  require(a.isNotEmpty()&&a.toSet()==b.toSet()){ "组件签名不匹配，已停止安装" }
 }
 fun installer(context:Context,file:File):Intent {
  verifyArchive(context,file)
  return Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context,"${context.packageName}.files",file),"application/vnd.android.package-archive")
   .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
 }
 fun readResult(context:Context,uri:Uri):TeachingImport {
  check(installed(context)){"课表组件签名发生变化，请重新安装后读取"}
  require(TimetableComponentResult.allowedUri(uri.toString())){"返回的课表地址不可信，已停止读取"}
  val p=context.contentResolver.openInputStream(uri)?.use(TimetableComponentResult::read)?:error("课表文件无法读取，请重试")
  return TimetableImport.read(TimetableImport.term(p.getJSONObject("grid")),p.getJSONObject("courses"))
 }
}

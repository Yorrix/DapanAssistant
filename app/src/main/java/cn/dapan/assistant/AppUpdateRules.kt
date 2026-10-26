package cn.dapan.assistant

import org.json.JSONObject

internal data class AppRelease(val code:Int,val name:String,val url:String,val bytes:Long,val sha256:String,val minSdk:Int,val abis:List<String>,val notes:String){
 val fileName get()="dapan-$code.apk"
}
internal object AppUpdateRules {
 const val FEED="https://39.105.161.86/updates/latest.json"
 const val CHECK_INTERVAL=6*60*60*1000L
 fun due(auto:Boolean,last:Long,now:Long)=auto&&(last<=0||now<last||now-last>=CHECK_INTERVAL)
 fun parse(raw:String):AppRelease {
  require(raw.toByteArray(Charsets.UTF_8).size<=65536){"更新信息过大"}
  val j=JSONObject(raw)
  require(j.getInt("schema")==1){"更新信息版本不兼容"}
  val code=j.getInt("versionCode");val name=j.getString("versionName")
  val bytes=j.getLong("bytes");val hash=j.getString("sha256");val url=j.getString("apkUrl")
  val sdk=j.getInt("minSdk");val a=j.getJSONArray("abis")
  val abis=(0 until a.length()).map{a.getString(it)}
  require(code>0&&name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))){"版本信息不完整"}
  require(url=="https://39.105.161.86/updates/dapan-$code.apk"){"更新地址不可信"}
  require(bytes in 1..180_000_000&&hash.matches(Regex("[a-f0-9]{64}"))){"安装包信息不完整"}
  require(sdk in 26..100&&abis.isNotEmpty()&&abis.distinct().size==abis.size&&abis.all{it in setOf("arm64-v8a","armeabi-v7a","x86","x86_64")}){"系统兼容信息不完整"}
  val notes=j.getString("notes");require(notes.length<=3000){"更新说明过长"}
  return AppRelease(code,name,url,bytes,hash,sdk,abis,notes)
 }
 fun compatible(r:AppRelease,sdk:Int,abis:List<String>)=sdk>=r.minSdk&&r.abis.any{it in abis}
 fun validateArchive(r:AppRelease,packageName:String,ownPackage:String,code:Long,installed:Long,signatures:Set<String>,ownSignatures:Set<String>){
  require(packageName==ownPackage&&code==r.code.toLong()&&code>installed){"安装包与新版信息不符，已停止安装"}
  require(signatures.isNotEmpty()&&ownSignatures.isNotEmpty()&&signatures==ownSignatures){"安装包签名不匹配，已停止安装"}
 }
}

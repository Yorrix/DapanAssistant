package cn.dapan.assistant

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.security.keystore.*
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class Secrets(c: Context) {
 init { SharedAi.initialize(c.applicationContext) }
 var revision by androidx.compose.runtime.mutableIntStateOf(0);private set
 private val prefs=c.getSharedPreferences("credentials",Context.MODE_PRIVATE)
 private fun key(): SecretKey {
  val s=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; val alias="dapan-local-keys"
  return (s.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
 }
 fun configured(name:String)=!prefs.getString(name,null).isNullOrBlank()
 fun get(name: String): String { val raw=prefs.getString(name,null) ?: return ""; return runCatching { val (iv,data)=raw.split(":",limit=2); val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP))); String(c.doFinal(Base64.decode(data,Base64.NO_WRAP))) }.getOrDefault("") }
 fun set(name: String,value: String) { if(value.isBlank()) { check(prefs.edit().remove(name).commit()){"密钥保存失败"}; revision++; return }; val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key()); prefs.edit().putString(name,Base64.encodeToString(c.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(value.trim().toByteArray()),Base64.NO_WRAP)).commit().also{check(it){"密钥保存失败"}};revision++ }
 var model: String get()=prefs.getString("model","deepseek-flash")!!; set(v) { prefs.edit().putString("model",v).apply() }
}

package cn.dapan.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import androidx.compose.runtime.*
import java.io.File
import java.io.IOException
import org.json.JSONObject

data class HeardWord(val entry:VocabularyWord,val count:Int,val first:Long,val last:Long) {
 fun json()=JSONObject().put("word",entry.word).put("phonetic",entry.phonetic).put("meaning",entry.meaning).put("count",count)
}
object VocabularyState {
 const val OWNER="classroom-vocabulary"
 var running by mutableStateOf(false)
 var status by mutableStateOf("只显示生词 · 不显示整句")
 var error by mutableStateOf<String?>(null)
 var revision by mutableIntStateOf(0)
}
class VocabularyPreferences(c:Context) {
 private val prefs=c.applicationContext.getSharedPreferences("classroom-vocabulary",0)
 var level:VocabularyLevel
  get()=runCatching{VocabularyLevel.valueOf(prefs.getString("level","HIGH_SCHOOL")!!)}.getOrDefault(VocabularyLevel.HIGH_SCHOOL)
  set(value){prefs.edit().putString("level",value.name).apply()}
 var context:String
  get()=prefs.getString("context","").orEmpty()
  set(value){prefs.edit().putString("context",value.take(400)).apply()}
}
class VocabularyDictionary private constructor(private val db:SQLiteDatabase) {
 private val cache=LruCache<String,VocabularyWord>(4096)
 @Synchronized fun lookup(word:String):VocabularyWord? {
  cache.get(word)?.let{return it}
  return db.rawQuery("SELECT word,phonetic,meaning,tags FROM words WHERE word=COALESCE((SELECT lemma FROM forms WHERE form=?),?)",arrayOf(word,word)).use{row->
   if(!row.moveToFirst())null else VocabularyWord(row.getString(0),row.getString(1),row.getString(2),row.getInt(3)).also{cache.put(word,it)}
  }
 }
 @Synchronized private fun close(){cache.evictAll();db.close()}
 companion object {
  @Volatile private var instance:VocabularyDictionary?=null
  const val MISSING="请先在课堂生词页下载离线词典，完成后即可开始收听；已有生词仍可查看。"
  internal fun file(c:Context)=File(c.noBackupFilesDir,"vocabulary-ecdict-v1.db")
  fun installed(c:Context)=file(c).let{it.isFile&&it.length()>16}
  fun requireInstalled(c:Context){check(installed(c)){MISSING}}
  @Synchronized fun open(c:Context):VocabularyDictionary {
   instance?.let{return it}
   requireInstalled(c)
   val db=try{SQLiteDatabase.openDatabase(file(c).path,null,SQLiteDatabase.OPEN_READONLY)}catch(e:Exception){throw IOException("离线词典未能打开，请在课堂生词设置中重新下载。",e)}
   try{db.rawQuery("SELECT word,phonetic,meaning,tags FROM words LIMIT 1",null).use{check(it.moveToFirst())};db.rawQuery("SELECT lemma FROM forms LIMIT 1",null).use{check(it.moveToFirst())}}
   catch(e:Exception){db.close();throw IOException("离线词典不完整，请在课堂生词设置中重新下载。",e)}
   return VocabularyDictionary(db).also{instance=it}
  }
  @Synchronized internal fun install(c:Context,complete:File){
   check(!VocabularyState.running){"请先暂停课堂生词收听，再安装词典"}
   instance?.close();instance=null
   // The validated temporary file lives in the same directory; rename is atomic.
   android.system.Os.rename(complete.path,file(c).path)
  }
  @Synchronized fun remove(c:Context){
   check(!AudioState.running&&!VocabularyState.running){"请先结束录音，再删除词典"}
   instance?.close();instance=null
   val file=file(c)
   check(!file.exists()||file.delete()){"词典删除失败，请重试"}
  }
 }
}
class VocabularyStore(c:Context):SQLiteOpenHelper(c.applicationContext,"classroom-vocabulary.db",null,1) {
 override fun onCreate(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE heard(word TEXT PRIMARY KEY,phonetic TEXT NOT NULL,meaning TEXT NOT NULL,tags INTEGER NOT NULL,count INTEGER NOT NULL,first INTEGER NOT NULL,last INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE known(word TEXT PRIMARY KEY)")
 }
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
 fun known():Set<String> = readableDatabase.rawQuery("SELECT word FROM known ORDER BY word",null).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
 fun mark(word:String,value:Boolean){if(value)writableDatabase.execSQL("INSERT OR IGNORE INTO known(word) VALUES(?)",arrayOf(word))else writableDatabase.delete("known","word=?",arrayOf(word))}
 fun add(hits:List<VocabularyHit>){
  val db=writableDatabase;val now=System.currentTimeMillis();db.beginTransaction()
  try{
   // Preserve speech order even when several unfamiliar words arrive in one sentence.
   var first=db.rawQuery("SELECT COALESCE(MAX(first),0) FROM heard",null).use{it.moveToFirst();maxOf(now,it.getLong(0)+1)}
   hits.forEach{h->
   db.execSQL("INSERT OR IGNORE INTO heard VALUES(?,?,?,?,0,?,?)",arrayOf<Any>(h.word.word,h.word.phonetic,h.word.meaning,h.word.tags,first++,now))
   db.execSQL("UPDATE heard SET count=count+?,last=? WHERE word=?",arrayOf<Any>(h.count,now,h.word.word))
  };db.setTransactionSuccessful()}finally{db.endTransaction()}
 }
 fun list(level:VocabularyLevel,query:String="",limit:Int=600):List<HeardWord> = readableDatabase.rawQuery("SELECT word,phonetic,meaning,tags,count,first,last FROM heard WHERE (tags & ?)=0 AND word NOT IN (SELECT word FROM known) AND (instr(lower(word),lower(?))>0 OR instr(meaning,?)>0) ORDER BY first DESC,word LIMIT ?",arrayOf(level.mask.toString(),query,query,limit.toString())).use{c->buildList{while(c.moveToNext())add(HeardWord(VocabularyWord(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)),c.getInt(4),c.getLong(5),c.getLong(6)))}}
 fun clear(){writableDatabase.delete("heard",null,null)}
}

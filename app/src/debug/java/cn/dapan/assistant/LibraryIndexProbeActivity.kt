package cn.dapan.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Emulator-only integration fixture: never reads or submits a personal API key. */
class LibraryIndexProbeActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  if(!Build.MODEL.contains("sdk_gphone")&&!Build.FINGERPRINT.contains("generic")){finish();return}
  lifecycleScope.launch(Dispatchers.IO){
   val report=JSONObject()
   try{
    check(!Secrets(this@LibraryIndexProbeActivity).configured("deepseek")){"Probe requires an emulator with no configured key"}
    LibraryStore(this@LibraryIndexProbeActivity).use{store->
     val text=File(cacheDir,"library-probe.txt").apply{writeText("资料检索演示\n"+"课堂笔记与练习。\n".repeat(6000)+"期末报名截止为 10 月 28 日，携带玉兰卡。\n")}
     val doc=store.all().firstOrNull{it.name=="检索演示.txt"}?:store.saveAttachment(AssistantFile("probe-text","检索演示.txt","text/plain",text.length(),text.path,""))
     val hits=store.searchResults("报名 玉兰卡").firstOrNull{it.document.id==doc.id}?:error("Tail query did not match")
     check(hits.match.evidence.any{it.part.start>30000&&it.quote.contains("玉兰卡")})
     val page=LibraryRetrieval.document(doc,"",50000,1000)
     check(page.getInt("startOffset")==50000&&page.getString("text")==doc.text.substring(50000,51000))
     val image=File(cacheDir,"library-probe.png")
     Bitmap.createBitmap(900,500,Bitmap.Config.ARGB_8888).let{bitmap->
      try{Canvas(bitmap).apply{drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=44f};drawText("资料库图片识别",45f,100f,paint);drawText("请携带玉兰卡 · A01",45f,180f,paint)};image.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
     }
     val picture=store.all().firstOrNull{it.name=="图片识别演示.png"}?:store.saveAttachment(AssistantFile("probe-image","图片识别演示.png","image/png",image.length(),image.path,""))
     repeat(30){if(store.find(picture.id)?.state!=LibraryIndexState.NO_KEY)delay(100)}
     val saved=store.find(picture.id)?:error("Image not saved")
     check(File(saved.path).length()==image.length()&&saved.text.isEmpty()&&saved.state==LibraryIndexState.NO_KEY)
     report.put("tailSearch",true).put("readBeyond30000",true).put("originalImageRetained",true)
      .put("missingKeyState",saved.state).put("evidence",hits.match.evidence.first().part.label).put("networkRequests",0)
    }
   }catch(e:Exception){report.put("error",e.javaClass.simpleName+": "+e.message)}
   File(filesDir,"library-index-probe.json").writeText(report.toString())
   withContext(Dispatchers.Main){finish()}
  }
 }
}

package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ImageOcrGeometryTest {
 @Test fun longScreenshotsAreSplitBeforeDownsampling(){
  val parts=ImageOcrGeometry.parts(1080,20000,1)
  assertEquals(6,parts.size);assertTrue(parts.all{it.sample==1})
  assertEquals(0,parts.first().raw.top);assertEquals(20000,parts.last().raw.bottom)
  parts.zipWithNext().forEach{(a,b)->assertTrue(a.raw.bottom>=b.raw.top)}
 }
 @Test fun everyExifOrientationMapsStripsInsideOriginalAndPreservesUprightOrder(){
  for(orientation in 1..8)for((w,h) in listOf(1000 to 12000,12000 to 1000,1200 to 900)){
   val parts=ImageOcrGeometry.parts(w,h,orientation)
   assertTrue(parts.size in 1..6)
   parts.forEach{p->assertTrue(p.raw.left>=0&&p.raw.top>=0&&p.raw.right<=w&&p.raw.bottom<=h);assertTrue(p.raw.width>0&&p.raw.height>0)}
   val uprightW=if(orientation>=5)h else w;val uprightH=if(orientation>=5)w else h
   assertEquals(0,parts.first().upright.left);assertEquals(0,parts.first().upright.top)
   assertEquals(uprightW,parts.last().upright.right);assertEquals(uprightH,parts.last().upright.bottom)
   parts.zipWithNext().forEach{(a,b)->assertTrue(a.upright.left<=b.upright.left&&a.upright.top<=b.upright.top)}
  }
 }
 @Test fun oversizedPicturesHaveBoundedDecodedRegions(){
  ImageOcrGeometry.parts(30000,20000,6).forEach{p->assertTrue((p.raw.width.toLong()+p.sample-1)/p.sample*((p.raw.height+p.sample-1)/p.sample)<=6_000_000)}
 }
 @Test fun nullRefusalIsAcceptedAndRequestCannotBeReplayed(){
  val raw=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("finish_reason","stop")
   .put("message",JSONObject().put("refusal",JSONObject.NULL).put("content","{\"text\":\"玉兰卡\"}")))).toString()
  assertEquals("玉兰卡",ImageOcrProtocol.parse(raw).text)
  assertTrue(imageOcrRequestBody("{}").isOneShot())
 }
}

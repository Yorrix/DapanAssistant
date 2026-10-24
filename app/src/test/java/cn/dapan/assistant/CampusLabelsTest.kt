package cn.dapan.assistant

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CampusLabelsTest {
 @Test fun allWingsStayAtTheirMapPositionsThroughZoomAndRotation(){
  val center=Offset(180f,300f)
  for(zoom in listOf(.65f,1f,1.8f,3f,7f))for(degrees in 0 until 360 step 30){
   val angle=Math.toRadians(degrees.toDouble());val anchors=listOf("N" to Offset(0f,-8f),"S" to Offset(0f,5f),"W" to Offset(-7f,0f),"E" to Offset(7f,0f)).associate{(name,p)->
    "A01-$name" to center+Offset((p.x*cos(angle)-p.y*sin(angle)).toFloat(),(p.x*sin(angle)+p.y*cos(angle)).toFloat())*zoom
   }
   val labels=a01LabelPositions(anchors)
   assertEquals(listOf("A01-N","A01-S","A01-W","A01-E"),labels.keys.toList())
   assertEquals("Labels must never be repelled away from the building",anchors,labels)
  }
 }
 @Test fun movingPastViewportEdgeDoesNotClampOrHideIndividualLetters(){
  val c=Offset(5f,5f)
  val anchors=mapOf("A01-N" to c+Offset(0f,-10f),"A01-S" to c+Offset(0f,10f),"A01-W" to c+Offset(-10f,0f),"A01-E" to c+Offset(10f,0f))
  assertEquals(anchors,a01LabelPositions(anchors))
  val shifted=anchors.mapValues{it.value-Offset(500f,500f)}
  assertEquals(shifted,a01LabelPositions(shifted))
 }
}

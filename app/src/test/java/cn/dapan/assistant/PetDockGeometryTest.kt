package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test

class PetDockGeometryTest {
 @Test fun everyEdgeKeepsFortyEightPixelsVisibleAtSixtyPixelSize(){
  assertEquals(-12f,PetDockGeometry.x(PetEdge.LEFT,.5f,400f,60f),.01f)
  assertEquals(352f,PetDockGeometry.x(PetEdge.RIGHT,.5f,400f,60f),.01f)
  assertEquals(-12f,PetDockGeometry.y(PetEdge.TOP,.5f,800f,60f),.01f)
  assertEquals(752f,PetDockGeometry.y(PetEdge.BOTTOM,.5f,800f,60f),.01f)
 }
 @Test fun rotationPreservesRelativeEdgePosition(){
  val ratio=PetDockGeometry.ratio(PetEdge.RIGHT,300f,370f,400f,800f,60f)
  assertEquals(.5f,ratio,.001f)
  assertEquals(170f,PetDockGeometry.y(PetEdge.RIGHT,ratio,400f,60f),.001f)
 }
 @Test fun nearestEdgeAndSmallHostsStayBounded(){
  assertEquals(PetEdge.LEFT,PetDockGeometry.nearest(20f,400f,400f,800f))
  assertEquals(PetEdge.BOTTOM,PetDockGeometry.nearest(200f,790f,400f,800f))
  assertEquals(0f,PetDockGeometry.ratio(PetEdge.LEFT,0f,100f,30f,30f,60f),0f)
  assertEquals(1f,PetDockGeometry.ratio(PetEdge.RIGHT,0f,900f,400f,800f,60f),0f)
 }
}

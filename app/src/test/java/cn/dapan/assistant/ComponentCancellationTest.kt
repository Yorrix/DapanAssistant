package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

class ComponentCancellationTest {
 @Test fun blockedIoIsCancelledBySharedWatcher(){
  val started=CountDownLatch(1);val finished=CountDownLatch(1)
  val released=AtomicBoolean(false);val cancellations=AtomicInteger();val failure=AtomicReference<Throwable?>()
  val worker=Thread{
   try{
    ComponentCancellation.watch({cancellations.incrementAndGet();released.set(true)}){
     started.countDown()
     // Simulate a socket read that does not throw when its thread is interrupted.
     while(!released.get())LockSupport.parkNanos(5_000_000)
    }
   }catch(e:Throwable){failure.set(e)}finally{finished.countDown()}
  }
  worker.start()
  try{
   assertTrue(started.await(2,TimeUnit.SECONDS))
   worker.interrupt()
   assertTrue("Cancellation must not wait for the HTTP read timeout",finished.await(2,TimeUnit.SECONDS))
   assertEquals(1,cancellations.get())
   assertTrue(failure.get() is InterruptedIOException)
  }finally{released.set(true);worker.interrupt();worker.join(2000)}
 }

 @Test fun completedWatchDoesNotCancelLaterWorkOnTheSameThread(){
  val done=CountDownLatch(1);val exit=AtomicBoolean(false);val cancellations=AtomicInteger()
  val worker=Thread{
   ComponentCancellation.watch({cancellations.incrementAndGet()}){Unit}
   done.countDown()
   while(!exit.get())LockSupport.parkNanos(5_000_000)
  }
  worker.start()
  try{
   assertTrue(done.await(2,TimeUnit.SECONDS));worker.interrupt()
   CountDownLatch(1).await(300,TimeUnit.MILLISECONDS)
   assertEquals(0,cancellations.get())
  }finally{exit.set(true);worker.interrupt();worker.join(2000)}
 }

 @Test fun failedWatchPreservesTheNetworkFailure(){
  val expected=IOException("connection failed")
  val actual=assertThrows(IOException::class.java){ComponentCancellation.watch({}){throw expected}}
  assertSame(expected,actual)
 }

 @Test fun preInterruptedWorkDoesNotBegin(){
  val ran=AtomicBoolean(false)
  Thread.currentThread().interrupt()
  try{assertThrows(InterruptedIOException::class.java){ComponentCancellation.watch({}){ran.set(true)}}}
  finally{Thread.interrupted()}
  assertFalse(ran.get())
 }

 @Test fun waitingForAnotherDownloadCanBeCancelled(){
  val lock=ReentrantLock();val waiting=CountDownLatch(1);val done=CountDownLatch(1)
  val ran=AtomicBoolean(false);val failure=AtomicReference<Throwable?>()
  lock.lock()
  val worker=Thread{
   waiting.countDown()
   try{ComponentCancellation.locked(lock){ran.set(true)}}catch(e:Throwable){failure.set(e)}finally{done.countDown()}
  }
  worker.start()
  try{
   assertTrue(waiting.await(2,TimeUnit.SECONDS));worker.interrupt()
   assertTrue(done.await(2,TimeUnit.SECONDS))
   assertFalse(ran.get());assertTrue(failure.get() is InterruptedIOException)
   assertTrue(lock.isHeldByCurrentThread)
  }finally{lock.unlock();worker.join(2000)}
 }

 @Test fun failedOperationReleasesItsComponentLock(){
  val lock=ReentrantLock()
  assertThrows(IllegalStateException::class.java){ComponentCancellation.locked(lock){error("failed")}}
  assertFalse(lock.isLocked)
 }
}

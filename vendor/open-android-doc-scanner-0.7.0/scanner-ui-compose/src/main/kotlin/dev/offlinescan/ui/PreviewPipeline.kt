package dev.offlinescan.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import dev.offlinescan.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Display pixels only. Export always receives the original ScanConfig and source image. */
internal object PreviewPolicy {
    const val THUMBNAIL = 384
    const val FIRST_FRAME = 768
    fun edge(width: Int, height: Int, zoomed: Boolean = false): Int {
        if (width <= 0 || height <= 0) return FIRST_FRAME
        val pixels = maxOf(width, height)
        val desired = if (zoomed) pixels * 2 else pixels
        // Bucket dimensions avoid re-rendering for single-pixel layout/inset changes.
        return (((desired + 127) / 128) * 128).coerceIn(FIRST_FRAME, if (zoomed) 2560 else 1536)
    }
}

/** One bounded worker, shared by the selected page, thumbnails and neighbour peeks. */
internal class PreviewPipeline(private val protectedWork: suspend (() -> Bitmap?) -> Bitmap?) {
    private data class Key(val id: String, val original: File, val edits: Edits, val edge: Int)
    private class Work(val deferred: Deferred<Bitmap?>, var users: Int = 0)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = Mutex()
    private val pending = LinkedHashMap<Key, Work>()
    private val cache = LinkedHashMap<Key, Bitmap>(16, .75f, true)
    private var bytes = 0L
    private var closed = false
    private val maximumBytes = 32L * 1024 * 1024

    private fun Key.matches(page: ScanPage) = id == page.id && original == page.original && edits == page.edits

    fun cached(page: ScanPage, edge: Int, allowSmaller: Boolean = false): Bitmap? = synchronized(lock) {
        val matches = cache.keys.filter { it.matches(page) }
        val key = matches.filter { it.edge >= edge }.minByOrNull { it.edge }
            ?: if (allowSmaller) matches.maxByOrNull { it.edge } else null
        key?.let { cache[it] }?.takeUnless { it.isRecycled }
    }

    private fun put(key: Key, bitmap: Bitmap) = synchronized(lock) {
        if (closed || bitmap.isRecycled) return@synchronized
        cache.remove(key)?.let { bytes -= it.allocationByteCount }
        cache[key] = bitmap; bytes += bitmap.allocationByteCount
        while (bytes > maximumBytes || cache.size > 80) bytes -= cache.remove(cache.keys.first())!!.allocationByteCount
        // Compose can still hold evicted bitmaps. Drop references rather than recycling displayed images.
    }

    suspend fun render(page: ScanPage, processor: ImageProcessor, config: ScanConfig, requestedEdge: Int,
                       reusePendingFirstFrame: Boolean = false): Bitmap? {
        val edge = minOf(config.maxOutputDimension, requestedEdge)
        cached(page, edge)?.let { return it }
        val acquired = synchronized(lock) {
            check(!closed) { "Preview session is closed" }
            // A thumbnail can await an already requested larger preview; the selected
            // page can also use an in-flight thumbnail as its first visible frame.
            val existing = pending.keys.filter { it.matches(page) && (it.edge >= edge || reusePendingFirstFrame) }
                .minByOrNull { it.edge }
            val key = existing ?: Key(page.id, page.original, page.edits, edge)
            val work = pending[key] ?: Work(scope.async(start = CoroutineStart.LAZY) {
                worker.withLock {
                    currentCoroutineContext().ensureActive()
                    cached(page, key.edge)?.let { return@withLock it }
                    val job = currentCoroutineContext()[Job]
                    val decoded = AtomicReference<Bitmap?>(null)
                    val file = File(page.original.parentFile, "preview-${System.nanoTime()}.jpg")
                    val started = SystemClock.elapsedRealtime()
                    try {
                        protectedWork {
                            processor.render(page.original, page.edits, file, ExportFormat.JPEG,
                                config.copy(maxOutputDimension = key.edge, jpegQuality = 86),
                                Cancellation { if (job?.isActive != true) throw CancellationException() })
                            if (job?.isActive != true) throw CancellationException()
                            BitmapFactory.decodeFile(file.absolutePath).also { decoded.set(it) }
                        }.also { bitmap ->
                            currentCoroutineContext().ensureActive()
                            if (bitmap != null) { put(key, bitmap); decoded.set(null) }
                            if (Log.isLoggable("DapanScanPreview", Log.DEBUG))
                                Log.d("DapanScanPreview", "edge=${key.edge} elapsedMs=${SystemClock.elapsedRealtime()-started}")
                        }
                    } finally {
                        decoded.getAndSet(null)?.takeUnless { it.isRecycled }?.recycle()
                        file.delete()
                    }
                }
            }).also { pending[key] = it }
            work.users++
            key to work
        }
        val (key, work) = acquired
        try { work.deferred.start(); return work.deferred.await() }
        finally {
            synchronized(lock) {
                work.users--
                if (work.users == 0) {
                    if (pending[key] === work) pending.remove(key)
                    if (!work.deferred.isCompleted) work.deferred.cancel()
                }
            }
        }
    }

    fun retain(pages: List<ScanPage>) = synchronized(lock) {
        val originals = pages.map { it.id to it.original }.toSet()
        // Keep both adjusted and original comparison variants, plus recent slider values.
        // The previous cache incorrectly evicted the other comparison on every write.
        cache.keys.filter { (it.id to it.original) !in originals }.forEach { bytes -= cache.remove(it)!!.allocationByteCount }
        pending.keys.filter { (it.id to it.original) !in originals }.forEach { pending.remove(it)?.deferred?.cancel() }
    }

    fun cancelPending() = synchronized(lock) { pending.values.forEach { it.deferred.cancel() }; pending.clear() }
    fun close() = synchronized(lock) { closed = true; scope.cancel(); pending.clear(); cache.clear(); bytes = 0 }
}

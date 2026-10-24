package dev.offlinescan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import dev.offlinescan.core.*
import dev.offlinescan.processing.OpenCvProcessor
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicit debug entry point: generated pixels only, never the user's camera or library. */
object ScannerPreviewBenchmark {
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "scanner-preview-benchmark").apply { mkdirs() }
        val source = File(directory, "generated-document.jpg")
        val generated = Bitmap.createBitmap(3024, 4032, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(generated)
        canvas.drawColor(Color.rgb(230, 227, 219))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE; canvas.drawRect(60f, 60f, 2964f, 3972f, paint)
        paint.color = Color.rgb(40, 44, 52); paint.textSize = 72f
        canvas.drawText("Generated document / preview benchmark", 150f, 240f, paint)
        paint.textSize = 42f
        repeat(62) { row ->
            canvas.drawText("${row + 1}. Document text, numbers 0123456789; mathematical symbols + - =.", 150f, 350f + row * 55, paint)
        }
        source.outputStream().use { generated.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        generated.recycle()
        val before = digest(source)
        val originalConfig = ScanConfig()
        val page = ScanPage("benchmark", source, 3024, 4032, Edits(preset = Preset.AUTO))
        val processor = OpenCvProcessor()
        // Both comparisons run after native initialization; report processing rather than startup noise.
        val warmup = File(directory, "warmup.jpg")
        processor.render(source, page.edits, warmup, ExportFormat.JPEG, originalConfig.copy(maxOutputDimension = 256))
        warmup.delete()

        val baselineStart = SystemClock.elapsedRealtime()
        val baseline = coroutineScope {
            listOf(1024, 3072).map { edge -> async {
                delay(180) // Delay in the previous rememberRenderedPreview implementation.
                val file = File(directory, "baseline-$edge.png")
                try {
                    processor.render(source, page.edits, file, ExportFormat.PNG, originalConfig.copy(maxOutputDimension = edge))
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
                    val elapsed = SystemClock.elapsedRealtime() - baselineStart
                    bitmap.recycle()
                    edge to elapsed
                } finally { file.delete() }
            } }.awaitAll().toMap()
        }

        val calls = AtomicInteger()
        val counted = object : ImageProcessor by processor {
            override fun render(source: File, edits: Edits, destination: File, format: ExportFormat,
                                config: ScanConfig, cancellation: Cancellation): ExportedPage {
                calls.incrementAndGet()
                return processor.render(source, edits, destination, format, config, cancellation)
            }
        }
        val pipeline = PreviewPipeline { work -> work() }
        val optimizedStart = SystemClock.elapsedRealtime()
        try {
            var firstFrameMs = 0L
            var firstWidth = 0
            var firstHeight = 0
            coroutineScope {
                val selected = async(start = CoroutineStart.UNDISPATCHED) {
                    val first = checkNotNull(pipeline.render(page, counted, originalConfig, PreviewPolicy.FIRST_FRAME))
                    firstFrameMs = SystemClock.elapsedRealtime() - optimizedStart
                    firstWidth = first.width; firstHeight = first.height
                }
                val thumbnail = async {
                    delay(120)
                    checkNotNull(pipeline.render(page, counted, originalConfig, PreviewPolicy.THUMBNAIL))
                }
                selected.await(); thumbnail.await()
            }
            val firstFrameRenderCalls = calls.get()
            delay(120) // Full viewport refinement starts only after the first frame is available.
            val detailed = checkNotNull(pipeline.render(page, counted, originalConfig, 1536))
            val detailedMs = SystemClock.elapsedRealtime() - optimizedStart
            val callsBeforeCacheHit = calls.get()
            checkNotNull(pipeline.render(page, counted, originalConfig, 1536))
            val cacheReused = calls.get() == callsBeforeCacheHit
            // Preview dimensions and compression are local copies; export uses the original settings.
            pipeline.cancelPending()
            val exported = File(directory, "export.jpg")
            val exportPage = try {
                processor.render(source, page.edits, exported, ExportFormat.JPEG, originalConfig)
            } finally { exported.delete() }
            JSONObject()
                .put("generatedInputWidth", page.width).put("generatedInputHeight", page.height)
                .put("baselineFirstAvailableMs", baseline.values.min())
                .put("baseline1024Ms", baseline[1024]).put("baseline3072Ms", baseline[3072])
                .put("optimizedFirstFrameMs", firstFrameMs)
                .put("optimizedFirstFrameWidth", firstWidth).put("optimizedFirstFrameHeight", firstHeight)
                .put("optimizedViewportMs", detailedMs)
                .put("optimizedViewportWidth", detailed.width).put("optimizedViewportHeight", detailed.height)
                .put("firstFrameRenderCalls", firstFrameRenderCalls)
                .put("cacheReused", cacheReused)
                .put("exportWidth", exportPage.width).put("exportHeight", exportPage.height)
                .put("exportDimensionSetting", originalConfig.maxOutputDimension)
                .put("exportJpegQuality", originalConfig.jpegQuality)
                .put("sourceUnchanged", before.contentEquals(digest(source)))
                .put("measurement", "generated 12MP image; warm native engine; render+encode+decode wall time; excludes camera and Compose drawing")
                .toString(2)
        } finally { pipeline.close(); source.delete() }
    }

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) { val size = input.read(bytes); if (size < 0) break; hash.update(bytes, 0, size) }
        }
        return hash.digest()
    }
}

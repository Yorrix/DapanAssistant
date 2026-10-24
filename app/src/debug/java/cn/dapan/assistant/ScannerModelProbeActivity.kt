package cn.dapan.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.offlinescan.processing.LearnedDocumentDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Shell-only, emulator-only check using a synthetic paper image and free model download. */
class ScannerModelProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)
        if (!emulator) {
            finish()
            return
        }
        setContentView(TextView(this).apply { text = "正在验证离线扫描资源…" })
        lifecycleScope.launch(Dispatchers.IO) {
            val report = JSONObject().put("syntheticInput", true).put("paidApiRequests", 0)
            val started = SystemClock.elapsedRealtime()
            var bitmap: Bitmap? = null
            try {
                var received = 0L
                var total = 0L
                ScannerModelComponent.ensureReady(this@ScannerModelProbeActivity) { current, expected ->
                    received = current
                    total = expected
                }
                report.put("modelReady", true).put("modelBytes", total).put("verifiedBytes", received)
                    .put("modelPreparationMillis", SystemClock.elapsedRealtime() - started)
                val synthetic = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
                bitmap = synthetic
                Canvas(synthetic).apply {
                    drawColor(Color.rgb(25, 25, 25))
                    drawRect(100f, 40f, 540f, 440f, Paint().apply { color = Color.WHITE })
                    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 22f }
                    drawText("OFFLINE SCANNER", 145f, 100f, ink)
                    repeat(8) { index -> drawLine(145f, 140f + index * 30, 480f, 140f + index * 30, ink) }
                }
                val inferenceStarted = SystemClock.elapsedRealtime()
                LearnedDocumentDetector(this@ScannerModelProbeActivity).use { detector ->
                    val detection = detector.detectBitmap(synthetic)
                    report.put("inferenceSucceeded", true)
                        .put("inferenceMillis", SystemClock.elapsedRealtime() - inferenceStarted)
                        .put("detectedCorners", detection.corners != null)
                        .put("confidence", if (detection.confidence.isFinite()) detection.confidence else JSONObject.NULL)
                }
            } catch (error: Exception) {
                recordError(report, error)
            } catch (error: LinkageError) {
                recordError(report, error)
            } finally {
                bitmap?.recycle()
                report.put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                File(filesDir, "scanner-model-probe.json").writeText(report.toString())
            }
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private fun recordError(report: JSONObject, error: Throwable) {
        report.put("inferenceSucceeded", false)
            .put("error", error.javaClass.simpleName)
            .put("message", error.message.orEmpty().replace(Regex("\\s+"), " ").take(240))
    }
}

package io.github.snapledger.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.github.snapledger.parse.EngineReading
import io.github.snapledger.parse.Fusion
import io.github.snapledger.parse.OcrLine
import io.github.snapledger.parse.OcrPage
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 端侧文字识别，两个引擎配合，截图不会离开手机：
 * 1. ML Kit 找出每一行文字的位置并给出初步结果；
 * 2. PP-OCRv5 对每一行再识别一遍（中文更准）；
 * 3. [Fusion] 合并：汉字以 PP-OCRv5 为准，数字两边不一致时标记出来让用户核对。
 *
 * 第二个引擎加载或运行失败时自动退回只用 ML Kit，不影响记账。
 */
class OcrEngine(private val context: Context) : AutoCloseable {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var pp: PpRecognizer? = null
    private var ppUnavailable = false

    /** 识别图片，返回每个文字块及其位置（版面分析在 [io.github.snapledger.parse.Layout] 里做）。 */
    suspend fun recognize(uri: Uri): OcrPage {
        val bitmap = withContext(Dispatchers.IO) {
            decode(uri).let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
        }
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = text.textBlocks.flatMap { it.lines }
        if (lines.any { it.boundingBox == null }) return OcrPage.fromRows(text.text.lines())
        val base = lines.map { l ->
            val b = l.boundingBox!!
            OcrLine(l.text, b.left, b.top, b.right, b.bottom)
        }
        return withContext(Dispatchers.Default) { refine(bitmap, base) }
    }

    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // 需要直接读像素
            // 超长截图降采样，避免内存不足
            val pixels = info.size.width.toLong() * info.size.height
            if (pixels > MAX_PIXELS) decoder.setTargetSampleSize(2)
        }
    }

    /** 用第二个引擎逐行复核。 */
    private fun refine(bitmap: Bitmap, lines: List<OcrLine>): OcrPage {
        val rec = secondEngine() ?: return OcrPage(lines, bitmap.width, bitmap.height)
        val started = SystemClock.elapsedRealtime()
        val readings = ArrayList<EngineReading>(lines.size)
        val out = lines.mapIndexed { index, line ->
            val overBudget = index >= MAX_LINES || SystemClock.elapsedRealtime() - started > BUDGET_MS
            val box = if (overBudget) null else LineCrop.paddedBox(line.left, line.top, line.right, line.bottom, bitmap.width, bitmap.height)
            val result = box?.let { b ->
                runCatching {
                    val w = b[2] - b[0]
                    val h = b[3] - b[1]
                    val px = IntArray(w * h)
                    bitmap.getPixels(px, 0, w, b[0], b[1], w, h)
                    rec.recognize(px, w, h)
                }.getOrNull()
            }
            if (result == null) {
                readings += EngineReading(line.text, null, 0f)
                line
            } else {
                readings += EngineReading(line.text, result.text, result.mean)
                val fused = Fusion.fuse(line.text, result.text, result.conf)
                line.copy(text = fused.text, alt = fused.alt)
            }
        }
        return OcrPage(out, bitmap.width, bitmap.height, readings)
    }

    private fun secondEngine(): PpRecognizer? {
        pp?.let { return it }
        if (ppUnavailable) return null
        return runCatching {
            val model = context.assets.open("ppocr/rec.onnx").use { it.readBytes() }
            val dict = context.assets.open("ppocr/dict.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }
            PpRecognizer(model, dict)
        }.onSuccess { pp = it }.onFailure { ppUnavailable = true }.getOrNull()
    }

    /** 图片内容的 SHA-256，用来识别“同一张截图导入了两次”。 */
    fun sha256(uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    override fun close() {
        recognizer.close()
        runCatching { pp?.close() }
    }

    private companion object {
        const val MAX_PIXELS = 40_000_000L
        const val MAX_LINES = 160
        const val BUDGET_MS = 10_000L
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

package io.github.snapledger.ocr

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.github.snapledger.parse.OcrLine
import io.github.snapledger.parse.OcrPage
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** 端侧文字识别：ML Kit 中文模型打包在 APK 里，截图不会离开手机。 */
class OcrEngine(private val context: Context) : AutoCloseable {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /** 识别图片，返回每个文字块及其位置（版面分析在 [io.github.snapledger.parse.Layout] 里做）。 */
    suspend fun recognize(uri: Uri): OcrPage {
        val image = InputImage.fromFilePath(context, uri)
        val text = recognizer.process(image).await()
        val lines = text.textBlocks.flatMap { it.lines }
        if (lines.any { it.boundingBox == null }) return OcrPage.fromRows(text.text.lines())
        return OcrPage(
            lines.map { l ->
                val b = l.boundingBox!!
                OcrLine(l.text, b.left, b.top, b.right, b.bottom)
            },
            image.width,
            image.height,
        )
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

    override fun close() = recognizer.close()
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

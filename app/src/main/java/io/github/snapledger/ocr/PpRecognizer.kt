package io.github.snapledger.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor

/** 一行文字的识别结果：[conf] 是每个字符的置信度（0–1），与 [text] 等长。 */
class PpResult(val text: String, val conf: FloatArray) {
    val mean: Float get() = if (conf.isEmpty()) 0f else conf.sum() / conf.size
}

/**
 * PP-OCRv5 mobile 文字识别模型（PaddleOCR，Apache-2.0），用 ONNX Runtime 在本机运行。
 *
 * 只做“识别”：输入一行文字的图片，输出文字。行的位置仍由 ML Kit 给出（见 [OcrEngine]）。
 * 这个模型针对中文训练，字典 1.8 万字，商户名里的汉字比 ML Kit 认得准。
 *
 * 这个类不依赖 Android（输入是 ARGB 像素数组），所以可以在 JVM 单元测试里直接跑真实模型。
 */
class PpRecognizer(model: ByteArray, dict: List<String>, threads: Int = 2) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    /** CTC 类别表：0 是空白符，之后是字典，最后是空格。 */
    private val chars: Array<String> = (listOf("") + dict + " ").toTypedArray()

    init {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(threads)
        session = env.createSession(model, options)
        inputName = session.inputNames.first()
    }

    /** 识别一行文字。[argb] 是按行排列的 ARGB 像素，长度为 width × height。 */
    fun recognize(argb: IntArray, width: Int, height: Int): PpResult {
        if (width < 2 || height < 2) return PpResult("", FloatArray(0))
        val tensorWidth = tensorWidth(width, height)
        val data = preprocess(argb, width, height, tensorWidth)
        val shape = longArrayOf(1, 3, HEIGHT.toLong(), tensorWidth.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { output ->
                val tensor = output[0] as OnnxTensor
                val dims = tensor.info.shape // [1, 时间步, 类别数]
                return decode(tensor.floatBuffer, dims[1].toInt(), dims[2].toInt())
            }
        }
    }

    /** CTC 贪心解码：每个时间步取概率最大的类别，合并连续重复，去掉空白符。 */
    private fun decode(probs: FloatBuffer, steps: Int, classes: Int): PpResult {
        val text = StringBuilder()
        val conf = ArrayList<Float>()
        val row = FloatArray(classes)
        var previous = 0
        for (t in 0 until steps) {
            probs.position(t * classes)
            probs.get(row)
            var best = 0
            for (c in 1 until classes) if (row[c] > row[best]) best = c
            if (best != 0 && best != previous) {
                val ch = chars.getOrNull(best) ?: ""
                // 字典里个别条目占两个 UTF-16 单元（如旗帜表情），置信度按字符单元补齐，保证与 text 等长
                text.append(ch)
                val p = probability(row, best)
                repeat(ch.length) { conf += p }
            }
            previous = best
        }
        return PpResult(text.toString(), conf.toFloatArray())
    }

    /** 模型输出已经是概率时直接取值；如果是未归一化的分数，做一次 softmax。 */
    private fun probability(row: FloatArray, best: Int): Float {
        val v = row[best]
        if (v in 0f..1.0001f) return v.coerceAtMost(1f)
        var sum = 0.0
        for (x in row) sum += kotlin.math.exp((x - v).toDouble())
        return (1.0 / sum).toFloat()
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val HEIGHT = 48
        private const val MIN_WIDTH = 320
        private const val MAX_WIDTH = 2400

        /** 与 PaddleOCR 一致：高度缩放到 48，宽度按比例；不足 320 的右侧补零。 */
        internal fun scaledWidth(width: Int, height: Int): Int =
            ceil(HEIGHT * width.toDouble() / height).toInt().coerceIn(8, MAX_WIDTH)

        internal fun tensorWidth(width: Int, height: Int): Int = maxOf(MIN_WIDTH, scaledWidth(width, height))

        /**
         * 双线性缩放 + 归一化到 [-1, 1]，输出 CHW、BGR 顺序（模型按 OpenCV 读图的通道顺序训练）。
         * 半透明像素按白底合成。
         */
        internal fun preprocess(argb: IntArray, width: Int, height: Int, tensorWidth: Int): FloatArray {
            val w = scaledWidth(width, height)
            val plane = HEIGHT * tensorWidth
            val out = FloatArray(3 * plane) // 补零区域保持 0
            val sx = width.toDouble() / w
            val sy = height.toDouble() / HEIGHT
            for (y in 0 until HEIGHT) {
                val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, (height - 1).toDouble())
                val y0 = floor(fy).toInt()
                val y1 = minOf(y0 + 1, height - 1)
                val wy = (fy - y0).toFloat()
                for (x in 0 until w) {
                    val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, (width - 1).toDouble())
                    val x0 = floor(fx).toInt()
                    val x1 = minOf(x0 + 1, width - 1)
                    val wx = (fx - x0).toFloat()
                    val p00 = argb[y0 * width + x0]
                    val p01 = argb[y0 * width + x1]
                    val p10 = argb[y1 * width + x0]
                    val p11 = argb[y1 * width + x1]
                    val i = y * tensorWidth + x
                    for (channel in 0..2) {
                        val shift = channel * 8 // 0 = B, 8 = G, 16 = R
                        val top = lerp(component(p00, shift), component(p01, shift), wx)
                        val bottom = lerp(component(p10, shift), component(p11, shift), wx)
                        out[channel * plane + i] = (lerp(top, bottom, wy) / 255f - 0.5f) / 0.5f
                    }
                }
            }
            return out
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        private fun component(pixel: Int, shift: Int): Float {
            val alpha = pixel ushr 24
            val value = (pixel ushr shift) and 0xFF
            return if (alpha == 255) value.toFloat() else (value * alpha + 255 * (255 - alpha)) / 255f
        }
    }
}

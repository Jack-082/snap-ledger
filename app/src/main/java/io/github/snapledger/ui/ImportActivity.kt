package io.github.snapledger.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.snapledger.data.LedgerDb
import io.github.snapledger.model.Money
import io.github.snapledger.model.Source
import io.github.snapledger.ocr.OcrEngine
import io.github.snapledger.parse.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 截图导入：接收系统分享（微信 / 支付宝 / 相册里点“分享 → 截图记账”），
 * 或主界面“导入截图”选中的图片。逐张识别 → 预填表单 → 用户确认后保存。
 */
class ImportActivity : ComponentActivity() {

    enum class Phase { Recognizing, Ready, Saved, Skipped }

    class Item(val uri: Uri) {
        var phase by mutableStateOf(Phase.Recognizing)
        val draft = TxnDraft()
        var hash: String? = null
        var rows by mutableStateOf<List<String>>(emptyList())
        var thumb by mutableStateOf<Bitmap?>(null)
        var warning by mutableStateOf<String?>(null)
        var showRaw by mutableStateOf(false)
        var confident by mutableStateOf(true)
    }

    private val items = mutableStateListOf<Item>()
    private val db by lazy { LedgerDb.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有收到图片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        items += uris.take(MAX_IMAGES).map(::Item)
        setContent { LedgerTheme { Surface(Modifier.fillMaxSize(), color = Palette.Tint) { Screen() } } }

        lifecycleScope.launch {
            OcrEngine(applicationContext).use { ocr -> items.forEach { process(ocr, it) } }
        }
    }

    private fun extractUris(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it } }
        return out.distinct()
    }

    private suspend fun process(ocr: OcrEngine, item: Item) {
        val d = item.draft
        try {
            item.thumb = withContext(Dispatchers.IO) { runCatching { thumbnail(item.uri) }.getOrNull() }
            item.hash = withContext(Dispatchers.IO) { ocr.sha256(item.uri) }
            val alreadyImported = item.hash?.let { h -> withContext(Dispatchers.IO) { db.hasDedup(dedupKey(h)) } } == true

            val rows = ocr.recognize(item.uri)
            item.rows = rows
            val parsed = ReceiptParser.parse(rows)
            parsed.amountCents?.let { d.amount = Money.format(it).replace(",", "") }
            d.direction = parsed.direction
            d.category = parsed.category
            d.merchant = parsed.merchant
            d.note = parsed.channel
            parsed.time?.let { d.time = TimeFmt.format(it) }
            item.confident = parsed.confident

            val cents = parsed.amountCents
            val millis = d.timeMillis
            item.warning = when {
                alreadyImported -> "这张截图已经导入过，再次保存会被忽略。"
                cents == null -> "没有识别出金额，请手动填写。"
                else -> {
                    val similar = if (millis == null) null else withContext(Dispatchers.IO) {
                        db.findSimilar(cents, parsed.direction, millis)
                    }
                    similar?.let {
                        "可能重复：已有 ${TimeFmt.format(it.timeMillis)} ${it.merchant.ifEmpty { it.category }} " +
                            Money.formatSigned(it.amountCents, it.direction)
                    }
                }
            }
        } catch (e: Exception) {
            item.warning = "识别失败（${e.message ?: e.javaClass.simpleName}），可以手动填写。"
            item.confident = false
        }
        item.phase = Phase.Ready
    }

    private fun thumbnail(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val sample = (w / 360).coerceAtLeast(1)
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun save(item: Item) {
        val txn = item.draft.toTxn(source = Source.SCREENSHOT, dedupKey = item.hash?.let(::dedupKey)) ?: return
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { db.insertTxn(txn) }
            if (id == -1L) {
                item.warning = "这张截图已经导入过，未重复保存。"
                item.phase = Phase.Skipped
            } else {
                item.phase = Phase.Saved
            }
            finishIfDone()
        }
    }

    private fun skip(item: Item) {
        item.phase = Phase.Skipped
        finishIfDone()
    }

    private fun finishIfDone() {
        if (items.any { it.phase == Phase.Ready || it.phase == Phase.Recognizing }) return
        val saved = items.count { it.phase == Phase.Saved }
        Toast.makeText(this, if (saved > 0) "已记账 $saved 笔" else "没有保存任何记录", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch { delay(300); finish() }
    }

    @Composable
    private fun Screen() {
        val ready = items.filter { it.phase == Phase.Ready && it.draft.error == null }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("截图记账", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Caption("共 ${items.size} 张 · 识别完成后请核对再保存")
                }
                if (ready.size >= 2) {
                    PrimaryButton("全部保存 (${ready.size})") { ready.forEach(::save) }
                }
            }
            items.forEachIndexed { i, item -> ItemCard(i, item) }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    @Composable
    private fun ItemCard(index: Int, item: Item) {
        val status = when (item.phase) {
            Phase.Recognizing -> Status.None
            Phase.Ready -> if (item.warning != null || !item.confident) Status.Warn else Status.Ok
            Phase.Saved -> Status.Ok
            Phase.Skipped -> Status.None
        }
        val title = when (item.phase) {
            Phase.Recognizing -> "截图 ${index + 1} · 识别中…"
            Phase.Ready -> "截图 ${index + 1}"
            Phase.Saved -> "截图 ${index + 1} · 已保存"
            Phase.Skipped -> "截图 ${index + 1} · 已跳过"
        }
        Section(title, status) {
            item.thumb?.let { bmp ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Tint), contentAlignment = Alignment.Center) {
                    Image(
                        bmp.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.heightIn(max = 180.dp), contentScale = ContentScale.Fit,
                    )
                }
            }
            when (item.phase) {
                Phase.Recognizing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Palette.Navy, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在本机识别文字…", fontSize = 13.sp, color = Palette.Navy)
                }
                Phase.Ready -> {
                    item.warning?.let { Caption(it, Palette.Red) }
                    if (item.warning == null && !item.confident) Caption("金额把握不大，请核对。", Palette.Salmon)
                    TxnEditor(item.draft)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("保存", Modifier.weight(1f), enabled = item.draft.error == null) { save(item) }
                        SecondaryButton("跳过", Modifier.weight(1f)) { skip(item) }
                    }
                }
                Phase.Saved -> StatRow(
                    item.draft.merchant.ifEmpty { item.draft.category },
                    item.draft.amountCents?.let { Money.formatSigned(it, item.draft.direction) } ?: "",
                )
                Phase.Skipped -> item.warning?.let { Caption(it) }
            }
            if (item.rows.isNotEmpty()) {
                ExpandableHeader("识别文字", "${item.rows.size} 行", item.showRaw) { item.showRaw = !item.showRaw }
                if (item.showRaw) {
                    Box(Modifier.fillMaxWidth().background(Palette.Tint, RoundedCornerShape(8.dp)).padding(12.dp)) {
                        Text(item.rows.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = Palette.Ink)
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_IMAGES = 20

        fun dedupKey(hash: String) = "img:$hash"

        /** 从应用内（相册选择器）启动导入，把读取权限一并转交。 */
        fun start(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val clip = ClipData.newRawUri("screenshots", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            context.startActivity(
                Intent(context, ImportActivity::class.java).apply {
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
    }
}

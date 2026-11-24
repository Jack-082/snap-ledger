package io.github.snapledger.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.snapledger.data.LedgerDb
import io.github.snapledger.model.Direction
import io.github.snapledger.model.Money
import io.github.snapledger.model.Source
import io.github.snapledger.ocr.OcrEngine
import io.github.snapledger.parse.Categorizer
import io.github.snapledger.parse.Layout
import io.github.snapledger.parse.OcrPage
import io.github.snapledger.parse.PageKind
import io.github.snapledger.parse.ParsedReceipt
import io.github.snapledger.parse.ReceiptParser
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 截图导入：
 * - 系统分享（相册 / 微信 / 支付宝里“分享 → 拾账”），可多张；
 * - 主界面“相册多选”；
 * - 快捷开关或主界面“最新截图”：直接取相册里最新的一张截图。
 *
 * 每张图在本机识别，单笔详情页生成一笔，账单列表截图生成多笔（可勾选），用户确认后保存。
 */
class ImportActivity : ComponentActivity() {

    enum class Phase { Recognizing, Ready, Done }

    /** 一张截图识别出的一笔。 */
    class Entry(
        val draft: TxnDraft,
        val dedupKey: String?,
        include: Boolean,
        warning: String?,
        expanded: Boolean,
        val confident: Boolean,
    ) {
        var include by mutableStateOf(include)
        var warning by mutableStateOf(warning)
        var expanded by mutableStateOf(expanded)
    }

    class Item(val uri: Uri) {
        var phase by mutableStateOf(Phase.Recognizing)
        var kind by mutableStateOf(PageKind.DETAIL)
        val entries = mutableStateListOf<Entry>()
        var page: OcrPage? = null
        var rows by mutableStateOf<List<String>>(emptyList())
        var thumb by mutableStateOf<Bitmap?>(null)
        var notice by mutableStateOf<String?>(null)
        var result by mutableStateOf<String?>(null)
        var showRaw by mutableStateOf(false)
    }

    private val items = mutableStateListOf<Item>()
    private var status by mutableStateOf<String?>(null)
    private val db by lazy { LedgerDb.get(this) }
    private var history: Map<Pair<Direction, String>, String> = emptyMap()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadLatest() }

    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
            if (uris.isEmpty()) finish() else start(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { LedgerTheme { Surface(Modifier.fillMaxSize(), color = Palette.Tint) { Screen() } } }

        if (intent.action == ACTION_LATEST) {
            status = "正在查找最新截图…"
            if (mediaPermissions().any { granted(it) }) loadLatest() else permissionLauncher.launch(mediaPermissions())
            return
        }
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            toast("没有收到图片")
            finish()
            return
        }
        start(uris)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun start(uris: List<Uri>) {
        val fresh = uris.take(MAX_IMAGES).map(::Item)
        items += fresh
        lifecycleScope.launch {
            history = withContext(Dispatchers.IO) { runCatching { db.categoryHistory() }.getOrDefault(emptyMap()) }
            OcrEngine(applicationContext).use { ocr -> fresh.forEach { process(ocr, it) } }
        }
    }

    // ---------------------------------------------------------------- 最新截图

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** 取相册里最新的截图；没有权限或找不到时改用系统相册选择器。 */
    private fun loadLatest() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { latestScreenshot() }.getOrNull() }
            if (found == null) {
                status = null
                toast("没有找到截图，请从相册选择")
                pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                return@launch
            }
            val (uri, addedSec) = found
            val minutes = (System.currentTimeMillis() / 1000 - addedSec) / 60
            status = if (minutes > 30) "最新截图是 ${formatAge(minutes)}前的，请确认是不是要记账的那张。" else null
            start(listOf(uri))
        }
    }

    private fun formatAge(minutes: Long) = when {
        minutes < 120 -> "$minutes 分钟"
        minutes < 48 * 60 -> "${minutes / 60} 小时"
        else -> "${minutes / 60 / 24} 天"
    }

    private fun latestScreenshot(): Pair<Uri, Long>? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val path = MediaStore.Images.Media.RELATIVE_PATH
        val name = MediaStore.Images.Media.DISPLAY_NAME
        // 各家 ROM 的截图目录：Pictures/Screenshots、DCIM/Screenshots、截屏、截图
        val selection = "$path LIKE ? OR $path LIKE ? OR $path LIKE ? OR $name LIKE ?"
        val args = arrayOf("%Screenshot%", "%截屏%", "%截图%", "Screenshot%")
        return contentResolver.query(collection, projection, selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            ?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) to c.getLong(1) else null }
    }

    /** 图片的拍摄 / 截图时间，截图里没有时间时用作默认值。 */
    private fun dateTaken(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

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

    // ---------------------------------------------------------------- 识别

    private suspend fun process(ocr: OcrEngine, item: Item) {
        val hash = withContext(Dispatchers.IO) { ocr.sha256(item.uri) }
        val taken = withContext(Dispatchers.IO) { dateTaken(item.uri) }
        try {
            item.thumb = withContext(Dispatchers.IO) { runCatching { thumbnail(item.uri) }.getOrNull() }
            val page = ocr.recognize(item.uri)
            item.page = page
            item.rows = Layout.rows(page).map { it.text }
            val result = ReceiptParser.parsePage(page)
            item.kind = result.kind
            if (result.kind == PageKind.LIST) {
                result.items.forEach { p -> item.entries += buildEntry(p, contentKey(p), null, list = true) }
                val on = item.entries.count { it.include }
                item.notice = "识别为账单列表，共 ${item.entries.size} 笔，已勾选 $on 笔" +
                    (if (on < item.entries.size) "（可能重复或交易未成功的未勾选）" else "") + "。点一笔可以展开修改。"
            } else {
                item.entries += buildEntry(result.items.first(), hash?.let(::dedupKey), taken, list = false)
            }
        } catch (e: Exception) {
            item.notice = "识别失败（${e.message ?: e.javaClass.simpleName}），可以手动填写。"
            val d = TxnDraft().apply { taken?.let { time = TimeFmt.format(it) } }
            item.entries += Entry(d, hash?.let(::dedupKey), include = true, warning = null, expanded = true, confident = false)
        }
        item.phase = Phase.Ready
    }

    /** 列表条目的去重键：同方向、同金额、同一分钟、同商户视为同一笔（重复导入同一张列表截图不会重复记账）。 */
    private fun contentKey(p: ParsedReceipt): String? {
        val t = p.time ?: return null
        val cents = p.amountCents ?: return null
        return "bill:${p.direction.name}:$cents:${KEY_TIME.format(t)}:${Categorizer.merchantKey(p.merchant)}"
    }

    private suspend fun buildEntry(p: ParsedReceipt, key: String?, fallbackMillis: Long?, list: Boolean): Entry {
        val d = TxnDraft()
        p.amountCents?.let { d.amount = Money.format(it).replace(",", "") }
        d.direction = p.direction
        // 记住商户分类：这个商户以前用过的分类优先于关键词猜测
        val learned = if (p.merchant.isBlank()) null else history[p.direction to Categorizer.merchantKey(p.merchant)]
        d.category = learned ?: p.category
        d.merchant = p.merchant
        d.account = p.account
        d.note = p.channel
        (p.time?.let { TimeFmt.format(it) } ?: fallbackMillis?.let { TimeFmt.format(it) })?.let { d.time = it }

        val cents = p.amountCents
        val millis = d.timeMillis
        val duplicate = withContext(Dispatchers.IO) {
            when {
                key != null && db.hasDedup(key) -> if (list) "已经记过这笔" else "这张截图已经导入过，再次保存会被忽略"
                cents != null && millis != null -> db.findSimilar(cents, p.direction, millis)?.let {
                    "可能重复：已有 ${TimeFmt.format(it.timeMillis)} ${it.merchant.ifEmpty { it.category }} " +
                        Money.formatSigned(it.amountCents, it.direction)
                }
                else -> null
            }
        }
        val warning = listOfNotNull(
            duplicate,
            p.warning,
            p.amountAlt?.let { "两次识别的金额不一致（另一个结果：$it），请核对" },
            if (cents == null) "没有识别出金额，请手动填写" else null,
        ).joinToString("；").ifEmpty { null }
        return Entry(
            draft = d,
            dedupKey = key,
            include = if (list) duplicate == null && p.warning == null else true,
            warning = warning,
            expanded = !list,
            confident = p.confident && cents != null,
        )
    }

    private fun thumbnail(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.setTargetSampleSize((info.size.width / 360).coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    // ---------------------------------------------------------------- 保存

    private fun chosen(item: Item) = item.entries.filter { it.include }

    private fun canSave(item: Item) =
        item.phase == Phase.Ready && chosen(item).let { c -> c.isNotEmpty() && c.all { it.draft.error == null } }

    private fun save(item: Item) {
        val txns = chosen(item).mapNotNull { it.draft.toTxn(source = Source.SCREENSHOT, dedupKey = it.dedupKey) }
        item.phase = Phase.Done
        lifecycleScope.launch {
            val inserted = withContext(Dispatchers.IO) { txns.count { db.insertTxn(it) != -1L } }
            val ignored = txns.size - inserted
            item.result = "已保存 $inserted 笔" + if (ignored > 0) "，$ignored 笔已存在未重复保存" else ""
            finishIfDone()
        }
    }

    private fun skip(item: Item) {
        item.phase = Phase.Done
        item.result = "已跳过"
        finishIfDone()
    }

    private fun finishIfDone() {
        if (items.any { it.phase != Phase.Done || it.result == null }) return
        val saved = items.any { it.result?.startsWith("已保存") == true }
        toast(if (saved) "已记账" else "没有保存任何记录")
        lifecycleScope.launch { delay(400); finish() }
    }

    /** 识别数据（文字 + 位置）导出为 JSON，反馈识别问题时附上，可直接做成测试用例。 */
    private fun copyDiagnostics(item: Item) {
        val page = item.page ?: return
        val lines = JSONArray()
        page.lines.forEachIndexed { i, l ->
            val o = JSONObject().put("t", l.text).put("b", JSONArray(listOf(l.left, l.top, l.right, l.bottom)))
            l.alt?.let { o.put("alt", it) }
            // 两个引擎各自的原始结果，方便判断是哪一个认错了
            page.readings?.getOrNull(i)?.let { r ->
                o.put("ml", r.ml)
                r.pp?.let { o.put("pp", it).put("c", Math.round(r.ppConf * 100) / 100.0) }
            }
            lines.put(o)
        }
        val json = JSONObject()
            .put("now", TimeFmt.format(System.currentTimeMillis()))
            .put("width", page.width)
            .put("height", page.height)
            .put("lines", lines)
            .toString(1)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("snapledger-ocr", json))
        toast("已复制识别数据（含截图中的文字，分享前请确认）")
    }

    // ---------------------------------------------------------------- 界面

    @Composable
    private fun Screen() {
        val saveAll = items.filter { canSave(it) }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("识别截图", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Caption(if (items.isEmpty()) "准备中…" else "共 ${items.size} 张 · 本机识别，请核对后保存")
                }
                if (saveAll.size >= 2) PrimaryButton("全部保存") { saveAll.forEach(::save) }
            }
            status?.let { Caption(it, Palette.Salmon) }
            items.forEachIndexed { i, item -> ItemCard(i, item) }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    @Composable
    private fun ItemCard(index: Int, item: Item) {
        val warn = item.entries.any { it.warning != null || !it.confident }
        val status = when (item.phase) {
            Phase.Recognizing -> Status.None
            Phase.Ready -> if (warn) Status.Warn else Status.Ok
            Phase.Done -> if (item.result?.startsWith("已保存") == true) Status.Ok else Status.None
        }
        val kindLabel = if (item.kind == PageKind.LIST) "账单列表" else "单笔"
        val title = when (item.phase) {
            Phase.Recognizing -> "截图 ${index + 1} · 识别中…"
            Phase.Ready -> "截图 ${index + 1} · $kindLabel"
            Phase.Done -> "截图 ${index + 1} · ${item.result ?: "保存中…"}"
        }
        Section(title, status) {
            if (item.phase != Phase.Done) item.thumb?.let { bmp ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Tint), contentAlignment = Alignment.Center) {
                    Image(bmp.asImageBitmap(), null, Modifier.heightIn(max = 180.dp), contentScale = ContentScale.Fit)
                }
            }
            when (item.phase) {
                Phase.Recognizing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Palette.Navy, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在本机识别文字…", fontSize = 13.sp, color = Palette.Navy)
                }
                Phase.Ready -> {
                    item.notice?.let { Caption(it) }
                    if (item.kind == PageKind.LIST) {
                        item.entries.forEach { ListEntry(it) }
                    } else {
                        item.entries.forEach { DetailEntry(it) }
                    }
                    val n = chosen(item).size
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(
                            if (item.kind == PageKind.LIST) "保存选中 ($n)" else "保存",
                            Modifier.weight(1f), enabled = canSave(item),
                        ) { save(item) }
                        SecondaryButton("跳过", Modifier.weight(1f)) { skip(item) }
                    }
                }
                Phase.Done -> Unit
            }
            if (item.rows.isNotEmpty() && item.phase != Phase.Done) {
                ExpandableHeader("识别文字", "${item.rows.size} 行", item.showRaw) { item.showRaw = !item.showRaw }
                if (item.showRaw) {
                    Box(Modifier.fillMaxWidth().background(Palette.Tint, RoundedCornerShape(8.dp)).padding(12.dp)) {
                        Text(item.rows.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = Palette.Ink)
                    }
                    SecondaryButton("复制识别数据（反馈识别问题用）", Modifier.fillMaxWidth()) { copyDiagnostics(item) }
                }
            }
        }
    }

    @Composable
    private fun DetailEntry(e: Entry) {
        e.warning?.let { Caption(it, Palette.Red) }
        if (e.warning == null && !e.confident) Caption("金额把握不大，请核对。", Palette.Salmon)
        TxnEditor(e.draft)
    }

    @Composable
    private fun ListEntry(e: Entry) {
        val d = e.draft
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { e.expanded = !e.expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = e.include, onCheckedChange = { e.include = it },
                    colors = CheckboxDefaults.colors(checkedColor = Palette.Navy),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        d.merchant.ifEmpty { d.category }, fontSize = 14.sp, color = if (e.include) Palette.Ink else Palette.Faint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${d.category} · ${d.time}", fontSize = 11.sp, color = Palette.Muted, maxLines = 1)
                }
                Text(
                    d.amountCents?.let { Money.formatSigned(it, d.direction) } ?: "?",
                    fontSize = 14.sp, style = Tabular, fontWeight = FontWeight.Medium,
                    color = when {
                        !e.include -> Palette.Faint
                        d.direction == Direction.INCOME -> Palette.Teal
                        else -> Palette.Ink
                    },
                )
            }
            e.warning?.let { Text(it, Modifier.padding(start = 48.dp), fontSize = 11.sp, color = Palette.Salmon) }
            if (e.expanded) Box(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 8.dp)) { TxnEditor(d) }
        }
    }

    companion object {
        private const val MAX_IMAGES = 20
        const val ACTION_LATEST = "io.github.snapledger.action.IMPORT_LATEST"
        private val KEY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

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

        /** 导入相册里最新的一张截图（快捷开关、主界面按钮）。 */
        fun latestIntent(context: Context): Intent =
            Intent(context, ImportActivity::class.java).setAction(ACTION_LATEST)
    }
}

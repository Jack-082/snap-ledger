package io.github.snapledger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 期刊插图风格（Nature / Cell / Science）：浅底白卡片、细边框、克制配色，
 * 主色取自 Nature Publishing Group 常用调色板。
 */
object Palette {
    val Navy = Color(0xFF3C5488)
    val Red = Color(0xFFE64B35)
    val Teal = Color(0xFF00A087)
    val Salmon = Color(0xFFF39B7F)
    val Ink = Color(0xFF1A1A1A)
    val Muted = Color(0xFF6B6B6B)
    val Faint = Color(0xFFB4B4B4)
    val Rule = Color(0xFFE2E2E2)
    val Tint = Color(0xFFF5F5F3)
    val Paper = Color.White
}

enum class Status(val color: Color) {
    Ok(Palette.Teal), Warn(Palette.Salmon), Error(Palette.Red), None(Palette.Faint)
}

internal val SmallShape = RoundedCornerShape(6.dp)
private val CardShape = RoundedCornerShape(12.dp)
internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val Scheme = lightColorScheme(
    primary = Palette.Navy, onPrimary = Color.White,
    secondary = Palette.Teal, onSecondary = Color.White,
    error = Palette.Red, onError = Color.White,
    background = Palette.Tint, onBackground = Palette.Ink,
    surface = Palette.Paper, onSurface = Palette.Ink,
    surfaceVariant = Palette.Tint, onSurfaceVariant = Palette.Muted,
    outline = Palette.Rule, outlineVariant = Palette.Rule,
)

@Composable
fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

private fun Modifier.card() = this
    .fillMaxWidth()
    .clip(CardShape)
    .background(Palette.Paper)
    .border(1.dp, Palette.Rule, CardShape)

/** 带边框的卡片分区：标题 + 右侧状态点。 */
@Composable
fun Section(title: String, status: Status? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            if (status != null) Dot(status.color, 8)
        }
        content()
    }
}

/** 卡片内的可折叠标题行：点击展开/收起。 */
@Composable
fun ExpandableHeader(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(SmallShape).background(Palette.Tint)
            .clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.Ink)
        Spacer(Modifier.width(8.dp))
        Text(summary, Modifier.weight(1f), fontSize = 12.sp, color = Palette.Muted)
        Text(if (expanded) "收起 ▴" else "展开 ▾", fontSize = 13.sp, color = Palette.Navy)
    }
}

@Composable
fun StatRow(label: String, value: String, status: Status? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.38f), fontSize = 13.sp, color = Palette.Muted)
        Row(Modifier.weight(0.62f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (status != null) {
                Dot(status.color)
                Spacer(Modifier.width(6.dp))
            }
            Text(value, fontSize = 13.sp, color = Palette.Ink, textAlign = TextAlign.End, style = Tabular)
        }
    }
}

@Composable
fun Dot(color: Color, diameter: Int = 7) {
    Box(Modifier.size(diameter.dp).background(color, CircleShape))
}

/** 图例式状态摘要：● 账号  ● 权限  ● 同步 */
@Composable
fun LegendItem(status: Status, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(status.color, 8)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = Palette.Muted)
    }
}

/** 图注风格的说明文字。 */
@Composable
fun Caption(text: String, color: Color = Palette.Muted) {
    Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = color)
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = SmallShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = SmallShape,
        border = BorderStroke(1.dp, if (enabled) Palette.Navy else Palette.Rule),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Navy),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { Text(text, fontSize = 14.sp, maxLines = 1) }
}

/** 分段选择器。 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp).clip(SmallShape).border(1.dp, Palette.Navy, SmallShape)) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Navy))
            val on = value == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (on) Palette.Navy else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 13.sp, style = Tabular,
                    color = if (on) Color.White else Palette.Navy,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.Muted, letterSpacing = 0.2.sp)
}

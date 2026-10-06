package com.oneus.lab.ui.charts

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneus.lab.ui.theme.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

// —— 与旧实验页兼容的语义常量(全部映射到新的 M3 深色配色)——
val Bg = Color(0xFF0A0D13)
val Surface1 = Color(0xFF131A22)
val SurfaceHigh = Color(0xFF1D242E)
val Line = Color(0xFF2A3646)
val TextPrimary = Color(0xFFE3EAF3)
val TextSecondary = Color(0xFFB3C2D0)
val TextMuted = Color(0xFF8A9AAD)

internal fun androidPaint(sizePx: Float, color: Color): Paint = Paint().apply {
    textSize = sizePx
    this.color = android.graphics.Color.argb(
        (color.alpha * 255).toInt(), (color.red * 255).toInt(),
        (color.green * 255).toInt(), (color.blue * 255).toInt()
    )
    isAntiAlias = true
}

// —— 排版(建立在 M3 Typography 之上)——
fun body13() = TextStyleAlias(14, 22, TextSecondary)
fun body14() = TextStyleAlias(15, 25, TextSecondary)
fun bold13() = TextStyleAlias(14, 21, TextPrimary, FontWeight.SemiBold)
fun title18() = TextStyleAlias(21, 29, TextPrimary, FontWeight.Bold)
fun mono10() = MonoSmall.copy(color = TextMuted)
fun mono11() = MonoSmall.copy(color = TextSecondary)
fun mono13() = MonoStyle.copy(color = TextPrimary)
fun bigNum() = MonoBig.copy(color = Accent)

private fun TextStyleAlias(
    size: Int, line: Int, color: Color, weight: FontWeight = FontWeight.Normal
) = androidx.compose.ui.text.TextStyle(
    fontSize = size.sp, lineHeight = line.sp, color = color, fontWeight = weight
)

/** 深度层级 */
enum class Depth(val label: String, val hint: String) {
    SHALLOW("浅", "人话,零公式"),
    MEDIUM("中", "机理解释,带数量级"),
    DEEP("深", "公式、推导与 ICD 出处")
}

/** 三层内容:同一件事的三种讲法 */
data class Layered(val shallow: String, val medium: String, val deep: String)

/** 术语开关:默认说人话,专家模式才显示标准术语 */
fun term(tech: Boolean, plain: String, formal: String): String = if (tech) formal else plain

/** 轻量点击修饰 */
fun Modifier.clickableBox(onClick: () -> Unit): Modifier = this.clickable { onClick() }

// ══════════════════════════════════════════
// 基础容器
// ══════════════════════════════════════════

/**
 * 卡片容器 —— M3 Surface。
 *
 * 这里必须用 **Column**,不能用 Box。
 * Card 的调用方普遍会往里塞多个并列子组件(标题、文本、图表、开关行……),
 * Box 会把它们全部定位到同一位置,于是文字压文字、图表盖文字。
 * Column 才是"从上到下依次排列"的语义。
 */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, accent: Color = TextMuted) {
    Row(
        modifier.padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(14.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp, lineHeight = 17.sp,
                color = accent, fontWeight = FontWeight.SemiBold
            )
        )
    }
}

// ══════════════════════════════════════════
// 深度切换
// ══════════════════════════════════════════

@Composable
fun DepthSwitch(depth: Depth, onChange: (Depth) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Depth.entries.forEach { d ->
            val on = d == depth
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onChange(d) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    d.label,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else TextSecondary,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                    )
                )
            }
        }
    }
}

@Composable
fun LayeredText(l: Layered, depth: Depth, modifier: Modifier = Modifier) {
    val t = when (depth) {
        Depth.SHALLOW -> l.shallow
        Depth.MEDIUM -> l.medium
        Depth.DEEP -> l.deep
    }
    Text(
        t,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = if (depth == Depth.SHALLOW) 15.sp else 13.sp,
            lineHeight = if (depth == Depth.SHALLOW) 26.sp else 21.sp,
            fontFamily = if (depth == Depth.SHALLOW) null else androidx.compose.ui.text.font.FontFamily.Monospace,
            color = if (depth == Depth.SHALLOW) TextSecondary else TextMuted
        ),
        modifier = modifier
    )
}

// ══════════════════════════════════════════
// 数据格 —— 关键修复:多格自动换行,不再硬塞一行
// ══════════════════════════════════════════

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                style = mono10(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

/** 一行 N 个数据格,窄屏自动折行 —— 这是文字重叠的主要来源,已根治 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatRow(tiles: List<StatData>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3
    ) {
        tiles.forEach { t ->
            StatTile(
                label = t.label,
                value = t.value,
                color = t.color,
                modifier = Modifier.weight(1f, fill = false).widthIn(min = 96.dp)
            )
        }
    }
}

data class StatData(val label: String, val value: String, val color: Color = TextPrimary)

/**
 * 常驻结果条 —— 沙盒类实验的关键组件。
 *
 * 这些页面有几十个滑块和开关,如果结果只显示在内容顶部,
 * 用户就得"往下调参 → 再往上看结果"来回滚动,体验极其糟糕。
 * 把关键数字钉在内容区之外、滚动区之上,它就永远在视野里。
 */
@Composable
fun LiveResultBar(
    tiles: List<StatData>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tiles.forEach { t ->
                Column(Modifier.weight(1f)) {
                    Text(
                        t.label, style = mono10(), maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t.value,
                        color = t.color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.ui.text.TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
    }
}

/**
 * 误差分档配色:<1 m 绿,<5 m 琥珀,再大偏红。
 *
 * 参数刻意声明成 **Double?** 而不是 Double:解不出位置时传 `null`,不能传 0.0。
 * 0.0 会被 min/max 当成一个"误差恰好为零"的真实数据点 ——
 * 于是室内、桥下这种一颗星都收不到的场景被染成**绿色**,
 * 第 09 关还会拿它判"达标"并弹出「过了。」。激励方向直接是反的。
 *
 * 传 Double 的旧调用点不受影响:Kotlin 会隐式装箱,不用逐个改。
 */
fun errorColor(m: Double?): Color = when {
    m == null || !m.isFinite() -> TextMuted      // 解不出 / 非有限值:中性灰,绝不判绿
    m <= 1.0 -> AccentGreen
    m <= 5.0 -> AccentWarm
    else -> Danger
}

// ══════════════════════════════════════════
// 下注卡
// ══════════════════════════════════════════

/** 判定结果 —— 独立成纯函数,方便写单元测试 */
enum class BetVerdict { CORRECT, WRONG, NEUTRAL }

/**
 * 判分。
 *
 * 曾经这里写的是 `i == 0`(把正确答案硬编码在第一项),
 * 结果四处下注卡全部判反 —— App 把正确答案标成错误,还配了一句
 * 与判定相矛盾的解释。这里改成由 [Bet.correctIndex] 显式声明。
 */
fun judge(picked: Int, correct: Int, revealed: Boolean): BetVerdict = when {
    !revealed -> BetVerdict.NEUTRAL
    picked == correct -> BetVerdict.CORRECT
    else -> BetVerdict.WRONG
}

/**
 * 一道下注题。
 *
 * 正确答案和选项写在同一个对象里,这样**不可能**出现
 * "选项顺序变了、正确下标忘了跟着改"这种错误。
 */
data class Bet(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val reveal: String
) {
    init {
        require(correctIndex in options.indices) {
            "正确下标 $correctIndex 超出选项范围 ${options.indices}"
        }
    }
}

@Composable
fun BetCard(
    bet: Bet,
    onPick: (Int) -> Unit,
    picked: Int?,
    modifier: Modifier = Modifier
) {
    val revealed = picked != null
    Card(modifier) {
        Text(bet.question, style = bold13())
        Spacer(Modifier.height(12.dp))
        bet.options.forEachIndexed { i, o ->
            val verdict = judge(picked ?: -1, bet.correctIndex, revealed)
            val v = if (i == picked) verdict else
                if (revealed && i == bet.correctIndex) BetVerdict.CORRECT else BetVerdict.NEUTRAL
            val bg = when (v) {
                BetVerdict.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
                BetVerdict.CORRECT -> MaterialTheme.colorScheme.tertiaryContainer
                BetVerdict.WRONG -> MaterialTheme.colorScheme.errorContainer
            }
            val borderCol = when (v) {
                BetVerdict.NEUTRAL -> Line
                BetVerdict.CORRECT -> AccentGreen
                BetVerdict.WRONG -> Danger
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .border(1.dp, borderCol, RoundedCornerShape(12.dp))
                    .clickable(enabled = !revealed) { onPick(i) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    o, style = body13(), modifier = Modifier.weight(1f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                if (v == BetVerdict.CORRECT && revealed) {
                    Spacer(Modifier.width(8.dp)); Text("✓", color = AccentGreen, style = bold13())
                }
                if (v == BetVerdict.WRONG) {
                    Spacer(Modifier.width(8.dp)); Text("✗", color = Danger, style = bold13())
                }
            }
        }
        if (revealed) {
            Spacer(Modifier.height(4.dp))
            val correctText = bet.options[bet.correctIndex]
            Text(
                if (picked == bet.correctIndex) "答对了。$correctText"
                else "正确答案:$correctText",
                style = MaterialTheme.typography.titleSmall,
                color = if (picked == bet.correctIndex) AccentGreen else Accent
            )
            Spacer(Modifier.height(4.dp))
            Text(bet.reveal, style = body13(), color = AccentWarm)
        }
    }
}

// ══════════════════════════════════════════
// 滑块
// ══════════════════════════════════════════

@Composable
fun ControlSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = body13(),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(10.dp))
            Text(valueText, style = mono13(), color = Accent, maxLines = 1)
        }
        Slider2(value, range, steps, onChange)
        if (hint != null) Text(hint, style = mono10())
    }
}

@Composable
fun Slider2(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null
) {
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        onValueChangeFinished = { onChangeFinished?.invoke() },
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    )
}

/**
 * 滑块 + "重算中"指示。
 *
 * 代价大的参数(比如要跑几百毫秒的滤波器对比)必须区分**拖动中**和**松手后**:
 * 拖动时只更新读数,松手才触发重算,并且重算跑在后台线程。
 * 否则每帧都重算,主线程直接被堵死 —— 表现就是滑动卡顿。
 */
@Composable
fun HeavySlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = body13(), modifier = Modifier.weight(1f).padding(end = 10.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(10.dp))
            Text(valueText, style = mono13(), color = Accent, maxLines = 1)
        }
        Slider2(value, range, 0, onChange) { onCommit(value) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hint != null) {
                Text(hint, style = mono10(), modifier = Modifier.weight(1f))
            }
            if (busy) {
                Text("重算中…", style = mono10(), color = AccentWarm)
            }
        }
    }
}

// ══════════════════════════════════════════
// 分段选择器 —— 关键修复:选项多时自动折行,不再挤成一团
// ══════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = 2
    ) {
        options.forEach { o ->
            val on = o == selected
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (on) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { onSelect(o) }
            ) {
                Text(
                    label(o),
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else TextSecondary,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp, lineHeight = 18.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = body13(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = mono10(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                if (checked) "开" else "关",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (checked) MaterialTheme.colorScheme.onPrimary else TextMuted
                ),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

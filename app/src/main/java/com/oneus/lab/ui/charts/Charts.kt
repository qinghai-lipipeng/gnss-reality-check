package com.oneus.lab.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.oneus.lab.sim.*
import com.oneus.lab.ui.theme.*
import kotlin.math.*
import kotlin.math.ln
import kotlin.random.Random

/**
 * 不会抛异常的夹取:把 [value] 夹到 `[lo, hi]`,区间本身非法时返回 [fallback]。
 *
 * 为什么需要它:`Float.coerceIn` / `Double.coerceIn` 的第一行是
 * `require(minimumValue <= maximumValue)`,**上下界翻转时直接抛
 * `IllegalArgumentException: Cannot coerce value to an empty range`**。
 * 而画布尺寸(`size.width` / `size.height` / 由它们推出来的 `w` / `h`)是布局给的,
 * 可以是 0,极端情况下还能被外部约束压成负数 —— 一旦拿它去当下界,这条
 * coerce 就从"夹一下"变成"必崩"。v8 第 01 节的闪退就是这么来的:
 * `coerceIn(inset + markHalfW, w - inset - markHalfW)` 在 `w = 0` 时
 * 等价于 `coerceIn(6.5.dp, -6.5.dp)`。
 *
 * 语义与 `coerceIn` 逐条对齐(Kotlin 的实现就是下面这个 when 链):
 * `value < lo → lo`;`value > hi → hi`;其余(含 NaN)原样返回。
 * 唯一的区别是 `hi < lo` 时走 [fallback] 而不是抛异常 —— 调用方在
 * fallback 不可画的地方自己 `return`,语义保持"不画"而不是"画错"。
 */
private fun coerceSafe(value: Float, lo: Float, hi: Float, fallback: Float): Float = when {
    hi < lo -> fallback
    value < lo -> lo
    value > hi -> hi
    else -> value
}

/**
 * 天空图:极坐标投影的可视卫星。
 * 圆心是天顶,边缘是地平线,半径 = 90° − 仰角。
 */
@Composable
fun SkyPlot(
    obs: List<Measurement>,
    solutionEcef: Vec3? = null,
    truthEcef: Vec3? = null,
    dops: DoubleArray? = null,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.aspectRatio(1f)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val c = center
        val r = min(size.width, size.height) / 2f - 10.dp.toPx()
        // 画布比 20 dp 还小 → r 是负半径。负半径交给底层原生绘制不同版本行为不一致
        // (有的抛、有的静默不画),这里直接不画。
        if (r <= 0f) return@Canvas

        // 仰角环
        for (el in intArrayOf(30, 60, 0)) {
            val rr = r * (90 - el) / 90.0f
            drawCircle(Line, rr, c, style = Stroke(1f))
            if (el > 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "$el°", c.x + 3f, c.y - rr + 12f, androidPaint(10f, TextMuted)
                )
            }
        }
        drawCircle(Line, r, c, style = Stroke(1.5f))

        // 方位刻度
        for (a in intArrayOf(0, 90, 180, 270)) {
            val rad = Math.toRadians((a - 90).toDouble())
            drawLine(
                Line, c,
                Offset(c.x + (r * cos(rad)).toFloat(), c.y + (r * sin(rad)).toFloat()),
                1f
            )
        }

        // 误差椭圆(水平 1σ,用 DOP 近似形状)
        if (solutionEcef != null && dops != null && !dops[0].isNaN()) {
            val scale = r / 40.0f           // 40 m 对应一个 HDOP 单位
            val h = (dops[0] * scale.toDouble()).toFloat()
            val v = (dops[1] * scale.toDouble() * 0.55).toFloat()
            drawOval(
                color = Accent.copy(alpha = 0.45f),
                topLeft = Offset(c.x - h, c.y - v),
                size = Size(h * 2, v * 2),
                style = Stroke(1.5f)
            )
        }

        // 卫星
        for (m in obs) {
            val rad = Math.toRadians((m.azDeg - 90.0))
            val rr = r * (90.0 - m.elDeg.coerceIn(0.0, 90.0)).toFloat() / 90.0f
            val p = Offset(c.x + (rr * cos(rad)).toFloat(), c.y + (rr * sin(rad)).toFloat())
            val col = systemColor(m.system)
            // C/N0 越高点越大
            val dotR = 2.2f + (m.cn0 - 20.0).coerceIn(0.0, 30.0).toFloat() * 0.09f
            drawCircle(col, dotR, p)
            drawCircle(col.copy(alpha = 0.22f), dotR * 2.4f, p)
        }
    }
}

/**
 * 水平条形图。
 *
 * 关键修复:原来 label / note / value 三者挤在同一行,窄屏上 note 和 value
 * 会把 label 压扁甚至溢出。现在 label 独占一行(可换行、末尾省略号),
 * 数值右对齐,note 另起一行 —— 任何屏宽都不会重叠。
 */
@Composable
fun BarList(
    rows: List<Row>,
    unit: String = "m",
    modifier: Modifier = Modifier,
    valueFormat: (Double) -> String = { LabEngine.fmtNum(it) }
) {
    val maxV = rows.maxOfOrNull { if (it.na) 0.0 else it.value } ?: 1.0
    // scale 恒 > 0:maxV > 0 时取 maxV,否则(含 0 / 负数 / NaN,NaN 的比较恒为 false)
    // 一律回落到 1.0,所以下面 r.value / scale 不会除零。
    val scale = if (maxV > 0) maxV else 1.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { r ->
            // 边界是编译期常量 0.0 < 1.0,区间恒非空
            val frac = if (r.na) 0f else (r.value / scale).coerceIn(0.0, 1.0).toFloat()
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        r.label,
                        color = if (r.highlight) TextPrimary else TextSecondary,
                        style = if (r.highlight) bold13() else body13(),
                        modifier = Modifier.weight(1f).padding(end = 10.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (r.na) "—" else "${valueFormat(r.value)} $unit",
                        color = if (r.na) TextMuted else if (r.highlight) Accent else TextSecondary,
                        style = mono13(),
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(6.dp))
                val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                    if (frac > 0f) {
                        drawRoundRect(
                            color = if (r.highlight) Accent else Accent.copy(alpha = 0.55f),
                            size = Size(size.width * frac, size.height),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    } else if (r.na) {
                        // 不可用:画一截斜纹占位,让人一眼看出"这里没有数",而不是"误差为零"
                        val seg = 10.dp.toPx()
                        var x = 0f
                        while (x < size.width) {
                            drawLine(
                                color = TextMuted.copy(alpha = 0.35f),
                                start = Offset(x, size.height),
                                end = Offset(x + seg * 0.6f, 0f),
                                strokeWidth = 2f
                            )
                            x += seg
                        }
                    }
                }
                if (r.note.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(r.note, style = mono10(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * 收敛曲线:多条滤波轨迹叠在一起。
 * 纵轴对数刻度 —— 否则 0.1 m 和 15 m 画在一张图上什么都看不出来。
 */
@Composable
fun ConvergenceChart(
    traces: List<FilterTrace>,
    modifier: Modifier = Modifier,
    logScale: Boolean = true
) {
    val all = traces.flatMap { it.points }.filter { !it.isNaN() }
    if (all.isEmpty()) { Box(modifier); return }
    val minV = max(all.min(), 1e-3)
    val maxV = all.max()
    val lo = if (logScale) log10(minV * 0.7) else 0.0
    val hi = if (logScale) log10(maxV * 1.4) else maxV * 1.1
    val span = max(hi - lo, 1e-6)

    Canvas(modifier = modifier.fillMaxWidth()) {
        val padL = 34.dp.toPx(); val padB = 16.dp.toPx()
        val padT = 6.dp.toPx(); val padR = 6.dp.toPx()
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        // 画布比 padding 还小 → 绘图区宽/高为负,画出来的东西全是噪声
        if (w <= 0f || h <= 0f) return@Canvas
        val nE = max(traces.firstOrNull()?.points?.size ?: 1, 2)

        // 参考线
        for (g in listOf(0.1, 1.0, 10.0, 100.0)) {
            if (g < minV * 0.7 || g > maxV * 1.4) continue
            val y = padT + h * (1 - ((log10(g) - lo) / span)).toFloat()
            drawLine(Line, Offset(padL, y), Offset(padL + w, y), 1f)
            drawContext.canvas.nativeCanvas.drawText(
                if (g >= 1) "${g.toInt()}" else "0.1", 2f, y + 3.5f, androidPaint(9f, TextMuted)
            )
        }

        traces.forEachIndexed { ti, t ->
            val col = filterColors[ti % filterColors.size]
            val path = Path()
            var started = false
            t.points.forEachIndexed { i, v ->
                if (v.isNaN()) return@forEachIndexed
                val x = padL + w * (i.toFloat() / (nE - 1))
                val vv = if (logScale) log10(max(v, 1e-3)) else v
                val y = padT + h * (1 - ((vv - lo) / span)).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(path, col, style = Stroke(2f))
        }
    }
}

// ══════════════════════════════════════════
// 误差预算:贡献判据 + 三档文案
// ══════════════════════════════════════════

/** 关掉这一项,总误差变化小于它 → 判为"这一项不进水平误差" */
private const val BUDGET_FLAT = 0.05     // m
/** 变化小于它但不为零 → 弱项,走中间档文案 */
private const val BUDGET_WEAK = 0.30     // m

private enum class BudgetKind { FLAT, WEAK, REAL }

/**
 * 这一项到底有多重要:关掉它,总误差实际摆动了多少(|Δ|,不看方向)。
 *
 * 刻意不用 [BudgetLine.marginal]:它是方差合成量 sqrt(allOn² − withoutIt²),
 * 一旦"关掉这项反而更差"(电离层不改正 / 关掉对流层模型 / 理想去多路径),
 * allOn² − withoutIt² < 0 会被 clamp 成 0 —— 预算里最大的两项反而画成
 * 0 长度的条。改用摆动量后,"没有贡献"(Δ≈0)和"影响最大但方向相反"
 * 才在条形上分得开,方向信息留给文案去说。
 */
private fun budgetImpact(l: BudgetLine): Double = abs(l.allOn - l.withoutIt)

private fun budgetKind(impact: Double): BudgetKind = when {
    impact < BUDGET_FLAT -> BudgetKind.FLAT
    impact < BUDGET_WEAK -> BudgetKind.WEAK
    else -> BudgetKind.REAL
}

/** 摆动量文案用:fmtNum(0.0) 会写成 "0.0000",这里直接给 0。 */
private fun fmtImpact(v: Double): String = if (v < 0.005) "0" else LabEngine.fmtNum(v)

/** 接收机钟差是共模量:关掉它,解算器用钟差未知量把它整个吸收,水平误差一步不动。 */
private fun isCommonMode(label: String) = label.contains("接收机钟差")

/** 斜纹:沿用 BarList 里 na 占位那套视觉语言 —— "这里没有量",不是"量很小" */
private fun DrawScope.drawHatch(
    color: Color,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    seg: Float,
    stroke: Float = 2f
) {
    val rise = bottom - top
    var x = left - rise          // 从左界外起笔,左端才不会被斜纹挖空
    while (x < right) {
        drawLine(color, Offset(x, bottom), Offset(x + rise, top), stroke)
        x += seg
    }
}

/** 「0 m」徽标:给不贡献误差的行一个明确记号,而不是一根 0 长度的普通条 */
private fun DrawScope.drawZeroChip(color: Color) {
    val text = "0 m"
    val tp = 9.dp.toPx()
    val p = androidPaint(tp, color)
    val chipW = p.measureText(text) + 12f
    val chipH = min(size.height - 4f, 10.dp.toPx()).coerceAtLeast(0f)   // 画布太矮时不画负高
    val cy = size.height / 2f
    drawRoundRect(
        color = color.copy(alpha = 0.20f),
        topLeft = Offset(4f, cy - chipH / 2f),
        size = Size(chipW, chipH),
        cornerRadius = CornerRadius(3f, 3f)
    )
    drawContext.canvas.nativeCanvas.drawText(text, 10f, cy + tp * 0.35f, p)
}

/**
 * 误差预算瀑布图:每项的边际贡献。
 *
 * 修复记录(2026-10):原来每一行都固定输出「全部误差 X m · 关掉这项降到 Y m」。
 * 接收机钟差是共模量,关掉它误差一步不动,于是渲染出
 * 「全部误差 8.88 m · 关掉这项降到 8.88 m」—— 同一个数字出现两次,
 * 读起来像渲染坏了,实际上这是要教的核心物理。现在按 |Δ| 分三档:
 *  - FLAT(< 0.05 m):「关掉它误差没变 —— 这一项是共模量,被钟差未知量吸收了」,
 *    条形画成斜纹 + 虚边 + "0 m" 徽标,明确表达"没有贡献";
 *  - WEAK(0.05~0.30 m):中间档文案,条形降低不透明度并加端帽;
 *  - REAL:保留「全部误差 X m · 关掉这项降到 Y m」。
 *
 * 顺带修掉同一类文案错误:关掉某项让误差"变差"时(电离层不改正 / 关掉对流层模型),
 * 旧文案仍写"降到",数字却变大了;现在按方向说「降到 / 反而涨到」。
 */
@Composable
fun WaterfallChart(
    lines: List<BudgetLine>,
    modifier: Modifier = Modifier
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val maxV = lines.maxOfOrNull { budgetImpact(it) } ?: 1.0
    // 同 BarList:scale 恒 > 0(NaN 的 `> 0` 为 false → 回落 1.0),除法安全
    val scale = if (maxV > 0) maxV else 1.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEachIndexed { i, l ->
            val impact = budgetImpact(l)
            val kind = budgetKind(impact)
            // 边界是编译期常量 0.0 < 1.0,区间恒非空
            val frac = (impact / scale).coerceIn(0.0, 1.0).toFloat()
            Column {
                Row {
                    Text(
                        l.label,
                        color = if (kind == BudgetKind.FLAT) TextMuted else TextSecondary,
                        style = body13(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(l.shortNote, color = TextMuted, style = mono11())
                }
                Spacer(Modifier.height(4.dp))
                val col = when {
                    i < 2 -> Danger
                    i < 4 -> Warn
                    else -> Accent.copy(alpha = 0.7f)
                }
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    val r = CornerRadius(3f, 3f)
                    drawRoundRect(color = track, cornerRadius = r)
                    when (kind) {
                        BudgetKind.FLAT -> {
                            // 不画 0 长度的条:斜纹 + 虚边 + 徽标,一眼是"没有贡献"
                            drawHatch(
                                TextMuted.copy(alpha = 0.22f),
                                0f, 0f, size.width, size.height, seg = 8f, stroke = 1.5f
                            )
                            drawRoundRect(color = col.copy(alpha = 0.5f), cornerRadius = r, style = Stroke(1f))
                            drawZeroChip(col)
                        }
                        BudgetKind.WEAK -> {
                            if (frac > 0f) {
                                val bw = size.width * frac
                                drawRoundRect(
                                    color = col.copy(alpha = 0.40f),
                                    size = Size(bw, size.height),
                                    cornerRadius = r
                                )
                                // 端帽:把"就这么长"标出来
                                drawRoundRect(
                                    color = col,
                                    size = Size(min(bw, 3f), size.height),
                                    cornerRadius = r
                                )
                            }
                        }
                        BudgetKind.REAL -> if (frac > 0f) {
                            drawRoundRect(
                                color = col.copy(alpha = 0.85f),
                                size = Size(size.width * frac, size.height),
                                cornerRadius = r
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                when (kind) {
                    BudgetKind.FLAT -> {
                        Text(
                            "边际贡献 ${fmtImpact(impact)} m · 不进水平误差",
                            color = TextMuted, style = mono10()
                        )
                        Text(
                            if (isCommonMode(l.label))
                                "关掉它误差没变 —— 这一项是共模量,被钟差未知量吸收了"
                            else
                                "关掉它误差没变 —— 这一项对水平误差没有可观贡献",
                            color = TextMuted.copy(alpha = 0.70f), style = mono10()
                        )
                    }
                    BudgetKind.WEAK -> {
                        Text(
                            "只动 ${fmtImpact(impact)} m · 弱项",
                            color = TextMuted, style = mono10()
                        )
                        Text(
                            "关掉这项 ${LabEngine.fmtNum(l.allOn)} m → ${LabEngine.fmtNum(l.withoutIt)} m," +
                                    "只动 ${fmtImpact(impact)} m,基本淹没在总量里",
                            color = TextMuted.copy(alpha = 0.70f), style = mono10()
                        )
                    }
                    BudgetKind.REAL -> Text(
                        "全部误差 ${LabEngine.fmtNum(l.allOn)} m · 关掉这项" +
                                (if (l.withoutIt < l.allOn) "降到" else "反而涨到") +
                                " ${LabEngine.fmtNum(l.withoutIt)} m",
                        color = TextMuted, style = mono10()
                    )
                }
            }
        }
    }
}

/**
 * 光速尺:把时间刻度画成一把尺。
 * 1 μs = 300 m 是全 App 的认知锚点,满量程 600 m = 2 μs。
 *
 * 修复记录(2026-10):当前值原本画成一条 y 从 0 到 h 的**满高竖线**。
 * 20 ns × 0.3 ≈ 6 m ≈ 满量程 1%,那条线几乎贴死左边缘,又和上方滑块
 * 上下连成一片,用户以为渲染坏了。现在:
 *  - 尺身 = 凹槽 + 亮面 + 描边三层,主次刻度用 TextMuted,一眼是一把尺子
 *    (旧的 `0xFF1D242E` 和 `Line` 在 `0x0A0D13` 底上几乎看不见);
 *  - 刻度改成 0 / 150 / 300 / 450 / 600 m 等距,标签分别左 / 中 / 右对齐,
 *    彼此不重叠,也不会压到指标上;
 *  - 指标是**贴在尺身上的三角标**:高 8 dp(尺身 16 dp 的 50%,≤ 60%),
 *    底边浮在尺身上方、尖端扎进尺身内部,再补一段 60% 高的尺内短竖线,
 *    视觉上"长在尺子上";
 *  - 尺身左右各留 2 dp 内边距,指示位置再向内兜 4.5 dp,任何值都不贴边;
 *  - ns = 0 时不画任何竖线,只在尺身左端内侧点一个暗点;
 *  - 超过 600 m 量程时:指示标钉右端、整体换色、填充打斜纹(读作"尺子不够长"),
 *    末位刻度变成 "600 m+" —— 不会静默画到一个错误位置。
 *
 * 修复记录(2026-10-05,崩):v8 给指示位置加了 `coerceIn(inset + markHalfW, w - inset - markHalfW)`,
 * 而 Canvas 没有内在尺寸、调用点没传宽度修饰符,画布宽 0 → 区间变成 `6.5dp..-6.5dp`
 * → Kotlin `coerceIn` 抛 "Cannot coerce value to an empty range" → 每次打开第 01 节必崩。
 * 这和 v7 的"滑条下方一条莫名其妙的竖线"是同一件事(v7 那根满高竖线宽 0 时贴在左边缘,
 * 是个静默瑕疵),v8 改三角标 + 加上界时从瑕疵变成了硬崩。现在双管齐下:
 * 画布显式 `fillMaxWidth()`(有宽度了),指示标走 `coerceSafe`(极端窄也不抛)。
 */
@Composable
fun ClockRuler(
    ns: Double,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val grooveColor = MaterialTheme.colorScheme.surfaceContainerLow
    // fillMaxWidth 是根因修复:Canvas 没有内在尺寸,调用点(Experiments1.kt:277)
    // 只传了 `ClockRuler(satNs.toDouble())`、没给任何宽度修饰符,在 Card 的 Column 里
    // 被测量成 minWidth = 0 → 宽度 0 → 下面的指示标区间翻转 → 硬崩。
    // 放在 modifier 之后:调用点若将来显式传了固定宽度,那个宽度仍然优先。
    Canvas(modifier = modifier.fillMaxWidth().height(44.dp)) {
        val w = size.width
        val h = size.height
        // 防御:先确认画布有正尺寸,再拿它推导任何区间边界
        if (w <= 0f || h <= 0f) return@Canvas
        val meters = if (ns.isFinite()) ns * 0.299792458 else 0.0   // c·t
        val maxM = 600.0
        val over = meters > maxM
        val hasValue = meters > 0.0

        // 版面全部从底边往上算,改画布高度也不会挤掉刻度
        val inset = 2.dp.toPx()                    // 尺身左右内边距
        val bodyH = 16.dp.toPx()
        val bodyTop = h - bodyH - 4.dp.toPx()
        val bodyCy = bodyTop + bodyH / 2f
        val tickBottom = bodyTop - 6.dp.toPx()
        val tickTop = tickBottom - 5.dp.toPx()
        val labelBase = tickTop - 2.5.dp.toPx()
        val markHalfW = 4.5.dp.toPx()
        val markH = min(bodyH * 0.5f, 8.dp.toPx()) // ≤ 尺身高度 60%
        val indCol = if (over) Warn else Accent

        // 尺身:凹槽 → 亮面 → 描边,三层才立得住"这是一把尺子"
        val radius = CornerRadius(bodyH / 2f, bodyH / 2f)
        val grooveR = CornerRadius(bodyH / 2f + 2f, bodyH / 2f + 2f)
        drawRoundRect(grooveColor, Offset(0f, bodyTop - 2f), Size(w, bodyH + 4f), grooveR)
        drawRoundRect(trackColor, Offset(0f, bodyTop), Size(w, bodyH), radius)
        drawRoundRect(
            TextMuted.copy(alpha = 0.32f), Offset(0f, bodyTop), Size(w, bodyH), radius, Stroke(1f)
        )

        // 指示位置:两端各留 inset + markHalfW,永远不会贴到画布边缘。
        // 下界 6.5 dp / 上界 w − 6.5 dp,只要 w < 13 dp 区间就翻转 —— 那是 v8 的闪退点,
        // 所以这里走 coerceSafe 而不是 coerceIn:区间翻转时退回画布中点,绝不抛异常。
        val rawX = inset + (w - 2f * inset) * (meters / maxM).coerceIn(0.0, 1.0).toFloat()
        val markX = coerceSafe(rawX, inset + markHalfW, w - inset - markHalfW, fallback = w / 2f)
        if (hasValue) {
            drawRoundRect(
                indCol.copy(alpha = 0.90f),
                Offset(inset, bodyTop),
                Size((markX - inset).coerceAtLeast(0f), bodyH),
                radius
            )
            if (over) {
                // 溢出:填充打斜纹,读作"尺子不够长,这里被截断了"
                clipRect(0f, bodyTop, markX, bodyTop + bodyH) {
                    drawHatch(
                        grooveColor.copy(alpha = 0.65f), 0f, 0f, size.width, size.height, seg = 7f
                    )
                }
            }
        }

        // 刻度:等距主刻度 + 半数次刻度
        fun tickX(m: Double) = inset + (w - 2f * inset) * (m / maxM).toFloat()
        val pLabel = androidPaint(9.dp.toPx(), TextMuted)
        val pWarn = androidPaint(9.dp.toPx(), Warn)
        val majors = listOf(0.0, 150.0, 300.0, 450.0, 600.0)
        val lastIdx = majors.lastIndex
        majors.forEachIndexed { i, m ->
            val x = tickX(m)
            drawLine(TextMuted, Offset(x, tickTop), Offset(x, tickBottom), 1.2f)
            val isLast = i == lastIdx
            val label = if (isLast) (if (over) "600 m+" else "600 m") else LabEngine.fmtNum(m)
            val p = if (isLast && over) pWarn else pLabel
            val tw = p.measureText(label)
            val tx = when (i) {
                0 -> x                      // 首个左对齐,不出内边距
                lastIdx -> x - tw            // 末个右对齐,不会被画布裁掉
                else -> x - tw / 2f          // 中间居中
            }
            drawContext.canvas.nativeCanvas.drawText(label, tx, labelBase, p)
        }
        for (m in listOf(50.0, 100.0, 200.0, 250.0, 350.0, 400.0, 500.0, 550.0)) {
            val x = tickX(m)
            drawLine(TextMuted.copy(alpha = 0.45f), Offset(x, tickTop + 2f), Offset(x, tickBottom), 1f)
        }

        // 当前值:贴在尺身上的三角标(不是满高竖线)
        if (hasValue) {
            val apexY = bodyTop + markH * 0.45f     // 尖端扎进尺身内部
            val baseY = apexY - markH               // 底边浮在尺身上方
            val tri = Path().apply {
                moveTo(markX, apexY)
                lineTo(markX - markHalfW, baseY)
                lineTo(markX + markHalfW, baseY)
                close()
            }
            drawPath(tri, indCol)
            // 60% 高、只在尺身内部的短竖线:把三角标和尺身焊在一起
            drawLine(
                indCol,
                Offset(markX, bodyCy - bodyH * 0.3f),
                Offset(markX, bodyCy + bodyH * 0.3f), 2f
            )
        } else {
            // 值为 0:尺身左端内侧一个暗点,绝不画贴边竖线
            drawCircle(TextMuted.copy(alpha = 0.50f), 2.5.dp.toPx(), Offset(inset + 3.dp.toPx(), bodyCy))
        }
    }
}

/** 卫星残差柱状图:逐星看残差(深层的诊断视图) */
@Composable
fun ResidualBars(
    labels: List<String>,
    values: List<Double>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    val maxAbs = max(values.maxOfOrNull { abs(it) } ?: 1.0, 0.1)
    Canvas(modifier = modifier.height(90.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val w = size.width / n
        val midY = size.height / 2
        drawLine(Line, Offset(0f, midY), Offset(size.width, midY), 1f)
        values.forEachIndexed { i, v ->
            val h = (midY * (abs(v) / maxAbs)).toFloat()
            val x = w * i + w * 0.15f
            val bw = w * 0.7f
            drawRect(
                color = colors.getOrElse(i) { Accent },
                topLeft = Offset(x, if (v >= 0) midY - h else midY),
                size = Size(bw, h)
            )
        }
    }
}

/**
 * DOP 椭球的几何示意。
 *
 * 修复记录(2026-10-05):半轴原来写成 `coerceIn(4f, size.width / 2 - 6)`,下界是常量 4f、
 * 上界由画布尺寸推导 —— 宽度 < 20 px 时区间翻转,`coerceIn` 抛
 * "Cannot coerce value to an empty range"。和 ClockRuler 是同一族定时炸弹
 * (这个组件目前还没有调用点,所以还没炸)。现在先判正尺寸,再走 coerceSafe。
 */
@Composable
fun DopDiagram(
    hdop: Double,
    vdop: Double,
    modifier: Modifier = Modifier
) {
    // 同 ClockRuler:Canvas 无内在尺寸,不显式 fillMaxWidth 就是 0 宽
    Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val c = Offset(size.width / 2, size.height / 2)
        val s = 2.2f
        // 上界 = 尺寸 − 留边,可能小于常量下界 4f;翻转时退化成 0(退化成点,等于不画)
        val a = coerceSafe((hdop * s).toFloat(), 4f, size.width / 2 - 6, fallback = 0f)
        val b = coerceSafe((vdop * s).toFloat(), 4f, size.height / 2 - 6, fallback = 0f)
        if (a <= 0f || b <= 0f) return@Canvas
        drawOval(
            Accent.copy(alpha = 0.2f),
            Offset(c.x - a, c.y - b), Size(a * 2, b * 2)
        )
        drawOval(
            Accent, Offset(c.x - a, c.y - b), Size(a * 2, b * 2),
            style = Stroke(2f)
        )
        drawLine(Line, Offset(c.x, 0f), Offset(c.x, size.height), 1f)
        drawLine(Line, Offset(0f, c.y), Offset(size.width, c.y), 1f)
        drawCircle(Danger, 4f, c)
    }
}

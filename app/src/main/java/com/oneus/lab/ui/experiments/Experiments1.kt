package com.oneus.lab.ui.experiments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oneus.lab.sim.*
import com.oneus.lab.ui.charts.*
import com.oneus.lab.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** 每个实验页的通用外壳 */
@Composable
fun ExpScaffold(
    index: String,
    title: String,
    depth: Depth,
    onDepth: (Depth) -> Unit,
    /** 常驻区:钉在滚动区之外,调参时始终可见。不用时留空。 */
    sticky: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(index, style = mono13(), color = Accent)
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = title18(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            DepthSwitch(depth, onDepth)
            Spacer(Modifier.height(4.dp))
            Text(depth.hint, style = mono10())
        }
        if (sticky != null) {
            Spacer(Modifier.height(10.dp))
            sticky()
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 48.dp)
        ) { content() }
    }
}

/** 结论条:每层结尾一句话,可复述 */
@Composable
fun Takeaway(text: String, color: Color = Accent) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .heightIn(min = 20.dp)
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Text(text, style = body14(), color = TextPrimary)
        }
    }
}

// ══════════════════════════════════════════
// 实验 0 · 开场:量 / 算 / 猜
// ══════════════════════════════════════════

@Composable
fun Exp00_Opening(depth: Depth, onDepth: (Depth) -> Unit, onGo: (Int) -> Unit) {
    ExpScaffold("00", "卫星只负责「量」", depth, onDepth) {
        LayeredText(
            Layered(
                shallow = "定位不是「算」出来的,是「量」出来的。\n\n" +
                        "就像量身高:拿尺子量,一米六就是一米六。你的数学老师再聪明,也不能把一米六算成一米五五。\n\n" +
                        "卫星干的是同一件事——它报「我在哪、现在几点」,你的芯片量出「我离每颗星多远」。四颗一交,位置就出来了。",
                medium = "整套定位可以拆成三层,每层各干各的活:\n\n" +
                        "量:卫星广播自己的位置和时间,接收机测出伪距。精度上限由这一层决定:授时精度、大气延迟、卫星原子钟、星座几何。\n\n" +
                        "猜:手机拿卫星结果加上 WiFi、基站、地图、惯导去推断真实位置。它决定城里会不会漂。\n\n" +
                        "算:把量到的伪距拼成坐标。最小二乘,几十年前就写进课本,谁都一样。",
                deep = "单点定位的伪距方程(GPS ICD-200 §20.3):\n\n" +
                        "PR = ρ + c·δt_r + Δt_rel + c·δt_sat + I + T + ε_mp + ε_n\n\n" +
                        "未知量只有 4 个:x, y, z, c·δt_r。对 4 颗以上卫星线性化后做加权最小二乘。\n\n" +
                        "GPS / 北斗 / 伽利略 / 格洛纳斯共用这一个方程,没有任何一家有「专有的定位公式」。差别在广播的轨道参数、频点设计和信号结构上,不在解算上。"
            ),
            depth
        )
        Spacer(Modifier.height(16.dp))

        // 三层图示
        ThreeLayerDiagram()
        Spacer(Modifier.height(16.dp))

        SectionTitle("这一层会反复用到", accent = Accent)
        Spacer(Modifier.height(8.dp))
        Card {
            LayerRow("量", "卫星", Accent, "北斗 / GPS / 伽利略 / 格洛纳斯", "决定精度天花板")
            LayerRow("猜", "手机", AccentWarm, "WiFi · 基站 · 地图 · 惯导", "决定城里漂不漂")
            LayerRow("算", "解算器", AccentGreen, "最小二乘 / 卡尔曼 / 粒子滤波", "边际收益很小")
        }
        Spacer(Modifier.height(14.dp))

        if (depth != Depth.SHALLOW) {
            SectionTitle("怎么走", accent = AccentWarm)
            Spacer(Modifier.height(8.dp))
            Card {
                Text(
                    "一共 ${11} 小节,底部常驻上一节 / 下一节,顺着做下来大约 30 分钟。\n" +
                            "右上角「目录」可以随时跳到任意一节。系统返回手势会回到上一级,不会退出应用。",
                    style = body13()
                )
                Spacer(Modifier.height(12.dp))
                SmallBtn("开始 →", Accent) { onGo(1) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Takeaway("卫星只负责「量」,手机负责「猜」,算法只负责把量出来的东西拼整齐。")
    }
}

@Composable
private fun ThreeLayerDiagram() {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            LayerBlock("量", "我离每颗星多远", Accent, 1.0f)
            LayerBlock("算", "把这些距离拼成坐标", AccentGreen, 0.72f)
            LayerBlock("猜", "补上卫星没覆盖的部分", AccentWarm, 0.55f)
        }
    }
}

@Composable
private fun LayerBlock(tag: String, text: String, color: Color, frac: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .background(color.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) { Text(tag, style = TextStyle(color = color, fontSize = 16.sp), textAlign = TextAlign.Center) }
        Spacer(Modifier.width(11.dp))
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.10f * frac))
        ) {
            Text(
                text, style = body13(), color = TextPrimary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun LayerRow(tag: String, who: String, color: Color, detail: String, role: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(26.dp).height(26.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) { Text(tag, style = TextStyle(color = color, fontSize = 13.sp), textAlign = TextAlign.Center) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(who, style = bold13())
            Text(detail, style = mono10())
        }
        Text(
            role, style = mono11(), color = color,
            textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SmallBtn(text: String, accent: Color = Accent, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (accent == Line) MaterialTheme.colorScheme.surfaceContainerHigh
        else accent.copy(alpha = 0.16f),
        modifier = Modifier.clickableBox(onClick)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (accent == Line) TextSecondary else accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

// ══════════════════════════════════════════
// 实验 1 · 光速尺
// ══════════════════════════════════════════

@Composable
fun Exp01_Clock(depth: Depth, onDepth: (Depth) -> Unit) {
    var satNs by remember { mutableFloatStateOf(20f) }
    var bet by remember { mutableStateOf<Int?>(null) }
    val rows = remember { LabEngine.clockSweep(Scenarios.OPEN) }
    val meters = satNs.toDouble() * 0.299792458

    ExpScaffold("01", "光速尺", depth, onDepth) {
        LayeredText(
            Layered(
                shallow = "卫星不说「你离我一公里」,它说的是「我的信号到你那里,花了 3.33 微秒」。芯片把时间换算成距离。\n\n" +
                        "所以这把尺子的刻度是光:1 微秒 = 300 米,1 纳秒 = 30 厘米。\n\n" +
                        "但位置误差不按这把尺子算。",
                medium = "刻度就这么大:0.3 米/纳秒。\n\n" +
                        "那如果接收机的钟慢了 1 微秒呢?量出来的东西整体偏 300 米,地图应该偏出街区。\n\n" +
                        "但实测不是这样:**偏不了多少。**\n\n" +
                        "因为 c·δt_r 本来就是解算的四个未知量之一。所有伪距一起偏一个相同的量,那个共模偏移被完整吸收掉了。" +
                        "解算器自己把钟差解出来了。",
                deep = "**共模被吸收。**\n\n" +
                        "未知量:x, y, z, c·δt_r。把每颗星的伪距都加上同一个常数 c·δt,最小二乘的解完全不变。" +
                        "这个常数被 c·δt_r 这个未知量精确吸收。所以单纯给接收机加一个恒定钟差,位置几乎不动(实测只差零点几米,是条件数放大)。\n\n" +
                        "**但各星之间的不一致,谁也吸收不了。**\n\n" +
                        "接收机钟差要是随时间漂(非恒定),共模假设就不成立了,残余直接进位置。\n" +
                        "更常见的是**卫星之间**的钟差不一致。那是真正的误差源。\n\n" +
                        "所以「授时精度决定定位精度」这句话,准确的说法是:\n" +
                        "**时间基准的彼此一致,比某个钟的绝对快慢重要得多。**"
            ),
            depth
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle("先看尺子", accent = Accent)
        Spacer(Modifier.height(6.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("时间偏差", style = body13())
                    Text("光在这段时间里能跑多远", style = mono10())
                }
                Text(LabEngine.fmtNs(satNs.toDouble()), style = bigNum())
            }
            Spacer(Modifier.height(4.dp))
            Slider2(satNs, 0f..1000f, 0, onChange = { satNs = it })
            ClockRuler(satNs.toDouble())
            Spacer(Modifier.height(8.dp))
            StatRow(
                listOf(
                    StatData("等效距离", "${LabEngine.fmtNum(meters)} m"),
                    StatData("1 ns", "0.30 m", AccentWarm)
                )
            )
        }
        Spacer(Modifier.height(14.dp))

        SectionTitle("但位置误差不是这么算的", accent = Danger)
        Spacer(Modifier.height(6.dp))
        Text(
            "同一片天空、同一个解算器,只改「量」的时间品质。上面三行是你自己的钟,没动。" +
                    "下面几行是各颗星之间对不齐,那才是致命的。",
            style = mono10()
        )
        Spacer(Modifier.height(8.dp))
        Card { BarList(rows) }
        Spacer(Modifier.height(14.dp))

        SectionTitle("先猜一个", accent = AccentWarm)
        Spacer(Modifier.height(8.dp))
        BetCard(
            bet = Bet(
                question = "给接收机加 1 微秒的钟差,地图会偏多少?",
                options = listOf("约 300 米", "几乎不动"),
                correctIndex = 1,                       // ← 正确答案是「几乎不动」
                reveal = "上面表格里那三行就是实测:1 微秒的共同钟差只带来零点几米。" +
                        "因为 c·δt_r 本来就是解算的四个未知量之一,共模偏移被完整吸收了。" +
                        "真正打位置的是下面那几行,各颗星彼此对不齐。"
            ),
            picked = bet,
            onPick = { bet = it }
        )
        Spacer(Modifier.height(16.dp))

        Takeaway("1 微秒 = 300 米,换算没错。决定定位精度的是各个时间源彼此对不对得上。")
    }
}

// ══════════════════════════════════════════
// 实验 2 · 四颗星与几何
// ══════════════════════════════════════════

@Composable
fun Exp02_Geometry(depth: Depth, onDepth: (Depth) -> Unit) {
    var scenarioId by remember { mutableStateOf("open") }
    var count by remember { mutableIntStateOf(4) }
    val sc = Scenarios.byId(scenarioId)
    val rows = remember(scenarioId) { LabEngine.geometrySweep(sc) }
    val cur = rows.firstOrNull { it.label.startsWith("$count") }
    val shot = remember(scenarioId, count) {
        LabEngine.shot(sc, GnssSystem.entries.toSet(), cleanBudget(), maxSats = count)
    }

    ExpScaffold("02", "四颗星和几何", depth, onDepth,
        sticky = {
            LiveResultBar(
                listOf(
                    StatData("可见星", "${shot.numSats}"),
                    StatData("HDOP", if (shot.solution == null) "—" else LabEngine.fmtNum(shot.solution.hdop)),
                    StatData("水平误差", "${LabEngine.fmtNum(shot.errorM)} m", errorColor(shot.errorM))
                )
            )
        }
    ) {
        LayeredText(
            Layered(
                shallow = "四颗星就能算出你在哪,前提是四颗星摆得开。\n\n" +
                        "如果它们都挤在你头顶一小片天空,就像用四把尺子从同一个方向量你,怎么算都糊。\n\n" +
                        "卫星数量决定「能不能解」,卫星摆法决定「解得准不准」。",
                medium = "线性化最小二乘的协因数阵是 (HᵀH)⁻¹,它的几何部分就是 DOP(星历精度衰减因子)。\n\n" +
                        "HDOP 0.8~2 良好,>5 就很差。HDOP 说的是「水平误差被几何放大多少倍」。\n\n" +
                        "一颗星:一个球面,无穷多解。两颗:一个圆,还是无穷多解。\n四颗:四个球面相交给一个点。第五颗开始,是让这个点更稳。",
                deep = "设计矩阵第 i 行:\n\n" +
                        "H_i = [ (x_s−x_r)/ρ,  (y_s−y_r)/ρ,  (z_s−z_r)/ρ,  1 ]\n\n" +
                        "位置协方差 ∝ (HᵀH)⁻¹ 的位置块。转到 ENU 局部坐标后:\n" +
                        "HDOP = √(P_EN + P_NN),  PDOP = √(P_EN+P_NN+P_UU)\n\n" +
                        "几何差的本质是 H 的列向量接近线性相关。卫星位置向量张成的立体角太窄。\n" +
                        "高轨(GEO/IGSO)因为几乎不动,单靠它们几何必然差,必须搭配 MEO。"
            ),
            depth
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle("环境", accent = Accent)
        Spacer(Modifier.height(6.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(14.dp))

        SectionTitle("只留 ${count} 颗星", accent = Accent)
        Spacer(Modifier.height(6.dp))
        Card {
            SkyPlot(shot.obs, shot.solutionEcef(), shot.truthEcef, shot.solution?.let {
                doubleArrayOf(it.hdop, it.pdop, it.gdop)
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Slider2(count.toFloat(), 4f..14f, 0, onChange = { count = it.toInt() })
            Spacer(Modifier.height(6.dp))
            Text(sc.blurb, style = mono10())
        }
        Spacer(Modifier.height(14.dp))

        if (depth != Depth.SHALLOW) {
            SectionTitle("卫星数 → 误差", accent = Accent)
            Spacer(Modifier.height(8.dp))
            BarList(rows)
        }
        Spacer(Modifier.height(16.dp))
        Takeaway("四颗星是「及格线」,不是「好成绩」。一颗加得对,比一颗加在角落有用得多。")
    }
}

internal fun cleanBudget() = ErrorBudget(
    receiverClockNs = 0.0, satClockErrorM = 0.0, relativityEnabled = true,
    ionoHandling = IonoHandling.TEC_MODEL, tropoEnabled = true,
    ephemerisErrorM = 0.0, multipathRemoved = true
)

private fun ShotResult.solutionEcef() = solution?.ecef

@Composable
fun ScenarioPicker(current: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Scenarios.all.forEach { s ->
            val on = s.id == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) Accent.copy(alpha = 0.12f) else Surface1, RoundedCornerShape(9.dp))
                    .clickableBox { onPick(s.id) }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.name, style = if (on) bold13() else body13(), modifier = Modifier.weight(1f))
                Text(
                    "${s.maskDeg.toInt()}° 遮挡",
                    style = mono10(),
                    color = if (on) Accent else TextMuted
                )
            }
        }
    }
}

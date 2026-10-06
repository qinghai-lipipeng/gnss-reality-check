package com.oneus.lab.ui.experiments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oneus.lab.sim.*
import com.oneus.lab.ui.charts.*
import com.oneus.lab.ui.theme.*
import kotlin.math.roundToInt

// ══════════════════════════════════════════
// 实验 7 · 差分与 PPP
// ══════════════════════════════════════════

@Composable
fun Exp07_Precision(depth: Depth, onDepth: (Depth) -> Unit) {
    var scenarioId by remember { mutableStateOf("city") }
    val sc = Scenarios.byId(scenarioId)
    val rows = remember(scenarioId) { LabEngine.precisionSweep(sc) }
    val conv = remember(scenarioId) { LabEngine.pppConvergence(sc) }
    val trace = remember(conv) {
        listOf(FilterTrace("PPP 历元平均", "PPP", conv, conv.drop(conv.size * 2 / 3).filter { !it.isNaN() }
            .let { if (it.isEmpty()) 0.0 else it.average() }, 0.0))
    }

    ExpScaffold("07", "差分与 PPP", depth, onDepth) {
        LayeredText(
            Layered(
                shallow = "差分和 PPP 能到厘米级。但它们不是把数学换掉了。\n\n" +
                        "它们做的事很朴素:找一个知道自己在哪的基准站,或者拿到一套更准的卫星位置和时间,拿来给卫星的错「对答案」。\n\n" +
                        "算还是那套算。",
                medium = "差分(RTK):基准站的坐标是已知的,它也能收到同一批卫星。\n" +
                        "把两边的伪距一减,卫星钟差、轨道误差、大气延迟在短基线上大部分被差掉。\n\n" +
                        "PPP:不用基准站,直接用事后解算的精密轨道和精密钟差产品,自己估对流层。\n\n" +
                        "两者改善精度的方式是**注入信息**,不是改善算法。\n\n" +
                        "但先看下面的实测数字:在城市里,单点 15.0 米,双频 15.3,PPP 15.4,只有差分做到 0.45 米。\n" +
                        "因为城市里**多路径**才是主项,而只有差分能把它差掉。\n" +
                        "双频和精密产品各自值零点几米,全淹没在噪声里。",
                deep = "误差来源与手段的对应:\n\n" +
                        "卫星钟差  1~3 m   → 精密钟差产品 / 差分改正,消除\n" +
                        "轨道误差  1~2 m   → 精密轨道 / 差分改正,消除\n" +
                        "电离层    5~20 m  → 双频无组合 / 差分,消除\n" +
                        "对流层    0.2~0.5m→ 本地估计(PPP 需要,差分也难完全差掉)\n" +
                        "多路径    5~50 m  → 唯一需要「算法」的地方\n\n" +
                        "所以厘米级的战场只剩最后两行。\n" +
                        "模糊度固定策略、多路径抑制、周跳检测,这三样是接收机厂商的技术壁垒。"
            ),
            depth
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle("环境", accent = Accent)
        Spacer(Modifier.height(6.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(14.dp))

        SectionTitle("精度跃升的四个台阶", accent = Accent)
        Spacer(Modifier.height(8.dp))
        Card { BarList(rows) }
        Spacer(Modifier.height(8.dp))
        Text(
            "每上一级台阶,换的不是算法,是「喂进去的东西」。\n" +
                    "切到开阔地再点一次算比例:那里多路径小,精密产品的贡献才看得出来。",
            style = mono10()
        )
        Spacer(Modifier.height(8.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(16.dp))

        SectionTitle("PPP 收敛", accent = Accent)
        Spacer(Modifier.height(6.dp))
        Card {
            Text("静态下,历元平均把随机噪声压下去,曲线会一路往下走。", style = body13())
            Spacer(Modifier.height(8.dp))
            ConvergenceChart(trace, Modifier.fillMaxWidth().height(150.dp))
        }
        Spacer(Modifier.height(16.dp))

        Takeaway("从米级到厘米级,靠的是「知道得更准」,不是「算得更聪明」。这一跃是外部信息给的,换滤波器的贡献在零点几米以内。")
    }
}

// ══════════════════════════════════════════
// 实验 8 · 自由沙盒
// ══════════════════════════════════════════

/** 沙盒预设 —— 一键套用,省得每次拖十几个滑块 */
private data class Preset(
    val label: String,
    val scenario: String? = null,
    val clock: Float = 0f,
    val satClock: Float = 1f,
    val eph: Float = 1f,
    val rel: Boolean = true,
    val tropo: Boolean = true,
    val mp: Boolean = false,
    val iono: IonoHandling = IonoHandling.TEC_MODEL
)

private val presets = listOf(
    Preset("开阔实况", "open"),
    Preset("城市实况", "city"),
    Preset("峡谷实况", "canyon"),
    Preset("只动钟差", clock = 100f, satClock = 0f, eph = 0f, rel = true, tropo = true),
    Preset("只动大气", clock = 0f, satClock = 0f, eph = 0f, iono = IonoHandling.NONE),
    Preset("只动多路径", clock = 0f, satClock = 0f, eph = 0f, mp = false, scenario = "city"),
    Preset("全部归零", clock = 0f, satClock = 0f, eph = 0f, rel = true, tropo = true, mp = true)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Exp08_Sandbox(depth: Depth, onDepth: (Depth) -> Unit) {
    var scenarioId by remember { mutableStateOf("city") }
    var clockNs by remember { mutableFloatStateOf(0f) }
    var satClock by remember { mutableFloatStateOf(1f) }
    var eph by remember { mutableFloatStateOf(1f) }
    var relOn by remember { mutableStateOf(true) }
    var tropoOn by remember { mutableStateOf(true) }
    var mpOn by remember { mutableStateOf(false) }
    var iono by remember { mutableStateOf(IonoHandling.TEC_MODEL) }
    var freq by remember { mutableStateOf(FreqPlan.L1_L5) }
    var systems by remember { mutableStateOf(GnssSystem.entries.toSet()) }
    // 面板展开状态提升到这一层 —— LivePanel 只负责画,状态由页面持有,两边共享同一份
    var panelOpen by remember { mutableStateOf(false) }

    val sc = Scenarios.byId(scenarioId)
    val budget = remember(clockNs, satClock, eph, relOn, tropoOn, mpOn, iono, freq) {
        ErrorBudget(
            receiverClockNs = clockNs.toDouble(),
            satClockErrorM = satClock.toDouble(),
            relativityEnabled = relOn,
            ionoHandling = iono,
            freqPlan = freq,
            tropoEnabled = tropoOn,
            ephemerisErrorM = eph.toDouble(),
            multipathRemoved = mpOn
        )
    }
    val shot = remember(scenarioId, budget, systems) { LabEngine.shot(sc, systems, budget) }

    ExpScaffold("08", "自由沙盒", depth, onDepth,
        sticky = {
            // 常驻可视化面板:收起态是数字条,展开态是天空图。
            // 整块钉在滚动区之外 —— 拖滑块时数字一直在视野顶部,
            // 想看图点一下「展开 ▲」就原地长出来,看完再点收起,全程零滚动。
            LivePanel(
                open = panelOpen,
                onToggleOpen = { panelOpen = !panelOpen },
                collapsed = listOf(
                    StatData("水平误差", "${LabEngine.fmtNum(shot.errorM)} m", errorColor(shot.errorM)),
                    StatData("可见星", "${shot.numSats}"),
                    StatData("HDOP", shot.solution?.let { LabEngine.fmtNum(it.hdop) } ?: "—")
                ),
                sky = {
                    SkyPlot(shot.obs, shot.solution?.ecef, shot.truthEcef,
                        shot.solution?.let { doubleArrayOf(it.hdop, it.pdop, it.gdop) },
                        Modifier.fillMaxWidth())
                },
                extra = {
                    Text(
                        "外圈是地平线,圆心是天顶,点的亮度代表信号强度。圈外的椭圆是 DOP 几何放大倍数。",
                        style = mono10()
                    )
                    Spacer(Modifier.height(10.dp))
                    StatRow(
                        listOf(
                            StatData("残差RMS",
                                shot.solution?.let { "${LabEngine.fmtNum(it.residualRms)} m" } ?: "—")
                        )
                    )
                    // 逐星残差跟着图一起放进面板 —— 原来它挂在内容最底部,
                    // 每次想看都得滚到底,和天空图是同一个毛病
                    if (depth == Depth.DEEP) {
                        Spacer(Modifier.height(12.dp))
                        SectionTitle("逐星残差", accent = Accent)
                        Text("解算后,每颗星的伪距还剩多少没解释。共相偏移 = 钟差,随机起伏 = 噪声。", style = mono10())
                        Spacer(Modifier.height(6.dp))
                        val sol = shot.solution
                        if (sol != null) {
                            val idx = shot.obs
                                .mapIndexed { i, m -> m to sol.residuals.getOrElse(i) { 0.0 } }
                                .sortedBy { it.second }
                            ResidualBars(
                                idx.map { it.first.prn },
                                idx.map { it.second },
                                idx.map { systemColor(it.first.system) },
                                Modifier.fillMaxWidth()
                            )
                        } else {
                            Text("当前配置解不出位置,没有残差。", style = mono10(), color = TextMuted)
                        }
                    }
                }
            )
        }
    ) {
        LayeredText(
            Layered(
                shallow = "这里没有对错,只有「你想让哪一项变大」。\n\n" +
                        "每一根滑块都对应「量」里的一个环节。把它推到头,再推回去。\n\n" +
                        "顶部那块面板钉在滚动区之外:数字一直在,想看天空图点一下「展开 ▲」,看完再收起。",
                medium = "九个旋钮,全部作用在伪距方程的各项上。解算器从头到尾没变过。\n\n" +
                        "试试:只把钟差拉大,其余全部归零 —— 你会看到一条非常干净的线性关系。\n" +
                        "再试试:只把多路径拉大 —— 误差变得随机而且脏,因为它跟仰角强相关。\n\n" +
                        "不同项的脾气完全不同,这就是为什么「授时精度」不能一句话概括。",
                deep = "当前误差预算:\n\n" +
                        "c·δt_r      = ${LabEngine.fmtNum(clockNs * 0.299792458)} m\n" +
                        "c·δt_sat    = ${LabEngine.fmtNum(satClock.toDouble())} m (1σ)\n" +
                        "Δt_rel      = ${if (relOn) "已施加" else "未施加"}\n" +
                        "I           = ${iono.label}\n" +
                        "T           = ${if (tropoOn) "已施加" else "未施加"}\n" +
                        "ε_轨道      = ${LabEngine.fmtNum(eph.toDouble())} m (1σ)\n" +
                        "ε_多路径    = ${if (mpOn) "已改正" else "未改正"}\n\n" +
                        "解算:加权最小二乘,${shot.solution?.iterations ?: 0} 次迭代收敛\n" +
                        "卫星数 ${shot.numSats} · 场景「${sc.name}」"
            ),
            depth
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle("场景", accent = Accent)
        Spacer(Modifier.height(6.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(16.dp))

        SectionTitle("星座", accent = Accent)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4
        ) {
            GnssSystem.entries.forEach { s ->
                val on = s in systems
                val col = systemColor(s)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (on) col.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickableBox { systems = if (on) systems - s else systems + s }
                ) {
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) col else TextMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        SectionTitle("预设", accent = AccentGreen)
        Spacer(Modifier.height(4.dp))
        Text("不想从头拖起?点一个直接套用。", style = mono10())
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { p ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickableBox {
                        p.scenario?.let { scenarioId = it }
                        clockNs = p.clock; satClock = p.satClock; eph = p.eph
                        relOn = p.rel; tropoOn = p.tropo; mpOn = p.mp; iono = p.iono
                    }
                ) {
                    Text(
                        p.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        SectionTitle("误差预算", accent = Danger)
        Spacer(Modifier.height(10.dp))
        Card {
            ControlSlider("接收机钟差", LabEngine.fmtNs(clockNs.toDouble()), clockNs, 0f..1000f,
                onChange = { clockNs = it })
            Spacer(Modifier.height(6.dp))
            ControlSlider("卫星钟差残余", "${LabEngine.fmtNum(satClock.toDouble())} m", satClock, 0f..10f,
                onChange = { satClock = it })
            Spacer(Modifier.height(6.dp))
            ControlSlider("星历轨道误差", "${LabEngine.fmtNum(eph.toDouble())} m", eph, 0f..10f,
                onChange = { eph = it })
        }
        Spacer(Modifier.height(10.dp))
        Card {
            ToggleRow("相对论偏心项修正", relOn, { relOn = it }, "Δt_rel = F·e·√A·sin(E)")
            ToggleRow("对流层模型", tropoOn, { tropoOn = it })
            ToggleRow("多路径改正", mpOn, { mpOn = it }, "现实里做不到,只当理想上限")
            Spacer(Modifier.height(8.dp))
            SectionTitle("电离层处理", accent = Accent)
            Spacer(Modifier.height(6.dp))
            Segmented(IonoHandling.entries.toList(), iono, { it.label }, { iono = it })
            if (iono == IonoHandling.DUAL_FREQ) {
                Spacer(Modifier.height(8.dp))
                Segmented(FreqPlan.entries.toList(), freq, { it.label }, { freq = it })
            }
        }

        // 天空图和逐星残差都搬进了上面的常驻面板:图在内容最底部时,
        // 调完滑块要往下滚才看得到、看完又要往上滚看数字,来回滚动就是这么来的。
        // 这里只留一句指路,免得用户以为图不见了。
        Spacer(Modifier.height(14.dp))
        Text("天空图在顶部那块常驻面板里,点「展开 ▲」原地就出来,不用往回滚。", style = mono10())
    }
}

// ══════════════════════════════════════════
// 实验 9 · 通关挑战
// ══════════════════════════════════════════

data class Challenge(
    val title: String,
    val brief: String,
    val target: Double,
    val scenarioId: String,
    val hint: Layered
)

private val challenges = listOf(
    Challenge(
        "城市峡谷,1 米",
        "在楼宇夹缝里,要求水平误差不超过 1 米。",
        1.0, "canyon",
        Layered(
            "卫星在这里几乎不可用。想清楚:这个目标要靠「量」还是靠「猜」?",
            "裸定位在峡谷里主要受多路径和几何限制。先把几何和多路径处理掉,再考虑其他。",
            "峡谷里 mask 25°、多路径 12 m。可用手段:双频无组合 + 多路径理想改正 + 卫星钟差归零。\n" +
                    "仍不够的话,真实系统里这时该切融合定位了 —— 但沙盒里只有卫星。"
        )
    ),
    Challenge(
        "开阔地,3 米",
        "几何很好,目标定在 3 米。",
        3.0, "open",
        Layered(
            "条件很好,随便怎么配都能过。这一题是让你先建立起「能过」的手感。",
            "开阔地主要剩电离层和钟差。双频 + 归零钟差,基本就到噪声底了。",
            "开阔地 mask 10°、多路径 0.5 m。预算:电离层无组合 + 卫星钟差 0 + 轨道误差 0 + 钟差 0。"
        )
    ),
    Challenge(
        "磁暴天,5 米",
        "把电子含量拉到 4 倍,还要 5 米。",
        5.0, "city",
        Layered(
            "太阳发脾气了,大气里的电特别多。",
            "单频模型肯定不够,得用双频。",
            "TEC 4 倍时 L1 单频延迟可达 20 m 以上。双频无组合消一阶项,残余只有二阶 + 噪声放大。"
        )
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Exp09_Challenge(depth: Depth, onDepth: (Depth) -> Unit) {
    var idx by remember { mutableIntStateOf(0) }
    val ch = challenges[idx]
    var clockNs by remember(idx) { mutableFloatStateOf(0f) }
    var satClock by remember(idx) { mutableFloatStateOf(1f) }
    var eph by remember(idx) { mutableFloatStateOf(1f) }
    var relOn by remember(idx) { mutableStateOf(true) }
    var tropoOn by remember(idx) { mutableStateOf(true) }
    var mpOn by remember(idx) { mutableStateOf(false) }
    var iono by remember(idx) { mutableStateOf(IonoHandling.TEC_MODEL) }
    var freq by remember(idx) { mutableStateOf(FreqPlan.L1_L5) }

    val sc = remember(idx) { Scenarios.byId(ch.scenarioId) }
    val scen = remember(idx) { if (ch.scenarioId == "city") sc.copy(tec = 4.0e17) else sc }

    val budget = remember(clockNs, satClock, eph, relOn, tropoOn, mpOn, iono, freq) {
        ErrorBudget(
            receiverClockNs = clockNs.toDouble(), satClockErrorM = satClock.toDouble(),
            relativityEnabled = relOn, ionoHandling = iono, freqPlan = freq,
            tropoEnabled = tropoOn, ephemerisErrorM = eph.toDouble(), multipathRemoved = mpOn
        )
    }
    val shot = remember(scen, budget) { LabEngine.shot(scen, GnssSystem.entries.toSet(), budget) }
    val passed = shot.errorM <= ch.target

    ExpScaffold("09", "通关挑战", depth, onDepth,
        sticky = {
            LiveResultBar(
                listOf(
                    StatData("目标", "≤ ${LabEngine.fmtNum(ch.target)} m", Good),
                    StatData("当前", "${LabEngine.fmtNum(shot.errorM)} m", errorColor(shot.errorM)),
                    StatData("状态", if (passed) "达标" else "未达标",
                        if (passed) AccentGreen else TextMuted)
                )
            )
        }
    ) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${idx + 1} / ${challenges.size} 关", style = mono11(), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(ch.title, style = title18())
            Spacer(Modifier.height(4.dp))
            Text(ch.brief, style = body13())
        }
        Spacer(Modifier.height(14.dp))

        if (passed) {
            Box(
                Modifier.fillMaxWidth().background(Good.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(13.dp)
            ) {
                Text("过了。你刚才改的每一项都作用在「量」上,算法那部分一个旋钮都没碰。", style = body13(), color = Good)
            }
            Spacer(Modifier.height(14.dp))
        }

        SectionTitle("提示", accent = AccentWarm)
        Spacer(Modifier.height(6.dp))
        Card { LayeredText(ch.hint, depth) }
        Spacer(Modifier.height(14.dp))

        SectionTitle("调参", accent = Accent)
        Spacer(Modifier.height(8.dp))
        Card {
            ControlSlider("接收机钟差", LabEngine.fmtNs(clockNs.toDouble()), clockNs, 0f..500f, onChange = { clockNs = it })
            ControlSlider("卫星钟差残余", "${LabEngine.fmtNum(satClock.toDouble())} m", satClock, 0f..10f, onChange = { satClock = it })
            ControlSlider("星历轨道误差", "${LabEngine.fmtNum(eph.toDouble())} m", eph, 0f..10f, onChange = { eph = it })
        }
        Spacer(Modifier.height(10.dp))
        Card {
            ToggleRow("相对论偏心项修正", relOn, { relOn = it })
            ToggleRow("对流层模型", tropoOn, { tropoOn = it })
            ToggleRow("多路径改正", mpOn, { mpOn = it }, "理想条件,现实中做不到")
            Spacer(Modifier.height(8.dp))
            SectionTitle("电离层处理", accent = Accent)
            Spacer(Modifier.height(6.dp))
            Segmented(IonoHandling.entries.toList(), iono, { it.label }, { iono = it })
            if (iono == IonoHandling.DUAL_FREQ) {
                Spacer(Modifier.height(8.dp))
                Segmented(FreqPlan.entries.toList(), freq, { it.label }, { freq = it })
            }
        }
        Spacer(Modifier.height(16.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallBtn(if (idx > 0) "上一关" else "第一关", Line) {
                idx = if (idx > 0) idx - 1 else 0
            }
            SmallBtn(if (idx < challenges.size - 1) "下一关" else "已是最后一关", Accent) {
                idx = if (idx < challenges.size - 1) idx + 1 else challenges.size - 1
            }
        }
    }
}

// ══════════════════════════════════════════
// 收尾 · 误差预算终图 + 辟谣 + 口袋卡
// ══════════════════════════════════════════

@Composable
fun Closing(depth: Depth, onDepth: (Depth) -> Unit, onRestart: () -> Unit) {
    var scenarioId by remember { mutableStateOf("city") }
    val sc = Scenarios.byId(scenarioId)
    val lines = remember(scenarioId) { LabEngine.budgetBreakdown(sc) }

    ExpScaffold("★", "一张图收尾", depth, onDepth) {
        LayeredText(
            Layered(
                shallow = "回到最开始那句话。\n\n" +
                        "卫星负责量,手机负责猜,算法负责把量出来的拼整齐。\n\n" +
                        "下面是这次实验里,每一项分别拿走了多少误差。",
                medium = "每一行的算法:在「全部误差都开」和「关掉这一项」两种情况下各解一次,用方差合成算边际贡献。\n\n" +
                        "看哪几行是大头:授时、大气、环境。没有一行是「算法」。",
                deep = "边缘贡献 = √(σ_全部² − σ_关掉该项²)\n\n" +
                        "这是一个方差合成意义的分解,各项之间不是严格可加的(高斯假设下才成立)。\n" +
                        "它的用途是排序:谁大谁小一眼可见,而不是精确记账。\n\n" +
                        "注意这张表里没有「算法」这一行:因为四种滤波器跑出来的终值差在零点几米以内,\n" +
                        "放进这个量级里等于零。"
            ),
            depth
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle("场景", accent = Accent)
        Spacer(Modifier.height(6.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(14.dp))

        SectionTitle("谁在拿误差", accent = Danger)
        Spacer(Modifier.height(8.dp))
        Card { WaterfallChart(lines) }
        Spacer(Modifier.height(16.dp))

        SectionTitle("量级对照", accent = Accent)
        Spacer(Modifier.height(8.dp))
        Card {
            ScaleRow("1 微秒 = 300 米(尺子的刻度)", "换算关系", TextMuted)
            ScaleRow("卫星间钟差 ±20 ns", "约 5 m", Danger)
            ScaleRow("电离层(磁暴天,单频)", "1~3 m", Warn)
            ScaleRow("多路径(城市)", "5~15 m", Warn)
            ScaleRow("几何(HDOP 2 / HDOP 5)", "2.5 倍", Accent)
            ScaleRow("换星座(GPS ↔ 北斗 ↔ 四系统)", "约 0.3 m", Good)
            ScaleRow("换更高级的解算算法", "几乎不动", Good)
        }
        Spacer(Modifier.height(18.dp))

        SectionTitle("几句常见误解", accent = AccentWarm)
        Spacer(Modifier.height(8.dp))
        Card {
            MythRow("「北斗比 GPS 准」",
                "北斗和 GPS 的精度差在 0.x m量级,日常体感不可分辨。能分出高下的是可用性、授时服务、多频与增强。")
            MythRow("「GPS 是军用的,所以全球特别准」",
                "分水岭是频段数量和增强系统,不是哪家的算法更聪明。")
            MythRow("「不依赖美国 = 更准」",
                "这是安全与自主,不是精度。这两件事被混在一起,才是大家吵不清的根源。")
            MythRow("「接收机钟差 1 微秒 = 偏 300 米」",
                "换算没错,但单点定位里 c·δt_r 本身就是解算的未知量,共模偏移被完整吸收,实测只差零点几米。\n" +
                        "真正致命的是**各星之间**对不齐:±20 ns 就是几米。")
            MythRow("「算法不重要」",
                "算法在厘米级是刚需(模糊度固定、多路径抑制),在城市里也是刚需(融合定位)。\n" +
                        "它管的是「稳不稳、漂不漂」,不是「准的上限是多少」。")
        }
        Spacer(Modifier.height(18.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column {
                Text("口袋卡", style = mono11(), color = Accent)
                Spacer(Modifier.height(8.dp))
                Text("定位不是算出来的,是量出来的。", style = title18())
                Spacer(Modifier.height(6.dp))
                Text(
                    "1 微秒 = 300 米,但决定精度的是各个时间源彼此对不对得上。\n" +
                            "时间一致性、大气、多路径,这些决定精度天花板;\n" +
                            "WiFi、基站、地图匹配,这些决定城里漂不漂;\n" +
                            "而算法只负责把量出来的拼整齐。",
                    style = body13()
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        SmallBtn("从头再看一遍", Accent, onRestart)
    }
}

@Composable
private fun ScaleRow(label: String, value: String, color: Color) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, style = body13(), modifier = Modifier.weight(1f).padding(end = 12.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Text(value, style = mono13().copy(color = color), maxLines = 1)
    }
}

@Composable
private fun MythRow(myth: String, fact: String) {
    Column(Modifier.padding(vertical = 7.dp)) {
        Text("✗ $myth", style = bold13().copy(color = Danger))
        Spacer(Modifier.height(3.dp))
        Text("✓ $fact", style = body13())
    }
}

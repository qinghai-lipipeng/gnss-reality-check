package com.oneus.lab.ui.experiments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oneus.lab.sim.*
import com.oneus.lab.ui.charts.*
import com.oneus.lab.ui.theme.*
import kotlin.math.abs

// ══════════════════════════════════════════
// 实验 3 · 星座之争 ★ 核心
// ══════════════════════════════════════════

@Composable
fun Exp03_Constellation(env: SectionEnv) {
    val depth = env.depth
    // 默认开阔地:四个系统都解得出来,"同一量级"这个结论才能在表里直接看见。
    // 切到城市/峡谷会看到数字跳动 —— 那不是换星座变差了,是单次采样的噪声,
    // 所以下面那张表是 9 次采样平均的结果。
    var scenarioId by env.state("scenarioId") { "open" }
    var useClock by env.state("useClock") { false }   // true = 注入卫星间钟差不一致
    var bet by env.state<Int?>("bet") { null }
    val sc = Scenarios.byId(scenarioId)

    val budget = remember(useClock) {
        // 注意:这里动的是**卫星之间**的钟差,不是接收机自己的钟差。
        // 接收机钟差会被解算器当未知量吸收掉(见实验 01)。
        if (useClock) ErrorBudget(satClockErrorM = 20.0 * 0.299792458, ionoHandling = IonoHandling.TEC_MODEL)
        else ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
    }
    val rows = remember(scenarioId, useClock) { LabEngine.constellationSweep(sc, budget) }

    ExpScaffold(env, "星座之争") {
        LayeredText(
            Layered(
                shallow = "北斗、GPS、伽利略不是三个软件在比谁聪明。它们就是三群卫星,在天上一起喊「我在哪、现在几点」。\n\n" +
                        "手机里就一颗芯片,全都听着,一起算。不是三选一。\n\n" +
                        "所以把同一份场景的数据依次交给不同星座,误差几乎不动。",
                medium = "所有星座共用同一套解算:x、y、z、cδt 四个未知量,伪距线性化最小二乘。没有任何一家有「更聪明」的定位公式。\n\n" +
                        "换星座改变的是几何(可见卫星的分布)和可用性,不是解算的数学。\n\n" +
                        "四个系统的误差落在同一档,是因为它们都卡在同一批物理限制上:时间一致性、大气、多路径。",
                deep = "四个星座的轨道设计差异:\n\n" +
                        "GPS       6 面 × 4 星,倾角 55°,20200 km\n" +
                        "北斗 MEO  3 面 × 8 星,倾角 55°,21528 km\n" +
                        "北斗 IGSO 3 星,大圆轨道,35786 km\n" +
                        "北斗 GEO  5 星,静止,35786 km\n" +
                        "伽利略    6 面 × 4 星,倾角 56°,23222 km\n" +
                        "格洛纳斯  3 面 × 8 星,倾角 64.5°,19140 km\n\n" +
                        "北斗的 MEO 数量是其他系统的两倍,几何冗余确实更好。\n" +
                        "但几何改善的是 HDOP 和可用性,不是误差的量级。"
                ),
            depth
        )
        Spacer(Modifier.height(14.dp))

        Card {
            LayerRow("量", "同一片天空", Accent, "星座只改变谁在上面", "几何变好")
            LayerRow("算", "同一个解算器", AccentGreen, "最小二乘,一个字没改", "结果几乎不变")
        }
        Spacer(Modifier.height(14.dp))

        SectionTitle("先猜", accent = AccentWarm)
        Spacer(Modifier.height(8.dp))
        BetCard(
            bet = Bet(
                question = "只用北斗,比只用 GPS 准多少?",
                options = listOf("会,明显更准", "差不多,在同一个量级", "北斗反而更差"),
                correctIndex = 1,                       // ← 正确答案是「差不多」
                reveal = "差别在零点几米,四个系统都卡在同一批物理限制上。" +
                        "真正拉开差距的是下面那一栏。"
            ),
            picked = bet,
            onPick = { bet = it }
        )
        Spacer(Modifier.height(14.dp))

        SectionTitle("环境", accent = Accent)
        Spacer(Modifier.height(6.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(14.dp))

        SectionTitle("同一份场景,换星座", accent = Accent)
        Spacer(Modifier.height(6.dp))
        ToggleRow(
            "让各颗星的钟互相对不齐 (±20 ns)",
            useClock,
            { useClock = it },
            "解算器一个字都没改,只把「量」弄差了"
        )
        Spacer(Modifier.height(8.dp))
        Card {
            if (useClock) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Danger.copy(alpha = 0.10f), RoundedCornerShape(9.dp))
                        .padding(10.dp)
                ) {
                    Text("卫星间钟差不一致生效中,看看所有星座一起飞成什么样", style = body13(), color = Danger)
                }
                Spacer(Modifier.height(10.dp))
            }
            BarList(rows)
        }
        Spacer(Modifier.height(16.dp))

        if (depth != Depth.SHALLOW) {
            SectionTitle("对照:同一个解算器,只改钟差", accent = Danger)
            Spacer(Modifier.height(8.dp))
            val contrast = remember(scenarioId) { LabEngine.clockContrast(sc) }
            BarList(contrast)
        }
        Spacer(Modifier.height(16.dp))

        Takeaway(
            if (useClock)
                "换星座:零点几米。各星钟差对不齐 ±20 ns:几米。同一套数学,差的是「量」的一致性。"
            else
                "四个星座在同一场景下误差在同一个量级。它们不是四个选手,是四群卫星,一颗芯片一起用。"
        )
    }
}

// ══════════════════════════════════════════
// 实验 4 · 多给了你料
// ══════════════════════════════════════════

@Composable
fun Exp04_MoreStuff(env: SectionEnv) {
    val depth = env.depth
    var scenarioId by env.state("scenarioId") { "city" }
    val tecFrac by env.state("tecFrac") { 1.0f }
    val sc = Scenarios.byId(scenarioId)
    val tec = Scenarios.CITY.tec * tecFrac.toDouble()
    val rows = remember(scenarioId, tecFrac) { LabEngine.ionoSweep(sc, tec) }
    val rel = remember { LabEngine.relativityContrast(Scenarios.OPEN) }

    ExpScaffold(env, "多给了你料") {
        LayeredText(
            Layered(
                shallow = "北斗强在哪?\n\n" +
                        "多一个频段,能把大气那层干扰消掉。还有一个免费的修正广播,相当于有人提前告诉你「今天误差是这么多,你减一下」。\n\n" +
                        "不是数学更聪明,是多给了你料。",
                medium = "电离层是地球电离层对 1.5 GHz 信号的延迟,和频率平方成反比:I ∝ 1/f²。\n\n" +
                        "两个频率一减,一阶电离层被彻底消掉。这就是双频。\n" +
                        "只给你一个频率,你只能靠模型猜大气有多厚,猜不准就是几米误差。\n\n" +
                        "增强系统(SBAS、北斗的 GEO 广播)则是直接播发电离层修正系数,相当于把模型的参数免费送给你。",
                deep = "无组合双频:\n\n" +
                        "PR_IF = (f₁²·PR₁ − f₂²·PR₂) / (f₁² − f₂²)\n\n" +
                        "一阶电离层系数 f² 被消掉,残余只有二阶项(约 cm 级)。\n" +
                        "代价:噪声被放大 √(α₁²+α₂²)。L1/L5 约 2.59 倍,L1/L2 约 2.97 倍。这就是 L1/L5 更受青睐的原因。\n\n" +
                        "Klobuchar 模型(ICD-GPS-200 Table 20-V)用广播的 4+4 个系数在接收机端建模,\n" +
                        "好的时候能压到 1~2 米,差的时候和单频不改正差不多。\n\n" +
                        "相对论偏心项 Δt_rel = F·e·√A·sin(E),GPS 轨道 e ≈ 0.007,逐星可达 ±50~70 ns。\n" +
                        "但这些偏移在各星之间并不同步。共模那部分照样被钟差吸收,打进位置的只有差异部分。\n" +
                        "实测:不做这一项,水平误差多出约 2 米。"
            ),
            depth
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle("环境", accent = Accent)
        Spacer(Modifier.height(6.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(14.dp))

        SectionTitle("拖动太阳活动", accent = Accent)
        Spacer(Modifier.height(4.dp))
        Card {
            Row {
                Text("大气里的电子含量", style = body13(), modifier = Modifier.weight(1f))
                Text(LabEngine.fmtTec(tec), style = bigNum())
            }
            Slider2(tecFrac, 0.2f..4.0f, 0, onChange = { tecFrac = it })
            Text("安静时 10 TECU 上下。太阳活跃时能到 50,磁暴期间更高。", style = mono10())
        }
        Spacer(Modifier.height(14.dp))

        SectionTitle("同一颗星,不同的处理方式", accent = Accent)
        Spacer(Modifier.height(8.dp))
        BarList(rows)
        Spacer(Modifier.height(14.dp))

        if (depth != Depth.SHALLOW) {
            SectionTitle("顺带一提:相对论", accent = Danger)
            Spacer(Modifier.height(6.dp))
            Card {
                Text(
                    "卫星高速飞行,广义相对论说它们的钟会走得不一样。这项修正在播导航电文里," +
                            "但周期性偏心项要接收机自己算。",
                    style = body13()
                )
                Spacer(Modifier.height(10.dp))
                BarList(rel)
            }
            Spacer(Modifier.height(14.dp))
        }

        Takeaway("双频和增强广播,给的是「更好的料」,不是「更快的脑子」。料准了,算得快也没用。")
    }
}

// ══════════════════════════════════════════
// 实验 5 · 城市蓝点的真相 ★
// ══════════════════════════════════════════

@Composable
fun Exp05_Hybrid(env: SectionEnv) {
    val depth = env.depth
    var scenarioId by env.state("scenarioId") { "city" }
    val sc = Scenarios.byId(scenarioId)
    val city = remember(scenarioId) { LabEngine.hybridSweep(sc) }
    val bridge = remember { LabEngine.hybridUnderBridge() }
    var bet by env.state<Int?>("bet") { null }

    ExpScaffold(env, "城市蓝点的真相") {
        LayeredText(
            Layered(
                shallow = "为什么有的准,有的不准?\n\n" +
                        "因为手机定位根本不是纯靠卫星。城里那个蓝点,一大半是 WiFi、基站,还有「你肯定在路上,我帮你贴回路面上」猜出来的。\n\n" +
                        "所以你在比的,其实是手机厂商的整合功力,跟卫星系统本身关系不大。",
                medium = "融合定位把多个来源按方差倒数加权:\n\n" +
                        "卫星精度高,缺点是有盲区;WiFi 在城市里到处是,精度中等;基站粗,但永远在线;地图匹配把结果吸到最近的那条路上,误差能降一个量级。前提是你就在那条路上。\n\n" +
                        "这一层拼的是融合:能不能用、漂不漂,取决于怎么加权;准的上限还压在「量」那一层。",
                deep = "加权融合:\n\n" +
                        "x̂ = Σ(xᵢ/σᵢ²) / Σ(1/σᵢ²),  σ̂ = √(1/Σ(1/σᵢ²))\n\n" +
                        "σ 由各源的实时不确定度决定(高斯-牛顿法的权重,和 WLS 同源)。\n\n" +
                        "道路匹配最容易翻车的地方是立交桥:在二维地图上,桥上和下面的路重合。\n" +
                        "位置误差一旦超过几十米,匹配器分不清你在桥上还是桥下,就把你贴到错误的那一层。\n\n" +
                        "这也是为什么室内和桥下必须靠惯导 + 步数 + 气压计,而不是硬靠地图匹配。"
            ),
            depth
        )
        Spacer(Modifier.height(14.dp))

        SectionTitle("先猜", accent = AccentWarm)
        Spacer(Modifier.height(8.dp))
        BetCard(
            bet = Bet(
                question = "城市里那一点点精度,是卫星给的还是手机猜的?",
                options = listOf("大部分是卫星", "大部分是猜的", "一半一半"),
                correctIndex = 1,                       // ← 正确答案是「大部分是猜的」
                reveal = "往下看,差距会比你想象的大。"
            ),
            picked = bet,
            onPick = { bet = it }
        )
        Spacer(Modifier.height(14.dp))

        SectionTitle("同一个位置,四种说法", accent = Accent)
        Spacer(Modifier.height(6.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(10.dp))
        Card { BarList(city) }
        Spacer(Modifier.height(16.dp))

        SectionTitle("换个地方:桥下", accent = Danger)
        Spacer(Modifier.height(6.dp))
        Card {
            Text(
                "上面那套组合,全都用上。",
                style = body13()
            )
            Spacer(Modifier.height(4.dp))
            Text("现在把同样的组合搬到桥下:", style = body13(), color = TextMuted)
            Spacer(Modifier.height(10.dp))
            BarList(bridge)
        }
        Spacer(Modifier.height(16.dp))

        Takeaway("卫星量的是下限,融合猜的是体验。信号一断,融合再强也没得猜,因为卫星什么都没送来。")
    }
}

// ══════════════════════════════════════════
// 实验 6 · 滤波器之争 ★ 高潮
// ══════════════════════════════════════════

@Composable
fun Exp06_Filters(env: SectionEnv) {
    val depth = env.depth
    var scenarioId by env.state("scenarioId") { "open" }

    // 拖动中的临时值:只驱动读数显示,不触发重算
    var dragNs by env.state("dragNs") { 0f }
    // 已提交的值:只有它变化才重算
    var committedNs by env.state("committedNs") { 0f }
    var busy by env.state("busy") { false }
    var bet by env.state<Int?>("bet") { null }

    val sc = Scenarios.byId(scenarioId)
    val traceKey = "$scenarioId-$committedNs"

    // 重算放在后台线程,并用 traceKey 丢弃过期结果。
    // 之前这里是 remember { filterRun(...) },主线程同步跑近 1 秒 —— 滑动必然卡。
    var traces by env.state("traces") { emptyList<FilterTrace>() }
    LaunchedEffect(traceKey) {
        busy = true
        traces = withContext(Dispatchers.Default) {
            val b = ErrorBudget(
                // 标签写的是「卫星间钟差」,注入的就必须是**卫星之间**那部分。
                // 这里原来注入的是 receiverClockNs —— 第 01 节花整节说明会被完全吸收的共模量,
                // 于是这个滑块是一条纹丝不动的平线:拖到 0 / 50 / 200 ns 都是 1.158 m。
                satClockErrorM = committedNs.toDouble() * 0.299792458,
                ionoHandling = IonoHandling.TEC_MODEL
            )
            // 800 粒子:曲线形状与 1000 肉眼无差别,耗时少三成
            LabEngine.filterRun(sc, b, epochs = 32, particleCount = 800)
        }
        busy = false
    }

    val spread = if (traces.isEmpty()) 0.0
    else traces.maxOf { it.finalError } - traces.minOf { it.finalError }

    ExpScaffold(env, "滤波器之争",
        sticky = {
            // 常驻:四条曲线的终值与抖动,调滑块时始终可见
            LiveResultBar(
                if (traces.isEmpty()) listOf(StatData("状态", "计算中…", TextMuted))
                else traces.map { t ->
                    StatData(
                        t.short,
                        "${LabEngine.fmtNum(t.finalError)} m",
                        errorColor(t.finalError)
                    )
                }
            )
        }
    ) {
        LayeredText(
            Layered(
                shallow = "同一份数据,换四种完全不同的算法:\n\n" +
                        "普通最小二乘、加权最小二乘、卡尔曼滤波、粒子滤波。\n\n" +
                        "一个每历元从零开始,一个按每颗星的可靠程度加权,一个带着上一秒的解往下走,一个撒开八百个候选位置再挑一遍。\n\n" +
                        "然后看曲线。",
                medium = "四条曲线的终值差在零点几米以内。\n\n" +
                        "差的全在曲线的形状上:单历元最小二乘每历元重新开始,跳得厉害;" +
                        "卡尔曼和粒子滤波的曲线明显更平。\n\n" +
                        "算法改善的是**稳定性**,不是**准确度**。\n" +
                        "准确度的上限由误差预算决定,平滑救不了偏差。",
                deep = "四种实现的机理完全不同:\n\n" +
                        "OLS  每历元独立解一个法方程,无内部状态\n" +
                        "WLS  同上,但权重取 1/σ²,σ 随仰角与电离层残差变化\n" +
                        "EKF  4 状态 [x,y,z,cδt] 随机游走模型,Kalman 增益逐历元更新协方差\n" +
                        "PF   粒子群,以伪距似然加权 + 重采样,完全非高斯\n\n" +
                        "它们的收敛速度和方差不同,收敛**终值**相同,因为终值由\n" +
                        "「这批伪距里到底掺了多少系统性偏差」决定,和用什么方法解无关。\n\n" +
                        "算法真正能决定厘米级的场合是 RTK/PPP:固定模糊度、收敛策略、\n" +
                        "周跳与多路径抑制。那是米级以下的战场。"
            ),
            depth
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle("先猜", accent = AccentWarm)
        Spacer(Modifier.height(8.dp))
        BetCard(
            bet = Bet(
                question = "换更高级的滤波器,能把误差压下来多少?",
                options = listOf("至少一半", "一个数量级", "几乎压不动"),
                correctIndex = 2,                       // ← 正确答案是「几乎压不动」
                reveal = "看下面这四条曲线的终值差:${LabEngine.fmtNum(spread)} 米。"
            ),
            picked = bet,
            onPick = { bet = it }
        )
        Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(16.dp))

        SectionTitle("现在动「量」这一层", accent = Danger)
        Spacer(Modifier.height(4.dp))
        Card {
            HeavySlider(
                label = env.term("卫星间钟差", "卫星间钟差 c·δt_sat"),
                valueText = LabEngine.fmtNs(dragNs.toDouble()),
                value = dragNs,
                range = 0f..200f,
                onChange = { dragNs = it },
                onCommit = { committedNs = it },
                busy = busy,
                hint = "解算器一个字都没改。四条曲线会一起飞出去。"
            )
            Spacer(Modifier.height(4.dp))
            if (busy && committedNs != dragNs) {
                Text(
                    "松手后自动重算,拖动过程中只更新读数,避免卡顿。",
                    style = mono10(), color = AccentWarm
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ScenarioPicker(scenarioId) { scenarioId = it }
        Spacer(Modifier.height(18.dp))

        SectionTitle("四条曲线,同一份数据", accent = Accent)
        Spacer(Modifier.height(8.dp))
        Card {
            if (traces.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(190.dp),
                    contentAlignment = Alignment.Center
                ) { Text("正在计算…", style = mono11(), color = TextMuted) }
            } else {
                ConvergenceChart(traces, Modifier.fillMaxWidth().height(190.dp))
            }
            Spacer(Modifier.height(10.dp))
            traces.forEachIndexed { i, t ->
                Row(
                    Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(10.dp).background(
                            filterColors[i % 4], RoundedCornerShape(2.dp)
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.name, style = body13(), modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("终值 ${LabEngine.fmtNum(t.finalError)} m", style = mono11(), color = TextPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("抖动 ${LabEngine.fmtNum(t.scatter)} m", style = mono10())
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Takeaway(
            "算法把曲线抹平了,抹不平偏差。\n" +
                    "曲线更平 = 更稳,误差没变 = 更准不了。准的上限由误差预算定,换哪一种滤波器都改不动它。"
        )
    }
}

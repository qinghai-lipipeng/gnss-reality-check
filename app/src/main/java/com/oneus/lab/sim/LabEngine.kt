package com.oneus.lab.sim

import com.oneus.lab.solve.*
import com.oneus.lab.fusion.*
import kotlin.math.*

/** 一次定位的结果快照 */
data class ShotResult(
    val scenario: Scenario,
    val obs: List<Measurement>,
    val solution: Solution?,
    val errorM: Double,
    val truthEcef: Vec3,
    val verticalErrorM: Double = 0.0
) {
    val numSats: Int get() = obs.size
    val ok: Boolean get() = solution != null && solution.ok
    fun satsOf(sys: GnssSystem) = obs.filter { it.system == sys }

    /**
     * 水平误差(距真值,m)。**解不出来时必须是 null。**
     *
     * UI 一律走这个属性,不要用 [errorM]:后者在无解时被 [shot] 吞成了 0.0,
     * 而 0.0 是一个"误差恰好为零"的合法数值,会被配色判成绿色、被 `passed` 判成达标。
     * 「没有解」和「解得非常好」在业务上必须能被区分开,类型上就得能区分。
     */
    val horizontalError: Double? get() = solution?.horizontalError(truthEcef)

    /** 同上,垂直分量。 */
    val verticalError: Double? get() = solution?.verticalError(truthEcef)

    /**
     * 挑战判定:**必须先有解**,而且解本身要可信([Solution.ok])。
     *
     * 曾经写成界面里的 `shot.errorM <= target`。桥下 / 室内一颗星都收不到,
     * errorM 是被吞出来的 0.0,于是 `0.0 <= target` 成立 —— 一个必然失败的场景被判达标,
     * 界面弹出「过了。」。用户选最难的那个,反而通关了。
     *
     * 规则收在这里只有一份,界面与测试共用,不会再各算各的。
     */
    fun passes(targetM: Double): Boolean {
        val s = solution ?: return false
        if (!s.ok) return false
        return s.horizontalError(truthEcef) <= targetM
    }
}

/** 表格里的一行。na = 数据不可得(例如可见卫星不足 4 颗,根本没有解) */
data class Row(
    val label: String,
    val value: Double,
    val note: String = "",
    val highlight: Boolean = false,
    val na: Boolean = false
)

/** 误差预算瀑布图的一行 */
data class BudgetLine(
    val label: String,
    val allOn: Double,
    val withoutIt: Double,
    val shortNote: String = ""
) {
    /** 边际贡献(方差合成意义) */
    val marginal: Double get() = sqrt(max(allOn * allOn - withoutIt * withoutIt, 0.0))
}

/** 一条滤波轨迹:逐历元水平误差 */
data class FilterTrace(
    val name: String,
    val short: String,
    val points: List<Double>,     // 每历元水平误差 (m)
    val finalError: Double,       // 后 1/3 历元均值
    val scatter: Double           // 后 1/3 历元的标准差 —— 稳定性
)

/**
 * 实验引擎:把仿真 → 解算 → 融合串起来,产出各实验需要的数据。
 *
 * 所有方法都接受 seed,保证同场景 A/B 可严格对照。
 */
object LabEngine {

    const val DEFAULT_TIME = 43_200.0     // GPS 周内秒,当地正午附近
    const val DEFAULT_SEED = 20240101L

    // ——————————————————————————————
    // 基础单次定位
    // ——————————————————————————————

    fun shot(
        scenario: Scenario = Scenarios.OPEN,
        systems: Set<GnssSystem> = GnssSystem.entries.toSet(),
        budget: ErrorBudget = ErrorBudget(),
        time: Double = DEFAULT_TIME,
        seed: Long = DEFAULT_SEED,
        maxSats: Int = 0,
        solveConfig: SolveConfig? = null
    ): ShotResult {
        val obs0 = Simulator().generate(scenario, systems, budget, time, seed)
        val obs = if (maxSats in 1..obs0.size - 1)
            obs0.sortedByDescending { it.elDeg }.take(maxSats) else obs0
        val cfg = solveConfig ?: SolveConfig(
            iono = IonoCorrector.of(budget.ionoHandling, budget.freqPlan),
            relativityCorrected = budget.relativityEnabled,
            tropoCorrected = budget.tropoEnabled
        )
        val sol = LsSolver(cfg).solve(obs, scenario, time)
        val truth = scenario.site.toEcef()
        return ShotResult(
            scenario = scenario, obs = obs, solution = sol,
            // 注意:这两个字段在无解时是 0.0,那是**历史遗留**,不是"误差为零"。
            // 引擎内部的表格/统计(Row、avgError)只喂有解的场景,不受影响;
            // 凡是会显示给用户的地方一律用 ShotResult.horizontalError / verticalError,它们是 null。
            errorM = sol?.horizontalError(truth) ?: 0.0,
            truthEcef = truth,
            verticalErrorM = sol?.verticalError(truth) ?: 0.0
        )
    }

    // ——————————————————————————————
    // 实验 1:光速尺
    // ——————————————————————————————

    /**
     * 授时的两种形态,结果天差地别:
     *
     * ① 接收机**共同**钟差 —— 所有伪距等量偏移,而 c·δt_r 正是解算的四个未知量之一,
     *    这份共模偏移被完整吸收,位置几乎不动(只有 0.x m 的条件数放大)。
     *
     * ② **卫星之间**的钟差不一致 —— 每一颗星各偏各的,共模假设失效,
     *    直接按 0.3 m/ns 打进位置。
     *
     * 所以"授时精度决定定位精度"这句话,准确的说法是:
     * **时间基准的彼此一致,比某个钟的绝对快慢重要得多。**
     */
    fun clockSweep(
        scenario: Scenario = Scenarios.OPEN,
        time: Double = DEFAULT_TIME
    ): List<Row> {
        val base = cleanBase()
        val out = ArrayList<Row>()

        // ① 共同钟差:位置几乎不动
        for (ns in listOf(0.0, 50.0, 1000.0)) {
            val r = shot(scenario, GnssSystem.entries.toSet(), base.copy(receiverClockNs = ns), time)
            out.add(
                Row(
                    label = "接收机共同钟差 ${LabEngine.fmtNs(ns)}",
                    value = r.errorM,
                    note = "c·δt = ${LabEngine.fmtNum(ns * 0.299792458)} m",
                    highlight = ns == 1000.0
                )
            )
        }
        // ② 卫星间钟差不一致:直接进位置
        for (ns in listOf(0.0, 1.0, 5.0, 20.0, 50.0)) {
            val meters = ns * 0.299792458
            val r = shot(
                scenario, GnssSystem.entries.toSet(),
                base.copy(satClockErrorM = meters), time
            )
            out.add(
                Row(
                    label = "卫星间钟差 ±${LabEngine.fmtNs(ns)}",
                    value = r.errorM,
                    note = "等效 ${LabEngine.fmtNum(meters)} m",
                    highlight = ns == 50.0
                )
            )
        }
        return out
    }

    /** 实验 1 的纯换算尺:光在给定时间跑多远 */
    fun clockRulerMeters(ns: Double): Double = ns * 0.299792458

    /** 除钟差外的"干净"误差预算 */
    fun cleanBase() = ErrorBudget(
        receiverClockNs = 0.0, satClockErrorM = 0.0, relativityEnabled = true,
        ionoHandling = IonoHandling.TEC_MODEL, tropoEnabled = true,
        ephemerisErrorM = 0.0, multipathRemoved = true
    )

    // ——————————————————————————————
    // 实验 2:几何
    // ——————————————————————————————

    fun geometrySweep(
        scenario: Scenario = Scenarios.OPEN,
        time: Double = DEFAULT_TIME
    ): List<Row> {
        val base = ErrorBudget(
            receiverClockNs = 0.0, satClockErrorM = 0.0, relativityEnabled = true,
            ionoHandling = IonoHandling.TEC_MODEL, tropoEnabled = true,
            ephemerisErrorM = 0.0, multipathRemoved = true
        )
        val out = ArrayList<Row>()
        for (n in 4..12) {
            val r = shot(scenario, GnssSystem.entries.toSet(), base, time, maxSats = n)
            if (r.solution == null) continue
            out.add(
                Row(
                    label = "$n 颗星",
                    value = r.errorM,
                    note = "HDOP ${fmtNum(r.solution.hdop)}",
                    highlight = n == 4
                )
            )
        }
        return out
    }

    // ——————————————————————————————
    // 实验 3:星座之争
    // ——————————————————————————————

    private val SYSTEM_SETS = listOf(
        "只用 GPS" to setOf(GnssSystem.GPS),
        "只用北斗" to setOf(GnssSystem.BDS),
        "只用伽利略" to setOf(GnssSystem.GAL),
        "只用格洛纳斯" to setOf(GnssSystem.GLO),
        "GPS + 北斗" to setOf(GnssSystem.GPS, GnssSystem.BDS),
        "四系统全开" to GnssSystem.entries.toSet()
    )

    /**
     * 多历元平均的水平误差。
     *
     * 凡是"比较两个方案差多少"的场合都必须走这里:
     * 单历元的随机波动在城市里就有 ±5 米,而要比较的差异常常只有零点几米,
     * 不平均就等于拿噪声当结论。
     */
    fun avgError(
        scenario: Scenario,
        systems: Set<GnssSystem>,
        budget: ErrorBudget,
        time: Double = DEFAULT_TIME,
        samples: Int = 9
    ): Pair<Double?, Int> {
        val errs = ArrayList<Double>()
        var sats = 0
        for (k in 0 until samples) {
            val r = shot(scenario, systems, budget, time + k * 30.0, DEFAULT_SEED + k * 7919L)
            sats = max(sats, r.numSats)
            if (r.ok) errs.add(r.errorM)
        }
        // 一颗都解不出来时返回 null,**不能**返回 0.0 ——
        // 0.0 会被 max/min 当成一个"误差为零"的真实数据点,直接把结论带偏。
        return Pair(if (errs.isEmpty()) null else errs.average(), sats)
    }

    /**
     * 星座对比。
     *
     * 关键:**必须多历元平均**。
     * 城市里多路径有 20 米量级,单历元的随机波动就有 ±5 米,
     * 而换星座真正的差异只有零点几米 —— 单次采样根本看不见,
     * 反而会让人误以为"换一个系统能差好几米"。
     * 这和结论本身是矛盾的,必须靠平均把它压下去。
     */
    fun constellationSweep(
        scenario: Scenario = Scenarios.OPEN,
        budget: ErrorBudget = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL),
        time: Double = DEFAULT_TIME,
        samples: Int = 9
    ): List<Row> = SYSTEM_SETS.map { (label, sys) ->
        var errSum = 0.0
        var okCount = 0
        var sats = 0
        for (k in 0 until samples) {
            val r = shot(
                scenario, sys, budget,
                time + k * 30.0, DEFAULT_SEED + k * 7919L
            )
            sats = max(sats, r.numSats)
            if (r.ok) { errSum += r.errorM; okCount++ }
        }
        // 一半以上历元解不出来,就如实标成不可用
        val usable = okCount * 2 >= samples
        Row(
            label = label,
            value = if (usable) errSum / okCount else 0.0,
            note = when {
                !usable -> "最多 $sats 颗可见 · 不足 4 颗,解不出"
                okCount < samples -> "$samples 次平均(部分历元解不出)"
                else -> "$samples 次采样平均"
            },
            highlight = label == "四系统全开" && usable,
            na = !usable
        )
    }

    /**
     * 同一个解算器、同一片天空,只改"量"的品质。
     * 换星座 vs 改大气 vs 改时间一致性 —— 三条路径放在一张表里比。
     */
    fun clockContrast(
        scenario: Scenario = Scenarios.CITY,
        time: Double = DEFAULT_TIME,
        samples: Int = 9
    ): List<Row> {
        val base = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        fun err(bb: ErrorBudget) =
            avgError(scenario, GnssSystem.entries.toSet(), bb, time, samples).first ?: 0.0

        val ref = err(base)
        return listOf(
            Row("全开(基准)", ref, "$samples 次平均"),
            Row(
                "只开 GPS 一家",
                avgError(scenario, setOf(GnssSystem.GPS), base, time, samples).first ?: 0.0,
                "换星座"
            ),
            Row("电离层不改正", err(base.copy(ionoHandling = IonoHandling.NONE)), "改大气"),
            Row(
                "卫星钟差 ±20 ns",
                err(base.copy(satClockErrorM = 20.0 * 0.299792458)),
                "改时间一致性"
            ),
            Row("不做相对论修正", err(base.copy(relativityEnabled = false)), "改星上时间")
        )
    }

    // ——————————————————————————————
    // 实验 4:多给了你料(大气 + 频段)
    // ——————————————————————————————

    fun ionoSweep(
        scenario: Scenario = Scenarios.CITY,
        tec: Double = scenario.tec,
        time: Double = DEFAULT_TIME
    ): List<Row> {
        val sc = scenario.copy(tec = tec)
        val base = ErrorBudget(
            receiverClockNs = 0.0, satClockErrorM = 0.0, relativityEnabled = true,
            tropoEnabled = true, ephemerisErrorM = 0.0, multipathRemoved = true
        )
        val out = ArrayList<Row>()
        out.add(Row("单频 · 不改正", shot(sc, GnssSystem.entries.toSet(), base.copy(ionoHandling = IonoHandling.NONE), time).errorM, "TEC ${fmtTec(tec)}", true))
        out.add(Row("单频 + TEC 模型", shot(sc, GnssSystem.entries.toSet(), base.copy(ionoHandling = IonoHandling.TEC_MODEL), time).errorM, "模型有偏差"))
        out.add(Row("单频 + 广播改正", shot(sc, GnssSystem.entries.toSet(), base.copy(ionoHandling = IonoHandling.KLOBUCHAR), time).errorM, "Klobuchar"))
        out.add(
            Row("双频 L1+L5", shot(sc, GnssSystem.entries.toSet(), base.copy(ionoHandling = IonoHandling.DUAL_FREQ, freqPlan = FreqPlan.L1_L5), time).errorM, "无组合", true)
        )
        out.add(
            Row("双频 L1+L2", shot(sc, GnssSystem.entries.toSet(), base.copy(ionoHandling = IonoHandling.DUAL_FREQ, freqPlan = FreqPlan.L1_L2), time).errorM, "噪声放大更多")
        )
        return out
    }

    /** 相对论偏心修正关掉会怎样 */
    fun relativityContrast(
        scenario: Scenario = Scenarios.OPEN,
        time: Double = DEFAULT_TIME
    ): List<Row> {
        val base = ErrorBudget(
            receiverClockNs = 0.0, satClockErrorM = 0.0,
            ionoHandling = IonoHandling.TEC_MODEL, tropoEnabled = true,
            ephemerisErrorM = 0.0, multipathRemoved = true
        )
        val withRel = shot(scenario, GnssSystem.entries.toSet(), base.copy(relativityEnabled = true), time)
        val noRel = shot(scenario, GnssSystem.entries.toSet(), base.copy(relativityEnabled = false), time)
        // 统计一下该项本身的量级
        var maxRelNs = 0.0
        val sats = Constellations.satellitesOf(GnssSystem.entries.toSet())
        for (k in 0 until 600) {
            val t = time + k * 30.0
            for (s in sats) {
                if (s.system != GnssSystem.GPS) continue
                maxRelNs = max(maxRelNs, abs(s.relativityClockOffsetSec(t) * 1e9))
            }
        }
        return listOf(
            Row("做了相对论修正", withRel.errorM, "基准"),
            Row("不做", noRel.errorM, "该项量级 ±${maxRelNs.toInt()} ns", true)
        )
    }

    // ——————————————————————————————
    // 实验 5:城市蓝点的真相
    // ——————————————————————————————

    fun hybridSweep(
        scenario: Scenario = Scenarios.CITY,
        budget: ErrorBudget = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL),
        time: Double = DEFAULT_TIME
    ): List<Row> {
        val hp = HybridPositioner(scenario)
        val r = shot(scenario, GnssSystem.entries.toSet(), budget, time)
        val cfgs = listOf(
            "只用卫星" to AssistConfig(gnss = true),
            "卫星 + WiFi" to AssistConfig(gnss = true, wifi = true),
            "卫星 + WiFi + 基站" to AssistConfig(gnss = true, wifi = true, cell = true),
            "再加道路匹配" to AssistConfig(gnss = true, wifi = true, cell = true, mapMatch = true)
        )
        return cfgs.map { (label, cfg) ->
            val res = hp.locate(r.solution, cfg)
            Row(label, res.errorM, if (res.notes.isEmpty()) "" else res.notes.first(), highlight = label == "只用卫星")
        }
    }

    /** 同一套辅助手段,换到桥下 —— 全线崩溃 */
    fun hybridUnderBridge(
        time: Double = DEFAULT_TIME
    ): List<Row> {
        val budget = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        val city = shot(Scenarios.CITY, GnssSystem.entries.toSet(), budget, time)
        val bridge = shot(Scenarios.UNDER_BRIDGE, GnssSystem.entries.toSet(), budget, time)
        val hpCity = HybridPositioner(Scenarios.CITY)
        val hpBridge = HybridPositioner(Scenarios.UNDER_BRIDGE)
        val full = AssistConfig(gnss = true, wifi = true, cell = true, mapMatch = true)
        val out = ArrayList<Row>()
        out.add(Row("街道 · 只用卫星", city.errorM, "${city.numSats} 颗可见"))
        out.add(Row("街道 · 全套辅助", hpCity.locate(city.solution, full).errorM, "WiFi + 基站 + 贴路", true))
        out.add(Row("桥下 · 只用卫星", bridge.errorM, "${bridge.numSats} 颗可见", true))
        out.add(Row("桥下 · 全套辅助", hpBridge.locate(bridge.solution, full).errorM, "一样失效", true))
        return out
    }

    // ——————————————————————————————
    // 实验 6:滤波器之争
    // ——————————————————————————————

    /**
     * 四种滤波器同场对比。
     *
     * 性能上有三处必须注意,这里都做了处理:
     *
     * 1. **观测只生成一次**。四个滤波器吃的是同一批数据 —— 真实接收机也只收到一次信号。
     *    之前每个历元重复 `Simulator().generate` 四次,等于把 Kepler 传播做了四遍。
     *
     * 2. **冷启动只做一次**。第一个历元做全球粗搜找到正确解,之后每历元用上一历元的解
     *    作为初值(真实接收机的差分定位就是这样)。避免每个历元都重跑 600 多点的粗搜。
     *
     * 3. **OLS 与 WLS 真的分开算**。它们共用一份观测,但权重不同,结果本来就该不同。
     */
    fun filterRun(
        scenario: Scenario = Scenarios.OPEN,
        budget: ErrorBudget = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL),
        epochs: Int = 40,
        time: Double = DEFAULT_TIME,
        particleCount: Int = 1000
    ): List<FilterTrace> {
        val truth = scenario.site.toEcef()
        val cfg = SolveConfig(
            iono = IonoCorrector.of(budget.ionoHandling, budget.freqPlan),
            relativityCorrected = budget.relativityEnabled,
            tropoCorrected = budget.tropoEnabled
        )

        val ols = LsFilter(weighted = false, name = "普通最小二乘", short = "OLS")
        val wls = LsFilter(weighted = true, name = "加权最小二乘", short = "WLS")
        val ekf = EkfFilter()
        val pf = ParticleFilter(particleCount = particleCount)

        val olsPts = ArrayList<Double>(epochs)
        val wlsPts = ArrayList<Double>(epochs)
        val ekfPts = ArrayList<Double>(epochs)
        val pfPts = ArrayList<Double>(epochs)

        var warm: Vec3? = null          // 上一历元的解,给 LS 当初值,省掉粗搜
        var seeded = false              // EKF / PF 是否已播种

        for (e in 0 until epochs) {
            val cor = Obs.correct(
                Simulator().generate(
                    scenario, GnssSystem.entries.toSet(), budget,
                    time + e, DEFAULT_SEED + e * 7919L
                ),
                scenario, cfg.iono, time + e, cfg.relativityCorrected, cfg.tropoCorrected
            )

            val a = ols.run(cor, e, warm)
            val b = wls.run(cor, e, warm ?: a?.pos)
            warm = b?.pos ?: a?.pos ?: warm

            if (!seeded) {
                val seed = b?.pos ?: a?.pos
                if (seed != null) {
                    ekf.reset(seed); pf.reset(seed); seeded = true
                }
            }

            olsPts.add(a?.let { horizontalErr(it.pos, truth) } ?: Double.NaN)
            wlsPts.add(b?.let { horizontalErr(it.pos, truth) } ?: Double.NaN)
            ekfPts.add(ekf.step(cor, e)?.let { horizontalErr(it.pos, truth) } ?: Double.NaN)
            pfPts.add(pf.step(cor, e)?.let { horizontalErr(it.pos, truth) } ?: Double.NaN)
        }

        fun trace(f: PositionFilter, pts: List<Double>) = FilterTrace(
            f.name, f.short, pts,
            pts.drop(epochs * 2 / 3).filter { !it.isNaN() }.let { t ->
                if (t.isEmpty()) 0.0 else t.average()
            },
            pts.drop(epochs * 2 / 3).filter { !it.isNaN() }.let { t ->
                if (t.size < 2) 0.0 else {
                    val m = t.average(); sqrt(t.sumOf { (it - m) * (it - m) } / t.size)
                }
            }
        )
        return listOf(trace(ols, olsPts), trace(wls, wlsPts), trace(ekf, ekfPts), trace(pf, pfPts))
    }

    // ——————————————————————————————
    // 实验 7:差分与 PPP —— 精度的真正来源是"喂了什么信息"
    // ——————————————————————————————

    /**
     * RTK / PPP 不是更高级的解算器,是**注入了更好的外部约束**。
     * 差分改正来自基准站:卫星钟差、轨道误差、大气延迟一起被差掉。
     * PPP 用精密轨道钟差产品 + 对流层估计,残下多路径和湿延迟。
     * 两者都还跑最小二乘 —— 同一套数学。
     */
    fun precisionSweep(
        scenario: Scenario = Scenarios.CITY,
        time: Double = DEFAULT_TIME,
        samples: Int = 9
    ): List<Row> {
        val sp = ErrorBudget(ionoHandling = IonoHandling.NONE)
        val dual = ErrorBudget(ionoHandling = IonoHandling.DUAL_FREQ)
        fun avg(b: ErrorBudget) =
            avgError(scenario, GnssSystem.entries.toSet(), b, time, samples).first ?: 0.0
        return listOf(
            Row("单点定位", avg(sp), "广播星历 + 单频", true),
            Row("双频单点", avg(dual), "消了一阶电离层"),
            Row("PPP(收敛后)", avg(dual.copy(satClockErrorM = 0.0, ephemerisErrorM = 0.05)),
                "精密轨道钟差 + 三频", true),
            Row("差分 / RTK",
                avg(dual.copy(satClockErrorM = 0.0, ephemerisErrorM = 0.0, multipathRemoved = true)),
                "基准站改正几乎差掉一切", true)
        )
    }

    /** PPP 收敛曲线:静态情况下历元平均把噪声压下去,但偏差压不下去 */
    fun pppConvergence(
        scenario: Scenario = Scenarios.CITY,
        epochs: Int = 30,
        time: Double = DEFAULT_TIME
    ): List<Double> {
        val b = ErrorBudget(
            ionoHandling = IonoHandling.DUAL_FREQ,
            satClockErrorM = 0.0, ephemerisErrorM = 0.05,
            multipathRemoved = false
        )
        val truth = scenario.site.toEcef()
        val out = ArrayList<Double>(epochs)
        var sumLat = 0.0
        var sumLon = 0.0
        var n = 0
        for (e in 0 until epochs) {
            val r = shot(scenario, GnssSystem.entries.toSet(), b, time + e, DEFAULT_SEED + e * 7919L)
            val sol = r.solution
            if (sol == null) { out.add(Double.NaN); continue }
            val g = sol.ecef.toGeo()
            sumLat += g.latDeg; sumLon += g.lonDeg; n++
            val averaged = Geo(sumLat / n, sumLon / n, g.altM).toEcef()
            out.add(horizontalErr(averaged, truth))
        }
        return out
    }

    private fun horizontalErr(a: Vec3, b: Vec3): Double {
        val ga = a.toGeo(); val gb = b.toGeo()
        return hypot(
            (ga.latDeg - gb.latDeg) * 111_320.0,
            wrap180(ga.lonDeg - gb.lonDeg) * 111_320.0 * cos(Math.toRadians(gb.latDeg))
        )
    }

    // ——————————————————————————————
    // 实验 7 / 收尾:误差预算瀑布
    // ——————————————————————————————

    /**
     * 误差预算瀑布图(收尾页的总结)。
     *
     * 和星座对比同样的理由:**必须多历元平均**。
     * 城市里单历元的随机波动就有好几米,而各项真正的影响常常只有零点几米,
     * 单次采样会让整张总结图被噪声主导 —— 那会让 App 的压轴结论失去说服力。
     */
    fun budgetBreakdown(
        scenario: Scenario = Scenarios.CITY,
        time: Double = DEFAULT_TIME,
        samples: Int = 9
    ): List<BudgetLine> {
        val all = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        val allSys = GnssSystem.entries.toSet()

        fun avg(b: ErrorBudget): Double =
            avgError(scenario, allSys, b, time, samples).first ?: 0.0

        val base = avg(all)
        fun line(label: String, off: ErrorBudget, note: String) =
            BudgetLine(label, base, avg(off), note)

        return listOf(
            line("接收机钟差 (10 ns)", all.copy(receiverClockNs = 0.0), "授时"),
            line("电离层 (不改正)", all.copy(ionoHandling = IonoHandling.DUAL_FREQ), "大气"),
            line("多路径", all.copy(multipathRemoved = true), "环境"),
            line("卫星钟差残余", all.copy(satClockErrorM = 0.0), "星上原子钟"),
            line("星历轨道误差", all.copy(ephemerisErrorM = 0.0), "星上"),
            line("对流层模型", all.copy(tropoEnabled = false), "大气")
        )
    }

    // ——————————————————————————————
    // 格式化
    // ——————————————————————————————

    fun fmtNum(v: Double): String = when {
        v >= 100 -> v.toInt().toString()
        v >= 10 -> String.format("%.1f", v)
        v >= 1 -> String.format("%.2f", v)
        v >= 0.01 -> String.format("%.3f", v)
        else -> String.format("%.4f", v)
    }

    fun fmtM(v: Double): String = "${fmtNum(v)} m"

    fun fmtNs(ns: Double): String = when {
        ns == 0.0 -> "0"
        abs(ns) >= 1000 -> "${fmtNum(ns / 1000)} μs"
        abs(ns) >= 1 -> "${fmtNum(ns)} ns"
        else -> "${fmtNum(ns * 1000)} ps"
    }

    fun fmtTec(tec: Double): String = "${fmtNum(tec / 1e16)} TECU"
}

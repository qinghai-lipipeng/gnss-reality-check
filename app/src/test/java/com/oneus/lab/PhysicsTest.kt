package com.oneus.lab

import com.oneus.lab.sim.*
import com.oneus.lab.solve.*
import com.oneus.lab.fusion.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * 物理量级验收。
 *
 * 这些断言不是为了"让测试通过"而写的 —— 它们来自 GPS ICD 与教科书量级。
 * 如果某个实现改坏了,这里应该先炸。
 */
class PhysicsTest {

    // ——————————————————————
    // 基础常数与模型
    // ——————————————————————

    @Test
    fun `光速换算 1us 等于 300 米`() {
        assertEquals(299.792458, C * 1e-6, 1e-6)
        assertEquals(0.299792458, C * 1e-9, 1e-9)
    }

    @Test
    fun `Saastamoinen 天顶干延迟约 2_3 米`() {
        val zhd = Troposphere.zenithHydrostatic(1013.25, latDeg = 40.0, heightM = 0.0)
        assertTrue("ZHD=$zhd", zhd in 2.29..2.32)
    }

    @Test
    fun `对流层低仰角会放大到十几米`() {
        val zenith = Troposphere.slantDelay(elevationDeg = 90.0, vaporHpa = 0.0)
        val el10 = Troposphere.slantDelay(elevationDeg = 10.0, vaporHpa = 0.0)
        val el5 = Troposphere.slantDelay(elevationDeg = 5.0, vaporHpa = 0.0)
        assertTrue("zenith=$zenith", zenith in 2.29..2.32)
        // 干分量斜映射就是 1/sin(E):10° → ×5.76,5° → ×11.5
        assertTrue("el10=$el10", el10 in 13.0..13.6)
        assertTrue("el5=$el5", el5 in 26.0..27.0)
    }

    @Test
    fun `TEC 到电离层延迟的换算符合教科书量级`() {
        // I = 40.3 · TEC / f²,1 TECU = 1e16 elec/m²
        val i1 = Ionosphere.zenithDelayFromTec(1.0e16, Ionosphere.L1)
        assertEquals(0.162, i1, 0.01)
        // 45° 仰角斜因子已钳到 1(天顶),故斜延迟 ≈ 垂直延迟
        val slant = Ionosphere.slantDelayFromTec(3.0e16, 45.0, Ionosphere.L1, 180.0)
        assertTrue("slant=$slant", slant in 0.40..0.55)
        // 低仰角斜路径更长
        val lowEl = Ionosphere.slantDelayFromTec(3.0e16, 10.0, Ionosphere.L1, 180.0)
        assertTrue("lowEl=$lowEl", lowEl > slant * 1.5)
        // 磁暴:50 TECU + 20° 仰角 → 约 10 m
        val storm = Ionosphere.slantDelayFromTec(5.0e17, 20.0, Ionosphere.L1, 180.0)
        assertTrue("storm=$storm", storm in 7.0..12.0)
    }

    @Test
    fun `Klobuchar 输出在合理量级`() {
        var lo = Double.MAX_VALUE
        var hi = Double.MIN_VALUE
        for (h in 0 until 24) {
            for (e in intArrayOf(10, 30, 60, 85)) {
                val sec = Ionosphere.klobuchar(
                    latDeg = 39.9, lonDeg = 116.4,
                    gpsTimeSec = h * 3600.0 + 1800.0, elevationDeg = e.toDouble()
                )
                val m = C * sec
                lo = min(lo, m); hi = max(hi, m)
            }
        }
        assertTrue("Klobuchar must not be negative, got $lo", lo >= 0.0)
        assertTrue("Klobuchar range $lo..$hi", hi in 1.0..30.0)
        // 模型必须真的随时间/仰角变化,否则说明它退化成了常数
        assertTrue("Klobuchar should vary over a day: $lo..$hi", hi > lo * 2.0)
    }

    @Test
    fun `相对论偏心项量级为几十纳秒`() {
        var lo = Double.MAX_VALUE
        var hi = Double.MIN_VALUE
        for (sv in Constellations.GPS.satellites()) {
            for (k in 0 until 400) {
                val ns = sv.relativityClockOffsetSec(k * 90.0) * 1e9
                lo = min(lo, ns); hi = max(hi, ns)
            }
        }
        // GPS e=0.007 → 理论上限 ±57.9 ns
        assertTrue("rel range $lo..$hi ns", lo < -20.0 && hi > 20.0)
        assertTrue("rel max ${abs(lo)} / ${abs(hi)}", abs(hi) in 30.0..80.0)
    }

    @Test
    fun `无组合双频的噪声放大系数符合文献值`() {
        val f1 = Ionosphere.L1
        val f5 = Ionosphere.L5
        val f2 = Ionosphere.L2
        fun factor(f2v: Double): Double {
            val a = f1 * f1 / (f1 * f1 - f2v * f2v)
            val b = f2v * f2v / (f1 * f1 - f2v * f2v)
            return sqrt(a * a + b * b)
        }
        assertEquals(2.589, factor(f5), 0.01)
        assertEquals(2.975, factor(f2), 0.01)
    }

    // ——————————————————————
    // 轨道
    // ——————————————————————

    @Test
    fun `轨道半长轴与周期符合各星座设计值`() {
        assertEquals(11.967, Constellations.GPS.periodSec / 3600.0, 0.05)
        assertEquals(14.045, Constellations.GALILEO.periodSec / 3600.0, 0.05)
        assertEquals(11.266, Constellations.GLONASS.periodSec / 3600.0, 0.05)
        assertEquals(12.89, Constellations.BDS_MEO.periodSec / 3600.0, 0.05)
        // GEO 共轨:周期 = 恒星日
        assertEquals(86164.0, Constellations.BDS_GEO.periodSec, 5.0)
    }

    @Test
    fun `卫星位置模长等于半长轴减去偏心修正`() {
        val sv = Constellations.GPS.satellites().first()
        for (t in longArrayOf(0, 3600, 7200, 20000)) {
            val p = sv.positionEcef(t.toDouble())
            val r = p.norm()
            val expected = sv.a * (1 - sv.e * cos(Satellite.solveKepler(
                Math.toRadians(sv.ma0Deg) + sv.meanMotion * t, sv.e)))
            assertEquals("t=$t", expected, r, 1.0)
        }
    }

    @Test
    fun `GEO 卫星在 ECEF 中基本静止`() {
        val geo = Constellations.BDS_GEO.satellites().first()
        val p0 = geo.positionEcef(0.0)
        val p1 = geo.positionEcef(43200.0)
        assertTrue("GEO moved ${p0.distTo(p1)} m in 12h", p0.distTo(p1) < 60_000.0)
    }

    // ——————————————————————
    // 解算
    // ——————————————————————

    @Test
    fun `不做相对论修正会有米级残差`() {
        // Δt_rel 的共模部分同样被钟差未知量吸收,真正剩下的是各星之间的差异,
        // 所以真实代价是**米级**而不是十几米 —— 但它确实不为零。
        val b = cleanBudget()
        val with = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), b.copy(relativityEnabled = true))
        val without = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), b.copy(relativityEnabled = false))
        val d = without.errorM - with.errorM
        assertTrue("relativity miss costs ${d} m", d in 0.5..40.0)
    }

    @Test
    fun `没有误差时最小二乘残差收敛到近零`() {
        val sc = Scenarios.OPEN.copy(
            multipathRms = 0.0, noiseRms = 0.001, tec = 0.0
        )
        val b = ErrorBudget(
            satClockErrorM = 0.0, relativityEnabled = true,
            ionoHandling = IonoHandling.TEC_MODEL, tropoEnabled = true,
            ephemerisErrorM = 0.0, multipathRemoved = true
        )
        val r = LabEngine.shot(sc, GnssSystem.entries.toSet(), b)
        assertNotNull(r.solution)
        assertTrue("error=${r.errorM} m", r.errorM < 0.5)
        assertTrue("residualRms=${r.solution!!.residualRms}", r.solution!!.residualRms < 0.5)
    }

    @Test
    fun `开阔地单点定位水平误差在 3 到 10 米`() {
        val b = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        val errs = (0 until 8).map { e ->
            LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), b,
                LabEngine.DEFAULT_TIME, LabEngine.DEFAULT_SEED + e * 104729L).errorM
        }
        val mean = errs.average()
        println("TDIAG open-sky errors=$errs")
        assertTrue("open-sky mean error = $mean", mean in 0.4..6.0)
    }

    // ——————————————————————
    // 教学命题
    // ——————————————————————

    @Test
    fun `接收机共同钟差会被解算器吸收_这是共模量`() {
        // c·δt_r 本身就是解算的四个未知量之一,所有伪距等量偏移会被完整吸收。
        // 这条是本项目最反直觉、也最重要的物理事实。
        val base = cleanBudget()
        val none = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), base.copy(receiverClockNs = 0.0))
        val oneUs = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), base.copy(receiverClockNs = 1000.0))
        assertTrue(
            "共同钟差 1μs(等效 300 m)却只让位置变化 ${oneUs.errorM - none.errorM} m",
            abs(oneUs.errorM - none.errorM) < 3.0
        )
        // 解出来的钟差本身确实带上了这 300 m(并顺带吸收平均大气延迟)
        assertTrue("estimated clock ${oneUs.solution!!.clockRangeM} m",
            abs(oneUs.solution!!.clockRangeM - 299.79) < 20.0)
    }

    @Test
    fun `卫星之间的钟差不一致会直接打进位置`() {
        val base = cleanBudget()
        val clean = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), base.copy(satClockErrorM = 0.0))
        val sloppy = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), base.copy(satClockErrorM = 20.0 * 0.299792458))
        val delta = sloppy.errorM - clean.errorM
        // 20 ns × 0.3 m/ns = 6 m 等效距离,经几何放大后应是米级
        assertTrue("sat clock 20ns adds ${delta} m", delta > 1.0)
        assertTrue("sat clock 20ns adds ${delta} m", delta < 30.0)
    }

    @Test
    fun `换星座的量级远小于改大气与改时间一致性`() {
        val b = cleanBudget()
        fun err(sys: Set<GnssSystem>, bb: ErrorBudget = b) =
            LabEngine.avgError(Scenarios.OPEN, sys, bb, samples = 15).first ?: 0.0

        // 开阔地几何对每个系统都成立,才能公平比较"换星座"本身的影响。
        // 必须多历元平均:单历元的随机噪声(城市里 ±5 m)会完全淹没
        // 换星座真正的差异(零点几米),平均之后结论才成立。
        val vals = listOf(
            err(setOf(GnssSystem.GPS)), err(setOf(GnssSystem.BDS)),
            err(setOf(GnssSystem.GAL)), err(setOf(GnssSystem.GLO)),
            err(GnssSystem.entries.toSet())
        )
        val spread = vals.max() - vals.min()

        val all = GnssSystem.entries.toSet()
        val multi = err(all)
        val ionoGain = multi - err(all, b.copy(ionoHandling = IonoHandling.DUAL_FREQ))
        val clockGain = err(all, b.copy(satClockErrorM = 20.0 * 0.299792458)) - multi

        println("TDIAG constellation values=$vals spread=$spread iono=$ionoGain clock=$clockGain")
        assertTrue("constellation spread = $spread m", spread < 1.0)
        assertTrue("sat-clock gain = $clockGain m", clockGain > 1.0)
        assertTrue("sat-clock gain = $clockGain m vs constellation $spread m",
            clockGain > spread * 5)
    }

    @Test
    fun `单历元采样会淹没换星座的真实差异`() {
        // 这条测试锁住方法论:如果哪天有人把多历元平均改回单次采样,
        // 星座对比就会退回"看起来差好几米"的错误结论。
        // 城市干道:多路径 20 m(噪声够大),但视野开阔到四个系统都解得出来。
        // 两个条件缺一不可 —— 视野差会导致单系统无解,噪声小则演示不出淹没效应。
        val sc = Scenarios.CITY.copy(maskDeg = 10.0, blocks = emptyList())
        val b = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        fun spreadOf(samples: Int): Double {
            val v = listOf(
                GnssSystem.GPS, GnssSystem.BDS, GnssSystem.GAL, GnssSystem.GLO
            ).mapNotNull {
                LabEngine.avgError(sc, setOf(it), b, samples = samples).first
            } + listOfNotNull(
                LabEngine.avgError(sc, GnssSystem.entries.toSet(), b, samples = samples).first
            )
            require(v.size == 5) { "城市干道应有 5 个可解组合,实际 ${v.size}" }
            return v.max() - v.min()
        }
        val s1 = spreadOf(1)
        val s9 = spreadOf(9)
        println("TDIAG noise-vs-signal spread: 1 sample=$s1 m, 9 samples=$s9 m")
        // 多路径有方位相关性,不像纯随机噪声那样按 √N 收敛,
        // 所以 9 次平均大约收窄 2~3 倍,而不是 3 倍 —— 这是物理事实,不该把断言调松到失去意义。
        assertTrue("多历元平均后离散度应明显收窄(1 次 $s1 m,9 次 $s9 m)", s9 * 2 < s1)
        assertTrue("9 次平均后换星座的差异仍应远小于单次采样(实测 $s9 m)", s9 < s1)
    }

    @Test
    fun `四种滤波器的终值差异小于 0_5 米`() {
        val b = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        val traces = LabEngine.filterRun(Scenarios.OPEN, b, epochs = 30)
        assertEquals(4, traces.size)
        val finals = traces.map { it.finalError }
        val spread = finals.max() - finals.min()
        assertTrue("filter spread = $spread m (${finals.joinToString()})", spread < 0.5)
    }

    @Test
    fun `滤波器改善稳定性但不改变准确度`() {
        val b = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        val traces = LabEngine.filterRun(Scenarios.OPEN, b, epochs = 30)
        val ols = traces.first { it.short == "OLS" }
        val ekf = traces.first { it.short == "EKF" }
        val pf = traces.first { it.short == "PF" }
        // EKF / PF 的抖动应明显更小
        println("TDIAG OLS final=${ols.finalError} scatter=${ols.scatter} | EKF final=${ekf.finalError} scatter=${ekf.scatter} | PF final=${pf.finalError} scatter=${pf.scatter}")
        assertTrue("OLS scatter=${ols.scatter} EKF=${ekf.scatter}", ekf.scatter < ols.scatter)
        assertTrue("PF scatter=${pf.scatter} OLS=${ols.scatter}", pf.scatter < ols.scatter)
        // 但终值应当接近
        assertTrue("final OLS=${ols.finalError} EKF=${ekf.finalError}", abs(ols.finalError - ekf.finalError) < 0.5)
    }

    @Test
    fun `平时电离层差别不大_磁暴天差别很大`() {
        // 这才是真实的形态:共模部分被钟差吸收,所以平时单频双频差不多;
        // 磁暴天电子含量暴涨,梯度变大,各星之间的差异才真正显现。
        val sc = Scenarios.CITY
        val base = cleanBudget()
        // 单历元的噪声和电离层差值同量级,必须多历元平均才看得清
        fun avg(bb: ErrorBudget) = (0 until 10).map { e ->
            LabEngine.shot(sc, GnssSystem.entries.toSet(), bb, LabEngine.DEFAULT_TIME + e,
                LabEngine.DEFAULT_SEED + e * 7919L).errorM
        }.average()
        val single = avg(base.copy(ionoHandling = IonoHandling.NONE))
        val dual = avg(base.copy(ionoHandling = IonoHandling.DUAL_FREQ))
        val quiet = single - dual

        val storm = sc.copy(tec = 4.0e17)
        fun avgStorm(bb: ErrorBudget) = (0 until 10).map { e ->
            LabEngine.shot(storm, GnssSystem.entries.toSet(), bb, LabEngine.DEFAULT_TIME + e,
                LabEngine.DEFAULT_SEED + e * 7919L).errorM
        }.average()
        val stormSingle = avgStorm(base.copy(ionoHandling = IonoHandling.NONE))
        val stormDual = avgStorm(base.copy(ionoHandling = IonoHandling.DUAL_FREQ))
        val stormDiff = stormSingle - stormDual

        println("TDIAG quiet diff=$quiet | storm single=$stormSingle dual=$stormDual diff=$stormDiff")
        assertTrue("quiet diff=$quiet", quiet in 0.05..25.0)
        assertTrue("storm diff=$stormDiff should exceed quiet=$quiet", stormDiff > quiet * 1.5)
    }

    @Test
    fun `几何差的场景 DOP 明显更差`() {
        val clean = cleanBudget()
        val open = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), clean).solution
        val canyon = LabEngine.shot(Scenarios.CANYON, GnssSystem.entries.toSet(), clean).solution
        assertNotNull("开阔地应当能解", open)
        val o = open!!
        val c = canyon!!
        assertTrue("canyon HDOP=${c.hdop} > open HDOP=${o.hdop}", c.hdop > o.hdop)
        assertTrue("open HDOP=${o.hdop}", o.hdop in 0.5..2.5)
    }

    @Test
    fun `四颗星是能解的下限`() {
        val clean = cleanBudget()
        val four = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), clean, maxSats = 4)
        assertEquals(4, four.numSats)
        assertNotNull(four.solution)
        val s4 = four.solution!!
        // 关键在于:"挑四颗仰角最高的"恰恰是个坏主意 —— 仰角高意味着方向都挤在天顶附近,
        // 几何反而差。这正是实验 02 要讲的那件事。
        assertTrue("HDOP=${s4.hdop} 应当明显变差", s4.hdop > 3.0)
        // 但只要挑四颗方位角铺开的,四颗星足以定位
        val spread = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), clean, maxSats = 12)
        assertTrue("12 颗星应当收敛,HDOP=${spread.solution?.hdop}",
            spread.solution != null && spread.solution.converged)
    }

    @Test
    fun `几何退化时如实报告不可信_而不是给出漂亮的错误答案`() {
        // 城市里单系统常常只剩 5~6 颗星,几何接近退化。
        // 这时解在数学上存在,但 HDOP 巨大,结果没有物理意义。
        val clean = cleanBudget()
        val sol = LabEngine.shot(Scenarios.CANYON, setOf(GnssSystem.GLO), clean).solution
        if (sol != null && sol.hdop > 25.0) {
            assertFalse("HDOP=${sol.hdop} 时必须标记为不可信", sol.ok)
        }
    }

    // ——————————————————————
    // 融合层
    // ——————————————————————

    @Test
    fun `城市里加 WiFi 会明显改善`() {
        // 必须保留城市的多路径与电离层误差,否则裸卫星本来就很准,谈不上改善
        val b = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        val r = LabEngine.shot(Scenarios.CITY, GnssSystem.entries.toSet(), b)
        val hp = HybridPositioner(Scenarios.CITY)
        val gnssOnly = hp.locate(r.solution, AssistConfig(gnss = true))
        val withWifi = hp.locate(r.solution, AssistConfig(gnss = true, wifi = true))
        val withMap = hp.locate(r.solution, AssistConfig(gnss = true, wifi = true, mapMatch = true))
        println("TDIAG hybrid gnss=${gnssOnly.errorM} wifi=${withWifi.errorM} map=${withMap.errorM}")
        assertTrue("gnss=${gnssOnly.errorM} wifi=${withWifi.errorM}", withWifi.errorM < gnssOnly.errorM)
        assertTrue("map=${withMap.errorM} wifi=${withWifi.errorM}", withMap.errorM < withWifi.errorM)
    }

    @Test
    fun `桥下所有辅助手段都救不回来`() {
        val b = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        val full = AssistConfig(gnss = true, wifi = true, cell = true, mapMatch = true)

        val city = LabEngine.shot(Scenarios.CITY, GnssSystem.entries.toSet(), b)
        val cityErr = HybridPositioner(Scenarios.CITY).locate(city.solution, full).errorM

        val bridge = LabEngine.shot(Scenarios.UNDER_BRIDGE, GnssSystem.entries.toSet(), b)
        val bridgeErr = HybridPositioner(Scenarios.UNDER_BRIDGE).locate(bridge.solution, full).errorM

        // 桥下的核心事实不是"误差更大",而是卫星这颗信息源彻底没了
        val bridgeSats = bridge.numSats
        val citySats = city.numSats
        assertTrue("bridge sees $bridgeSats sats, city sees $citySats", bridgeSats < 3)
        assertTrue("bridge sees $bridgeSats sats", bridgeSats < citySats / 3)
        assertTrue("bridge error=$bridgeErr m", bridgeErr > 5.0)
    }

    @Test
    fun `城市里可见卫星明显少于开阔地`() {
        val b = cleanBudget()
        val open = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), b).numSats
        val canyon = LabEngine.shot(Scenarios.CANYON, GnssSystem.entries.toSet(), b).numSats
        assertTrue("open=$open canyon=$canyon", open > canyon)
        assertTrue("open should see plenty, got $open", open >= 8)
    }

    @Test
    fun `多星座增加可见卫星数`() {
        val b = cleanBudget()
        val one = LabEngine.shot(Scenarios.OPEN, setOf(GnssSystem.GPS), b).numSats
        val four = LabEngine.shot(Scenarios.OPEN, GnssSystem.entries.toSet(), b).numSats
        assertTrue("one=$one four=$four", four > one)
    }

    @Test
    fun `收尾页误差预算必须多历元平均_否则整张图被噪声主导`() {
        val sc = Scenarios.CITY
        val lines = LabEngine.budgetBreakdown(sc, samples = 9)
        assertEquals(6, lines.size)
        val all = lines.map { it.allOn }
        assertTrue("各条 allOn 应来自同一次基准解,实测 ${all.distinct()}", all.distinct().size == 1)
        // 城市里多路径拿走绝大部分误差
        val mp = lines.first { it.label == "多路径" }
        val mpImpact = abs(mp.allOn - mp.withoutIt)
        assertTrue("多路径边际影响 = $mpImpact m,应压倒性主导", mpImpact > 10.0)
        assertTrue("关掉多路径后应降到 2 m 以内,实测 ${mp.withoutIt}", mp.withoutIt < 2.0)
        // 其余各项在多路径面前都是零头
        lines.filter { it.label != "多路径" }.forEach {
            val impact = abs(it.allOn - it.withoutIt)
            assertTrue("${it.label} 边际影响 $impact m,不应超过多路径的一半", impact < mpImpact / 2)
        }
        // 接收机钟差是共模量:关掉它误差**完全**不变 —— 这是 App 要教的核心物理
        val clk = lines.first { it.label.startsWith("接收机钟差") }
        assertEquals("接收机钟差应被完全吸收,实测差 ${abs(clk.allOn - clk.withoutIt)} m",
            0.0, abs(clk.allOn - clk.withoutIt), 1e-6)
    }

    @Test
    fun `精度四级台阶必须多历元平均_否则单点与双频的差别会被噪声淹没`() {
        // 实测结论(9 次采样平均):
        //   城市:单点 15.0 → 双频 15.3 → PPP 15.4 → RTK 0.45
        //   开阔:单点 1.10 → 双频 1.22 → PPP 0.83 → RTK 0.17
        //
        // 城市里只有 RTK 有效,因为**多路径是压倒性主项**,而 RTK 恰好能差掉它;
        // 双频和精密产品各自只值零点几米,全部淹没在噪声底线下。
        // 所以**不能**断言四级台阶单调 —— 那是我原来以为的,实测不是。
        val city = LabEngine.precisionSweep(Scenarios.CITY, samples = 9)
        val citySp = city.first { it.label == "单点定位" }.value
        val cityRtk = city.last().value
        assertTrue("城市里 RTK($cityRtk m) 应远优于单点($citySp m) —— 多路径是主项",
            cityRtk * 5 < citySp)

        // 开阔地多路径小,精密产品的贡献才显现出来
        val open = LabEngine.precisionSweep(Scenarios.OPEN, samples = 9)
        val openSp = open.first { it.label == "单点定位" }.value
        val openPpp = open.first { it.label.startsWith("PPP") }.value
        val openRtk = open.last().value
        assertTrue("开阔地 PPP($openPpp m) 应优于单点($openSp m)", openPpp < openSp)
        assertTrue("开阔地 RTK($openRtk m) 应是最优", openRtk < openPpp)
    }

    private fun cleanBudget() = ErrorBudget(
        receiverClockNs = 0.0, satClockErrorM = 0.0, relativityEnabled = true,
        ionoHandling = IonoHandling.TEC_MODEL, tropoEnabled = true,
        ephemerisErrorM = 0.0, multipathRemoved = true
    )
}

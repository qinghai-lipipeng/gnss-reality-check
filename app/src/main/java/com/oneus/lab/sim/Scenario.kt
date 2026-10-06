package com.oneus.lab.sim

import kotlin.math.*
import kotlin.random.Random

/** 天空遮挡块(桥下、楼宇、山体) */
data class SkyBlock(
    val azCenterDeg: Double,
    val elCenterDeg: Double,
    val azWidthDeg: Double,
    val elWidthDeg: Double
) {
    fun blocks(az: Double, el: Double): Boolean {
        val dAz = abs(wrap180(az - azCenterDeg))
        val dEl = abs(el - elCenterDeg)
        return dAz <= azWidthDeg / 2.0 && dEl <= elWidthDeg / 2.0
    }
}

/**
 * 场景:一个"环境"。
 * 它决定几何遮挡、大气基准、多路径强度、WiFi 密度——也就是"量"得好不好。
 */
data class Scenario(
    val id: String,
    val name: String,
    val site: Geo,
    val maskDeg: Double = 10.0,
    val tec: Double = 1.0e17,
    val multipathRms: Double = 0.0,
    val noiseRms: Double = 0.3,
    val cn0Base: Double = 42.0,
    val blocks: List<SkyBlock> = emptyList(),
    /** 主反射面方位角(度)—— 多路径的相干分量,决定误差往哪个方向偏 */
    val reflectorAzDeg: Double = 90.0,
    val wifiDensity: Double = 0.0,      // WiFi AP 每 km²
    val onRoad: Boolean = true,         // 是否在道路上(道路匹配才有意义)
    val blurb: String = ""
) {
    fun isVisible(az: Double, el: Double): Boolean {
        if (el < maskDeg) return false
        return blocks.none { it.blocks(az, el) }
    }
}

object Scenarios {

    /** 北京天安门广场西侧,开阔 */
    val OPEN = Scenario(
        id = "open",
        name = "开阔地",
        site = Geo(39.9050, 116.3900, 45.0),
        maskDeg = 10.0,
        tec = 1.5e17,
        multipathRms = 0.8,
        noiseRms = 0.3,
        cn0Base = 44.0,
        wifiDensity = 3.0,
        blurb = "空旷、看得见大半个天。几何好,多路径几乎没有。"
    )

    /** 城市街道,两侧楼宇 */
    val CITY = Scenario(
        id = "city",
        name = "城市街道",
        site = Geo(39.9080, 116.3960, 40.0),
        maskDeg = 18.0,
        tec = 1.5e17,
        multipathRms = 20.0,
        noiseRms = 0.6,
        cn0Base = 36.0,
        blocks = listOf(
            SkyBlock(azCenterDeg = 90.0, elCenterDeg = 35.0, azWidthDeg = 60.0, elWidthDeg = 70.0),
            SkyBlock(azCenterDeg = 270.0, elCenterDeg = 35.0, azWidthDeg = 60.0, elWidthDeg = 70.0)
        ),
        reflectorAzDeg = 85.0,
        wifiDensity = 400.0,
        blurb = "两边高楼。几何被砍掉一半,信号在楼间弹来弹去。"
    )

    /** 城市峡谷:更窄更深 */
    val CANYON = Scenario(
        id = "canyon",
        name = "城市峡谷",
        site = Geo(39.9070, 116.3990, 40.0),
        maskDeg = 22.0,
        tec = 1.8e17,
        multipathRms = 35.0,
        noiseRms = 0.9,
        cn0Base = 32.0,
        blocks = listOf(
            SkyBlock(azCenterDeg = 90.0, elCenterDeg = 55.0, azWidthDeg = 110.0, elWidthDeg = 90.0),
            SkyBlock(azCenterDeg = 270.0, elCenterDeg = 55.0, azWidthDeg = 110.0, elWidthDeg = 90.0)
        ),
        reflectorAzDeg = 95.0,
        wifiDensity = 600.0,
        blurb = "楼更高、路更窄。可见卫星骤减,多路径称王。"
    )

    /** 桥下:信号被彻底遮断 */
    val UNDER_BRIDGE = Scenario(
        id = "bridge",
        name = "桥下",
        site = Geo(39.9065, 116.3930, 40.0),
        maskDeg = 15.0,
        tec = 2.2e17,
        multipathRms = 20.0,
        noiseRms = 1.0,
        cn0Base = 24.0,
        blocks = listOf(
            SkyBlock(azCenterDeg = 0.0, elCenterDeg = 45.0, azWidthDeg = 360.0, elWidthDeg = 90.0)
        ),
        wifiDensity = 120.0,
        blurb = "桥面把天挡住了。卫星量不到,再聪明的算法也猜不出来。"
    )

    /** 室内 */
    val INDOOR = Scenario(
        id = "indoor",
        name = "室内",
        site = Geo(39.9075, 116.3945, 40.0),
        maskDeg = 30.0,
        tec = 1.2e17,
        multipathRms = 15.0,
        noiseRms = 1.4,
        cn0Base = 16.0,
        blocks = listOf(
            SkyBlock(azCenterDeg = 0.0, elCenterDeg = 50.0, azWidthDeg = 360.0, elWidthDeg = 100.0)
        ),
        wifiDensity = 1500.0,
        blurb = "混凝土盒子。卫星基本用不上,全靠 WiFi 和惯导撑。"
    )

    val all = listOf(OPEN, CITY, CANYON, UNDER_BRIDGE, INDOOR)
    fun byId(id: String): Scenario = all.firstOrNull { it.id == id } ?: OPEN
}

/** 卫星信号的频率配置 */
enum class FreqPlan(val label: String, val secondHz: Double) {
    L1_L5("L1 + L5", Ionosphere.L5),
    L1_L2("L1 + L2", Ionosphere.L2)
}

/** 解算时如何处理电离层 */
enum class IonoHandling(val label: String) {
    /** 单频,不做任何改正 —— 电离层延迟直接变成位置误差 */
    NONE("单频,不改"),

    /** 单频 + TEC 模型改正 */
    TEC_MODEL("单频 + 模型改正"),

    /** 单频 + Klobuchar 广播改正 */
    KLOBUCHAR("单频 + 广播改正"),

    /** 双频无组合 —— 一阶电离层被消除,噪声被放大 */
    DUAL_FREQ("双频无组合")
}

/**
 * 误差预算 —— 用户可以拨动的旋钮。
 *
 * 每一项都对应"量"里的一件事。App 的论点就是:
 * 改动这里的任何一项,误差都剧烈变化;而换解算算法,误差几乎不动。
 */
data class ErrorBudget(
    /** 接收机钟差(ns)—— ★ 主控旋钮,1 μs = 300 m */
    val receiverClockNs: Double = 0.0,

    /** 卫星钟差残余(rms, m) */
    val satClockErrorM: Double = 1.0,

    /** 是否施加周期性相对论钟差修正 */
    val relativityEnabled: Boolean = true,

    /** 电离层处理方式 */
    val ionoHandling: IonoHandling = IonoHandling.NONE,
    val freqPlan: FreqPlan = FreqPlan.L1_L5,

    /** 是否施加对流层模型 */
    val tropoEnabled: Boolean = true,

    /** 广播星历轨道误差(rms, m) */
    val ephemerisErrorM: Double = 1.0,

    /** 是否施加多路径改正(理想改正 = 0) */
    val multipathRemoved: Boolean = false
)

/** 一次观测(双频:L1 + L5/L2) */
class Measurement(
    val prn: String,
    val system: GnssSystem,
    val sv: Satellite,
    /** 该历元的卫星 ECEF 位置 —— 解算器用,不重新推算 */
    val svEcef: Vec3,
    val azDeg: Double,
    val elDeg: Double,
    val cn0: Double,
    val range: Double,        // 真几何距离 (m)
    val prL1: Double,         // L1 伪距 (m)
    val prL2: Double,         // L5(或 L2)伪距 (m)
    val ionoDelayL1: Double,  // L1 电离层延迟 (m)
    val ionoDelayL2: Double,  // L5/L2 电离层延迟 (m)
    val tropoDelayM: Double,  // 对流层延迟 (m)
    val clockRange: Double,   // c·δt_r (m)
    val secondFreqHz: Double
) {
    /** 单频模式下使用的伪距 */
    val pseudorange: Double get() = prL1

    /**
     * 电离层无组合双频伪距(m)。
     * PR_IF = (f1²·PR1 − f2²·PR2) / (f1² − f2²)
     * 一阶电离层被完全消除;代价是噪声被放大(见 [ionoFreeNoiseFactor])。
     */
    val ionoFree: Double
        get() {
            val f1 = Ionosphere.L1
            val f1sq = f1 * f1
            val f2sq = secondFreqHz * secondFreqHz
            return (f1sq * prL1 - f2sq * prL2) / (f1sq - f2sq)
        }

    /** 无组合的噪声放大倍数。L1/L5 约 2.59,L1/L2 约 2.97。 */
    val ionoFreeNoiseFactor: Double
        get() {
            val f1 = Ionosphere.L1
            val f1sq = f1 * f1
            val f2sq = secondFreqHz * secondFreqHz
            val a = f1sq / (f1sq - f2sq)
            val b = f2sq / (f1sq - f2sq)
            return sqrt(a * a + b * b)
        }

    /** 伪距残差 = 伪距 − (真距离 + 钟差 + 对流层) —— 理论上只剩电离层与噪声 */
    val residual: Double
        get() = prL1 - (range + clockRange + tropoDelayM)

    /** 观测标准差(m) —— 决定加权最小二乘的权重 */
    val sigma: Double by lazy {
        val el = sin(Math.toRadians(max(elDeg, 5.0)))
        val codeNoise = 0.3 + 0.8 / el
        val ionoSigma = ionoDelayL1 * 0.30
        sqrt(codeNoise * codeNoise + ionoSigma * ionoSigma)
    }
}

/**
 * 观测生成器:由 场景 + 误差预算 + 星座集合 → 双频伪距。
 *
 * 伪距模型(GPS ICD-200):
 *   PR = ρ + c·δt_r + Δt_rel + c·δt_sat + I + T + ε_mp + ε_n + b_hw
 *
 * 注意:电离层延迟 **总是存在**。它是被"改正"的,不是被"关掉"的。
 * 仿真器只负责造出真实信号,怎么处理是解算器的事。
 */
class Simulator {

    fun generate(
        scenario: Scenario,
        systems: Set<GnssSystem>,
        budget: ErrorBudget,
        gpsTimeSec: Double,
        seed: Long = 20240101L
    ): List<Measurement> {
        val rng = Random(seed)
        val sats = Constellations.satellitesOf(systems)
        val rcv = scenario.site.toEcef()
        val out = ArrayList<Measurement>()
        val f2 = budget.freqPlan.secondHz

        for (sv in sats) {
            val pos = sv.positionEcef(gpsTimeSec)
            val (az, el) = scenario.site.azElTo(pos)
            if (!scenario.isVisible(az, el)) continue

            val range = pos.distTo(rcv)

            // —— 钟差 ——
            val clockRange = C * budget.receiverClockNs * 1e-9

            // —— 相对论偏心项:卫星钟的物理事实,永远存在,解不改正是接收机的事 ——
            val relRange = C * sv.relativityClockOffsetSec(gpsTimeSec)

            // —— 卫星钟差残余 ——
            val satClock = gaussian(rng) * budget.satClockErrorM

            // —— 电离层(客观存在,两个频率不同) ——
            val ionoL1 = Ionosphere.slantDelayFromTec(scenario.tec, el, Ionosphere.L1, az)
            val ionoL2 = Ionosphere.slantDelayFromTec(scenario.tec, el, f2, az)

            // —— 对流层(与频率无关),同样永远存在 ——
            val tropo = Troposphere.slantDelay(el, scenario.site.latDeg, scenario.site.altM)

            // —— 轨道误差 ——
            val eph = gaussian(rng) * budget.ephemerisErrorM

            // —— 多路径 ——
            // 关键在于**方位相关性**:城市里同一片楼宇会同时反射给方位角相近的卫星,
            // 这一部分是各星"共模"的,不会随卫星数量平均掉,直接打进位置。
            // 只有各星独立的那部分才会被平均掉 —— 这正是城市里误差降不下来的原因。
            val mpBase = if (budget.multipathRemoved) 0.0 else scenario.multipathRms
            val elFactor = 1.0 / max(sin(Math.toRadians(max(el, 5.0))), 0.2) * 0.3
            val azRad = Math.toRadians(az)
            val coherent =
                1.5 * cos(azRad - Math.toRadians(scenario.reflectorAzDeg)) +
                        0.9 * cos(2.0 * (azRad - Math.toRadians(scenario.reflectorAzDeg + 70.0)))
            val mp = mpBase * ((0.5 * coherent + 0.5 * gaussian(rng)) * (0.5 + elFactor))

            // —— 载波跟踪噪声 ——
            val noise = gaussian(rng) * scenario.noiseRms / max(sin(Math.toRadians(max(el, 5.0))), 0.2) * 0.5

            val common = clockRange + relRange + satClock + tropo + eph + mp
            val prL1 = range + common + ionoL1 + noise
            val prL2 = range + common + ionoL2 + noise * 0.8

            // C/N0:低仰角与多路径都会拉低
            val elLoss = 20.0 * log10(max(sin(Math.toRadians(max(el, 5.0))), 0.05))
            val mpLoss = if (mpBase > 1.0) 3.0 * log10(mpBase) else 0.0
            val cn0 = scenario.cn0Base - elLoss - mpLoss + gaussian(rng) * 0.8

            out.add(
                Measurement(
                    prn = sv.prn, system = sv.system, sv = sv, svEcef = pos,
                    azDeg = az, elDeg = el, cn0 = cn0,
                    range = range, prL1 = prL1, prL2 = prL2,
                    ionoDelayL1 = ionoL1, ionoDelayL2 = ionoL2,
                    tropoDelayM = tropo, clockRange = clockRange,
                    secondFreqHz = f2
                )
            )
        }
        return out
    }

    /** Box-Muller,返回标准正态 */
    private fun gaussian(rng: Random): Double {
        var u1 = rng.nextDouble()
        if (u1 < 1e-12) u1 = 1e-12
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
}

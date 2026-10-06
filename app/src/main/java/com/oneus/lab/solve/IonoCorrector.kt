package com.oneus.lab.solve

import com.oneus.lab.sim.*
import kotlin.math.*

/**
 * 电离层改正器。
 *
 * 电离层延迟是**客观存在**的,接收机只能"改正"它,不能"消除"它。
 * 不同的改正方式,剩下的残差量级差 20 倍 —— 这就是"多给了你料"的含义。
 */
sealed class IonoCorrector {
    /** 返回 (校正后伪距, 附加观测标准差) */
    abstract fun apply(m: Measurement, sc: Scenario, gpsTimeSec: Double): Pair<Double, Double>

    /** 单频:什么都不做。电离层延迟直接变成位置误差。 */
    object None : IonoCorrector() {
        override fun apply(m: Measurement, sc: Scenario, gpsTimeSec: Double) =
            m.prL1 to (m.ionoDelayL1 * 0.30)
    }

    /**
     * 单频 + TEC 模型改正。
     * 模型只知道"大概多少",有系统误差 —— @param modelError 模型相对误差。
     */
    class TecModel(private val modelError: Double = 0.30) : IonoCorrector() {
        override fun apply(m: Measurement, sc: Scenario, gpsTimeSec: Double): Pair<Double, Double> {
            val estimated = Ionosphere.slantDelayFromTec(sc.tec, m.elDeg, Ionosphere.L1, m.azDeg)
            val corrected = m.prL1 - estimated
            val residual = m.ionoDelayL1 * modelError
            return corrected to abs(residual)
        }
    }

    /**
     * 单频 + Klobuchar 广播改正(真实 ICD-GPS-200 算法)。
     * 广播系数有龄期、电离层有空间梯度,实际效果比理想 TEC 模型差。
     */
    class Klobuchar(private val extraSigma: Double = 1.0) : IonoCorrector() {
        override fun apply(m: Measurement, sc: Scenario, gpsTimeSec: Double): Pair<Double, Double> {
            val sec = Ionosphere.klobuchar(
                freqHz = Ionosphere.L1,
                latDeg = sc.site.latDeg,
                lonDeg = sc.site.lonDeg,
                gpsTimeSec = gpsTimeSec,
                elevationDeg = m.elDeg
            )
            val corrected = m.prL1 - C * sec
            return corrected to extraSigma
        }
    }

    /**
     * 双频无组合。一阶电离层被彻底消除,残余只有二阶项(约 cm 级);
     * 代价是噪声被放大 [Measurement.ionoFreeNoiseFactor] 倍。
     */
    object DualFreq : IonoCorrector() {
        override fun apply(m: Measurement, sc: Scenario, gpsTimeSec: Double): Pair<Double, Double> {
            val noise = 0.3 + 0.8 / max(sin(Math.toRadians(max(m.elDeg, 5.0))), 0.2)
            val amp = m.ionoFreeNoiseFactor
            return m.ionoFree to (noise * (amp - 1.0))
        }
    }

    companion object {
        fun of(kind: IonoHandling, freqPlan: FreqPlan = FreqPlan.L1_L5): IonoCorrector =
            when (kind) {
                IonoHandling.NONE -> None
                IonoHandling.TEC_MODEL -> TecModel()
                IonoHandling.KLOBUCHAR -> Klobuchar()
                IonoHandling.DUAL_FREQ -> DualFreq
            }
    }
}

/** 解算配置 */
data class SolveConfig(
    val iono: IonoCorrector = IonoCorrector.None,
    val weighted: Boolean = true,
    val relativityCorrected: Boolean = true,
    val tropoCorrected: Boolean = true,
    val maxIter: Int = 200,
    val tol: Double = 1e-4
)

/** 归一化后的观测:解算器内部使用 */
class CorrectedObs(
    val m: Measurement,
    val pr: Double,
    val sigma: Double
)

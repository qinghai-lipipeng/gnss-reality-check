package com.oneus.lab.sim

import kotlin.math.*

/**
 * 电离层延迟模型。
 *
 * 两套模型并存,各有教学分工:
 * - [Klobuchar]  真实的 ICD-GPS-200 广播电离层改正算法,用于"深"层与工程模式
 * - [Tec]        直接按电子总含量 TEC 给出延迟,用于交互滑块(用户能直接拖动"太阳活动")
 */
object Ionosphere {

    /** 常用 L1 / L5 / L2 载波频率 (Hz) */
    const val L1 = 1_575_420_000.0
    const val L5 = 1_176_450_000.0
    const val L2 = 1_227_600_000.0

    /**
     * 斜路径因子(ICD-GPS-200 F = 1 + 16(0.53 − E)³)。
     *
     * 注意:这个闭式只在低仰角有意义。仰角超过 0.53 rad(约 30°)后它单调下降,
     * 在 53° 以上甚至变成负值 —— 那显然不是物理含义。
     * 真实含义是"斜穿电离层的路径比天顶方向长多少倍",仰角越高越趋近 1。
     * 所以这里取 max(1, ·),把高仰角段钳在天顶值上,并在深层说明这一处工程处理。
     */
    fun slantFactor(elevationRad: Double): Double =
        max(1.0, 1.0 + 16.0 * Math.pow(0.53 - elevationRad, 3.0))

    /**
     * 由电子总含量直接求垂直延迟(m)。
     * 经典关系: I_zenith = 40.3 · TEC / f²   (TEC 单位 electrons/m²)
     */
    fun zenithDelayFromTec(tec: Double, freqHz: Double = L1): Double =
        40.3 * tec / (freqHz * freqHz)

    /** 斜路径延迟(m)。azDeg 为信号方位角,用于引入水平梯度。 */
    fun slantDelayFromTec(
        tec: Double,
        elevationDeg: Double,
        freqHz: Double = L1,
        azDeg: Double = 0.0
    ): Double {
        val base = zenithDelayFromTec(tec, freqHz) * slantFactor(Math.toRadians(elevationDeg))
        return base * (1.0 + TEC_GRADIENT * cos(Math.toRadians(azDeg)))
    }

    /**
     * 电子含量的水平不均匀度。
     *
     * 真实电离层在几百公里尺度上就有 10%~20% 的水平梯度,它随着仰角差
     * 转化成**各颗星之间**的延迟差。这部分才是真正打���位置的部分 ——
     * 平均的那一份会被接收机钟差这个未知量吸收掉。
     */
    const val TEC_GRADIENT = 0.15

    /**
     * Klobuchar 模型 —— GPS ICD-GPS-200 Table 20-V 广播电离层改正算法。
     *
     * @param freqHz 载波频率
     * @param alpha 广播系数 α0..α3
     * @param beta  广播系数 β0..β3
     * @param latDeg 用户纬度(度)
     * @param lonDeg 用户经度(度)
     * @param gpsTimeSec GPS 时(秒),内部 mod 86400
     * @param elevationDeg 仰角(度)
     * @return 延迟秒
     */
    fun klobuchar(
        freqHz: Double = L1,
        alpha: DoubleArray = DEFAULT_ALPHA,
        beta: DoubleArray = DEFAULT_BETA,
        latDeg: Double,
        lonDeg: Double,
        gpsTimeSec: Double,
        elevationDeg: Double
    ): Double {
        // ICD 内部用半圆(semi-circle)单位
        val phiU = latDeg / 180.0
        val lamU = lonDeg / 180.0
        val eRad = Math.toRadians(elevationDeg)

        val phiI = phiU + 0.064 * cos(lamU - 1.617)
        val lamI = lamU + 0.015 * cos(phiU)

        var t = 43200.0 * lamI + gpsTimeSec
        if (t >= 86400.0) t -= 86400.0
        if (t < 0.0) t += 86400.0

        val phiM = phiI + 0.064 * cos(lamI - 1.617)
        val lamM = lamI + 0.015 * cos(phiI)

        val phiM2 = phiM * phiM
        val phiM3 = phiM2 * phiM
        val amp = alpha[0] + alpha[1] * phiM + alpha[2] * phiM2 + alpha[3] * phiM3

        val lamM2 = lamM * lamM
        val lamM3 = lamM2 * lamM
        var per = beta[0] + beta[1] * lamM + beta[2] * lamM2 + beta[3] * lamM3
        if (per < 72000.0) per = 72000.0

        val x = 2.0 * PI * (t - 50400.0) / per
        val f = slantFactor(eRad)

        val delaySec = if (abs(x) < 1.57) {
            f * (5.0e-9 + amp * (1.0 - x * x / 2.0 + x * x * x * x / 24.0))
        } else {
            f * 5.0e-9
        }
        return delaySec
    }

    /** 广播电离层改正的典型 α/β 系数(星历播发) */
    val DEFAULT_ALPHA = doubleArrayOf(1.0245e-08, 2.2352e-08, -1.9606e-08, -3.0505e-08)
    val DEFAULT_BETA = doubleArrayOf(9.0584e+04, 3.5612e+05, -8.7558e+04, -1.5523e+05)

    /**
     * 双频无组合电离层延迟(m) —— 消除一阶项后的残余。
     * IF 组合对二阶项的放大系数约 0.03,故 residual ≈ 0.03 · I_L1
     */
    fun ionosphereFreeResidual(delayL1: Double): Double = 0.03 * delayL1
}

/**
 * 对流层延迟模型 —— Saastamoinen。
 * 电离层不散射射频(频率 1.5 GHz 远高于等离子体截止),对流层是中性大气,会折射。
 */
object Troposphere {

    /**
     * 天顶方向静力学(干)延迟 (m)。
     * ZHD = 0.0022768 · P / (1 − 0.00266 cos 2φ − 0.00028 h_km)
     * @param pressureHpa 气压(hPa),海平面 1013.25
     */
    fun zenithHydrostatic(pressureHpa: Double, latDeg: Double, heightM: Double): Double {
        val lat = Math.toRadians(latDeg)
        val hKm = heightM / 1000.0
        return 0.0022768 * pressureHpa /
                (1.0 - 0.00266 * cos(2.0 * lat) - 0.00028 * hKm)
    }

    /**
     * 天顶方向湿延迟 (m)。
     * ZWD = 0.002277 · (1255/T + 0.05) · e
     * @param tempK 绝对温度(K)
     * @param vaporHpa 水汽压(hPa)
     */
    fun zenithWet(tempK: Double, vaporHpa: Double): Double =
        0.002277 * (1255.0 / tempK + 0.05) * vaporHpa

    /** 干分量斜路径映射 */
    fun mapHydrostatic(elevationDeg: Double): Double {
        val el = Math.toRadians(max(elevationDeg, 3.0))
        return 1.0 / sin(el)
    }

    /** 湿分量斜路径映射(Sæther/Roff 型,低仰角不发散) */
    fun mapWet(elevationDeg: Double): Double {
        val el = Math.toRadians(max(elevationDeg, 3.0))
        return 1.003 / (sin(el) + 0.025 * exp(-11.0 * cos(el)))
    }

    /**
     * 斜路径总延迟 (m)。
     * 海平面标准大气静海面:天顶 ≈2.3 m;仰角 10° 时可到 20 m 以上。
     */
    fun slantDelay(
        elevationDeg: Double,
        latDeg: Double = 40.0,
        heightM: Double = 0.0,
        pressureHpa: Double = 1013.25,
        tempK: Double = 288.15,
        vaporHpa: Double = 10.0
    ): Double {
        val zhd = zenithHydrostatic(pressureHpa, latDeg, heightM)
        val zwd = zenithWet(tempK, vaporHpa)
        return zhd * mapHydrostatic(elevationDeg) + zwd * mapWet(elevationDeg)
    }
}

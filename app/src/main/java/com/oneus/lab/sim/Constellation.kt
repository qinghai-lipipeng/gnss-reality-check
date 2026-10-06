package com.oneus.lab.sim

import kotlin.math.*

/**
 * GNSS 星座系统。
 * 颜色在 UI 层映射,这里只存标识。
 */
enum class GnssSystem(val label: String, val code: String) {
    GPS("GPS", "G"),
    BDS("北斗", "C"),
    GAL("伽利略", "E"),
    GLO("格洛纳斯", "R");

    companion object {
        fun fromCode(c: String): GnssSystem = entries.first { it.code == c }
    }
}

/**
 * 轨道面定义。
 *
 * @param raanDeg 升交点赤经
 * @param sats 该面卫星数
 * @param walkerF Walker 相位因子。真实星座(北斗、伽利略)都按 Walker Δ 排布,
 *   各面在平均近点角上互相对齐偏转,使可见星在方位角上散得开。
 *   用整齐的方格排布会让同一时刻的可见星挤在一条经线上,DOP 直接爆掉 ——
 *   那是排布假象,不是真实几何。
 */
class Plane(
    val raanDeg: Double,
    val sats: Int,
    val walkerF: Int = 1
)

/** 星座轨道配置 */
class Constellation(
    val system: GnssSystem,
    val semiMajorAxisM: Double,
    val eccentricity: Double,
    val inclinationDeg: Double,
    val planes: List<Plane>,
    val sidereal: Boolean = false,   // true = 随地球自转共轨(GEO)
    val meanAnomalyOffsetDeg: Double = 0.0
) {
    val meanMotion: Double = sqrt(MU / (semiMajorAxisM * semiMajorAxisM * semiMajorAxisM))
    val periodSec: Double get() = 2.0 * PI / meanMotion
    val altitudeM: Double get() = semiMajorAxisM - WGS84_A

    /** 该星座的卫星总数 */
    val satelliteCount: Int get() = planes.sumOf { it.sats }

    fun satellites(): List<Satellite> {
        val out = ArrayList<Satellite>(satelliteCount)
        for (pi in planes.indices) {
            val p = planes[pi]
            for (k in 0 until p.sats) {
                // Walker Δ:面内按 360/S 均匀,面间按 360·F/N 错开。
                // 注意分母是**总星数 N = P·S**,不是轨道面数 P ——
                // 写成 360·F/P 会让 (Ω,u) 与 (Ω+180,u+180) 撞车,
                // 等于把两颗卫星放到了同一个位置,可见星方位角成对重合、HDOP 直接爆掉。
                val ma0 = meanAnomalyOffsetDeg +
                        360.0 * k / p.sats +
                        360.0 * p.walkerF * pi / satelliteCount
                out.add(
                    Satellite(
                        prn = "${system.code}${k + 1}",
                        system = system,
                        raanDeg = p.raanDeg,
                        ma0Deg = ma0,
                        a = semiMajorAxisM,
                        e = eccentricity,
                        iDeg = inclinationDeg,
                        meanMotion = if (sidereal) OMEGA_E else meanMotion,
                        coRotate = sidereal
                    )
                )
            }
        }
        return out
    }
}

/** 单颗卫星的轨道根数与状态解算 */
class Satellite(
    val prn: String,
    val system: GnssSystem,
    val raanDeg: Double,
    val ma0Deg: Double,
    val a: Double,
    val e: Double,
    val iDeg: Double,
    val meanMotion: Double,
    /** 共轨(GEO):升交点在 ECEF 中以 -ω_E 退行,卫星才真正相对地球静止 */
    val coRotate: Boolean = false
) {
    /** 历元 t(s) 后的 ECEF 位置 (m) */
    fun positionEcef(t: Double): Vec3 {
        val (pos, _) = stateEcef(t)
        return pos
    }

    /** 历元 t(s) 后的 ECEF 位置与速度 (m, m/s) */
    fun stateEcef(t: Double): Pair<Vec3, Vec3> {
        val M = Math.toRadians(ma0Deg) + meanMotion * t
        val E = solveKepler(M, e)

        // 轨道平面内
        val cosE = cos(E); val sinE = sin(E)
        val r = a * (1.0 - e * cosE)
        val xp = r * cosE
        val yp = r * sinE

        // 速度(轨道平面)
        val n = meanMotion
        val Edot = n / (1.0 - e * cosE)
        val vxp = -a * sinE * Edot
        val vyp = a * cosE * Edot

        val om = Math.toRadians(raanDeg) - if (coRotate) OMEGA_E * t else 0.0
        val inc = Math.toRadians(iDeg)
        // 轨道面 → ECEF 的顺序:先绕 X 轴加倾角 i,再绕 Z 轴加升交点赤经 Ω。
        // 反过来的话 Ω 会混进近点角里,子卫星点轨迹完全错乱,
        // 可见星会挤在同一条大圆上,HDOP 直接爆到几百 —— 看起来像"星座排布问题",其实是旋转顺序。
        val pos = rotZ(rotX(Vec3(xp, yp, 0.0), inc), om)
        val vel = rotZ(rotX(Vec3(vxp, vyp, 0.0), inc), om)
        return pos to vel
    }

    /**
     * 周期性相对论钟差修正 (s)。
     * GPS ICD-GPS-200:  Δt_rel = F · e · √A · sin(E)
     * F = -4.442807633e-10 s/m^0.5, A = 26560180 m
     *
     * 注意区分:星座整体 38.6 μs/day 的平均钟速差由系统自行校准(频率偏置),
     * 接收机需要处理的是这一项**周期性偏心修正**。
     */
    fun relativityClockOffsetSec(t: Double): Double {
        val M = Math.toRadians(ma0Deg) + meanMotion * t
        val E = solveKepler(M, e)
        return RELATIVITY_F * e * sqrt(GPS_SEMI_MAJOR_AXIS) * sin(E)
    }

    companion object {
        /** Newton 迭代解开普勒方程 M = E - e sinE */
        fun solveKepler(M: Double, e: Double): Double {
            var E = M
            repeat(6) {
                val d = (E - e * sin(E) - M) / (1.0 - e * cos(E))
                E -= d
                if (abs(d) < 1e-12) return E
            }
            return E
        }
    }
}

/** 绕 Z 轴旋转升交点经度 Ω */
private fun rotZ(v: Vec3, th: Double): Vec3 {
    val c = cos(th); val s = sin(th)
    return Vec3(v.x * c - v.y * s, v.x * s + v.y * c, v.z)
}

/** 绕 X 轴旋转倾角 i */
private fun rotX(v: Vec3, th: Double): Vec3 {
    val c = cos(th); val s = sin(th)
    return Vec3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
}

/**
 * 四个星座的真实轨道设计参数。
 * 高度取自各星座 ICD / 公开设计文件。
 */
object Constellations {

    val GPS = Constellation(
        system = GnssSystem.GPS,
        semiMajorAxisM = 26_560_180.0,
        eccentricity = 0.0070,
        inclinationDeg = 55.0,
        meanAnomalyOffsetDeg = 20.0,
        planes = (0 until 6).map { Plane(raanDeg = 60.0 * it, sats = 4, walkerF = 1) }
    )

    val BDS_MEO = Constellation(
        system = GnssSystem.BDS,
        semiMajorAxisM = 27_906_100.0,
        eccentricity = 0.0020,
        inclinationDeg = 55.0,
        meanAnomalyOffsetDeg = 55.0,
        planes = (0 until 3).map { Plane(raanDeg = 120.0 * it, sats = 8, walkerF = 1) }
    )

    val BDS_IGSO = Constellation(
        system = GnssSystem.BDS,
        semiMajorAxisM = 42_164_157.0,
        eccentricity = 0.0020,
        inclinationDeg = 55.0,
        meanAnomalyOffsetDeg = 130.0,
        planes = listOf(
            Plane(raanDeg = 0.0, sats = 1),
            Plane(raanDeg = 120.0, sats = 1),
            Plane(raanDeg = 240.0, sats = 1)
        )
    )

    val BDS_GEO = Constellation(
        system = GnssSystem.BDS,
        semiMajorAxisM = 42_164_157.0,
        eccentricity = 0.0001,
        inclinationDeg = 0.0,
        meanAnomalyOffsetDeg = 200.0,
        sidereal = true,
        planes = listOf(Plane(raanDeg = 0.0, sats = 5, walkerF = 0))
    )

    val GALILEO = Constellation(
        system = GnssSystem.GAL,
        semiMajorAxisM = 29_600_000.0,
        eccentricity = 0.0010,
        inclinationDeg = 56.0,
        meanAnomalyOffsetDeg = 95.0,
        planes = (0 until 6).map { Plane(raanDeg = 60.0 * it, sats = 4, walkerF = 1) }
    )

    val GLONASS = Constellation(
        system = GnssSystem.GLO,
        semiMajorAxisM = 25_510_000.0,
        eccentricity = 0.0010,
        inclinationDeg = 64.5,
        meanAnomalyOffsetDeg = 160.0,
        planes = (0 until 3).map { Plane(raanDeg = 120.0 * it, sats = 8, walkerF = 1) }
    )

    /** 系统 → 全部轨道(北斗含 MEO/IGSO/GEO) */
    fun planesOf(system: GnssSystem): List<Constellation> = when (system) {
        GnssSystem.GPS -> listOf(GPS)
        GnssSystem.BDS -> listOf(BDS_MEO, BDS_IGSO, BDS_GEO)
        GnssSystem.GAL -> listOf(GALILEO)
        GnssSystem.GLO -> listOf(GLONASS)
    }

    fun all(): List<Constellation> = GnssSystem.entries.flatMap { planesOf(it) }

    fun satellitesOf(systems: Set<GnssSystem>): List<Satellite> =
        systems.flatMap { planesOf(it) }.flatMap { it.satellites() }

    fun allSatellites(): List<Satellite> = all().flatMap { it.satellites() }
}

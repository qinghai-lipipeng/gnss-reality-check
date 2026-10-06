package com.oneus.lab.solve

import com.oneus.lab.sim.*
import kotlin.math.*
import kotlin.math.ln
import kotlin.random.Random

/** 观测批次的构造与电离层改正入口 */
object Obs {
    /**
     * 电离层改正 + 相对论改正。
     *
     * 相对论偏心项 Δt_rel 是**确定性可算**的(由广播星历的 e、√A、sin E 直接给出),
     * 真实接收机每次都会算,不做就是十几米误差 —— 所以它属于解算侧,不属于仿真侧。
     */
    fun correct(
        obs: List<Measurement>,
        scenario: Scenario,
        iono: IonoCorrector,
        gpsTimeSec: Double,
        relativityCorrected: Boolean = true,
        tropoCorrected: Boolean = true
    ): List<CorrectedObs> = obs.map { m ->
        var pr = m.prL1
        var extra = 0.0

        val (prIono, sg) = iono.apply(m, scenario, gpsTimeSec)
        pr = prIono
        extra = sg

        if (relativityCorrected) pr -= C * m.sv.relativityClockOffsetSec(gpsTimeSec)

        // 对流层改正:斜路径建模,残余就是湿延迟的不可预报部分
        if (tropoCorrected) {
            pr -= Troposphere.slantDelay(m.elDeg, scenario.site.latDeg, scenario.site.altM)
            extra = sqrt(extra * extra + 0.3 * 0.3)
        }

        CorrectedObs(m, pr, sqrt(m.sigma * m.sigma + extra * extra))
    }
}

/** 滤波器的统一输出:一个历元的位置 */
data class FilterState(
    val pos: Vec3,
    val clockRange: Double,
    val spread: Double,
    val epoch: Int
)

/** 定位滤波器接口 —— 同一份观测,四种实现 */
interface PositionFilter {
    val name: String
    val short: String
    fun reset(initial: Vec3)
    fun step(obs: List<CorrectedObs>, epoch: Int): FilterState?
}

private fun seedOf(obs: List<CorrectedObs>): Vec3 {
    var sx = 0.0; var sy = 0.0; var sz = 0.0
    for (o in obs) { val u = o.m.svEcef.unit(); sx += u.x; sy += u.y; sz += u.z }
    return Vec3(sx, sy, sz).unit() * (WGS84_A + 2000.0)
}

/**
 * 单历元最小二乘。无内部状态,每历元从零开始。
 * weighted=false 即"普通最小二乘"(所有星等权)。
 */
class LsFilter(
    private val weighted: Boolean = true,
    override val name: String = if (weighted) "加权最小二乘" else "普通最小二乘",
    override val short: String = if (weighted) "WLS" else "OLS"
) : PositionFilter {

    override fun reset(initial: Vec3) {}
    override fun step(obs: List<CorrectedObs>, epoch: Int): FilterState? = null

    /**
     * @param initial 上一历元的解。有它就不用再做全球粗搜 ——
     *   真实接收机的差分定位也是这么做的,静态目标下相邻历元相距不过几米。
     */
    fun run(obs: List<CorrectedObs>, epoch: Int, initial: Vec3? = null): FilterState? {
        if (obs.size < 4) return null
        return linearSolve(obs, weighted, initial)?.let {
            FilterState(it.ecef, it.clockRangeM, if (it.hdop.isNaN()) 0.0 else it.hdop * 3.0, epoch)
        }
    }

    fun linearSolve(obs: List<CorrectedObs>, weighted: Boolean, initial: Vec3?): Solution? {
        // 初值离真值很远时(比如冷启动),线性化跨度过大会卡在坏解上 —— 那就退回粗搜。
        var rcv = if (initial != null && initial.norm() > WGS84_A * 0.5) initial else seedOf(obs)
        var clock = 0.0
        var last: Solution? = null
        for (iter in 0 until 10) {
            val n = obs.size
            val h = Array(n) { DoubleArray(4) }
            val v = DoubleArray(n)
            for (i in 0 until n) {
                val d = obs[i].m.svEcef - rcv
                val r = d.norm()
                if (r < 1.0) return null
                h[i][0] = -d.x / r; h[i][1] = -d.y / r; h[i][2] = -d.z / r; h[i][3] = 1.0
                v[i] = obs[i].pr - (r + clock)
            }
            val w = DoubleArray(n) { if (weighted) 1.0 / (obs[it].sigma * obs[it].sigma) else 1.0 }
            val htw = Array(4) { DoubleArray(4) }
            val wv = DoubleArray(4)
            for (i in 0 until n) {
                for (r0 in 0 until 4) {
                    wv[r0] += w[i] * h[i][r0] * v[i]
                    for (c in 0 until 4) htw[r0][c] += w[i] * h[i][r0] * h[i][c]
                }
            }
            for (r0 in 0 until 4) htw[r0][r0] += 1e-6
            val dx = LinAlg.solve(htw, wv) ?: return null
            rcv = rcv + Vec3(dx[0], dx[1], dx[2]); clock += dx[3]
            val res = DoubleArray(n) { i -> obs[i].pr - (obs[i].m.range + clock) }
            val cov = LinAlg.inverse(htw)
            val dop = dopOf(cov, rcv)
            last = Solution(rcv, clock, res, iter + 1, true, cov, dop[0], dop[1], dop[2], n)
            if (sqrt(dx[0] * dx[0] + dx[1] * dx[1] + dx[2] * dx[2]) < 1e-4) return last
        }
        return last
    }

    private fun dopOf(cov: Array<DoubleArray>?, rcv: Vec3): DoubleArray {
        if (cov == null) return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
        val lat = atan2(rcv.z, hypot(rcv.x, rcv.y)); val lon = atan2(rcv.y, rcv.x)
        val sl = sin(lat); val cl = cos(lat); val so = sin(lon); val co = cos(lon)
        val ax = arrayOf(Vec3(-so, co, 0.0), Vec3(-sl * co, -sl * so, cl), Vec3(cl * co, cl * so, sl))
        val p = Array(3) { i -> DoubleArray(3) { j ->
            var s = 0.0
            for (k in 0 until 3) for (l in 0 until 3) s += ax[k][i] * cov[k][l] * ax[l][j]
            s } }
        return doubleArrayOf(
            sqrt(max(p[0][0] + p[1][1], 0.0)),
            sqrt(max(p[0][0] + p[1][1] + p[2][2], 0.0)),
            sqrt(max(p[0][0] + p[1][1] + p[2][2] + cov[3][3], 0.0))
        )
    }
}

/**
 * 静态定位的扩展卡尔曼滤波(4 状态:x, y, z, cδt)。
 *
 * 与单历元 LS 的差别:用运动模型把多个历元的信息积累起来。
 * 后果是**方差更小、曲线更平滑** —— 但准确度上限依然由误差预算决定。
 */
class EkfFilter(
    override val name: String = "扩展卡尔曼滤波",
    override val short: String = "EKF",
    private val qPos: Double = 4.0,
    private val qClock: Double = 8.0
) : PositionFilter {

    private var x = DoubleArray(4)
    private var p = Array(4) { DoubleArray(4) }
    private var init = false

    override fun reset(initial: Vec3) {
        x = doubleArrayOf(initial.x, initial.y, initial.z, 0.0)
        p = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0e8 else 0.0 } }
        init = true
    }

    override fun step(obs: List<CorrectedObs>, epoch: Int): FilterState? {
        if (obs.size < 4) return null
        if (!init) reset(seedOf(obs))

        val n = obs.size

        // —— 1. 预测(静态:F = I,只加过程噪声) ——
        p[0][0] += qPos * qPos; p[1][1] += qPos * qPos; p[2][2] += qPos * qPos
        p[3][3] += qClock * qClock

        var pos = Vec3(x[0], x[1], x[2])
        var clock = x[3]

        // —— 2. 量测预测 ——
        // 静态接收机:F = I,状态不动,只让协方差长大。
        // 每历元不再做全量高斯-牛顿 —— 那样 EKF 就退化成单历元 LS 了,
        // 它的全部价值就在于**只**用 K·新息去修正状态,剩下的留给后续历元。

        // —— 3. 标准卡尔曼更新(这才是 EKF 相对 LS 的全部意义) ——
        // 关键在这里:只用 **一部分** 新观测去修正状态(K 由不确定性决定),
        // 剩下的留给后续历元。这才是 EKF 相对单历元 LS 的全部意义 ——
        // 它压的是方差,不是准确度上限。
        val h = Array(n) { i ->
            val d = obs[i].m.svEcef - pos
            val r = d.norm()
            doubleArrayOf(-d.x / r, -d.y / r, -d.z / r, 1.0)
        }
        val innov = DoubleArray(n) { i ->
            val d = obs[i].m.svEcef - pos
            obs[i].pr - (d.norm() + clock)
        }

        // S = H P Hᵀ + R
        val ph = Array(4) { r0 -> DoubleArray(n) { i ->
            p[r0][0] * h[i][0] + p[r0][1] * h[i][1] + p[r0][2] * h[i][2] + p[r0][3] * h[i][3] } }
        val s = Array(n) { i ->
            DoubleArray(n) { j ->
                var acc = 0.0
                for (r0 in 0 until 4) acc += h[i][r0] * ph[r0][j]
                acc + if (i == j) obs[i].sigma * obs[i].sigma else 0.0
            } }
        val sinv = LinAlg.inverse(s) ?: return null

        // K = P Hᵀ S⁻¹
        val k = Array(4) { r0 -> DoubleArray(n) { j ->
            var acc = 0.0
            for (i in 0 until n) acc += ph[r0][i] * sinv[i][j]
            acc
        } }

        var dx0 = 0.0; var dx1 = 0.0; var dx2 = 0.0; var dxc = 0.0
        for (j in 0 until n) {
            dx0 += k[0][j] * innov[j]; dx1 += k[1][j] * innov[j]
            dx2 += k[2][j] * innov[j]; dxc += k[3][j] * innov[j]
        }
        pos = pos + Vec3(dx0, dx1, dx2)
        clock += dxc
        x = doubleArrayOf(pos.x, pos.y, pos.z, clock)

        // P = (I − KH) P
        val kh = Array(4) { r0 ->
            DoubleArray(4) { c ->
                var acc = 0.0
                for (i in 0 until n) acc += k[r0][i] * h[i][c]
                if (r0 == c) acc -= 1.0
                acc
            } }
        val newP = Array(4) { r0 ->
            DoubleArray(4) { c ->
                var acc = 0.0
                for (m in 0 until 4) acc += kh[r0][m] * p[m][c]
                acc
            } }
        p = newP

        val v = (p[0][0] + p[1][1] + p[2][2]) / 3.0
        return FilterState(pos, clock, sqrt(max(v, 0.0)), epoch)
    }
}

/**
 * 粒子滤波(4 状态)。与 EKF 机理完全不同 ——
 * 用来证明:机理不同,结果一样。"算"这一层的自由度,管不了"量"的误差。
 */
class ParticleFilter(
    override val name: String = "粒子滤波",
    override val short: String = "PF",
    private val particleCount: Int = 1000,
    private val qPos: Double = 0.8,
    private val qClock: Double = 1.5,
    private val seed: Long = 4242L
) : PositionFilter {

    private class Particle(var x: Double, var y: Double, var z: Double, var c: Double, var w: Double)

    private var parts = ArrayList<Particle>()
    private val rng = Random(seed)
    private var init = false

    override fun reset(initial: Vec3) {
        parts = ArrayList(particleCount)
        repeat(particleCount) {
            // 接收机已经跑过单点解,粒子只需在粗解附近铺开。
            // 铺得太开会让似然全部下溢,粒子群再也收不回来。
            parts.add(Particle(
                initial.x + gauss() * 5.0, initial.y + gauss() * 5.0,
                initial.z + gauss() * 5.0, gauss() * 20.0, 1.0 / particleCount
            ))
        }
        init = true
    }

    override fun step(obs: List<CorrectedObs>, epoch: Int): FilterState? {
        if (obs.size < 4) return null
        if (!init) reset(seedOf(obs))

        for (p in parts) {
            p.x += gauss() * qPos; p.y += gauss() * qPos; p.z += gauss() * qPos; p.c += gauss() * qClock
        }

        var maxLog = Double.NEGATIVE_INFINITY
        val logs = DoubleArray(parts.size)
        for (i in parts.indices) {
            val p = parts[i]
            var logL = 0.0
            for (o in obs) {
                val d = o.m.svEcef - Vec3(p.x, p.y, p.z)
                val r = d.norm() + p.c
                val res = o.pr - r
                logL -= 0.5 * res * res / (2.0 * o.sigma * o.sigma) - ln(o.sigma) - 0.5 * ln(2.0 * PI)
            }
            logs[i] = logL
            if (logL > maxLog) maxLog = logL
        }
        var sum = 0.0
        for (i in parts.indices) { val w = exp(logs[i] - maxLog); parts[i].w = w; sum += w }
        if (sum <= 0.0) return null
        for (p in parts) p.w /= sum

        if (epoch % 2 == 0 || epoch < 5) {
            val next = ArrayList<Particle>(particleCount)
            repeat(particleCount) {
                val r = rng.nextDouble()
                var acc = 0.0
                var chosen = parts[parts.size - 1]
                for (p in parts) { acc += p.w; if (r <= acc) { chosen = p; break } }
                next.add(Particle(chosen.x, chosen.y, chosen.z, chosen.c, 1.0 / particleCount))
            }
            parts = next
        }

        var mx = 0.0; var my = 0.0; var mz = 0.0; var mc = 0.0
        var vx = 0.0
        for (p in parts) {
            mx += p.x * p.w; my += p.y * p.w; mz += p.z * p.w; mc += p.c * p.w
        }
        for (p in parts) vx += p.w * (p.x - mx) * (p.x - mx)
        return FilterState(Vec3(mx, my, mz), mc, sqrt(max(vx, 0.0)), epoch)
    }

    private fun gauss(): Double {
        var u1 = rng.nextDouble(); if (u1 < 1e-12) u1 = 1e-12
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * rng.nextDouble())
    }
}

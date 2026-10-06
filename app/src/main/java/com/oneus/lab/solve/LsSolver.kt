package com.oneus.lab.solve

import com.oneus.lab.sim.*
import kotlin.math.*

/** 一次解算结果 */
data class Solution(
    val ecef: Vec3,
    val clockRangeM: Double,
    val residuals: DoubleArray,
    val iterations: Int,
    val converged: Boolean,
    val covariance: Array<DoubleArray>?,   // 4×4,ECEF
    val hdop: Double,
    val pdop: Double,
    val gdop: Double,
    val numSats: Int
) {
    val ok: Boolean get() = converged && numSats >= 4

    val residualRms: Double
        get() = if (residuals.isEmpty()) 0.0
        else sqrt(residuals.sumOf { it * it } / residuals.size)

    /** 水平误差(距真值,m) */
    fun horizontalError(truth: Vec3): Double {
        val a = ecef.toGeo(); val b = truth.toGeo()
        val latM = 111_320.0
        val lonM = 111_320.0 * cos(Math.toRadians(b.latDeg))
        return hypot((a.latDeg - b.latDeg) * latM, wrap180(a.lonDeg - b.lonDeg) * lonM)
    }

    fun verticalError(truth: Vec3): Double = ecef.toGeo().altM - truth.toGeo().altM

    /** 解出的接收机钟差(ns) */
    fun clockNs(): Double = clockRangeM / C * 1e9
}

/**
 * 线性化最小二乘单点定位。
 *
 * 未知量 4 个:x, y, z, c·δt_r
 *   ∂PR/∂x   = (x_s − x_r)/ρ
 *   ∂PR/∂cδt = 1
 *
 * 这是**所有** GNSS 星座共用的解算内核 —— 不存在"GPS 的算法"和"北斗的算法"。
 */
class LsSolver(
    private val config: SolveConfig = SolveConfig(),
    /**
     * 单步最大位移(m)。冷启动的粗定位可能离真值上千公里,
     * 此时线性化误差很大,不加阻尼的高斯-牛顿会直接发散。
     * 这是标准的信赖域做法,不是数值技巧。
     */
    private val maxStepM: Double = 100_000.0,
    /** HDOP 超过这个值,解在数学上存在但在物理上不可信 */
    private val maxHdop: Double = 25.0
) {

    fun solve(
        obs: List<Measurement>,
        scenario: Scenario,
        gpsTimeSec: Double = 0.0,
        initial: Vec3? = null
    ): Solution? {
        if (obs.size < 4) return null

        val cor = Obs.correct(obs, scenario, config.iono, gpsTimeSec, config.relativityCorrected)

        val seeds = if (initial != null) listOf(initial) else coarseSeeds(cor)

        var best: Solution? = null
        for (seed in seeds) {
            val r = iterate(cor, seed)
            if (r != null && (best == null || r.residualRms < best.residualRms)) best = r
            if (best?.converged == true) break
        }
        return best
    }

    /**
     * 粗搜初始位置。
     *
     * 单个系统(GPS / 伽利略 / 格洛纳斯)可见星往往来自同一组轨道面,
     * 卫星单位矢量和可能偏出 1000 公里以上。从那么远的地方做线性化会卡在坏解上。
     *
     * 但要判断一个候选位置对不对,不需要迭代 —— 直接看**残差的平均水平**:
     * 真位置上所有伪距残差都接近零(只差钟差和误差项),错位置上则是几百公里。
     * 所以先在全球粗网格上按这个量筛一遍,再交给高斯-牛顿精化。
     * 这正是真实接收机"粗定位 + 精定位"两段式做的事。
     */
    private fun coarseSeeds(cor: List<CorrectedObs>): List<Vec3> {
        if (cor.isEmpty()) return emptyList()

        fun score(p: Vec3): Double {
            var sum = 0.0
            for (c in cor) sum += c.pr - c.m.svEcef.distTo(p)
            return abs(sum / cor.size)
        }

        val candidates = ArrayList<Pair<Double, Vec3>>()
        // 10° 网格,再加两极
        for (latDeg in -80..80 step 10) {
            for (lonDeg in -180..180 step 10) {
                val p = Geo(latDeg.toDouble(), lonDeg.toDouble(), 0.0).toEcef()
                candidates.add(score(p) to p)
            }
        }
        candidates.add(score(coldStart(cor.map { it.m })) to coldStart(cor.map { it.m }))

        candidates.sortBy { it.first }
        // 取最好的几个,避免局部最优
        return candidates.take(3).map { it.second }
    }

    private fun iterate(cor: List<CorrectedObs>, seed: Vec3): Solution? {
        var rcv = seed
        var clockRange = 0.0
        var last: Solution? = null

        for (iter in 0 until config.maxIter) {
            val n = cor.size
            val h = Array(n) { DoubleArray(4) }
            val v = DoubleArray(n)

            for (i in 0 until n) {
                val s = cor[i].m.svEcef
                val d = s - rcv
                val r = d.norm()
                if (r < 1.0) return null
                h[i][0] = -d.x / r
                h[i][1] = -d.y / r
                h[i][2] = -d.z / r
                h[i][3] = 1.0
                v[i] = cor[i].pr - (r + clockRange)
            }

            val w = DoubleArray(n) { i ->
                if (config.weighted) 1.0 / (cor[i].sigma * cor[i].sigma) else 1.0
            }

            val htw = Array(4) { DoubleArray(4) }
            val wv = DoubleArray(4)
            for (i in 0 until n) {
                for (r0 in 0 until 4) {
                    wv[r0] += w[i] * h[i][r0] * v[i]
                    for (c in 0 until 4) htw[r0][c] += w[i] * h[i][r0] * h[i][c]
                }
            }
            for (r in 0 until 4) htw[r][r] += 1e-6

            val dxRaw = LinAlg.solve(htw, wv) ?: return null

            // 阻尼:位移过大时按比例缩小,保证从粗定位出发也能收敛
            val rawStep = sqrt(dxRaw[0] * dxRaw[0] + dxRaw[1] * dxRaw[1] + dxRaw[2] * dxRaw[2])
            val damp = if (rawStep > maxStepM) maxStepM / rawStep else 1.0
            val dx = DoubleArray(4) { dxRaw[it] * damp }

            rcv = rcv + Vec3(dx[0], dx[1], dx[2])
            clockRange += dx[3]

            val step = sqrt(dx[0] * dx[0] + dx[1] * dx[1] + dx[2] * dx[2])

            val res = DoubleArray(n) { i -> cor[i].pr - (cor[i].m.range + clockRange) }
            val cov = LinAlg.inverse(htw)
            val dop = dopOf(cov, rcv)

            // 几何退化时法方程接近奇异:岭正则会让它"解出来",但结果毫无意义。
            // 与其输出一个漂亮的错误答案,不如如实标记为不可信。
            val geometryOk = !dop[0].isNaN() && dop[0] < maxHdop

            last = Solution(
                ecef = rcv, clockRangeM = clockRange, residuals = res,
                iterations = iter + 1,
                converged = step < config.tol && geometryOk,
                covariance = cov,
                hdop = dop[0], pdop = dop[1], gdop = dop[2], numSats = n
            )
            if (step < config.tol && geometryOk) return last
        }
        return last
    }

    /**
     * 冷启动初值:可见卫星单位矢量和的方向,投影到地球面。
     * 真实接收机用星历粗定位做同样的事,这里给一个够收敛的近似。
     */
    fun coldStart(obs: List<Measurement>): Vec3 {
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (m in obs) { val u = m.svEcef.unit(); sx += u.x; sy += u.y; sz += u.z }
        return Vec3(sx, sy, sz).unit() * (WGS84_A + 2000.0)
    }

    private fun dopOf(cov: Array<DoubleArray>?, rcv: Vec3): DoubleArray {
        if (cov == null) return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
        val lat = atan2(rcv.z, hypot(rcv.x, rcv.y))
        val lon = atan2(rcv.y, rcv.x)
        val sl = sin(lat); val cl = cos(lat); val so = sin(lon); val co = cos(lon)
        val axes = arrayOf(
            Vec3(-so, co, 0.0),
            Vec3(-sl * co, -sl * so, cl),
            Vec3(cl * co, cl * so, sl)
        )
        val p = Array(3) { i ->
            DoubleArray(3) { j ->
                var s = 0.0
                for (k in 0 until 3) for (l in 0 until 3) s += axes[k][i] * cov[k][l] * axes[l][j]
                s
            }
        }
        return doubleArrayOf(
            sqrt(max(p[0][0] + p[1][1], 0.0)),
            sqrt(max(p[0][0] + p[1][1] + p[2][2], 0.0)),
            sqrt(max(p[0][0] + p[1][1] + p[2][2] + cov[3][3], 0.0))
        )
    }
}

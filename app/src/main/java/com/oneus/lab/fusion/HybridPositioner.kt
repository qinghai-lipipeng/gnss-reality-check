package com.oneus.lab.fusion

import com.oneus.lab.sim.*
import com.oneus.lab.solve.Solution
import kotlin.math.*
import kotlin.random.Random

/** 辅助定位源 */
enum class AssistSource(val label: String, val short: String) {
    GNSS("卫星", "卫星"),
    WIFI("WiFi", "WiFi"),
    CELL("基站", "基站"),
    MAP("道路匹配", "贴路")
}

/** 融合开关 */
data class AssistConfig(
    val gnss: Boolean = true,
    val wifi: Boolean = false,
    val cell: Boolean = false,
    val mapMatch: Boolean = false
)

/** 地图上的一段道路 */
data class RoadSegment(
    val a: Geo,
    val b: Geo,
    val elevated: Boolean = false
)

/**
 * 教学用道路网。
 *
 * 真实地图匹配在立交桥上有著名的歧义问题:桥上桥下在二维地图上重合,
 * 一旦位置误差超过几十米,匹配器就可能把你贴到错误的那一层。
 * 这里显式构造这个场景,而不是假装它不存在。
 */
class RoadNetwork(val segments: List<RoadSegment>) {

    data class MatchResult(
        val point: Geo?,
        val distanceM: Double,
        val matched: Boolean,
        val wrongLevel: Boolean
    )

    /** 最近的段。maxDist 之外视为匹配失败(没有可信的候选)。 */
    fun nearest(p: Geo, maxDist: Double, elevation: Double = 0.0): MatchResult {
        var best: RoadSegment? = null
        var bestD = Double.MAX_VALUE
        for (s in segments) {
            val d = distanceToSegment(p, s)
            if (d < bestD) { bestD = d; best = s }
        }
        val seg = best ?: return MatchResult(null, Double.MAX_VALUE, false, false)
        val proj = projectOnSegment(p, seg)
        val matched = bestD <= maxDist
        // 位置误差大到一定程度,就有可能匹配到"另一层"的路
        val wrongLevel = matched && seg.elevated && bestD > maxDist * 0.6
        return MatchResult(if (matched) proj else null, bestD, matched, wrongLevel)
    }

    private fun projectOnSegment(p: Geo, s: RoadSegment): Geo {
        // 局部平面近似:米 → 度
        val lat0 = Math.toRadians((s.a.latDeg + s.b.latDeg) / 2.0)
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(lat0)
        val ax = s.a.lonDeg * mPerDegLon; val ay = s.a.latDeg * mPerDegLat
        val bx = s.b.lonDeg * mPerDegLon; val by = s.b.latDeg * mPerDegLat
        val px = p.lonDeg * mPerDegLon; val py = p.latDeg * mPerDegLat
        val vx = bx - ax; val vy = by - ay
        val len2 = vx * vx + vy * vy
        val t = if (len2 < 1e-9) 0.0 else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * vx; val qy = ay + t * vy
        return Geo(
            latDeg = qy / mPerDegLat,
            lonDeg = qx / mPerDegLon,
            altM = if (s.elevated) 12.0 else p.altM
        )
    }

    private fun distanceToSegment(p: Geo, s: RoadSegment): Double {
        val proj = projectOnSegment(p, s)
        return horizontalDistance(p, proj)
    }

    companion object {
        fun horizontalDistance(a: Geo, b: Geo): Double {
            val mPerDegLat = 111_320.0
            val mPerDegLon = 111_320.0 * cos(Math.toRadians(a.latDeg))
            return hypot(
                (a.latDeg - b.latDeg) * mPerDegLat,
                wrap180(a.lonDeg - b.lonDeg) * mPerDegLon
            )
        }
    }
}

/** 道路网构建器:为每个场景配一份合理路网 */
object RoadNetworks {
    fun forScenario(s: Scenario): RoadNetwork {
        val c = s.site
        val dLat = 0.0009     // ~100 m
        val dLon = 0.0011
        return when (s.id) {
            "bridge" -> RoadNetwork(
                listOf(
                    // 你在桥下的这条路
                    RoadSegment(Geo(c.latDeg - dLat, c.lonDeg - 0.004), Geo(c.latDeg + dLat, c.lonDeg + 0.004)),
                    // 头顶上横穿的桥 —— 二维上离你很近
                    RoadSegment(
                        Geo(c.latDeg - 0.002, c.lonDeg - dLon), Geo(c.latDeg - 0.002, c.lonDeg + dLon),
                        elevated = true
                    )
                )
            )
            else -> RoadNetwork(
                listOf(
                    RoadSegment(Geo(c.latDeg - dLat, c.lonDeg - 0.004), Geo(c.latDeg + dLat, c.lonDeg + 0.004)),
                    RoadSegment(Geo(c.latDeg - 0.0008, c.lonDeg - 0.006), Geo(c.latDeg + 0.0012, c.lonDeg + 0.006))
                )
            )
        }
    }
}

/**
 * 混合定位:卫星 + WiFi + 基站 + 道路匹配。
 *
 * 这是算法真正"发力"的地方 —— 它决定的是**能不能用、漂不漂**,
 * 而不是**精度天花板**。前提是猜得到信息:信号断了,再聪明也没用。
 */
class HybridPositioner(
    private val scenario: Scenario,
    private val roads: RoadNetwork = RoadNetworks.forScenario(scenario),
    private val seed: Long = 991L
) {

    data class Result(
        val ecef: Vec3,
        val sigmaM: Double,
        val errorM: Double,
        val used: List<AssistSource>,
        val notes: List<String> = emptyList()
    )

    private val truth = scenario.site

    fun locate(gnss: Solution?, config: AssistConfig): Result {
        val rng = Random(seed)
        val truthEcef = truth.toEcef()
        val parts = ArrayList<Pair<Vec3, Double>>()   // (位置, σ)
        val used = ArrayList<AssistSource>()
        val notes = ArrayList<String>()

        // —— 卫星 ——
        if (config.gnss && gnss != null) {
            // 卫星自身的不确定度由几何与残差决定
            val base = if (gnss.residualRms > 0.1) gnss.residualRms * 1.5 else 5.0
            val geom = if (gnss.hdop.isNaN()) 1.0 else gnss.hdop
            parts.add(gnss.ecef to (base * geom).coerceAtLeast(3.0))
            used.add(AssistSource.GNSS)
        }

        // —— WiFi ——
        if (config.wifi) {
            val density = scenario.wifiDensity
            if (density < 1.0) {
                notes.add("附近几乎没有 WiFi,猜不出来")
            } else {
                // 指纹定位精度随 AP 密度提升,密集城区约 5~8 m,稀疏处十几米
                val sigma = (14.0 * exp(-density / 260.0) + 2.0).coerceIn(2.0, 40.0)
                // 指纹库有系统性偏差:不是零均值
                val bias = (rng.nextDouble() - 0.5) * sigma * 0.7
                val p = truthEcef + Vec3(rng.nextGaussian() * sigma + bias, rng.nextGaussian() * sigma, 0.0)
                parts.add(p to sigma)
                used.add(AssistSource.WIFI)
            }
        }

        // —— 基站 ——
        if (config.cell) {
            val sigma = 300.0
            val p = truthEcef + Vec3(rng.nextGaussian() * sigma, rng.nextGaussian() * sigma, 0.0)
            parts.add(p to sigma)
            used.add(AssistSource.CELL)
        }

        // —— 道路匹配 ——
        if (config.mapMatch) {
            val base = parts.minByOrNull { it.second }?.first ?: truthEcef
            val p0 = base.toGeo()
            val m = roads.nearest(p0, maxDist = 60.0)
            when {
                !m.matched -> notes.add("离任何道路都超过 60 米,贴不上去")
                m.wrongLevel -> {
                    notes.add("贴错层了:匹配到头顶那座桥")
                    parts.add(m.point!!.toEcef() to 25.0)
                }
                else -> {
                    parts.add(m.point!!.toEcef() to 4.0)
                }
            }
            if (m.matched) used.add(AssistSource.MAP)
        }

        if (parts.isEmpty()) return Result(truthEcef, 1e9, 0.0, emptyList(), listOf("没有任何可用来源"))

        // 按方差倒数加权融合
        var sw = 0.0
        var ax = 0.0; var ay = 0.0; var az = 0.0
        for ((p, s) in parts) {
            val w = 1.0 / (s * s)
            sw += w
            ax += p.x * w; ay += p.y * w; az += p.z * w
        }
        val fused = Vec3(ax / sw, ay / sw, az / sw)
        val sigma = sqrt(1.0 / sw)

        val err = Solution(
            ecef = fused, clockRangeM = 0.0, residuals = DoubleArray(0), iterations = 0,
            converged = true, covariance = null,
            hdop = 0.0, pdop = 0.0, gdop = 0.0, numSats = 0
        ).horizontalError(truthEcef)

        return Result(fused, sigma, err, used, notes)
    }
}

private fun Random.nextGaussian(): Double {
    var u1 = nextDouble()
    if (u1 < 1e-12) u1 = 1e-12
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * nextDouble())
}

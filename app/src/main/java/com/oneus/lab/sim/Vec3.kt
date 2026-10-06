package com.oneus.lab.sim

import kotlin.math.*

/** 光速 (m/s) —— GPS ICD 定义 */
const val C = 299792458.0

/** 地球引力常数 μ = GM (m^3/s^2) */
const val MU = 3.986004418e14

/** 地球自转角速度 (rad/s) */
const val OMEGA_E = 7.2921151467e-5

/** WGS-84 长半轴 (m) */
const val WGS84_A = 6378137.0

/** WGS-84 扁率倒数相关 */
const val WGS84_F = 1.0 / 298.257223563

/** WGS-84 第一偏心率平方 */
val WGS84_E2: Double = WGS84_F * (2.0 - WGS84_F)

/** 相对论常数 F = -2*sqrt(mu)/c^2 (s/m^0.5) —— GPS ICD-GPS-200 */
const val RELATIVITY_F = -4.442807633e-10

/** GPS 基准半长轴 (m) —— 相对论偏心项公式中的 A */
const val GPS_SEMI_MAJOR_AXIS = 265601800.0

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) =
        Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun norm2() = x * x + y * y + z * z
    fun unit(): Vec3 {
        val n = norm()
        return if (n == 0.0) this else Vec3(x / n, y / n, z / n)
    }
    fun distTo(o: Vec3) = (this - o).norm()

    /** 分量下标:0=x, 1=y, 2=z */
    operator fun get(i: Int): Double = when (i) {
        0 -> x
        1 -> y
        else -> z
    }
}

/** 地理坐标:纬度/经度(度), 高程(m) */
data class Geo(val latDeg: Double, val lonDeg: Double, val altM: Double = 0.0) {
    val latRad: Double get() = Math.toRadians(latDeg)
    val lonRad: Double get() = Math.toRadians(lonDeg)

    /** 地理坐标 → ECEF */
    fun toEcef(): Vec3 {
        val lat = latRad
        val lon = lonRad
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val n = WGS84_A / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
        return Vec3(
            (n + altM) * cosLat * cos(lon),
            (n + altM) * cosLat * sin(lon),
            (n * (1.0 - WGS84_E2) + altM) * sinLat
        )
    }

    /** 方位角(度,北起顺时针)与高度角(度) */
    fun azElTo(target: Vec3): DoubleArray {
        val rx = toEcef()
        val e = rx.toEnu(origin = rx)
        val v = (target - rx)
        val enu = Vec3(v.dot(e.east), v.dot(e.north), v.dot(e.up))
        val el = asin(enu.z / enu.norm()) * 180.0 / PI
        var az = atan2(enu.x, enu.y) * 180.0 / PI
        if (az < 0) az += 360.0
        return doubleArrayOf(az, el)
    }
}

/** 局部 ENU 基向量 */
class EnuBasis(val east: Vec3, val north: Vec3, val up: Vec3)

fun Vec3.toEnu(origin: Vec3): EnuBasis {
    val lat = atan2(origin.z, sqrt(origin.x * origin.x + origin.y * origin.y))
    val lon = atan2(origin.y, origin.x)
    val sl = sin(lat); val cl = cos(lat)
    val so = sin(lon); val co = cos(lon)
    return EnuBasis(
        east = Vec3(-so, co, 0.0),
        north = Vec3(-sl * co, -sl * so, cl),
        up = Vec3(cl * co, cl * so, sl)
    )
}

/** ECEF → 地理坐标(Vermeille / 迭代法, 精度足够) */
fun Vec3.toGeo(): Geo {
    val r = sqrt(x * x + y * y)
    val lon = atan2(y, x)
    var lat = atan2(z, r * (1.0 - WGS84_E2))
    var h = 0.0
    repeat(8) {
        val sl = sin(lat)
        val n = WGS84_A / sqrt(1.0 - WGS84_E2 * sl * sl)
        h = r / cos(lat) - n
        lat = atan2(z, r * (1.0 - WGS84_E2 * n / (n + h)))
    }
    return Geo(lat * 180.0 / PI, lon * 180.0 / PI, h)
}

fun wrap180(deg: Double): Double {
    var d = deg
    while (d > 180.0) d -= 360.0
    while (d < -180.0) d += 360.0
    return d
}

fun wrap360(deg: Double): Double {
    var d = deg
    while (d < 0.0) d += 360.0
    while (d >= 360.0) d -= 360.0
    return d
}

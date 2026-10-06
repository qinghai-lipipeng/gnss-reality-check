package com.oneus.lab.solve

import kotlin.math.*

/** 小矩阵工具:解线性方程组与求逆(高斯消元 + 部分主元) */
object LinAlg {

    /** 解 A x = b,奇异返回 null */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() }
        val v = b.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-14) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            val tv = v[col]; v[col] = v[piv]; v[piv] = tv
            val d = m[col][col]
            for (c in col until n) m[col][c] /= d
            v[col] /= d
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                v[r] -= f * v[col]
            }
        }
        return v
    }

    /** 求逆,奇异返回 null */
    fun inverse(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = Array(n) { i -> a[i].copyOf() }
        val inv = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-14) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            val ti = inv[col]; inv[col] = inv[piv]; inv[piv] = ti
            val d = m[col][col]
            for (c in 0 until n) { m[col][c] /= d; inv[col][c] /= d }
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (c in 0 until n) {
                    m[r][c] -= f * m[col][c]
                    inv[r][c] -= f * inv[col][c]
                }
            }
        }
        return inv
    }

    /** 转置 */
    fun transpose(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val t = Array(n) { i -> DoubleArray(n) { j -> a[j][i] } }
        return t
    }
}

package com.oneus.lab

import com.oneus.lab.sim.*
import com.oneus.lab.solve.*
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PerfTest {

    @Test
    fun `性能基准_滑块一次重算的耗时`() {
        val b = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        // 预热
        LabEngine.filterRun(Scenarios.OPEN, b, epochs = 6)
        val t0 = System.nanoTime()
        LabEngine.filterRun(Scenarios.OPEN, b, epochs = 36)
        val ms = (System.nanoTime() - t0) / 1e6
        println("BENCH filterRun(36历元, 1000粒子) = $ms ms")

        val t1 = System.nanoTime()
        LabEngine.filterRun(Scenarios.OPEN, b, epochs = 36, particleCount = 250)
        val ms2 = (System.nanoTime() - t1) / 1e6
        println("BENCH filterRun(36历元, 250粒子) = $ms2 ms")

        // 一次单历元解算的成本(粗搜 vs 热启动)
        val obs = Simulator().generate(Scenarios.OPEN, GnssSystem.entries.toSet(), b, 43200.0, 1L)
        val cor = Obs.correct(obs, Scenarios.OPEN, IonoCorrector.TecModel(), 43200.0)
        val cold = LsFilter(weighted = true)
        val t2 = System.nanoTime(); cold.run(cor, 0); val coldMs = (System.nanoTime() - t2) / 1e6
        val warm = cold.run(cor, 0)?.pos
        val t3 = System.nanoTime(); cold.run(cor, 1, warm); val warmMs = (System.nanoTime() - t3) / 1e6
        println("BENCH 单历元解: 冷启动(含粗搜) = $coldMs ms, 热启动 = $warmMs ms")
        println("BENCH 可见卫星 = ${obs.size}")
    }

    @Test
    fun `OLS 与 WLS 必须是两条不同的曲线`() {
        // 回归测试:曾经两者共用同一次加权解,画出来一模一样
        val b = ErrorBudget(ionoHandling = IonoHandling.TEC_MODEL)
        val traces = LabEngine.filterRun(Scenarios.OPEN, b, epochs = 30)
        val ols = traces.first { it.short == "OLS" }
        val wls = traces.first { it.short == "WLS" }
        println("DIFF OLS=${ols.points.take(5)} WLS=${wls.points.take(5)}")
        val same = ols.points.zip(wls.points).count { p -> abs(p.first - p.second) < 1e-12 }
        assertTrue("OLS 与 WLS 有 $same 个历元完全相同,说明还是共用了同一次解", same < ols.points.size / 2)
    }
}

package com.oneus.lab

import com.oneus.lab.sim.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 「没有解」不等于「误差为零」。
 *
 * 线上事故:一颗星都收不到的场景被报告成史上最好成绩。
 * 链路是 `solution == null` → `errorM = 0.0` → `errorColor(0.0)` 判成**绿色**
 * → 显示「水平误差 0.0000 m」→ 第 09 关 `0.0 <= target` 判**达标** → 弹出「过了。」。
 *
 * 也就是说:用户专挑最不可能成功的场景,反而通关了 —— 激励方向是反的。
 *
 * 0.0 会被 min/max 当成一个"误差恰好为零"的真实数据点,所以这件事没法靠"显示层小心点"解决,
 * 必须在类型上让「解不出来」和「解得非常好」能被区分开:horizontalError / passes。
 */
class NoSolutionTest {

    /** 和界面上同一份预算,不另配一份(测试与屏幕必须同源) */
    private fun cleanBudget() = ErrorBudget(
        receiverClockNs = 0.0, satClockErrorM = 0.0, relativityEnabled = true,
        ionoHandling = IonoHandling.TEC_MODEL, tropoEnabled = true,
        ephemerisErrorM = 0.0, multipathRemoved = true
    )

    private fun shotOf(sc: Scenario) =
        LabEngine.shot(sc, GnssSystem.entries.toSet(), cleanBudget())

    @Test
    fun `桥下与室内根本解不出位置`() {
        for (sc in listOf(Scenarios.UNDER_BRIDGE, Scenarios.INDOOR)) {
            val s = shotOf(sc)
            println("TDIAG ${sc.id} sats=${s.numSats} solution=${s.solution != null} errorM=${s.errorM}")
            assertEquals("${sc.id} 应当可见星为 0", 0, s.numSats)
            assertNull("${sc.id} 一颗星都没有,不可能有解", s.solution)
        }
    }

    @Test
    fun `无解时误差必须是null而不是0`() {
        for (sc in listOf(Scenarios.UNDER_BRIDGE, Scenarios.INDOOR)) {
            val s = shotOf(sc)
            assertNull("${sc.id} 无解时 horizontalError 必须是 null", s.horizontalError)
            assertNull("${sc.id} 无解时 verticalError 必须是 null", s.verticalError)
        }
    }

    @Test
    fun `无解永远不判达标_哪怕目标宽松到100米`() {
        for (sc in listOf(Scenarios.UNDER_BRIDGE, Scenarios.INDOOR)) {
            val s = shotOf(sc)
            for (target in listOf(0.5, 1.0, 5.0, 100.0)) {
                // 这两条一起看才是完整的回归锁:
                // 旧写法 errorM <= target 在这里**确实成立**(errorM 被吞成了 0.0),
                // 说明陷阱本身还在;passes() 必须挡住它。
                assertTrue(
                    "旧判定在本场景仍然会误判达标,这正是事故的机制",
                    s.errorM <= target
                )
                assertFalse(
                    "${sc.id} target=$target 无解必须判未达标",
                    s.passes(target)
                )
            }
        }
    }

    @Test
    fun `开阔地确实能达标_规则不能写死成永不全过`() {
        val s = shotOf(Scenarios.OPEN)
        assertNotNull("开阔地应当有解", s.horizontalError)
        assertTrue("开阔地 50 m 的目标应当轻松通过,实测 ${s.errorM} m", s.passes(50.0))
    }

    @Test
    fun `第06节滑块必须真的推动误差_死控件的回归锁`() {
        // 事故:标签写着「卫星间钟差」,提示写着「四条曲线会一起飞出去」,
        // 代码注入的却是 receiverClockNs —— 第 01 节花整节说明会被完整吸收的共模量。
        // 后果是拖到 0 / 50 / 200 ns,三条曲线完全一样,滑块是个死控件。
        val errs = listOf(0.0, 50.0, 200.0).map { ns ->
            LabEngine.shot(
                Scenarios.OPEN, GnssSystem.entries.toSet(),
                cleanBudget().copy(satClockErrorM = ns * 0.299792458)
            ).errorM
        }
        println("TDIAG sat-clock slider errors=$errs")
        assertTrue("50 ns 必须明显改变误差,实测 $errs", errs[1] > errs[0] + 1.0)
        assertTrue("200 ns 必须明显改变误差,实测 $errs", errs[2] > errs[1] + 1.0)
    }

    @Test
    fun `共模的接收机钟差推不动这个滑块_两类钟差必须区分得开`() {
        // 与上一条互为对照:同一个"时间偏差"滑块,注入接收机钟差就是纹丝不动。
        // 这条不是为了证明共模无害,而是把两类钟差的差别锁死在测试里,
        // 免得以后有人又把它们混为一谈。
        val common = listOf(0.0, 1000.0).map { ns ->
            LabEngine.shot(
                Scenarios.OPEN, GnssSystem.entries.toSet(),
                cleanBudget().copy(receiverClockNs = ns)
            ).errorM
        }
        println("TDIAG receiver common-clock errors=$common")
        assertTrue("接收机共模钟差 1μs 几乎不改变位置,实测 $common", abs(common[1] - common[0]) < 3.0)
    }
}
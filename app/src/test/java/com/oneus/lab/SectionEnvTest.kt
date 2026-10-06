package com.oneus.lab

import com.oneus.lab.ui.charts.Depth
import com.oneus.lab.ui.experiments.SectionEnv
import org.junit.Assert.*
import org.junit.Test

/**
 * [SectionEnv] 的纯逻辑回归测试。
 *
 * 这一层以前根本不存在:深度、术语、跳转、回首页是几个平行参数分别往下穿,
 * 穿到 ScreenBody 就断了 —— 首页画着「术语:人话/专业」,而没有任何一条参数路径
 * 能把它递进某一节,于是 `term()` 全项目 1 处定义、0 处调用。
 *
 * 收成 SectionEnv 之后,"每节的环境"是可测的纯数据了。
 */
class SectionEnvTest {

    private val TOTAL = 11

    private fun env(
        current: Int,
        total: Int = TOTAL,
        tech: Boolean = false
    ) = SectionEnv(
        current = current,
        total = total,
        code = "00",
        depth = Depth.SHALLOW,
        tech = tech,
        onDepth = {},
        onTech = {},
        onGo = {},
        onHome = {}
    )

    @Test
    fun `顶栏节号只有这一个来源`() {
        assertEquals("第 1 / 11 节", env(0).ordinalLabel)
        assertEquals("第 11 / 11 节", env(10).ordinalLabel)
    }

    @Test
    fun `进度条首尾精确落在0和1`() {
        assertEquals(0f, env(0).progress, 1e-6f)
        assertEquals(1f, env(TOTAL - 1).progress, 1e-6f)
    }

    @Test
    fun `进度条在只有一节时不除以0`() {
        // total-1 = 0,不钳住就是 0/0 = NaN,而 NaN 会一路传到 LinearProgressIndicator
        assertEquals(0f, env(0, total = 1).progress, 1e-6f)
        assertEquals(0f, env(0, total = 0).progress, 1e-6f)
    }

    @Test
    fun `进度条对越界节号是钳过的`() {
        for (bad in listOf(-1, -5, TOTAL, TOTAL + 3, Int.MAX_VALUE)) {
            val p = env(bad).progress
            assertTrue("progress($bad) = $p 越界了", p.isFinite() && p in 0f..1f)
        }
    }

    @Test
    fun `末节标记与导航函数保持一致`() {
        assertTrue("第 11 节应当是末节", env(TOTAL - 1).isLast)
        assertFalse("第 10 节不是末节", env(TOTAL - 2).isLast)
    }

    @Test
    fun `术语开关真的能改变取到的词`() {
        assertEquals("人话", env(0, tech = false).term("人话", "DOP"))
        assertEquals("DOP", env(0, tech = true).term("人话", "DOP"))
    }

    @Test
    fun `env可以直接持有并触发回调`() {
        var picked = -1
        var home = false
        var depth: Depth? = null
        var tech: Boolean? = null
        val e = SectionEnv(
            current = 3, total = TOTAL, code = "03", depth = Depth.MEDIUM, tech = false,
            onDepth = { depth = it },
            onTech = { tech = it },
            onGo = { picked = it },
            onHome = { home = true }
        )
        e.onGo(7); e.onHome(); e.onDepth(Depth.DEEP); e.onTech(true)
        assertEquals(7, picked)
        assertTrue(home)
        assertEquals(Depth.DEEP, depth)
        assertEquals(true, tech)
    }
}
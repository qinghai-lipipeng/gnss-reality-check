package com.oneus.lab

import com.oneus.lab.ui.charts.Depth
import com.oneus.lab.ui.experiments.SectionEnv
import com.oneus.lab.ui.experiments.SectionStore
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
        tech: Boolean = false,
        store: SectionStore = SectionStore(),
        code: String = "00"
    ) = SectionEnv(
        current = current,
        total = total,
        code = code,
        depth = Depth.SHALLOW,
        tech = tech,
        store = store,
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
            store = SectionStore(),
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

    // ——————————————————————————————————————————————
    // 状态槽:横跳不丢参数
    // ——————————————————————————————————————————————

    @Test
    fun `同一节同一个键返回同一个槽`() {
        val s = SectionStore()
        var built = 0
        val a = s.state("k") { built++; "v" }
        val b = s.state("k") { built++; "v" }
        assertSame("同一个键必须是同一个槽", a, b)
        assertEquals("init 只能跑一次", 1, built)
    }

    @Test
    fun `槽里的值会被后续读取看到`() {
        val s = SectionStore()
        s.state("k") { 0 }.value = 42
        assertEquals(42, s.state("k") { 0 }.value)
    }

    @Test
    fun `不同节用同一个键名也不会串`() {
        // 这正是原来「离开一节参数全归零」的另一面:归零丢的是自己的状态,
        // 共享则会互相污染。两个方向都得锁住。
        val s = SectionStore()
        val e2 = env(2, store = s, code = "02")
        val e8 = env(8, store = s, code = "08")
        e2.state("scenarioId") { "open" }.value = "canyon"
        assertEquals("canyon", e2.state("scenarioId") { "open" }.value)
        assertEquals("第 08 节必须拿到自己的默认值", "city", e8.state("scenarioId") { "city" }.value)
    }

    @Test
    fun `横跳到别的节再回来_参数还在`() {
        val s = SectionStore()
        val e6 = env(6, store = s, code = "06")
        val e3 = env(3, store = s, code = "03")
        // 在第 06 节调参
        e6.state("scenarioId") { "open" }.value = "city"
        e6.state("clockNs") { 0f }.value = 137f
        // 切到第 03 节(模拟页面离开组合),槽不受影响
        e3.state("scenarioId") { "open" }.value = "canyon"
        // 回到第 06 节
        assertEquals("city", e6.state("scenarioId") { "open" }.value)
        assertEquals(137f, e6.state("clockNs") { 0f }.value, 1e-6f)
        assertEquals("第 03 节自己的值也没被动", "canyon", e3.state("scenarioId") { "open" }.value)
    }

    @Test
    fun `键名带上下文时相当于换一组新槽`() {
        // 第 09 挑战页原来用 remember(idx):换关卡重置参数。
        // 把 idx 拼进键名就能保住这条语义,同时 idx 自己还能跨节存活。
        val s = SectionStore()
        val e = env(9, store = s, code = "09")
        e.state("knob/0/clockNs") { 0f }.value = 10f
        e.state("knob/1/clockNs") { 0f }.value = 20f
        assertEquals(10f, e.state("knob/0/clockNs") { 0f }.value, 1e-6f)
        assertEquals(20f, e.state("knob/1/clockNs") { 0f }.value, 1e-6f)
    }

    @Test
    fun `空列表之类的可变默认值不会被复用共享`() {
        // init 每个新键各跑一次,所以不会有两个槽拿到同一个 List 实例。
        val s = SectionStore()
        val a = s.state("a") { mutableListOf<String>() }
        val b = s.state("b") { mutableListOf<String>() }
        a.value.add("x")
        assertTrue(b.value.isEmpty())
    }
}
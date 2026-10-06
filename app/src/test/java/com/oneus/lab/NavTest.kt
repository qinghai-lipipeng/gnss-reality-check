package com.oneus.lab

import com.oneus.lab.ui.isLastSection
import com.oneus.lab.ui.nextSection
import com.oneus.lab.ui.prevSection
import org.junit.Assert.*
import org.junit.Test

/**
 * 导航回归测试。
 *
 * 线上事故:「下一节」按钮点了没反应。根因是
 *   onNext = { if (current + 1 < entries.size) current + 1 else -1 }
 * 漏了赋值 —— lambda 算完就把结果丢掉,状态没被写回。
 * Kotlin 允许 `() -> Unit` 的 lambda 末表达式不是 Unit,所以它编译通过、一声不响,
 * 35 个物理测试全绿也没抓到。
 *
 * 这组测试锁住"算出新节号"这段纯逻辑,以后再漏赋值会立刻红。
 */
class NavTest {

    private val TOTAL = 11

    @Test
    fun `从第1节依次点下一节能走到第11节`() {
        var cur = 0
        val visited = mutableListOf(cur)
        while (cur >= 0 && cur < TOTAL - 1) {
            val next = nextSection(cur, TOTAL)
            assertNotEquals("第 $cur 节点下一节,状态必须变化", cur, next)
            cur = next
            visited.add(cur)
        }
        assertEquals("应当恰好走完 11 节", (0 until TOTAL).toList(), visited)
    }

    @Test
    fun `下一节必须真的改变节号_这是事故的回归锁`() {
        for (i in 0 until TOTAL - 1) {
            assertEquals("第 $i 节的下一节应是 ${i + 1}", i + 1, nextSection(i, TOTAL))
        }
    }

    @Test
    fun `末节的下一节回首页而不是越界`() {
        assertEquals(-1, nextSection(TOTAL - 1, TOTAL))
        assertTrue(isLastSection(TOTAL - 1, TOTAL))
        assertFalse(isLastSection(TOTAL - 2, TOTAL))
    }

    @Test
    fun `上一节递减_第1节回首页而不是禁用`() {
        for (i in 1 until TOTAL) assertEquals(i - 1, prevSection(i, TOTAL))
        assertEquals(-1, prevSection(0, TOTAL))
    }

    @Test
    fun `越界输入不会产生非法节号`() {
        for (bad in listOf(-1, -5, TOTAL, TOTAL + 3, Int.MAX_VALUE)) {
            val n = nextSection(bad, TOTAL)
            assertTrue("nextSection($bad) 越界了: $n", n == -1 || n in 0 until TOTAL)
            val p = prevSection(bad, TOTAL)
            assertTrue("prevSection($bad) 越界了: $p", p == -1 || p in 0 until TOTAL)
        }
    }
}

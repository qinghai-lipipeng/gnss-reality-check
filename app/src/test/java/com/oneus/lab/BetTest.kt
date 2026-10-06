package com.oneus.lab

import com.oneus.lab.ui.charts.Bet
import com.oneus.lab.ui.charts.BetVerdict
import com.oneus.lab.ui.charts.judge
import org.junit.Assert.*
import org.junit.Test

class BetTest {

    @Test
    fun `未揭晓时任何选项都是中性`() {
        assertEquals(BetVerdict.NEUTRAL, judge(0, 1, false))
        assertEquals(BetVerdict.NEUTRAL, judge(1, 1, false))
    }

    @Test
    fun `选对判正确_选错判错误`() {
        assertEquals(BetVerdict.CORRECT, judge(1, 1, true))
        assertEquals(BetVerdict.WRONG, judge(0, 1, true))
        assertEquals(BetVerdict.WRONG, judge(2, 1, true))
    }

    @Test
    fun `正确下标越界必须当场报错而不是静默判错`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            Bet(
                question = "q",
                options = listOf("a", "b"),
                correctIndex = 5,          // 越界
                reveal = "r"
            )
        }
        assertTrue(e.message!!.contains("正确下标"))
    }

    /**
     * 回归测试。
     *
     * 曾经 BetCard 里写死了 `i == 0`,把正确答案判定成第一个选项,
     * 四处下注卡全部判反 —— App 把正确答案标 ✗,还配了一句
     * 与判定自相矛盾的解释(用户选「差不多」被判错,而解释写着「差不多」对)。
     */
    @Test
    fun `下注卡的正确答案不能被写死在第一个选项`() {
        val bets = listOf(
            Bet("给接收机加 1 微秒的钟差,地图会偏多少?",
                listOf("约 300 米", "几乎不动"), 1, "r"),
            Bet("只用北斗,会比只用 GPS 准很多吗?",
                listOf("会,明显更准", "差不多,在同一个量级", "北斗反而更差"), 1, "r"),
            Bet("城市里那一点点精度,是卫星给的还是手机猜的?",
                listOf("大部分是卫星", "大部分是猜的", "一半一半"), 1, "r"),
            Bet("换更高级的滤波器,能把误差压下来多少?",
                listOf("至少一半", "一个数量级", "几乎压不动"), 2, "r")
        )
        // 正确下标不能全落在 0 —— 那样就说明又退回硬编码了
        val zeros = bets.count { it.correctIndex == 0 }
        assertTrue("4 道题的正确下标不应全为 0(实际 $zeros 个)", zeros < bets.size)
        // 逐题:选正确下标必须判对,选其它必须判错
        bets.forEach { b ->
            assertEquals(
                "题目「${b.question}」选正确答案却没判对",
                BetVerdict.CORRECT, judge(b.correctIndex, b.correctIndex, true)
            )
            b.options.indices.filter { it != b.correctIndex }.forEach { wrong ->
                assertEquals(
                    "题目「${b.question}」选第 $wrong 项(错误项)却判对了",
                    BetVerdict.WRONG, judge(wrong, b.correctIndex, true)
                )
            }
            // 揭晓文案不能为空,否则用户选完没有解释
            assertTrue("题目「${b.question}」缺少揭晓文案", b.reveal.isNotBlank())
        }
    }
}

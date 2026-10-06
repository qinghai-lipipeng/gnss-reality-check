package com.oneus.lab.ui.experiments

import com.oneus.lab.ui.charts.Depth

/**
 * 一节所处的**全部**环境。
 *
 * 以前 depth / onDepth / tech / onGo / onHome 是五六个**平行参数**从 AppRoot 一路穿到每个实验页:
 * 每 11 个实验页各写一遍签名,每加一个跨节能力就要把 11 处签名全改一遍。
 *
 * 这正是"术语开关"死掉的直接原因:
 * AppRoot 拥有 `tech`,首页也画了「术语:人话/专业」,但 `ScreenBody` 根本没有参数能把它递下去,
 * 于是 `term()` 全项目 **1 处定义、0 处调用** —— 控件承诺了一个它够不到的能力。
 *
 * 收成一个对象之后,新增跨节能力只改这一个类;实验页也重新变回"只讲自己那件事"。
 *
 * 纯数据 + 纯函数,不含 Composable,可以进单元测试。
 */
data class SectionEnv(
    /** 节号 0..total-1。只在节内构造;-1 表示在首页,不属于任何一节。 */
    val current: Int,
    val total: Int,
    /** 页面左上角的节号标记:"00"…"09",收尾节是 "★" */
    val code: String,
    val depth: Depth,
    /** 术语开关:false = 人话,true = 专业术语 */
    val tech: Boolean,
    val onDepth: (Depth) -> Unit,
    val onTech: (Boolean) -> Unit,
    val onGo: (Int) -> Unit,
    val onHome: () -> Unit
) {
    /** 顶栏的「第 n / N 节」。节号只有这一个来源,不再另算一份。 */
    val ordinalLabel: String get() = "第 ${current + 1} / $total 节"

    /**
     * 顶栏进度条 0f..1f。
     *
     * total <= 1 时直接返回 0f:只有一节时 `total - 1` 为 0,不钳住就是 0/0 = NaN,
     * 而 NaN 会一路传到 LinearProgressIndicator。
     * 越界的 current 先钳再除,导航函数永远不该吐出非法节号。
     */
    val progress: Float
        get() = if (total > 1) current.coerceIn(0, total - 1) / (total - 1f) else 0f

    /** 是否最后一节 —— 末节时底栏主按钮变成「回到首页」。 */
    val isLast: Boolean get() = current + 1 >= total

    /** 术语开关的读法。[plain] 是人话,[formal] 是标准术语。 */
    fun term(plain: String, formal: String): String = if (tech) formal else plain
}
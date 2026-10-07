package com.oneus.lab.ui.experiments

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
    /** 本节的状态槽。整份 App 一个实例,由 AppRoot 持有。 */
    val store: SectionStore,
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

    /**
     * 本节的一个状态槽,自动按 `节号/键名` 隔离。
     *
     * 取代 `remember { mutableStateOf(...) }`。区别在**谁持有状态**:
     * `remember` 把它交给组合 —— 而切节会把整页踢出组合,状态随之消失,
     * 横跳一次自己刚调好的参数就全没了。槽归 [SectionStore] 持有,跟着**节**走。
     *
     * ```
     * var scenarioId by env.state("scenario") { "open" }
     * ```
     *
     * 键名相同就是同一个槽,`init` 只在第一次求值时跑一次。
     * 需要"换个上下文就重置"时,把上下文拼进键名:`env.state("knob/$idx") { 0f }`。
     */
    fun <T> state(key: String, init: () -> T): MutableState<T> =
        store.state("$code/$key", init)
}

/**
 * 按「节号/键名」分槽的状态持有者。整份 App 一个实例。
 *
 * 以前 11 个实验页的 40 处状态全靠 `remember` 持有,而状态跟着组合走:
 * `ScreenBody` 切节时旧页整个离开组合,`remember` 的槽随之销毁。
 * 底栏存在的意义就是让用户横跳,横跳的代价却是丢掉自己刚做完的实验 ——
 * 这才是"课程像散页"的技术根源。
 *
 * 纯 Kotlin,不碰 Compose 运行时,可以直接进单元测试。
 */
class SectionStore {
    private val slots = HashMap<String, MutableState<*>>()

    /**
     * 取一个槽。同一 key 永远返回同一个 [MutableState],`init` 只跑第一次。
     *
     * 这里刻意**不用** `remember`:记忆已经是这个 map 本身了。
     * 再套一层 `remember` 只会多一次重组的假象,还会让人以为状态又回到组合上了。
     */
    fun <T> state(key: String, init: () -> T): MutableState<T> {
        @Suppress("UNCHECKED_CAST")
        slots[key]?.let { return it as MutableState<T> }
        val created = mutableStateOf(init())
        slots[key] = created
        return created
    }
}
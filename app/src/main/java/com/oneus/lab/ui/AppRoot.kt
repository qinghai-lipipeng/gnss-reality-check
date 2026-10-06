package com.oneus.lab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneus.lab.ui.charts.*
import com.oneus.lab.ui.experiments.*
import com.oneus.lab.ui.theme.*

private data class Entry(
    val index: String,
    val title: String,
    val blurb: String,
    val tier: String
)

private val entries = listOf(
    Entry("00", "卫星只负责「量」", "三层分工:量 / 算 / 猜", "入门"),
    Entry("01", "光速尺", "1 微秒 = 300 米,但你猜不到后面", "入门"),
    Entry("02", "四颗星和几何", "能解 ≠ 解得准", "机制"),
    Entry("03", "星座之争", "换星座,还是改时间一致性?", "★ 核心"),
    Entry("04", "多给了你料", "双频与增强广播", "机制"),
    Entry("05", "城市蓝点的真相", "你体感的准,有多少是猜的", "★ 核心"),
    Entry("06", "滤波器之争", "换算法,误差纹丝不动", "★ 高潮"),
    Entry("07", "差分与 PPP", "厘米级靠的是信息", "机制"),
    Entry("08", "自由沙盒", "九个旋钮,随便玩", "自由"),
    Entry("09", "通关挑战", "用「量」的思维过关", "挑战"),
    Entry("★", "一张图收尾", "误差预算 + 误解澄清", "收尾")
)

// ══════════════════════════════════════════
// 导航意图 —— 抽成纯函数,才能被单元测试覆盖
//
// 背景:这里曾经写成 `{ if (current + 1 < entries.size) current + 1 else -1 }`,
// 漏了赋值,lambda 算完就把结果丢掉,「下一节」点了没反应。
// Kotlin 允许 `() -> Unit` 的 lambda 末表达式不是 Unit,所以它编译通过、一声不响。
// 教训:凡是"算出一个新位置再写回状态"的地方,都应该先抽成纯函数再接 UI。
// ══════════════════════════════════════════

/** 下一节应该去哪。-1 表示回首页。越界一律回首页,绝不越界。 */
fun nextSection(current: Int, total: Int): Int =
    if (current in 0 until total && current + 1 < total) current + 1 else -1

/**
 * 上一节应该去哪。第 1 节点"上一节"回首页,而不是禁用。
 * 上界同样要钳:只判 `current > 0` 的话,一个越界的 current 会原样往下传一格,
 * 导航函数永远不该吐出非法节号。
 */
fun prevSection(current: Int, total: Int): Int =
    if (current in 1 until total) current - 1 else -1

/** 某一节是否已是最后一节。 */
fun isLastSection(current: Int, total: Int): Boolean = current + 1 >= total

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppRoot() {
    // 导航只有一个状态:current = 当前**节号**(0..entries.size-1),-1 表示在首页。
    // 详情页是「首页列表的下一层」,不是「上一节的下一层」,所以不需要导航栈 ——
    // 一旦引入栈,「栈深度」和「节号」就会变成两个可能不一致的量
    // (前两轮 bug:顶栏报「第 1/11 节」、末节漏出「下一节」、返回弹到上一节,都源于此)。
    // 用 rememberSaveable 而非 remember:进程内旋转/重建不丢当前节。
    var current by rememberSaveable { mutableStateOf(-1) }
    val inSection = current >= 0

    var depthName by rememberSaveable { mutableStateOf(Depth.SHALLOW.name) }
    var tech by rememberSaveable { mutableStateOf(false) }
    val depth = Depth.valueOf(depthName)

    fun go(i: Int) { current = i }
    fun home() { current = -1 }

    // 一节所处的全部环境,在这里**只构造一次**。
    // 以前 depth / tech / onGo / onHome 是几个平行参数分别往下穿,穿到 ScreenBody 就断了 ——
    // 「术语」开关就是这么变成死控件的:首页画了它,可没有任何一条参数路径能把它递进某一节。
    val env = SectionEnv(
        current = current,
        total = entries.size,
        code = entries.getOrNull(current)?.index ?: "",
        depth = depth,
        tech = tech,
        onDepth = { depthName = it.name },
        onTech = { tech = it },
        onGo = { go(it) },
        onHome = { home() }
    )

    // 系统返回手势/按键:在节内一律回首页(与左上角返回一致);
    // 在首页时 enabled = false,交还给系统去退出应用 —— 「侧滑直接退出」的根因修复仍然成立。
    BackHandler(enabled = inSection) { home() }

    var sheetOpen by remember { mutableStateOf(false) }

    OneUsTheme(dynamicColor = false) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (inSection) {
                    Column {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        entries.getOrNull(current)?.title ?: "",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    // 节号就是 env.current 本身,没有第二个来源
                                    Text(
                                        env.ordinalLabel,
                                        style = mono10()
                                    )
                                }
                            },
                            navigationIcon = {
                                // 详情页 → 列表:一律回首页,不是回上一节
                                TextButton(onClick = { home() }) { Text("←", style = MaterialTheme.typography.titleLarge) }
                            },
                            actions = {
                                TextButton(onClick = { sheetOpen = true }) {
                                    Text("目录", style = MaterialTheme.typography.labelLarge)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        LinearProgressIndicator(
                            // 0..(total-1) 映射到 0f..1f;第一节 0f,末节 1f。越界在 env 里钳过了。
                            progress = { env.progress },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                    }
                }
            },
            bottomBar = {
                if (inSection) {
                    SectionFooter(
                        current = current,
                        total = entries.size,
                        onPrev = { current = prevSection(current, entries.size) },
                        onNext = { current = nextSection(current, entries.size) },
                        onFinish = { home() }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                if (!inSection) {
                    Home(depth, { depthName = it.name }, tech, { tech = it }, ::go)
                } else {
                    ScreenBody(env)
                }
            }
        }

        if (sheetOpen) {
            SectionPicker(
                current = current,
                // 选中第 i 节就是「当前 = i」,不再往任何栈里压
                onPick = { go(it); sheetOpen = false },
                onHome = { home(); sheetOpen = false },
                onDismiss = { sheetOpen = false }
            )
        }
    }
}

/**
 * 把节号分发到具体页面。
 *
 * 每个页面只收一个 [SectionEnv]:它要什么就从 env 上拿,
 * 路由不再负责把 depth / tech / onGo 一路拆开往下递。
 *
 * @param env 当前这一节的全部环境;节号是 env.current,全局唯一的状态源
 */
@Composable
private fun ScreenBody(env: SectionEnv) {
    when (env.current) {
        0 -> Exp00_Opening(env)
        1 -> Exp01_Clock(env)
        2 -> Exp02_Geometry(env)
        3 -> Exp03_Constellation(env)
        4 -> Exp04_MoreStuff(env)
        5 -> Exp05_Hybrid(env)
        6 -> Exp06_Filters(env)
        7 -> Exp07_Precision(env)
        8 -> Exp08_Sandbox(env)
        9 -> Exp09_Challenge(env)
        10 -> Closing(env)
        // 不写 else:current 是受控状态,一旦出现越界值宁可什么都不渲染,
        // 也不要静默掉进收尾页 —— 静默兜底会把 bug 藏起来。
        else -> Unit
    }
}

/**
 * 常驻底栏:看完一节顺手就能进下一节,不用回首页
 *
 * @param current 当前**节号**(0..total-1),全局唯一的状态源
 * @param total   节数(entries.size)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionFooter(
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    val last = isLastSection(current, total)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 「← 上一节」永远可点:第 1 节时点它回首页,而不是禁用成灰
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPrev() }
            ) {
                Text(
                    "← 上一节",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1.6f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { if (last) onFinish() else onNext() }
            ) {
                Text(
                    if (last) "回到首页" else "下一节 →",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionPicker(
    current: Int,
    onPick: (Int) -> Unit,
    onHome: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("目录", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("随时跳到任意一节", style = mono10())
            Spacer(Modifier.height(16.dp))
            entries.forEachIndexed { i, e ->
                val accent = when (e.tier) {
                    "★ 核心" -> Accent
                    "★ 高潮" -> AccentWarm
                    "入门" -> AccentGreen
                    else -> TextMuted
                }
                val on = current == i
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (on) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPick(i) }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            e.index, style = mono13(), color = accent,
                            modifier = Modifier.width(28.dp)
                        )
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                e.title, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(e.blurb, style = mono10(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (on) Text("当前", style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onHome)
            ) {
                Text(
                    "回到首页",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Home(
    depth: Depth,
    onDepth: (Depth) -> Unit,
    tech: Boolean,
    onTech: (Boolean) -> Unit,
    onGo: (Int) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 40.dp)
    ) {
        Text(
            "1 微秒",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "定位精度的真相",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "卫星负责「量」,手机负责「猜」,算法只负责把量出来的东西拼整齐。\n" +
                    "十一个小节,自己把结论做出来。",
            style = body14()
        )
        Spacer(Modifier.height(20.dp))

        // 主行动按钮:新用户不需要先在列表里找该从哪开始
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onGo(0) }
        ) {
            Text(
                "从第一节开始 →",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            )
        }
        Spacer(Modifier.height(16.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DepthSwitch(depth, onDepth)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (tech) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickableBox { onTech(!tech) }
            ) {
                Text(
                    if (tech) "术语:专业" else "术语:人话",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (tech) MaterialTheme.colorScheme.onPrimaryContainer else TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (tech) "会显示电离层、DOP、协方差这些标准术语"
            else "不会出现「电离层」这种词,只说人话",
            style = mono10()
        )
        Spacer(Modifier.height(24.dp))

        entries.forEachIndexed { i, e ->
            val accent = when (e.tier) {
                "★ 核心" -> Accent
                "★ 高潮" -> AccentWarm
                "入门" -> AccentGreen
                else -> TextMuted
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onGo(i) }
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        e.index, style = mono13(), color = accent,
                        modifier = Modifier.width(32.dp)
                    )
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            e.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            e.blurb, style = mono10(),
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        e.tier, style = MaterialTheme.typography.labelSmall,
                        color = accent, maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "全部离线运行,不需要网络,不申请任何权限。\n" +
                    "所有数据由内置的轨道与大气模型实时生成。",
            style = mono10()
        )
    }
}

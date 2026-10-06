# 第 08 节:沙盒可视化常驻化(LivePanel)

> 任务:`sandbox-live-panel` · 只改 `Experiments3.kt` 的 `Exp08_Sandbox` + 新建 `charts/LivePanel.kt`
> 未运行 gradle(并行任务共用同一 build 目录);未新增依赖;未 git commit(该目录不是 git 仓库)。

---

## 1. 要解决的问题

用户实测原话:

> 「只不过是把第八节内容的可视化图变成了在下方,用户依旧需要来回滑动。」

上一版把天空图从内容顶部挪到了**所有控件之后**。这没有解决问题,因为症结不是图在
哪一端,而是:

> **图和数字会互相把对方挤出视野。**

第 08 节大约 10 组交互控件(场景 5 行 / 星座 4 个 / 预设 7 个 / 3 根滑块 / 3 个开关 /
电离层 4 个分段),内容高度远超一屏。只要「数字」和「图」分处可滚动区的两端,
用户在两者之间就必然来回滚动 —— 拖完滑块往下找图,看完图往上找数字。

## 2. 新组件:`LivePanel`

文件:`app/src/main/java/com/oneus/lab/ui/charts/LivePanel.kt`

### API

```kotlin
@Composable
fun LivePanel(
    open: Boolean,                                   // 展开状态(外部持有)
    onToggleOpen: () -> Unit,                        // 切换回调
    collapsed: List<StatData>,                       // 收起态常驻数字
    sky: @Composable () -> Unit,                     // 展开态主可视化
    extra: @Composable () -> Unit = {},              // 展开态附加内容
    modifier: Modifier = Modifier
)
```

与任务书建议签名的唯一差别:**多出 `open` / `onToggleOpen` 两个必填参数**。
这是"状态提升"的直接后果 —— 组件不再自己 `remember { mutableStateOf(false) }`,
而是把状态交给调用方。建议签名里隐含的内部状态与"状态提升到 `Exp08_Sandbox`"
这两条要求互相矛盾,这里按后者(更明确、可被父组件控制)实现。

内部两个私有辅助组件:

| 组件 | 作用 |
| --- | --- |
| `PanelCell(t: StatData, modifier)` | 面板里的一格数字。无内层 `Surface`,底色交给面板 —— 少一层嵌套,高度更好控 |
| `PanelToggle(open, onToggle)` | 48dp 见方的展开/收起开关,箭头单独一行并用 `Accent` 高亮 |

样式沿用现有语言:`surfaceContainerHigh` + `RoundedCornerShape(16.dp)`(与 `Card` 一致),
数字用等宽字体 17sp/SemiBold(与 `LiveResultBar` 一致)。

### 结构

```
Surface(surfaceContainerHigh, 16dp, animateContentSize(tween 220))
└─ Column(padding 14/8)
   ├─ Row(整行 clickable)          ← 常驻,两种状态都在
   │  ├─ PanelCell × N (weight 1f)  ← 收起态:误差 / 可见星 / HDOP
   │  └─ PanelToggle(48dp)         ← 展开 ▲ / 收起 ▼
   └─ if (open) Column(heightIn(max = 40% 屏高).verticalScroll)
      ├─ sky()                      ← SkyPlot(fillMaxWidth + aspectRatio(1f))
      └─ extra()                    ← 图注 + 残差RMS + (DEEP) 逐星残差
```

两个设计决定值得说明:

1. **头部不参与滚动。** `verticalScroll` 只加在展开区,数字行和「收起 ▼」留在外面。
   否则用户展开后想收起,得先在面板里滚回顶部才能点到按钮 —— 那是另一种来回滚动。
2. **40% 屏高封顶加内部滚动。** 展开区 `Modifier.heightIn(max = 屏高 × 0.4)`。
   360dp 宽的机器上 `SkyPlot` 是 332dp 见方,矮屏上会超过 40%,
   封顶 + 内部 `verticalScroll` 保证它不会把下面的滑块整排挤没。

## 3. 状态提升方式

`Exp08_Sandbox` 持有状态,面板只负责画:

```kotlin
// Experiments3.kt:127
var panelOpen by remember { mutableStateOf(false) }
```

```kotlin
// Experiments3.kt:149
LivePanel(
    open = panelOpen,
    onToggleOpen = { panelOpen = !panelOpen },
    ...
)
```

为什么不用组件内部 `remember`:父页面可能需要强制收起(例如换场景、点预设后),
组件内部的 `remember` 父层改不了。提升之后 `panelOpen` 是页面的普通状态,
和 `scenarioId` / `clockNs` 等同一层级,想在哪改就在哪改。

## 4. 为什么这样能消除滚动

关键在 `ExpScaffold`(`Experiments1.kt:40-74`)的结构,已逐行确认:

```kotlin
Column(Modifier.fillMaxSize()) {          // :40  ← 外层撑满
    Column(...) { 标题 / DepthSwitch }    // :41-62
    if (sticky != null) { Spacer(10.dp); sticky() }   // :63-66  ← 常驻区
    Column(Modifier.weight(1f).verticalScroll(...)) {  // :67-73  ← 只有这里能滚
        content()
    }
}
```

* 外层 `Column` 有 `fillMaxSize()` ✅
* 可滚动区用 `weight(1f)` ✅
* `sticky` 槽在可滚动区**之外**,是它的兄弟节点 ✅

于是:

| 场景 | 改之前 | 改之后 |
| --- | --- | --- |
| 拖滑块(屏幕任意位置) | 数字在 sticky 条 → 本来就常驻 | 数字在面板收起态 → 仍常驻 |
| 想看天空图 | 滚到内容最底部 | 点「展开 ▲」,面板在原地长出图 |
| 想收起 | — | 点「收起 ▼」,按钮在头部,不用先滚 |
| 看完图想看数字 | 往上滚 | 数字就在面板头部,没动过 |
| 底部导航「← 上一节 / 下一节 →」 | `Scaffold.bottomBar` 在 `ExpScaffold` 之外 | 同样在更外层,面板撑不到那儿 |

**展开态也不会挤掉控件**:面板 = 64dp 头 + ≤40% 屏高体,最坏情况占 ~46% 屏高,
`weight(1f)` 的滚动区还剩 50% 以上,滑块/开关仍然够得着。

## 5. 验收对照

| 验收项 | 结论 | 依据 |
| --- | --- | --- |
| 收起态高度 ≤ 72dp | **64dp** | 上下 padding 8 + 8,行高 = max(数字列 17+2+23 = 42dp, 开关 48dp) = 48dp → 合计 64dp |
| 底部拖滑块不用滚动就能看到误差 | ✅ | 面板在 `weight(1f)` 滚动区之外,任意滚动位置都在视野顶部 |
| 点一下看图、再点收起,全程零滚动 | ✅ | 展开区自己 `verticalScroll`;「收起 ▼」在头部,不随内容滚走 |
| 不遮挡底部导航栏 | ✅ | `Scaffold.bottomBar` > `ExpScaffold` > sticky,三层嵌套,面板最高只到内容区 |
| 点击区 ≥ 48dp | ✅ | `PanelToggle` 是 `size(48.dp)`;另外整行 `Row` 也是 `clickable`,不用瞄准 |
| 收起/展开有动画 | ✅ | `Modifier.animateContentSize(tween(220))` |
| 不出现两个来源 | ✅ | 滚动内容里的「天空图 SectionTitle + Card」已删除,`SkyPlot` 在 `Exp08_Sandbox` 内只剩面板一处 |
| 未新增依赖 | ✅ | `animateContentSize`/`tween` 走 `material3 → api foundation 1.7.0 → api animation 1.7.0` 传递依赖 |

## 6. `Exp08_Sandbox` 的具体改动

**改前 → 改后**

1. 新增 `var panelOpen by remember { mutableStateOf(false) }`(`Experiments3.kt:127`)。
2. `sticky` 槽:`LiveResultBar(4 个数字)` → `LivePanel(...)`(`Experiments3.kt:145-198`)。
   * 收起态:水平误差 / 可见星 / HDOP(3 格,给 48dp 开关腾位置)
   * 展开态:`SkyPlot` + 图注 + 残差RMS
   * DEEP 追加:逐星残差 `ResidualBars`
3. **删除**滚动内容里的「天空图」`SectionTitle` + `Card(SkyPlot)`。
4. **搬移**「逐星残差」段(原 `depth == Depth.DEEP` 分支)到展开面板的 `extra` 槽,
   逐行复用原逻辑(`shot.obs.mapIndexed { i, m -> m to sol.residuals.getOrElse(i) { 0.0 } }.sortedBy { it.second }`)。
5. 内容末尾留一句指路文案(避免用户以为图不见了)。
6. `LayeredText` 的浅层文案改成"点一下「展开 ▲」",和新交互对齐。

**关于第 4 点(需要复核确认的判断)**:任务书只点名删除天空图,残差段是"搬移"而非"复制"。
理由是任务书自己定的原则 —— "避免出现两个来源"。残差段原本挂在内容最底部,
和天空图是同一个毛病(想看就得滚到底);留一份在内容里、留一份在面板里,
等于把用户的抱怨复刻两次。所以**代码搬进了面板,内容里不再有第二份**。
如果复核认为残差段应保留在内容中,那需要把 `extra` 里 `if (depth == Depth.DEEP)` 那一段
剪切回滚动内容末尾 —— 其余部分不受影响。

**没有丢信息**:原 sticky 条第 4 个数字「残差RMS」现在在展开面板里
(浅/中层用户点开就能看到);DEEP 的「残差 RMS x.xxx m」文本行被 `StatRow` 里的
残差RMS 卡片取代,数值同源(`shot.solution.residualRms`)。

**未动**:场景 / 星座 / 预设 / 3 根滑块 / 3 个开关 / 电离层分段,顺序和参数全部保持原样。

## 7. 已知取舍

* 展开区用 `rememberScrollState()`,每次重新展开回到顶部。对"看一眼图"的用法是对的;
  若以后要支持"展开后停在残差段",需要在 `LivePanel` 里用 `rememberSaveable` 提升滚动位置。
* 40% 用的是 `LocalConfiguration.current.screenHeightDp`(整屏高度,含系统栏区域),
  不是内容区高度 —— 作为封顶启发式够用,精确做法是改成 `BoxWithConstraints` 取父约束。
* 面板头部整行可点,收起态时这是最省手指的入口;代价是收起态点数字也会展开面板
  (数字本身不可交互,无副作用)。
* 未运行 gradle 编译验证(任务书禁止)。编译面已逐符号静态核对:
  `animateContentSize` / `tween` / `surfaceContainerHighest` / `RowScope.weight`
  / 同包 `mono10`、`mono13`、`StatData` 均确认可用。

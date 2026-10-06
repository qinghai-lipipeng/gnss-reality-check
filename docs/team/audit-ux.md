# UI/UX 评审 ·《1 微秒》

**评审范围**:`AppRoot.kt` / `charts/{Components,Charts,LivePanel}.kt` / `experiments/Experiments{1,2,3}.kt` / `theme/Theme.kt`,并交叉核对了 `sim/LabEngine.kt`、`sim/Scenario.kt`、`solve/LsSolver.kt`、`fusion/HybridPositioner.kt`、`MainActivity.kt`。
**方法**:只读源码,未运行 gradle,未修改任何项目文件。文中所有数字(对比度、控件高度、dp 占用、触摸目标)都可由源码算式复核,复核方式写在每条后面。

---

## 0. 结论先行

**这套设计最核心的问题是:全 App 只有一个状态量 ——「当前第几节」(`current: Int?`),而 11 个页面各自带一套用 `remember` 写的临时状态;于是每一节都被当成"一张可以随时丢掉的画"来写,而不是"一节有记忆、有生命周期的课"。**

四个已修的真机事故,加上本报告里的 30 条问题,都是这一个选择的产物,不是四起孤立事故:

| 编号 | 用户报告的事故 | 同一根因的代码形态 |
| --- | --- | --- |
| 1 | 侧滑返回直接退出 | 没有"历史"这个状态量,`current: Int?` 连"我上一节在哪"都无处可放(`AppRoot.kt:56`) |
| 2 | 看完一节要回首页才能选下一节 | 课程不是一份数据,只是一个被 `when(current)` 散列掉的标量(`AppRoot.kt:167-182`);`Entry` 里没有 `visited`/`done` 字段(`AppRoot.kt:27-32`) |
| 3 | 调完参数要上下反复滑 | 常驻结果条只覆盖 11 节里的 4 节(02/06/08/09),其余有控件的 03/04/07/10 仍是"控件在上、结果在下";唯一带图的 08 还把可视化塞进了页面滚动区**内部**的第二个滚动容器 |
| 4 | 莫名其妙的竖线 / 两行字叠在一起 | 没有把"解不出来"当成一个需要设计的界面状态:引擎有 `Row.na` 和 `ShotResult.ok` 两套机制,**UI 一处都没用**,还有 5 个调用点把"无解"吞成 0.0 |

四个可直接复核的量化证据:

- `grep -c "remember {"` 在 `experiments/*.kt` = **5 + 16 + 14 = 35**;`rememberSaveable` 在这三个文件里 = **0**;而 `AppRoot.kt:56-60` 的 3 个状态全用了 `rememberSaveable`,注释还专门解释了为什么。
- `grep -rn "term(" app/src/main` = **1 条定义、0 条调用** → 首页的「术语:人话/专业」开关(`AppRoot.kt:392-404`)是个**接线都没接的装饰品**。
- `grep -rn "contentDescription\|semantics\|Role\.\|Icon(\|minimumInteractiveComponentSize" app/src/main` = **0 条**。
- `grep -n "?\: 0\.0" sim/LabEngine.kt` = **6 条**,其中 5 条是 `avgError(...).first ?: 0.0`;而 `avgError` 自己的注释写着"一颗都解不出来时返回 null,**不能**返回 0.0"(LabEngine.kt:218-220)。

**验证事故 4 是不是设计病根的最小实验**:在任意带 `ScenarioPicker` 的节(02/03/04/05/07/10)选「桥下」或「室内」——
`Scenarios.UNDER_BRIDGE` 有一块 `azWidthDeg=360 / elWidthDeg=90 / elCenterDeg=45` 的全向遮挡块(Scenario.kt:110-112),`INDOOR` 同理(Scenario.kt:127-129),两者都会让 `Simulator.generate` 返回空表 → `LsSolver.solve` 在 `obs.size < 4` 处 `return null`(LsSolver.kt:66)→ `ShotResult.errorM = sol?.horizontalError(truth) ?: 0.0` = **0.0**(LabEngine.kt:85)→ UI 显示 **绿色的「水平误差 0.0000 m」**(`errorColor(0.0)` → `AccentGreen`,Components.kt:280-285),第 09 节还会判 **「达标」并弹出"过了。"**(Experiments3.kt:406、431-437)。一个完全没有信号的环境,被 App 报告为史上最好的成绩。

---

## 1. 问题清单

### P0 · 会给出错误信息 / 丢失用户数据 / 界面卡住

**P0-1 离开一节,该节的全部参数归零 —— 底栏却明确在鼓励你来回跳节**
`AppRoot.kt:138, 167-182` + `experiments/*.kt` 全部 35 处 `remember`
现象:在 08 拖了十分钟 → 点底栏「下一节 →」进 09 → 再点「← 上一节」回到 08 → 场景回到"城市街道"、3 根滑块回到默认、`panelOpen` 塌回收起、滚回顶部。下注答案(`Experiments2.kt:33`、`Experiments1.kt:237`)、挑战关卡进度(`Experiments3.kt:384`)同理。
为什么是问题:`ScreenBody` 是一个纯 `when(current)`,没有给每节留任何槽位,离开即销毁组合。底栏的存在目的就是让用户横跳,而横跳的代价是丢掉自己刚做的实验 —— 这正是"课程像一堆散页"的技术来源,也是事故 2 的深层版本。
改法:把每节的可变状态提成一个 `data class SectionState`,用 `mutableStateMapOf<Int, SectionState>()` 按节号存放,`ScreenBody` 从 map 里取、页面写回同一实例。**分两档**:(a) 最低成本、只修旋屏与进程被杀 —— 35 处 `remember` 换成 `rememberSaveable`;(b) 真正解决横跳丢状态 —— 上面那个 map。(a) 不解决本条标题里的场景,必须做 (b)。

**P0-2 `errorM` 在"无解"时是 0.0,于是"没有信号"被渲染成"零误差、达标"**
`LabEngine.kt:85` + `Experiments1.kt:340` / `Experiments3.kt:162,406,413`
现象:桥下/室内(或 08 里把 4 个星座全关掉)→ 02/08 的常驻条显示绿色「水平误差 0.0000 m」;09 判定 `0.0 <= 1.0` → 「达标」→ 弹出"过了。注意你刚才改的都是「量」这一层"。深层文案还会打印"解算:加权最小二乘,0 次迭代收敛"(Experiments3.kt:226)。
为什么是问题:这是 App 里**唯一一个会主动对用户说错话**的路径,而且它专挑用户最可能去试的两个环境(桥下、室内)。
改法:给 `ShotResult` 加一个显式失败态(或让 `errorM` 返回 `Double.NaN` —— `errorColor` 已经处理了 `!isFinite()` → `TextMuted`,Components.kt:281),然后在 4 个消费点统一:`if (!shot.ok)` → 值写 `—`、颜色写 `TextMuted`、并在常驻条下面补一行 mono10 说明(`可见卫星 0 颗,这个环境没有信号`)。09 的 `passed` 改成 `shot.ok && shot.errorM <= ch.target`。

**P0-3 `avgError` 精心返回的 `null` 在 5 个调用点被吞成 0.0**
`LabEngine.kt:275, 282, 493, 561`(外加 `:85, :87` 两处 `sol?.… ?: 0.0`)
现象:03 的 `clockContrast`、07 的 `precisionSweep`、收尾页的 `budgetBreakdown` 在室内/桥下全表 0.0000;05 的 `hybridSweep` 走另一条路 —— `HybridPositioner.kt:207` 对"没有任何可用来源"返回 `errorM = 1e9`,于是 4 行里 3 行被压成 1e-4 宽的发丝。
为什么是问题:`LabEngine.kt:218-220` 专门写了注释警告过这个坑("0.0 会被 max/min 当成一个'误差为零'的真实数据点,直接把结论带偏"),`constellationSweep` 也正确地用了 `na = true` + 斜纹占位(LabEngine.kt:246-256)——**引擎层做对了,消费层违反了它自己的约定**。
改法:5 处全部去掉 `?: 0.0`,改成 `Row(..., value = v ?: 0.0, na = v == null)`(沿用 `constellationSweep` 的现成写法),`BarList` 已经会画斜纹占位(Charts.kt:172-185)。05 的 1e9 改成一个明确的哨兵值并标 `na = true`。

**P0-4 第 08 节把滚动容器套进了滚动容器,手指落在面板上时整页不动**
`LivePanel.kt:99-108`(面板体 `verticalScroll`)嵌在 `Experiments1.kt:67-73`(整页 `verticalScroll`)里
现象:展开面板后,手指按在面板上上下滑,滚的是面板内部;面板内容到底之后,页面**不会**跟着滚,直到手指完全离开面板区域。用户感知是"页面卡死了"。
为什么是问题:两个同向 `verticalScroll` 嵌套,内层吃掉全部手势,且没有 `nestedScroll` 桥接。
改法:面板体不滚 —— 内部内容用 `Column(Modifier.heightIn(max = bodyMax))` 但**不套 `verticalScroll`**,超出的部分改成"逐星残差折叠区"(见 P1-7);或把面板整体做成 `Modifier.pointerInput` 检测纵向拖动后把 delta 转发给外层。推荐前者,顺带解决下面那条高度问题。

**P0-5 面板内容高度恒定超过封顶高度,所以它永远处于"半截 + 内滚动"状态**
`LivePanel.kt:71`(bodyMax = 40% 屏高)vs `Experiments3.kt:166-205`
算式(复核方式:屏宽 393dp、屏高 873dp):面板体内容 = 天空图 `(393-28)dp` 方 = **365dp** + 10 + 图注 17 + 10 + StatRow 53 = **455dp**;`bodyMax` = `873 × 0.4` = **349dp** → 恒定溢出 106dp。小屏 360×640 更糟:天空图 332dp vs 封顶 256dp,**圆的下沿(地平线附近、仰角最低、最容易被遮挡的那批卫星)被裁掉**,而用户看不到任何"下面还有"的提示。
改法:天空图改 `Modifier.fillMaxWidth().height(220.dp)`(星空图不需要正圆以外的比例,或者用 `aspectRatio(1.35f)`),把省下的高度给图注和 StatRow;并在面板体底部加一条 3dp 的渐隐边(`Brush.verticalGradient`),让"被裁掉"这件事可见。

**P0-6 首页的「术语:人话/专业」是死控件,点了没有任何东西会变**
`AppRoot.kt:392-404`(`onTech`)→ `AppRoot.kt:136` → `Home()` 里只用于决定这个芯片**自己**的底色和文案;`Components.kt:72` 的 `term(tech, plain, formal)` 全项目 **0 调用点**。
现象:切到"专业",芯片变色了,说明文字变成"会显示电离层、DOP、协方差这些标准术语",但 11 节的正文一个字都没变。
为什么是问题:这是一个承诺了具体效果、并且给出了一句可证伪的解释、却什么都没接的开关。它同时污染了首页的信息架构(用户会以为术语和「浅/中/深」是两个维度)。
改法:二选一。(a) 删掉这个芯片和 `tech` 状态,首页只留 `DepthSwitch` —— 术语这件事 `Depth.DEEP` 已经覆盖了;(b) 真正接线:在每个 `Layered` 里给关键术语加 formal 别名。**推荐 (a)**,改动最小且不增加认知负担。

### P1 · 体验受损,但不至于说错话

**P1-1 有交互控件、结果在两屏之外的节:03 / 04 / 07 / 10**
`Experiments2.kt:114`(03 的 BarList 由 `ToggleRow` 驱动,隔 2 个区块)、`Experiments2.kt:191`(04 的 TEC 滑块驱动下方 BarList)、`Experiments3.kt:71,87`(07 的 picker 驱动两张图)、`Experiments3.kt:518`(收尾的 picker 驱动瀑布图);对比有常驻条的 02(`Experiments1.kt:335`)、06(`Experiments2.kt:322`)、08(`Experiments3.kt:154`)、09(`Experiments3.kt:409`)。
改法:立一条规则写进 KDoc —— **有会改变数值结果的控件的节,必须有常驻结果条**。03/04/07/10 各加一个只放 1~3 格的 `LiveResultBar`(`LiveResultBar` 本身对 1 格也成立,`tiles.forEach` + `weight(1f)` 没有下限要求)。

**P1-2 第 06 节重算期间,用户盯着的是**旧值**,而且没有任何提示**
`Experiments2.kt:325`(只有 `traces.isEmpty()` 时才显示"计算中…")、`Experiments2.kt:405-409`(图表区同样只在空时显示"正在计算…")
现象:第一次进来有提示;之后每次松手重算(~0.3~1s),常驻条继续显示上一次的四个终值,颜色照旧,曲线照旧,直到新结果到达才整块跳变。
为什么是问题:这是典型的"陈旧数据伪装成新鲜数据"。用户会以为松手没生效,于是再拖一次,触发第二次重算。
改法:`LaunchedEffect` 里不要清空 `traces`,改成在 sticky bar 上按 `busy` 加状态:`alpha = if (busy) 0.45f else 1f`,并在 `PanelCell`/结果条末尾追加一格 `StatData("状态", "重算中", AccentWarm)`。

**P1-3 那句解释"松手后自动重算"的显示条件写反了,它恰好在最需要的那一刻消失**
`Experiments2.kt:391`:`if (busy && committedNs != dragNs)`
`onCommit` 在松手瞬间把 `committedNs` 置为新值(Experiments2.kt:386),于是 `committedNs != dragNs` 立刻变 false —— **重算真正开始时,这句话反而看不见了**,它只在"拖完了但还没松手"的那 0.5 秒里出现过一次。
改法:`if (busy)` 单独判断,并把文案从"松手后自动重算"改成"正在按新参数重算…"。

**P1-4 第 08 节的"第一步"在第 4 屏;官方的快捷入口(预设)排在场景和星座之后**
`Experiments3.kt:233`(场景,5 行 ≈ 240dp)→ `:238`(星座,4 片)→ `:269`(预设,7 片)→ `:300`(误差预算,3 滑块)
现象:用户打开 08,第一屏从上到下是:标题 / 浅中深 / 深度提示 / 常驻面板(3 个此刻无意义的数字) / 约 250 字正文 / 5 行场景选择。滑块在第 4 屏。而 `Experiments3.kt:271` 自己写着"不想从头拖起?点一个直接套用"—— 这句唯一的行动指引,排在第三个控件组。
为什么是问题:这正是问题 4 问的"眼睛先落在哪"。常驻面板视觉权重最高(17sp 数字 + surfaceContainerHigh 底),却恰恰是 t=0 时最不可解读的东西;而"该干嘛"的提示埋得最深。
改法:把「预设」提到 `ExpScaffold` 的 `sticky` 槽里、LivePanel 正下方(一行 FlowRow 即可,7 个芯片 ≈ 2 行),并把它的 `SectionTitle` 改成「从这开始 · 一键套用」;场景/星座/误差预算顺次下移。面板加一行 mono10 的任务句(例:"目标:把水平误差从 15.02 m 压到 3 m 以下")。

**P1-5 顶栏和页面头部把同一个节标题显示了两遍,而且这两份标题是两份独立硬编码**
`AppRoot.kt:82`(顶栏 `entries[current].title`,16sp)与 `Experiments1.kt:50-56`(`ExpScaffold` 的 21sp 粗体标题)并列显示;`index` 也显示两遍(顶栏"第 1 / 11 节" + 页面"00")。
算式(360×640 小屏复核):固定 chrome = 状态栏 24 + TopAppBar 64 + 进度条 2 + `ExpScaffold` 头 12+29+10+31+4+17 = 103 + sticky 面板 64 + 底栏 46 + 导航栏 24 ≈ **327dp / 640dp = 51%**,其中那 29dp 的重复标题是纯浪费。
一致性风险:11 条标题在 `AppRoot.kt:34-46` 和 11 个 `ExpScaffold("..", "..")` 调用点各写了一遍,目前 11 条全对得上(我逐条比过了),但**任何一处改动都会静默不一致**。
改法:删掉 `ExpScaffold` 头部的 `Text(title)`(保留 `index` + `DepthSwitch` + hint),或反之删顶栏的 `title` 只留节号 —— 推荐前者,因为页面内标题应该跟着内容滚、顶栏应该保持稳定。同时把 `index` 参数也删掉(`AppRoot.kt:34-46` 的 `Entry.index` 是唯一真源)。

**P1-6 编号体系三套并存,末节无编号**
顶栏 `第 ${current + 1} / ${entries.size} 节`(1 起算,AppRoot.kt:88)vs 页面 `index`("00"~"10",0 起算)vs 末节 `★`(AppRoot.kt:45、Experiments3.kt:493)。"第 11 / 11 节" 无法与页面上的 `★` 对应。
改法:全 App 统一用 `Entry.index`("00"…"10" 和给末节一个 "11" 而不是 "★"),顶栏改成 `第 08 节 / 共 11 节`;`★` 那个星号移到 `tier` 字段里(它表达的是"收尾"这个 tier,不是编号)。

**P1-7 没有任何"读到哪"的记录:进度条画的是位置,不是进度**
`AppRoot.kt:108-116`(`progress = current / (entries.size - 1f)`,第 1 节 0f,看起来像"什么都没加载")+ `AppRoot.kt:27-32`(`Entry` 无 `visited`/`done`)+ 冷启动永远回首页(无"继续上次")
现象:11 节全部读完的用户,首页和目录里看不出任何区别;下次冷启动也不知道上次读到哪。
改法:`var visited by rememberSaveable { mutableStateOf(emptySet<Int>()) }`,在 `go(i)` 里 `visited += i`;然后三处消费:进度条改成 `visited.size / entries.size`;首页/目录行加一个 ✓;首页在 `visited.isNotEmpty()` 时把主按钮从"从第一节开始 →"换成"继续第 N 节 →"(并在其下保留次要入口"从第一节开始")。加起来约 15 行。

**P1-8 第 07 节把同一个 `ScenarioPicker` 渲染了两次,第二个还没有标题**
`Experiments3.kt:66` 与 `Experiments3.kt:79`,中间夹着 BarList 和一段"切到开阔地再点一次算比例"的引导(Experiments3.kt:73-77)。用户会以为第二个是别的控件,或者干脆以为 App 有 bug。
改法:删掉 `:79` 那个,并把引导文案改成"↑ 用上面的环境选择器切到开阔地"。

**P1-9 第 08 节的预设芯片会静默改掉你没点的参数,而且看不出哪个预设生效了**
`Experiments3.kt:100-120`(Preset 的 `rel/tropo/mp` 有构造默认值)+ `:278-297`(应用时无条件写回 7 个字段,但 `freq` 和 `systems` 不写)
现象:你打开了"多路径改正",再点「只动大气」→ `mp` 被重置成 `false`(preset 的默认值),你毫不知情;你把星座关到只剩 GPS,再点「开阔实况」→ 星座选择保持不变,所以这个"预设"名不副实;点完之后 7 个芯片长得一模一样(全是 `surfaceContainerHigh`),没有任何"当前预设"的标记。
改法:Preset 的字段改成可空(`Boolean? = null` / `Float? = null`),应用时只写非 null 的;`freq`/`systems` 要么纳入 Preset、要么把芯片改名为「套用这一组误差项」;选中态用 `primaryContainer`(和其他选中态一致)按"当前状态与该 preset 完全相等"计算出来。

**P1-10 第 09 节让用户"把电子含量拉到 4 倍",但整页没有 TEC 控件;而且全页不显示当前环境**
`Experiments3.kt:369-377`(挑战 3 文案)vs `Experiments3.kt:396`(`sc.copy(tec = 4.0e17)` 硬编码,无 UI);`Experiments3.kt:410-417`(常驻条只有 目标/当前/状态,没有场景)
现象:第 3 关的 brief 是"把电子含量拉到 4 倍,还要 5 米",但用户手上只有 8 个通用旋钮,没有一个和电子含量有关;同时他也不知道自己现在是在"城市街道 × TEC 4 倍"这个环境里(08 的深层文案会打印 `场景「${sc.name}」`,09 什么都不打印)。
改法:常驻条补两格 —— `StatData("环境", sc.name)` 和 `StatData("TEC", LabEngine.fmtTec(scen.tec))`;挑战 3 的 brief 改成"电子含量已经是 4 倍(已内置),目标 5 米",或者给它加一根 TEC 滑块让它可调。

**P1-11 星星图和残差柱用 4 个星座色编码,全 App 没有任何图例**
`Charts.kt:111`(`systemColor(m.system)`)、`Experiments3.kt:198`(残差柱同色)、`Theme.kt:38-45`(四个颜色)
现象:用户在 08 展开面板看到蓝点/绿点/粉点/黄点,无法知道蓝色是伽利略、绿色是 GPS。唯一能建立映射的地方是 08 滚动区里的星座芯片(Experiments3.kt:249-264),而那张图在常驻面板里,不在那里。
改法:在 LivePanel 的 `extra` 里加一行 8dp 高的图例(FlowRow,4 个 10dp 色块 + 星座名),只在 `obs.isNotEmpty()` 时显示。

**P1-12 触摸目标:12 类可点组件里 11 类算出来都不到 48dp**
逐个算式(高度 = 上下 padding + 文字行高;全部可在源码复核):

| 组件 | 位置 | 算式 | 高度 |
| --- | --- | --- | --- |
| 底栏「← 上一节 / 下一节」 | AppRoot.kt:229, 246 | 13×2 + labelLarge 20sp | **46dp** |
| 顶栏「←」「目录」 | AppRoot.kt:95, 98 | M3 `TextButton` = `ButtonDefaults.MinHeight` | **40dp** |
| 浅/中/深 三个 | Components.kt:141 | 7×2 + 13sp(无 lineHeight) | **~31dp** |
| 首页「术语」芯片 | AppRoot.kt:402 | 10×2 + 20sp | **40dp** |
| `Segmented` 芯片 | Components.kt:519 | 10×2 + 18sp | **38dp** |
| 场景行 | Experiments1.kt:410 | 9×2 + body13 22sp | **40dp** |
| `ToggleRow`(无副标题) | Components.kt:533 | 10×2 + 22sp | **42dp** |
| 预设芯片 | Experiments3.kt:293 | 10×2 + 20sp | **40dp** |
| 星座芯片 | Experiments3.kt:262 | 12×2 + 20sp | **44dp** |
| `SmallBtn` | Experiments1.kt:225 | 12×2 + 20sp | **44dp** |
| `BetCard` 选项 | Components.kt:359 | 12×2 + 22sp | **46dp** |
| 目录行 | AppRoot.kt:294 | 12×2 + titleSmall 20sp | **44dp** |
| `PanelToggle` | LivePanel.kt:143 | `.size(48.dp)` | **48dp** ✅ |

`grep -rn "minimumInteractiveComponentSize"` = 0 条。
为什么是问题:唯一做对的那个(PanelToggle)还专门写了注释说"48dp 见方,满足最小点击区"——说明这个问题被看见过一次,没被推广。
改法:给 `clickableBox`(Components.kt:75)和所有裸 `Modifier.clickable {}` 的 chip 加 `Modifier.minimumInteractiveComponentSize()`;FlowRow 里的芯片(预设/分段/星座)会因此长高,连带调 `verticalArrangement` 即可,视觉上反而更透气。底栏两个按钮把 13dp 改成 14dp 就够。

**P1-13 9 根滑块对 TalkBack 来说没有名字**
`Components.kt:404-418` / `:429-441`:label 是独立的 `Text`,`Slider` 本身没有 `Modifier.semantics { contentDescription = label }`。
现象:TalkBack 念"滑块,47%",用户完全不知道这是哪根、什么单位。
改法:`Slider` 上加 `.semantics { contentDescription = label; stateDescription = valueText }`;顺便把 `valueText` 塞进 `stateDescription`,否则只念百分比不念"300 m"。

**P1-14 sticky 区漏了水平内边距,和上下内容不对齐**
`Experiments1.kt:63-66`:`sticky()` 在 `Spacer(10.dp)` 之后被直接调用,没有 `padding(horizontal = 16.dp)`,而头部(`Experiments1.kt:44`)和滚动体(`Experiments1.kt:71`)都加了 16dp。
现象:02/06/08/09 的常驻条/面板**顶到屏幕左右边缘**,下面所有内容缩进 16dp,左右两条边对不齐。
改法:`sticky()` 外面包一层 `Modifier.padding(horizontal = 16.dp)`(或者反过来把内容区的 16dp 去掉 —— 但卡片类内容需要 16dp,所以改 sticky)。

**P1-15 第 08 节的星座芯片把"数据色"和"状态色"用在了同一处**
`Experiments3.kt:249-264`(`col.copy(alpha=0.20f)` + 文字用 `col`)、`Theme.kt:38-45`(`systemColor`)、`Components.kt:280-285`(`errorColor` 用 `AccentGreen`/`Danger`/`AccentWarm`)
现象:北斗芯片选中时是 `Danger`(#FFB4AB,粉红)—— 而这个粉红在常驻条里表示"误差很差";GPS 芯片选中时是 `AccentGreen` —— 同一个绿在常驻条里表示"误差 < 1 m,很好"。同一个颜色在屏幕上同时说"这是北斗"和"这里出错了"。
改法:选中态不要借用星座色,改成 `primaryContainer` + `onPrimaryContainer`(和 `Segmented`、目录一致),星座色只留在文字上;或者给选中态加一个 ✓ 角标。

### P2 · 一致性、可读性、可维护性

**P2-1 `Experiments1.kt:329` 的 `val cur` 是死变量 —— 卫星数滑块和下面的图表从来没接上**
`val cur = rows.firstOrNull { it.label.startsWith("$count") }` 之后再没被引用。`BarList` 支持逐行 `highlight`(Charts.kt:146、168),而 `geometrySweep` 把 `highlight` 硬编码成 `n == 4`(LabEngine.kt:177)。
现象:把"只留 N 颗星"拖到 11,下面图表里仍然是"4 颗星"那根高亮 —— 看起来像坏了。
改法:给 `BarList` 加一个 `highlightLabel: String? = null` 参数,优先于 `row.highlight`;调用处传 `"$count 颗星"`。

**P2-2 02 和 08 是同一类页面(天 + 场景 + 星座 + 常驻数字),两套布局**
02:数字在 sticky 条、天空图在滚动区的卡片里(Experiments1.kt:334-379);08:数字和图在同一个 `LivePanel`(Experiments3.kt:153-207)。`LivePanel` 的 KDoc(LivePanel.kt:32-45)明确说"数字和图分处两端就必须来回滚",而 02 正是被否掉的旧形态。
改法:02 也改用 `LivePanel`,或者反过来把 08 降级成 `LiveResultBar` + 图留在原地。二选一,别两样都留。

**P2-3 `SkyPlot` 没有空态:0 颗可见卫星时画的是一个只有刻度的空圆**
`Charts.kt:107`(`for (m in obs)` 无任何空分支)
改法:`if (obs.isEmpty())` 时在圆心 `drawText("没有可见卫星", …)`;并在 08 的图注里说明"0 颗 = 这个环境卫星信号全被挡住了"。

**P2-4 `BarList` 收到空 rows 时渲染成一张空白卡片**
`Experiments1.kt:385`(02 的 `BarList(rows)`,而 `geometrySweep` 在无解时是 `continue` 直接跳过该行,LabEngine.kt:171)→ 室内/桥下时该卡片是空的。
改法:`BarList` 开头加 `if (rows.isEmpty()) { Text("这个环境没有可见卫星,解不出位置", style = mono10()); return }`。

**P2-5 收尾页瀑布图的颜色按行号排,不按量级排 —— 最大的那项是蓝的,零贡献的那项是红的**
`Charts.kt:359-363`:`i < 2 -> Danger; i < 4 -> Warn; else -> Accent.copy(alpha=0.7f)`
现象:收尾页的全部意义是"看清楚哪几行是大头",但配色给了 0 项(接收机钟差,`FLAT` 档)红色、2 项(电离层、多路径)粉/琥珀、而"换算法"这类真正接近零的行是安静的蓝。用户第一眼扫颜色会得到和条长相反的印象。
改法:颜色按 `budgetKind` 分(FLAT=灰+斜纹、WEAK=琥珀、REAL=Accent),行号只留给次要的 `shortNote`。

**P2-6 `Line`(#2A3646)当结构线用时只有 1.43:1,而这个修复只做在了 `ClockRuler` 里**
实测:`Line` on `surfaceContainer` = **1.43:1**,on `background` = **1.59:1**,都远低于 WCAG 1.4.11 对非文本元素要求的 3:1。`Charts.kt:447-448` 的 KDoc 写明"旧的 0xFF1D242E 和 Line 在 0A0D13 底上几乎看不见"——**但 `SkyPlot` 的极坐标环与方位线(Charts.kt:74, 81, 86-90)、`ConvergenceChart` 的参考线(Charts.kt:227)、`ResidualBars` 的零线(Charts.kt:594)、`DopDiagram` 的十字(Charts.kt:639-640)仍然全用 `Line`。**
改法:抽一个 `val gridColor = surfaceContainerHighest`(#283039,on surfaceContainer 约 1.9:1,仍不足)——更实际的做法是提到 `TextMuted.copy(alpha=0.55f)`,实测约 2.6:1,配合 1.2dp 线宽才够看;改一处常量,五处一起生效。

**P2-7 中层/深层的正文比浅层更小、更暗、还是等宽 —— 越想读的人读得越费劲**
`Components.kt:163-172`:`fontSize = if (SHALLOW) 15.sp else 13.sp`,`fontFamily = if (SHALLOW) null else Monospace`,`color = if (SHALLOW) TextSecondary else TextMuted`
现象:13sp 低于中文正文常用的 14sp 下限;等宽字的 x-height 更小、字距更宽,读起来明显比 15sp 小一号;再叠上 `TextMuted`(5.4~6.8:1)比 `TextSecondary`(8.6~9.6:1)暗一档。而且 DEEP 层里的公式(`H_i = [...]`、`x̂ = Σ(...)`、`PR_IF = ...`)和散文是**同一个 `Text`** 里的 `\n\n`,所以没法只给公式上等宽。
改法:13sp → 14sp、`TextMuted` → `TextSecondary`;把公式行拆成独立的 `MonoBlock` composable(等宽 + `surfaceContainer` 底),散文回到比例字体。

**P2-8 Canvas 里的坐标轴文字用 dp 定尺寸,不跟随系统字体缩放**
`Charts.kt:229`(9f px)、`Charts.kt:532-533`(9.dp.toPx())、`Charts.kt:305`、`Charts.kt:77` 都用 `dp`;而正文全用 `sp`。
现象:用户把系统字体调到 200%,正文和所有 UI 都放大了,只有 4 张图里的刻度标签保持原样。
改法:`androidPaint(sizePx)` 的调用点改成 `TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)`,或直接乘 `LocalDensity.current.fontScale`。

**P2-9 全 App 没有一个 `contentDescription` / `semantics` / `Icon` / `role`**
`grep` 结果为 0。所有箭头是文本字符:`←`(AppRoot.kt:95)、`▲`/`▼`(LivePanel.kt:160)、`✓`/`✗`(Components.kt:367,370)、`→`(AppRoot.kt:375, 240, 241)。
问题:TalkBack 念不出"←"和"目录"的作用域;`SectionTitle`(Components.kt:100)那根 3dp 色条 + 12sp 文字是全 App 的分节标题,但没有 `heading()`,读屏用户无法按标题跳转。
改法:`←` 换成 `Icons.AutoMirrored.Filled.ArrowBack` + `contentDescription = "返回首页"`;「目录」加 `Icons.AutoMirrored.Filled.List`;`PanelToggle` 加 `role = Role.Button` + `stateDescription = if (open) "已展开" else "已收起"`;`SectionTitle` 和 `ExpScaffold` 的标题加 `Modifier.semantics { heading() }`。

**P2-10 「← 上一节」在第 1 节会把用户带出课程;末节的主按钮变成「回到首页」**
`AppRoot.kt:126`(第 1 节时 `current = -1`)、`AppRoot.kt:238-241`(末节时 `onFinish()`)
现象:按钮写着"上一节",点下去却回到首页(离开课程);最后一节的主按钮(唯一的 primary 色、权重 1.6)标签变成"回到首页",等于把"结束"做成了一次误触就会丢进度的操作。
改法:第 1 节时把次按钮改写成"← 回到目录";末节的主按钮保持"下一节 →"不可点样式 + 换成"回到首页"作为次按钮,或者直接换成"从头再看一遍"(收尾页里已经有这个按钮了,Experiments3.kt:573)。

**P2-11 从目录跳到第 5 节之后,「← 上一节」去的不是来处**
`AppRoot.kt:63`(`fun go(i: Int) { current = i }`)、`AppRoot.kt:128`(`onNext = current + 1`)、`AppRoot.kt:147`(`onPick` 也只是 `go(it)`)
现象:在 03 打开目录跳到 08,点「← 上一节」到的是 07。App 里没有任何"我刚才在哪"的记录。
改法:**不用引入导航栈**(见 §3),只需一个标量:在 `go()` 里判断"是否是跨节跳跃"(`abs(i - current) > 1` 且 `current >= 0`),是则记 `var cameFrom by rememberSaveable { mutableStateOf(-1) }`;底栏次按钮在 `cameFrom >= 0` 时临时显示 `← 回到第 03 节`,用掉一次后清空。

**P2-12 第 00 节对导航的说明是错的,而且默认深度下看不到**
`Experiments1.kt:142-143`:"系统返回手势会回到上一级,不会退出应用" —— 实现是 `BackHandler { home() }`(AppRoot.kt:68),回的是**首页**不是"上一级";而且这张卡被 `if (depth != Depth.SHALLOW)` 挡住(Experiments1.kt:137),默认 SHALLOW 的用户永远看不到 App 唯一一次解释自己怎么导航。
改法:文案改成"返回手势回到首页",并把这张卡移出深度判断(或把"怎么走"合并进浅层正文)。

**P2-13 同一个控件在不同节的说法不一致**
- 场景:章节标题在 02/03/04/07/收尾 叫「环境」,只有 08 叫「场景」(Experiments1.kt:364、Experiments2.kt:88/172、Experiments3.kt:64/511/233)。
- 「多路径改正」的副标题:08 写"现实里做不到,只当理想上限"(Experiments3.kt:316),09 写"理想条件,现实中做不到"(Experiments3.kt:457);「相对论偏心项修正」在 08 有公式副标题、09 没有(Experiments3.kt:314 vs 455)。
- 「接收机钟差」的量程:08 是 `0f..1000f`(Experiments3.kt:303),09 是 `0f..500f`(Experiments3.kt:449)—— 同一个物理量两个量程,而 09 的第一关目标可能需要拉满。
- **02 用的是"干净预算"**(`cleanBudget()`,Experiments1.kt:331,零钟差/零多路径/零轨道误差),08 用的是默认预算(含 1 m 卫星钟差、1 m 轨道误差、未做多路径改正,Experiments3.kt:139-150)。两处常驻条都写「水平误差」,但同一场景下数字可以差一个量级,用户来回切节会以为 App 坏了。
改法:统一量程;统一副标题(从 09 抽到共享常量);02 的 sticky 条下面加一行 mono10:"本页用理想预算,只隔离几何一个变量"。

**P2-14 第 08 节文案说"九个旋钮",`ErrorBudget` 是 8 个字段**
`AppRoot.kt:43`(目录卡片 blurb)和 `Experiments3.kt:214`(中层正文)都写"九个旋钮";`Scenario.kt:165-187` 的 `ErrorBudget` 有 8 个字段(钟差/星钟/相对论/电离层/频点/对流层/星历/多路径),加上场景和星座才是 10。
改法:两处都改成"八个旋钮",或者如果本意是"场景+8 项",写"九个旋钮:八个误差项 + 一个环境"。

**P2-15 DEEP 层的两句话写到一半断了**
`Experiments2.kt:61`(03 的深层,末句"…不是误差的量级 —— "后无下文)、`Experiments3.kt:58`(07 的深层,末句"…真正的技术壁垒,"以逗号结尾)。
现象:只有切到 DEEP 才看得见 —— 也就是说整个 App 最少被维护、最晚被校对的那一层,有两处句子没写完。这和 P2-7 是同一个现象的两面。
改法:补完这两句,并把 DEEP 层的文案单独校对一遍(它是唯一会露出 `ICD-200 §20.3`、`Klobuchar Table 20-V` 这类硬引用的一层,错字代价最高)。

**P2-16 命名与实际字号差 1~3sp,下一轮照着名字调会踩坑**
`Components.kt:46-53`:`body13()` = 14sp、`body14()` = 15sp、`bold13()` = 14sp、`title18()` = **21sp**、`mono10()` = 11sp、`mono11()` = 11sp、`mono13()` = 13sp、`bigNum()` = 28sp。九个里四个对不上名字。
改法:全部改成"名字 = 字号"(或直接换成 `TextStyle` 常量 + 语义名,如 `bodyProse` / `dataMono` / `heroNum`)。这一条纯属防下一轮改坏,不改变任何视觉。

**P2-17 死代码 / 死参数**
`Components.kt:72` `term()`(0 调用,见 P0-6)、`Charts.kt:616` `DopDiagram`(KDoc 自承无调用点,但 611-614 行那串 `coerceIn` 崩溃修复的注释要留着,它是给下一个人的路标)、`Components.kt:399/425` 的 `steps` 参数(所有调用点都传 0;顺带:02 的卫星数滑块 `4f..14f` 是离散量,`Experiments1.kt:376` 传了 `steps = 0`,应该传 9)、`Experiments1.kt:142` 的 `${11}`(字面量 11,不是 `entries.size`,改一节目录就会不同步)、`Experiments2.kt:373-375`(连续两个 `Spacer(16.dp)`,白占 16dp)、`Experiments1.kt:215/222`(用 `accent == Line` 这种"默认参数值相等"来判断按钮变体,应该传一个显式的 `variant` 参数)、`MainActivity.kt:21` 与 `AppRoot.kt:72` 重复包了一层 `OneUsTheme`。
改法:能删的删,`DopDiagram` 的注释保留;`steps` 给 02 传 9;`${11}` 换成共享常量。

**P2-18 `LiveResultBar` 4 格时没有宽度下限,窄屏会截断数字 —— 而 `StatRow` 有防护**
`Components.kt:256-274`(每个 cell `weight(1f)` + `maxLines=1` + Ellipsis)vs `Components.kt:217-230`(`FlowRow` + `maxItemsInEachRow=3` + `widthIn(min = 96.dp)`)
算式(320dp 宽屏):(320-16×2-8×3)/4 = **64dp/格**;"15.02 m" 在 17sp 等宽下约 71dp → 被截成 "15.0…"。06 的 sticky 条正好是 4 格(Experiments2.kt:326-332)。
改法:`LiveResultBar` 复用 `StatRow` 的 FlowRow 策略,或至少给每格加 `Modifier.widthIn(min = 72.dp)` 并允许换行;两种"结果条"应该合成一个组件,不要两套规则。

**P2-19 同一个 `LivePanel` 上叠了两层 `clickable`,区域重叠**
`LivePanel.kt:84-94`:整行 `Row(...).clickable(onToggleOpen)`,里面又有一个 48dp 的 `PanelToggle` 也 `clickable(onToggleOpen)`。
现象:两层点击区指向同一个动作,不管 Compose 的分发最终命中哪一层,这个设计都是错的(要么内层不可达、要么一次点击翻转两次而看起来"没反应")。而且那块区域正好覆盖了三个数字 —— 点数字会展开图表,这本身也值得商量。
改法:保留整行可点(KDoc 的理由成立:手指不用瞄准),把 `PanelToggle` 里的 `.clickable` 去掉,让它退化成纯视觉;想让它可点就把外层 Row 的 clickable 去掉。二选一,不要两层。

**P2-20 `ConvergenceChart` 在数值范围很窄时一张参考线、一个轴标签都不画**
`Charts.kt:224-231`:参考线只有 `{0.1, 1, 10, 100}` 四档,标签硬编码 `"0.1"`;当 `maxV*1.4 < 0.1`(即所有值都小于 0.07)时全部被 `continue` 掉。
现象:当前 06/07 的数据都在米级以上,**这一条现在触发不了**,属于潜伏项。
改法:档位改成 `listOf(0.01, 0.1, 1.0, 10.0, 100.0)`,标签用 `"${g}"` 而不是硬编码 `"0.1"`。一行改动,只加防御不改语义。

---

## 2. 已经做对的地方(下一轮别改坏)

1. **正文对比度全部过 WCAG AA,不用动。** 实测:`TextMuted` #8A9AAD on `surfaceContainer` #131A22 = **6.10:1**;on `surfaceContainerHigh` = **5.44:1**;on `background` = **6.77:1**。`TextSecondary` #B3C2D0 on `surfaceContainer` = **9.63:1**;`onPrimary`/`primary` = 7.49:1;`onPrimaryContainer`/`primaryContainer` = 7.27:1;`Accent` on `surfaceContainerHigh` = 8.89:1。**问题 6 里"TextMuted 对比度够不够"的答案是:够。**
2. **`PanelToggle` 48dp 且写了为什么**(LivePanel.kt:138-144)。它是全 App 唯一做对的触摸目标,注释和实现都在,改的时候照着它做。
3. **`BarList` 的"label 独占一行 + `na` 画斜纹占位"**(Charts.kt:142-190,172-185)。KDoc 明确写了"让人一眼看出'这里没有数',而不是'误差为零'"——这套视觉语言自洽,P0-3 应该复用它而不是发明新的。
4. **`budgetImpact` + FLAT/WEAK/REAL 三档 + 「0 m」徽标**(Charts.kt:254-316、367-400、403-434)。把"没有贡献"画成"没有贡献"而不是"长度为 0 的条",并按方向说"降到/反而涨到"。这是全 App 表达力最强的一段交互设计,别简化。
5. **`coerceSafe` + "先判正尺寸再推导区间"**(Charts.kt:44-49、64、69、220、481、624-630)。`ClockRuler`、`SkyPlot`、`ConvergenceChart`、`DopDiagram` 四处都防了画布为 0 导致的区间翻转崩溃。这是事故 4 的一半答案,另一半(空态)在 UI 侧还没做。
6. **`Card` 用 `Column` 而不是 `Box`,并把原因写在注释里**(Components.kt:81-97)。避免"文字压文字、图表盖文字"的那一类 bug,11 处调用方全部受益。
7. **`judge(picked, correct, revealed)` 做成纯函数 + `Bet.init { require(correctIndex in options.indices) }`**(Components.kt:292-324)。"选项顺序变了、正确下标忘了改"从运行期 bug 变成了启动期不可能。**下注卡的 ✓/✗ + 边框 + 底色是三通道冗余编码,色觉障碍友好,不要改成纯颜色。**
8. **`StatRow` 的 `FlowRow + maxItemsInEachRow=3 + widthIn(min=96.dp)`**(Components.kt:214-232)。这是"数据格多了会重叠"的正解。讽刺的是 `LiveResultBar` 没抄它(见 P2-18)。
9. **多历元平均 + `avgError` 返回 `null` 而不是 0.0**(LabEngine.kt:197-221)。9 次采样、注释写清了"不平均就等于拿噪声当结论"。**引擎这一层做得比 UI 好得多,P0-3 只是消费端没遵守。**
10. **`converged = step < tol && geometryOk`(HDOP ≥ 25 判为不可信)**(LsSolver.kt:170-179)。"与其输出一个漂亮的错误答案,不如如实标记为不可信"——这个判断在引擎里已经有了,只是 `Solution.ok` 从未被 UI 读取。
11. **「上一节」永远可点,第 1 节时回首页而不是禁用成灰**(AppRoot.kt:125-126,214)。避免死按钮,方向对(措辞问题见 P2-10)。
12. **`DepthSwitch` 放在滚动区之外**(Experiments1.kt:59)。全局设置随时可见,切了立刻看到正文变化,位置正确。
13. **底栏越界判定用 `current + 1 >= total` 这个不等式、末节判定用 `current + 1 >= total`**(AppRoot.kt:128、202),`ScreenBody` 不写 `else`(AppRoot.kt:181)宁可空白也不静默兜底。防御式写法的样板。
14. **注释解释了"为什么"而不是"是什么"。** `LivePanel` 的 KDoc(第 29-59 行)、`coerceSafe` 的 KDoc、`BarList` 的 KDoc、`Bet.init` 的注释,让本次评审能在 20 分钟内定位到事故 4 的两个根因。**这个习惯是 App 最有价值的资产,改代码时请保留这些 KDoc。**
15. **`OneUsTheme(dynamicColor = false)` 强制深色,与 `SkyPlot`/`BarList`/`WaterfallChart` 里硬编码的深色底自洽**(Theme.kt:162-168)。不该做浅色主题,见 §3。
16. **`HeavySlider` 的三件套:拖动只更新读数 / `onValueChangeFinished` 才提交 / 重算跑 `Dispatchers.Default`**(Components.kt:443-481,Experiments2.kt:292-316)。这是 08/09 该抄的范式,只是没抄。

---

## 3. 不修的(评估后认为不值得改,附理由)

1. **不引入 Navigation-Compose 的导航栈。** 11 个节是平级的,最深也只有"首页 → 一节"两层,`current: Int?` 这个标量对当前的信息结构是够用的(前两轮的栈深度/节号分叉 bug 也正是不该有栈的证据)。真正缺的不是栈,是 **P1-7 的 visited** 和 **P1-11 的 cameFrom**——两个标量就能补上,不需要栈。真正会让"栈"变必要的,是将来要加"某一节内的多步向导"或"可分享的深链",那时候再上。
2. **不做浅色主题。** `OneUsTheme(dynamicColor = false)` + Theme.kt:59-102 的深色调色板 + 语义色(Theme.kt:23-53)是为深色背景逐个调的;`systemColor`/`filterColors`/`errorColor` 是数据可视化的固定色板,做浅色版要重做一轮对比度验证。对一个 30 分钟读完的科普 App,收益为负。
3. **`TextMuted` 的颜色本身不改**(见 §2-1,6.1:1 已过 AA)。要改的是 §2 里 P2-7 那件事:`mono10()` 承载了太多"解释性正文"(深度提示、场景说明、图表脚注、首页落款、`Row.note`)。正确的做法是**把解释性文字换成 `body13()`**,而不是去调 `TextMuted` 的明度。
4. **不给 `SectionPicker` 加搜索/筛选。** 11 项,一屏放得下(每行 44dp × 11 = 484dp,小屏要滚一屏)。加搜索是过度设计。
5. **"上一节永远可点"这个决定不改**(AppRoot.kt:125-126 的注释和实现)。禁用态按钮在移动端是纯粹的负资产,现在的行为(第 1 节点它回首页)是对的,只需要把 P2-10 的措辞改掉。
6. **不把 LivePanel 展开态再加常驻元素。** 40% 屏高封顶(LivePanel.kt:71)的判断是对的:再高会把滑块挤出视野,那就白改了。P0-4/P0-5 要修的是"里面的内容别超过这个封顶",不是"把封顶调大"。
7. **不重写 `BarList` / `WaterfallChart` / `SkyPlot` 的 Canvas 布局。** 这三个组件的布局逻辑本身是稳的(避开了事故 4 里的重叠和竖线),它们的问题全在**上游的数据契约**(P0-2/P0-3/P2-4)和**颜色语义**(P2-5),改契约和改配色就够了,不要动绘制代码。
8. **不把 `BetCard` 的选项改成 radio / 单选按钮形态。** 现在是整行可点 + 12dp 内边距,已接近 48dp(P1-12 差 2dp,加 padding 即可),换成 radio 会让行长文选项更难点。
9. **`Exp00` 里的 `SmallBtn("开始 →")` 不删。** 它和底栏的「下一节 →」功能重复,但它是 00 节唯一的正向出口,新用户顺着点下去正好,底栏在别的节才是主出口。留着不碍事。
10. **DEEP 层的"ICD-200 §20.3 / Table 20-V"这类出处引用不做格式统一。** 只出现在 DEEP,且是给人查的字符串,不是 UI 元素。
11. **`DopDiagram` 不删也不接。** 它有明确的 KDoc 说明"目前没有调用点",但里面记着 `coerceIn` 区间翻转的崩溃分析(Charts.kt:608-615)——那是给下一个人的路标,删了等于删掉一个已经踩过的坑的记录。留着,标注 dead code 即可。
12. **02 的卫星数滑块加 `steps = 9`(离散刻度)优先级最低。** `4f..14f` 语义上确实是 11 个离散值,但 `it.toInt()` 的截断行为对用户来说没有可感知的错误,加刻度只是"更好看一点"。列在 P2-17 里只为完整性,不单独排期。
13. **`ConvergenceChart` 的 `logScale = false` 分支不删**(Charts.kt:204)。当前无人传 false,但它是给"非对数场景"留的口子,和 `DopDiagram` 同类。只补 P2-20 的档位防御。
14. **不给 05 的 `HybridPositioner` 加"无信号"专属 UI。** 它已经在 P0-3 里用 `na = true` 覆盖了(误差 1e9 那行标成不可用)。为了一个只有"选室内/桥下"才会出现的边界场景给融合定位单开一套空态,是过度设计。

---

## 附:给下一轮改的顺序建议

1. **先改数据契约**:`ShotResult` 的失败态 + `avgError` 的 5 处 `?: 0.0`(P0-2/P0-3)。这是唯一会让 App 说错话的一类,优先级高于所有视觉项。
2. **再改状态模型**:per-section 状态 map(P0-1)+ visited(P1-7)+ cameFrom(P2-11)。这一组做完后,"课程是一串散页"的观感会一次性消失。
3. **然后修 08 的手势与高度**(P0-4/P0-5)—— 第 08 节是 App 的门面,现在它有一块拖不动的区域。
4. **再补一致性与空态**:常驻条覆盖 03/04/07/10(P1-1)、07 重复 picker(P1-8)、预设语义(P1-9)、图例(P1-11)、SkyPlot/BarList 空态(P2-3/P2-4)。
5. **最后是 a11y 与像素级**:触摸目标(P1-12)、滑块命名(P1-13)、`heading()` 与图标(P2-9)、字号与画布文字(P2-7/P2-8)。这些改起来互相独立,适合并行。

**改动量估算**:P0 六条约 250 行(含 per-section 状态改造),P1 十四条约 180 行,P2 二十条约 150 行。总计约 580 行,分散在 7 个文件,不需要新增依赖,不需要动 `sim/` / `solve/` / `fusion/` 任何一个算法文件(唯一一处越界是 P0-2 建议改 `ShotResult.errorM` 的失败态表示 —— 可以只在 UI 层用 `ok` 判断而不动引擎,但那样 `errorM` 的 0.0 语义仍然会漏给别的消费者,长期看不值)。

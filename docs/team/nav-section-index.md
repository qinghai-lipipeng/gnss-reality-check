# 导航节号修复:用节号,不用栈深度

**文件**:`app/src/main/java/com/oneus/lab/ui/AppRoot.kt`(唯一改动文件)
**未执行 git commit**(该目录当前不是 git 仓库:`fatal: not a git repository`;也未运行 gradle)

---

## 1. 根因

导航栈 `stack: SnapshotStateList<Int>` 里存的是**节号**(0..10),但有三处代码把
`stack.lastIndex` 当成了「当前位置」在用。`lastIndex` 是**栈深度**(`size - 1`),
只有在「从第一节一路顺序点下来」时才恰好等于节号;一旦从目录/首页直达任意一节,
两者立刻分叉。

```
首页点「从第一节开始」→  stack = [0,1,2,...,10]   lastIndex == 节号  ✅ 巧合成立
从目录直达第 11 节     →  stack = [10]           lastIndex = 0      ❌ 显示「第 1 / 11 节」
从第 3 节点一次「下一节」→ stack = [2,3]          lastIndex = 1      ❌ 显示「第 2 / 11 节」
```

由这一个根因派生出 3 个实机可见的 bug:

| # | 现象 | 直接原因 |
|---|---|---|
| 1 | 顶栏「第 1 / 11 节」但停在第 11 节 | `AppRoot.kt:72` `val idx = stack.lastIndex` → `"第 ${idx + 1} / ..."` |
| 2 | 末节底栏仍写「下一节 →」 | `SectionFooter` 的 `last = position >= total - 1`,而 `position = stack.lastIndex` |
| 3 | **末节点「下一节」跳回第一节** | `onNext = { stack.add(stack.lastIndex + 1) }`;末节直达时 `lastIndex = 0` → `stack.add(1)` |

> 顺带说明:bug 3 在「一路走到末节」时反而**不发作**(`lastIndex = 10` → 正常),
> 只在**从目录/首页直达末节**这条路径上炸,所以很容易漏测。

**约定**(本次修复后全局遵守):

* **节号**(section index)= `current = stack.lastOrNull()`,取值 0..10。
  用于:顶栏节号、顶栏进度比例、末节判定、下一节目标、内容分发。
* **栈深度**(stack depth)= `stack.size`,`lastIndex = size - 1`。
  只用于:「上一节」弹层、回不去了就清栈回首页。**永远不要**用它算节号。

---

## 2. 改动的函数

| 函数 | 位置 | 改了什么 |
|---|---|---|
| `AppRoot()` | `AppRoot.kt:50` | 新增局部 `stepBack()`;`BackHandler`、顶栏标题、顶栏进度条、顶栏 `←`、`SectionFooter` 调用点全部改为节号语义 |
| `ScreenBody(...)` | `AppRoot.kt:159` | 形参 `screen: Int` → `current: Int` + KDoc 标注是节号;`when (screen)` → `when (current)`。调用点是位置传参,无行为变化 |
| `SectionFooter(...)` | `AppRoot.kt:189` | 签名 `position: Int` → `current: Int`,**删除** `canPrev: Boolean`;`last` 判定改节号;左键改为永远可点 |

**未改动但已核查语义正确**:`go()`(跳过重复的当前节)、`home()`(`stack.clear()`)、
`SectionPicker()`(`on = current == i`、`onPick(i)` 全是节号)、
`Home()`(`onGo(i)` 中 `i` 是 `forEachIndexed` 的节号)。

---

## 3. 逐处前后行为对照

> 下列所有场景中,`total = entries.size = 11`,末节节号 = 10。

### 3.1 顶栏节号

```kotlin
- val idx = stack.lastIndex
- "第 ${idx + 1} / ${entries.size} 节"
+ "第 ${current + 1} / ${entries.size} 节"
```

| 场景 | 修复前显示 | 修复后显示 |
|---|---|---|
| 首页 →「从第一节开始」(栈 `[0]`) | 第 1 / 11 节 | 第 1 / 11 节(不变) |
| 一路「下一节」到末节(栈 `[0..10]`) | 第 11 / 11 节 | 第 11 / 11 节(不变) |
| **目录直达第 11 节**(栈 `[10]`) | ❌ 第 1 / 11 节 | ✅ 第 11 / 11 节 |
| **目录直达第 6 节**(栈 `[5]`) | ❌ 第 1 / 11 节 | ✅ 第 6 / 11 节 |
| **第 3 节点一次「下一节」**(栈 `[2,3]`) | ❌ 第 2 / 11 节 | ✅ 第 4 / 11 节 |
| **走完 11 节后从目录跳第 3 节**(栈 `[0..10,2]`) | ❌ 第 12 / 11 节 | ✅ 第 3 / 11 节 |

最后一行是原来没有上报的另一个越界显示:栈深 11 → `lastIndex = 10` → `第 12 / 11 节`。

### 3.2 顶栏进度条

```kotlin
- progress = { (stack.lastIndex + 1f) / entries.size }
+ progress = { current / (entries.size - 1f) }
```

按规格要求用 `current / (total - 1)`,即第一节 0%、末节 100%。

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 第 1 节(栈 `[0]`) | 1/11 ≈ 9.1% | 0/10 = 0% |
| 第 2 节(栈 `[0,1]`) | 2/11 ≈ 18.2% | 1/10 = 10% |
| 第 3 节(栈 `[2,3]`) | 2/11 ≈ 18.2%(且 2 节共用同一值) | 2/10 = 20% |
| 第 11 节·一路走到(栈 `[0..10]`) | 11/11 = 100% | 10/10 = 100% |
| **第 11 节·目录直达**(栈 `[10]`) | ❌ 1/11 ≈ 9.1% | ✅ 100% |

注意「第一节进度为 0%」是新语义的必然结果(0-indexed 映射到 0..1),
这是规格明确要求的,不是回归。

### 3.3 `SectionFooter` 末节判定

```kotlin
- position: Int,        // 实际收到的是 stack.lastIndex(栈深度)
- canPrev: Boolean,
- val last = position >= total - 1
+ current: Int,         // 当前节号
+ val last = current + 1 >= total
```

`current + 1 >= total` 与 `current == total - 1` 在合法区间内完全等价;
写成不等式是为了顺带把 `current >= total` 的越界情况也吞掉,不会漏出「下一节 →」。

| 场景(栈) | 修复前 `position = lastIndex` | 修复前文案 | 修复后 `current` | 修复后文案 |
|---|---|---|---|---|
| `[10]` 目录直达末节 | 0 | ❌ 下一节 → | 10 | ✅ 回到首页 |
| `[0..10]` 一路走到末节 | 10 | ✅ 回到首页 | 10 | ✅ 回到首页 |
| `[0,1]` 第 2 节 | 1 | ✅ 下一节 → | 1 | ✅ 下一节 → |
| `[2,3]` 第 4 节 | 1 | ✅ 下一节 → | 3 | ✅ 下一节 → |
| **`[0..10,2]`** 走完后从目录跳第 3 节 | 11 | ❌ 回到首页 | 2 | ✅ 下一节 → |

最后一行是同一根因的**反向**症状:栈深 ≥ `total` 时,身处中间某一节却显示「回到首页」,
点它直接被踢回首页。

### 3.4 右键点击行为(含最严重的越界)

```kotlin
- onNext = { stack.add(stack.lastIndex + 1) }
+ onNext = { if (current + 1 < entries.size) stack.add(current + 1) else home() }
```

| 场景(栈,current) | 修复前 | 修复后 |
|---|---|---|
| **`[10]`,current=10** | ❌ `add(0+1)` → 变 `[10,1]`,**跳回第 2 节** | 文案已变「回到首页」→ `stack.clear()` 回首页 |
| **`[2]`,current=2** | ❌ `add(0+1)` → 变 `[2,1]`,**倒退回第 2 节** | `add(3)` → 第 4 节 |
| **`[9]`,current=9** | ❌ `add(0+1)` → 变 `[9,1]`,**跳回第 2 节** | `add(10)` → 第 11 节 |
| **`[0..10,2]`,current=2** | ❌ `add(11+1)` → 越界,`ScreenBody` 落到 `else` 渲染成收尾页,顶栏还写「第 13 / 11 节」 | `add(3)` → 第 4 节 |
| `[0..10]`,current=10 | `add(10+1)` 越界(但 `last=true` 走 `onFinish`,未触发) | 走「回到首页」分支,清栈 |

**双重保险**:`SectionFooter` 里的 `last` 决定文案与分支,`onNext` 内部再判一次
`current + 1 < entries.size`。即便将来有人绕过 `last` 直接调 `onNext`,也不会 `add` 出
越界节号。

### 3.5 左侧「← 上一节」(不再置灰)

```kotlin
- canPrev = stack.size > 1,
- .clickable(enabled = canPrev) { onPrev() }
- color = if (canPrev) TextSecondary else TextMuted
+ .clickable { onPrev() }
+ color = TextSecondary
```

| 栈深度 | 修复前 | 修复后 |
|---|---|---|
| 1(如 `[10]`,从目录直达) | ❌ 置灰(`TextMuted`),点了没反应 | ✅ 可点,`stack.clear()` 回首页 |
| 2(如 `[0,1]`) | 可点,弹一层 → 第 1 节 | 同左 |
| 11(`[0..10]`) | 可点,弹一层 → 第 10 节 | 同左 |

### 3.6 顶栏 `←` 与系统返回手势 —— 统一为 `stepBack()`

```kotlin
+ fun stepBack() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else home() }
  BackHandler(enabled = stack.isNotEmpty()) { stepBack() }              // 原:stack.removeAt(stack.lastIndex)
  navigationIcon = { TextButton(onClick = { stepBack() }) { ... } }      // 原:if (stack.size > 1) ... else home()
  SectionFooter(onPrev = { stepBack() })                                // 原:stack.removeAt(stack.lastIndex)
```

`stepBack()` 是**唯一**做「上一级」的地方,且它是故意用栈深度语义的(弹一层 = 弹栈顶),
与节号语义隔离,注释里写明了这点,避免下一个人再混用。

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 栈 `[10]`,系统返回 | `removeAt(0)` → `[]` 回首页 | `stepBack()` → `clear()` 回首页(等价) |
| 栈 `[10]`,顶栏 `←` | `clear()` 回首页 | 同左(逻辑搬进 `stepBack()`) |
| 栈 `[0,1,2]`,任意返回入口 | 弹一层 → 第 2 节 | 同左 |

---

## 4. 保持不变的部分

* M3 风格、`Surface` + `RoundedCornerShape` 配色、`navigationBarsPadding()` 全部原样保留。
* 未新增任何依赖;未改动任何其他文件。
* 未运行 gradle(并行任务共用 build 目录)。
* `SectionFooter` 为 `private` 函数,`SectionPicker` 同理,无跨文件调用点,签名变更影响面为零。

## 5. 建议手测路径(未在本次执行)

1. 首页 → 目录 → 直达第 11 节:顶栏「11 / 11 节」+ 进度条满 + 底栏「回到首页」。
2. 此时点「回到首页」→ 回首页;再点「从第一节开始」→ 第 1 节、进度条 0%、底栏「下一节 →」。
3. 第 1 节点「← 上一节」→ 回首页(不再是死按钮)。
4. 一路「下一节」到末节 → 「回到首页」;再点「上一节」→ 第 10 节。
5. 走到第 3 节 → 顶栏「4 / 11 节」(不是「第 2 / 11 节」)。
6. 走完 11 节 → 目录跳第 3 节 → 顶栏「3 / 11 节」、底栏「下一节 →」(不是「第 12 / 11 节」/「回到首页」)。
7. 任意节连按系统返回,直到回首页,再按一次应退出应用(不崩)。

# 修复:第 01 节「光速尺」打开即崩(零宽画布 → coerceIn 空区间)

- **改动范围**:仅 `app/src/main/java/com/oneus/lab/ui/charts/Charts.kt`
- **涉及组件**:`ClockRuler`(根因)、`DopDiagram`(同族未爆)、`SkyPlot` / `ConvergenceChart` / `ResidualBars` / `drawZeroChip`(同族加固)
- **新增私有工具**:`coerceSafe(value, lo, hi, fallback)`
- **未触碰**:`Experiments1.kt`(调用点归另一任务)、`Experiments2/3.kt`、`AppRoot.kt`、`Components.kt`
- **公开签名零变更**:`ClockRuler(ns, modifier)` / `DopDiagram(hdop, vdop, modifier)` 调用方无需改动
- **未执行 gradle**(并行任务共用 build 目录),验证方式见文末
- **未 git commit**

---

## 1. 根因

### 1.1 崩溃链路

`Experiments1.kt:277`(只读,未改):

```kotlin
ClockRuler(satNs.toDouble())      // ← 没有任何宽度修饰符
```

调用点在 `Card { ... }`(`Experiments1.kt:267-285`)的 Column 里,宽度约束是有界的,
但**`Canvas` 没有内在尺寸**:不写宽度修饰符时,Compose 只能按 `minWidth = 0` 测量,
于是 `size.width == 0`。

`Charts.kt` 里(`ClockRuler` 内,修复前):

```kotlin
val inset = 2.dp.toPx()          // 2 dp
val markHalfW = 4.5.dp.toPx()     // 4.5 dp
val rawX  = inset + (w - 2f * inset) * (meters / maxM).coerceIn(0.0, 1.0).toFloat()
val markX = rawX.coerceIn(inset + markHalfW, w - inset - markHalfW)   // ← 这里
```

`w = 0` 时下界 = `6.5 dp`、上界 = `-6.5 dp`。Kotlin 的 `coerceIn` 第一行是

```kotlin
require(minimumValue <= maximumValue) {
    "Cannot coerce value to an empty range: $minimumValue..$maximumValue"
}
```

上下界翻转 → 直接抛 `IllegalArgumentException`。`Canvas` 的绘制 lambda 在布局/绘制
阶段跑,异常冒泡出 Composable → **每次打开第 01 节必崩**,和用户填的 ns 值无关
(`markX` 在 `if (hasValue)` 之外,ns = 0 也照样走到)。

### 1.2 复核结论:报告准确,但触发面比描述的更宽

我按 v8 原样跑了一遍边界(3 px/dp 折算),`ClockRuler` 的崩溃阈值不是 `w = 0`,
而是**任何 `w < 13 dp`**:

| `w` | 下界 | 上界 | v8 结果 |
|---|---|---|---|
| 0 | 19.5 px | −19.5 px | 抛 `IllegalArgumentException` |
| 1 px | 19.5 | −18.5 | 抛 |
| 20 px | 19.5 | 0.5 | 抛 |
| 39 px (= 13 dp) | 19.5 | 19.5 | 正常(区间刚好退化成一点) |
| 1080 px | 19.5 | 1060.5 | 正常 |

也就是说,除了零宽,**多窗口/分屏、折叠屏展开动画的首帧、极端窄屏**下同样会崩。
只按"补 `fillMaxWidth()`"修,只能盖住其中一条路径。

### 1.3 和 v7 的关系:同一个老问题换面孔

- v7:指标是满高竖线 `drawLine(Accent, Offset(x, 0f), Offset(x, h), 2f)`。宽度为 0 时
  `x` 落在画布左边缘 → 用户报的"滑条下方一条莫名其妙的竖线",**静默瑕疵**。
  (根因见 `docs/team/chart-render-fixes.md` 问题 1。)
- v8:同一处改成三角标,并按那份文档"防贴边兜底"的建议加了 `coerceIn` ——
  瑕疵没消失,只是从"画错"变成了"抛异常"。

同一条 `w = 0` 的路径,先被当成刻度样式问题误判,后被当成"加个 clamp 就好"处理,
两次都没去看 `w` 本身是不是 0。

---

## 2. 根因修复:让画布有确定宽度

```kotlin
// Charts.kt:477
Canvas(modifier = modifier.fillMaxWidth().height(44.dp)) { ... }
```

- 写在 `modifier` **之后**:调用点将来若显式传 `Modifier.width(...)`,那个固定宽度仍然优先
  (`fillMaxWidth` 只在还有剩余空间时生效),不构成冲突。
- 同样的处理补到了 `DopDiagram`(`Charts.kt:623`)—— 它目前**没有调用点**,
  但和 `ClockRuler` 是完全相同的形状,不加就是个等着被踩的雷。
- `ConvergenceChart` 也顺手补了(两个现有调用点本来就传了 `fillMaxWidth`,**视觉零变化**)。

**关于调用点**:`Experiments1.kt:277` 我没有改(按约定归另一任务)。
修复后该行**即使保持原样也能正常工作**;另一任务若想顺手加上
`Modifier.fillMaxWidth()` 表达意图,是可选的冗余,不是必需项。

---

## 3. 防御性修复

### 3.1 统一收口:`coerceSafe`

```kotlin
// Charts.kt:44
private fun coerceSafe(value: Float, lo: Float, hi: Float, fallback: Float): Float = when {
    hi < lo -> fallback
    value < lo -> lo
    value > hi -> hi
    else -> value
}
```

语义与 `Float.coerceIn` **逐条对齐**(Kotlin 的实现本身就是这个 when 链),
唯一的区别:`hi < lo` 时返回 `fallback` 而不是抛异常。NaN 与 `coerceIn` 一致 ——
NaN 的两个比较都为 false,落到 `else` 原样返回。

放在文件顶部而不是各点内联,是为了让"尺寸推导出来的上下界"以后只有**一个**收口,
不会再散落出新的 `coerceIn`。

### 3.2 逐个改点

| 位置 | 改动 |
|---|---|
| `ClockRuler`(`Charts.kt:477-481`) | `fillMaxWidth()` + 画布 lambda 开头 `if (w <= 0f \|\| h <= 0f) return@Canvas` |
| `ClockRuler`(`Charts.kt:512`) | `rawX.coerceIn(...)` → `coerceSafe(rawX, inset + markHalfW, w - inset - markHalfW, fallback = w / 2f)` |
| `ClockRuler`(`Charts.kt:517`) | 填充条 `Size(markX - inset, bodyH)` → `Size((markX - inset).coerceAtLeast(0f), bodyH)`(极窄时 `markX` 兜底到中点,可能小于 `inset`) |
| `DopDiagram`(`Charts.kt:623-630`) | `fillMaxWidth()` + 正尺寸 guard + 两个半轴改 `coerceSafe(..., fallback = 0f)` + `if (a <= 0f \|\| b <= 0f) return@Canvas` |
| `SkyPlot`(`Charts.kt:63-70`) | 正尺寸 guard + `if (r <= 0f) return@Canvas`(见 §5) |
| `ConvergenceChart`(`Charts.kt:214-220`) | `fillMaxWidth()` + `w <= 0f \|\| h <= 0f` guard(绘图区扣掉 padding 后为负) |
| `ResidualBars`(`Charts.kt:589-593`) | 正尺寸 guard |
| `drawZeroChip`(`Charts.kt:307`) | `chipH` 补 `.coerceAtLeast(0f)`,画布不足 4 px 高时不画负高圆角矩形 |

`DopDiagram` 的 `fallback = 0f` 之后紧跟 `if (a <= 0f || b <= 0f) return@Canvas`:
空间不够放一个 4 px 的半轴时,退化成"什么都不画"而不是画一个越界的椭圆。

---

## 4. 同族排查:全文 `coerceIn(` 的边界论证

修复后 `Charts.kt` 里**还剩 4 个 `coerceIn` 调用**,全部是常量区间;
所有由 `size.width` / `size.height` / `w` / `h` 推导出来的边界,都已改走 `coerceSafe`。

| 行 | 调用 | 边界来源 | 论证 |
|---|---|---|---|
| 109 | `m.elDeg.coerceIn(0.0, 90.0)` | 字面量 | `0.0 < 90.0` 是编译期事实,`require` 恒成立,不可能抛 |
| 113 | `(m.cn0 - 20.0).coerceIn(0.0, 30.0)` | 字面量 | `0.0 < 30.0`,同上。NaN 落 `else` 原样返回,不抛 |
| 141 | `(r.value / scale).coerceIn(0.0, 1.0)`(`BarList`) | 字面量 | `0.0 < 1.0`,同上 |
| 347 | `(impact / scale).coerceIn(0.0, 1.0)`(`WaterfallChart`) | 字面量 | `0.0 < 1.0`,同上 |
| 511 | `(meters / maxM).coerceIn(0.0, 1.0)`(`ClockRuler`) | 字面量 | `0.0 < 1.0`。`meters` 为 `±Inf`(ns 有限但乘爆)时 `x > hi` 成立 → 返回 1.0,先于任何边界计算生效,安全 |

注释里(`Charts.kt:36/37/459/611`)出现的 `coerceIn` 字样是文档叙述,不是调用点。

### 4.1 除零:`scale` 恒 > 0

`BarList`(L137)与 `WaterfallChart`(L341)同构:

```kotlin
val maxV = rows.maxOfOrNull { ... } ?: 1.0
val scale = if (maxV > 0) maxV else 1.0
```

- `maxV` 为 `0` / 负数 / **`NaN`** 时,`maxV > 0` 恒为 false(IEEE:NaN 的任何比较都是 false)
  → `scale = 1.0`。
- `maxV > 0` 时 `scale = maxV > 0`。
- 结论:**`scale` 恒 `> 0`,永远除不到零**,两个 `coerceIn(0.0, 1.0)` 不会收到 `NaN` 之外的
  异常输入。即便分子分母同时是 `±Inf` 得到 `NaN`,`coerceIn` 也只是原样返回 `NaN`,
  下游 `if (frac > 0f)` 为 false → 不画,依旧不崩。已在两处加了注释固化这个结论。

### 4.2 其它算式自查(不改行为)

- `ClockRuler.tickX(m) = inset + (w - 2f * inset) * (m / maxM)`:只产生负坐标,不抛。
- `drawHatch` 的 `while (x < right) x += seg`:三处调用点的 `seg` 分别是 `8f` / `7f` / `10f`,
  **没有 0**,不会死循环。
- `ResidualBars`:`maxAbs = max(maxOfOrNull{...} ?: 1.0, 0.1)`;NaN 会传染成 `NaN` 高度,
  但 `drawRect` 对 NaN 尺寸不抛,且 `n == 0` 已提前返回,`size.width / n` 不会除零。

---

## 5. 一处超出 coerceIn 范围的加固(请复核是否认可)

`SkyPlot` 没有任何尺寸推导的 `coerceIn`,但有同源的崩溃面:

```kotlin
val r = min(size.width, size.height) / 2f - 10.dp.toPx()   // 宽 0 → r = -10 dp
drawCircle(Line, rr, c, style = Stroke(1f))                  // 负半径
```

负半径最终进 `android.graphics.Canvas` / Skia,不同版本对负半径的处理不一致
(部分版本直接抛、部分版本静默不画)。我**无法在离线环境里确认当前 Compose/Skia
版本的实际行为**(约束要求不跑 gradle,拿不到依赖源码),所以按"极端尺寸不崩"的验收
标准加了两道 guard:先判正尺寸,再判 `r > 0`(画布小于 20 dp 时半径就是负的)。
顺带说明:半径为负时 `scale = r / 40` 也是负的,`drawOval` 会拿到负的 `Size` ——
Compose 的 `drawOval` 没有 `require`,原生侧也只是不画,所以那条路本来不会崩,
但它画出来的是"负尺寸椭圆"这种没有意义的东西,一并挡掉。
如果评审认为这属于越界,回退这两处 guard 不影响本次崩修复本身。

---

## 6. 验证

**未跑 gradle**(并行任务共用 build 目录)。

1. **静态结构检查**:剔除注释/字符串后统计括号 —— 花括号 68/68、圆括号 392/392、
   方括号 4/4,全部配平;7 个 `@Composable` 齐全;无新增 import(用到
   `fillMaxWidth` / `coerceAtLeast` 均已被 `foundation.layout.*` 与 `kotlin.math.*` 覆盖)。
2. **逐边界的数值验证**:把 v8 原逻辑和修复后逻辑都复刻成脚本,对
   `w ∈ {0, 0.5, 1, 5, 12.9, 13, 13.01, 20, 100, 360, 1080, −1, −10⁴, −10⁶, 10⁹}`
   × `ns ∈ {0, −5, 1e-9, 20, 167, 500, 2003, 1e12, NaN, ±Inf}` 共 117 组跑:
   - 修复后:**0 异常**(含负数宽度、极大宽度、NaN/Inf 输入);
   - 修复前:`w < 13 dp` 的 12 组全部复现 `Cannot coerce value to an empty range`;
   - `DopDiagram` 同法验证,旧代码在 `w < 20 px` 抛,新代码 0 异常。
3. **视觉一致性**:在 `w ∈ {200, 360, 1080}`(真实卡片宽度)×
   `ns ∈ {0, 20, 167, 500, 2003}` 上,新旧 `markX` **逐位相同** ——
   `coerceSafe` 在区间合法时与 `coerceIn` 完全等价,v8 的刻度、三角标、溢出表现不变。
4. **回归点自查**:唯一会改变已有像素的地方是 `Size((markX - inset).coerceAtLeast(0f), ...)`,
   它只在 `w < 13 dp`(v8 必崩的区间)才可能生效;正常宽度下 `markX ≥ inset + markHalfW > inset`,
   `coerceAtLeast` 是恒等变换。

**仍需人工/真机确认**:第 01 节能正常打开、三角标位置与 v8 一致(我没有设备)。

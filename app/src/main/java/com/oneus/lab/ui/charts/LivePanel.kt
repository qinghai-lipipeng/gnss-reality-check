package com.oneus.lab.ui.charts

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneus.lab.ui.theme.Accent

// ══════════════════════════════════════════
// 常驻可视化面板
// ══════════════════════════════════════════

/**
 * 常驻可视化面板 —— 沙盒类实验的关键组件([LiveResultBar] 的升级版)。
 *
 * ## 要解决的问题
 *
 * 用户的原话是"把天空图挪到下面,还是得来回滑"。
 * 症结不是图放在哪一端,而是**图和数字会互相把对方挤出视野**:
 * 图在内容最底部时,调完滑块要往下滚才看得到,看完又得往上滚才看得到数字。
 *
 * ## 做法
 *
 * 把数字和图合成**同一块常驻面板**:
 * - 收起态 ≈ 64dp,一排关键数字 + 展开按钮,钉在滚动区之外,永远在视野顶部;
 * - 展开态在面板内部露出可视化(高度封顶 40% 屏高,超高部分在面板内自己滚)。
 *
 * 面板挂在 [com.oneus.lab.ui.experiments.ExpScaffold] 的 sticky 槽里 ——
 * 那个槽在 `weight(1f)` 可滚动区**之外**,所以"看数字"和"看图"永远不用互相找。
 *
 * ## 参数
 *
 * - [open] / [onToggleOpen]:展开状态。**由调用方持有**(状态提升),
 *   面板自己只负责画 —— 这样父页面和面板共享同一份状态,父页面也能强制收起。
 * - [collapsed]:收起态常驻的数字(通常是 误差 / 可见星 / HDOP)。
 * - [sky]:展开态的主可视化,尺寸由它自己决定(通常 `fillMaxWidth()` + `aspectRatio(1f)`)。
 * - [extra]:展开态的附加内容(图注、残差条……),不传就是只有图。
 *
 * ## 高度账
 *
 * 收起态 = 上下 8dp + max(数字列 42dp, 按钮 48dp) = **64dp**,不压可视区。
 * 展开态 = 64dp + min(内容高度, 40% 屏高);封顶是为了别把下面的滑块挤没。
 */
@Composable
fun LivePanel(
    /** 展开状态由外部持有 —— 见 KDoc 的"状态提升" */
    open: Boolean,
    onToggleOpen: () -> Unit,
    collapsed: List<StatData>,
    sky: @Composable () -> Unit,
    extra: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 展开区封顶 40% 屏高:再高就会把下面的滑块/开关挤出视野,那就白改了
    val bodyMax = (LocalConfiguration.current.screenHeightDp * 0.4f).dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // 展开/收起有动画,状态变化看得见;tween 不会像 spring 那样在高度上冲过头
            .animateContentSize(animationSpec = tween(220)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {

            // ── 头部:两种状态都在,所以「收起 ▼」永远够得着,不用先滚面板 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    // 整条都可点,手指不用瞄准那个小按钮(收起态尤其重要)
                    .clickable(onClick = onToggleOpen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                collapsed.forEach { t -> PanelCell(t, Modifier.weight(1f)) }
                Spacer(Modifier.width(4.dp))
                PanelToggle(open, onToggleOpen)
            }

            if (open) {
                Spacer(Modifier.height(10.dp))
                // 只有内容区自己会滚;头部(数字 + 收起按钮)始终固定
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = bodyMax)
                        .verticalScroll(rememberScrollState())
                ) {
                    sky()
                    Spacer(Modifier.height(10.dp))
                    extra()
                }
            }
        }
    }
}

/** 面板里的一格数字。没有内层 Surface,底色交给面板 —— 少一层嵌套,高度更好控。 */
@Composable
private fun PanelCell(t: StatData, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            t.label,
            style = mono10(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            t.value,
            color = t.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold
            )
        )
    }
}

/** 展开/收起开关:48dp 见方,满足最小点击区。箭头单独一行并高亮,一眼看出这是个开关。 */
@Composable
private fun PanelToggle(open: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (open) "收起" else "展开",
                style = mono10(),
                color = TextSecondary,
                maxLines = 1
            )
            Text(
                if (open) "▼" else "▲",
                style = mono13(),
                color = Accent,
                maxLines = 1
            )
        }
    }
}

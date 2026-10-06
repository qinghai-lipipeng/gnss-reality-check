package com.oneus.lab.ui.experiments

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oneus.lab.ui.charts.*
import com.oneus.lab.ui.theme.*

// ══════════════════════════════════════════
// 实验页的通用外壳
//
// 以前 ExpScaffold / Takeaway 住在 Experiments1.kt 里,而 Experiments2 / 3 的每一页
// 都要靠它才能画页框 —— 等于"页面框架"依赖于"第一节的内容文件"。
// 框架被倒着抽出来,三个实验文件才对等,谁也不再依赖谁的正文。
// ══════════════════════════════════════════

/**
 * 每个实验页的通用外壳。
 *
 * 深度、术语开关都从 [env] 读,不再由 11 个页面各接一遍平行参数。
 */
@Composable
fun ExpScaffold(
    env: SectionEnv,
    title: String,
    /** 常驻区:钉在滚动区之外,调参时始终可见。不用时留空。 */
    sticky: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(env.code, style = mono13(), color = Accent)
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = title18(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DepthSwitch(env.depth, env.onDepth)
                Spacer(Modifier.width(8.dp))
                TechChip(env)
            }
            Spacer(Modifier.height(4.dp))
            Text(env.depth.hint, style = mono10())
            // 这一行跟着术语开关变 —— 开关接进来之后必须是**看得见**地生效,
            // 否则它又变回首页那个"点了没反应"的控件。
            Text(
                env.term(
                    "人话模式:不出现「电离层」这种词,只说人话",
                    "术语模式:显示电离层、DOP、协方差这些标准术语"
                ),
                style = mono10()
            )
        }
        if (sticky != null) {
            Spacer(Modifier.height(10.dp))
            sticky()
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 48.dp)
        ) { content() }
    }
}

/** 术语开关。原来只画在首页,而首页之下没有任何地方读得到它。 */
@Composable
private fun TechChip(env: SectionEnv) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (env.tech) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickableBox { env.onTech(!env.tech) }
    ) {
        Text(
            if (env.tech) "术语:专业" else "术语:人话",
            style = MaterialTheme.typography.labelLarge,
            color = if (env.tech) MaterialTheme.colorScheme.onPrimaryContainer else TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

/** 结论条:每层结尾一句话,可复述 */
@Composable
fun Takeaway(text: String, color: Color = Accent) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .heightIn(min = 20.dp)
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Text(text, style = body14(), color = TextPrimary)
        }
    }
}
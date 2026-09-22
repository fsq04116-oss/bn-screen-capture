package com.baining.str.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baining.str.ui.theme.GlassStroke

/**
 * 液态玻璃卡片
 * iOS毛玻璃：半透明渐变背景 + 高光描边 + 柔和阴影
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    alpha: Float = 0.15f,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = !MaterialTheme.colorScheme.background.isLight()

    val bgColor = if (isDark)
        Color.White.copy(alpha = alpha)
    else
        Color.White.copy(alpha = (alpha + 0.5f).coerceAtMost(1f))

    val strokeColor = if (isDark)
        Color.White.copy(alpha = 0.25f)
    else
        Color.White.copy(alpha = 0.7f)

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        bgColor.copy(alpha = (bgColor.alpha + 0.05f).coerceAtMost(1f)),
                        bgColor
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        strokeColor,
                        strokeColor.copy(alpha = strokeColor.alpha * 0.3f)
                    )
                ),
                shape = shape
            ),
        content = content
    )
}

/**
 * 液态玻璃胶囊标签
 */
@Composable
fun GlassBadge(
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.2f),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .border(0.5.dp, GlassStroke, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        content = content
    )
}

/** 判断颜色是否为浅色 */
fun Color.isLight(): Boolean {
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return luminance > 0.5f
}

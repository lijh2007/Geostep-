package com.geostep.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 仿 iOS 的「液态玻璃」外观。
 *
 * Android 上真正的背景模糊（backdrop blur）只能对 View 层用 RenderEffect 实现，
 * Compose 里做不到跨界模糊，所以这里用「高透白渐变 + 渐变描边 + 内高光 + 双层阴影」
 * 组合出玻璃质感：远看是半透明磨砂片，近看有边缘高光和厚度，且在深浅色下都成立。
 */

/** 玻璃底色：上亮下暗的白，按主题深浅自动升降透明度。 */
private fun glassTint(isDark: Boolean, strength: Float): Brush {
    val top = Color.White.copy(alpha = if (isDark) 0.16f * strength else 0.62f * strength)
    val bottom = Color.White.copy(alpha = if (isDark) 0.06f * strength else 0.30f * strength)
    return Brush.verticalGradient(listOf(top, bottom))
}

/** 描边：左上亮、右下暗，模拟玻璃边缘的折射感。 */
private fun glassStroke(isDark: Boolean, strength: Float): Brush {
    val bright = Color.White.copy(alpha = if (isDark) 0.45f * strength else 0.95f * strength)
    val dim = Color.White.copy(alpha = if (isDark) 0.12f * strength else 0.35f * strength)
    return Brush.linearGradient(
        colors = listOf(bright, dim, dim, bright),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

/** 玻璃主体：圆角、渐变底、描边、内高光、柔和外阴影。 */
internal fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(22.dp),
    strength: Float = 0.4f,
    shadowElevation: Float = 14f,
    veilAlpha: Float = 0.92f,
): Modifier = composed {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val tint = glassTint(dark, strength)
    val stroke = glassStroke(dark, strength)
    val veil = MaterialTheme.colorScheme.surface.copy(alpha = veilAlpha)
    this
        .shadow(
            elevation = shadowElevation.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.16f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.16f),
        )
        .clip(shape)
        .background(veil)
        .background(tint)
        .border(width = 1.dp, brush = stroke, shape = shape)
        .drawWithContent {
            drawContent()
            // 顶部 1dp 高光：玻璃的「厚度」来源
            val highlight = Color.White.copy(alpha = if (dark) 0.22f else 0.55f)
            drawLine(
                color = highlight,
                start = Offset(size.width * 0.12f, 1f),
                end = Offset(size.width * 0.88f, 1f),
                strokeWidth = 1.5f,
            )
        }
}

/** 轻量玻璃：用于小胶囊与按钮，避免大阴影糊在薄元素上。 */
internal fun Modifier.liquidGlassChip(
    shape: Shape = RoundedCornerShape(50),
    strength: Float = 0.36f,
    veilAlpha: Float = 0.85f,
): Modifier = composed {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    this
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = veilAlpha))
        .background(glassTint(dark, strength))
        .border(1.dp, glassStroke(dark, strength), shape)
}

/** 真正的内容容器，省掉每次写 modifier 链。 */
@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    strength: Float = 1f,
    shadowElevation: Float = 14f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.liquidGlass(shape, strength, shadowElevation), content = content)
}

private fun Color.luminanceIsDark(): Boolean {
    val l = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return l < 0.5f
}

package com.geostep.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 触摸反馈：按下时轻微缩小 + 系统涟漪。
 *
 * 界面里所有“整块可点”的地方都应该用它，而不是裸 [clickable]：
 * 缩放比只有 4%，但在连续点击（开关、预设、底部快捷入口）时能明显感到“按进去了”。
 */
internal fun Modifier.tapFeedback(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tapFeedbackScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interaction,
        indication = LocalIndication.current,
        enabled = enabled,
        onClick = onClick,
    )
}

/** 只做按压缩放、不带点击（用于已经有 onClick 的组件外面套一层视觉反馈）。 */
@Composable
internal fun pressedScaleModifier(
    pressed: Boolean,
    scaleDown: Float = 0.96f,
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressedScale",
    )
    return Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

package com.geostep.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 开屏动画：一个由「定位引脚 + 行走节奏」构成的最小标记，配合字标的淡入与呼吸收束，
 * 全程约 900ms，不阻塞首帧，动画结束回调 [onFinished] 让内容层接管。
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val markProgress = remember { Animatable(0f) }
    val textProgress = remember { Animatable(0f) }
    val finished = rememberUpdatedState(onFinished)
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    // 低端机 / Android 8-9 上把开屏动画收短，避免首帧被判成卡顿。
    val context = LocalContext.current
    val scale = remember(context) { Perf.splashScale(Perf.isLowEnd(context)) }

    LaunchedEffect(Unit) {
        markProgress.animateTo(1f, tween((620 * scale).toInt(), easing = FastOutSlowInEasing))
        textProgress.animateTo(1f, tween((260 * scale).toInt(), easing = LinearEasing))
        kotlinx.coroutines.delay((180 * scale).toLong())
        finished.value()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(124.dp)
                    .graphicsLayer { scaleX = 0.86f + 0.14f * markProgress.value; scaleY = scaleX },
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension * 0.30f
                // 外圈：定位环，随进度旋转并入
                drawCircle(
                    color = primary.copy(alpha = 0.16f),
                    radius = radius * (0.75f + 0.55f * markProgress.value),
                    center = center,
                )
                drawCircle(
                    color = primary.copy(alpha = 0.55f),
                    radius = radius * 1.32f,
                    center = center,
                    style = Stroke(width = 2f),
                )
                // 内部步频脉冲：三条等距竖线，高度按呼吸节奏起伏
                val beat = (0.55f + 0.45f * sin(markProgress.value * PI).toFloat()).coerceIn(0.35f, 1f)
                val barWidth = radius * 0.16f
                val gap = radius * 0.30f
                listOf(0.62f, 1f, 0.78f).forEachIndexed { index, factor ->
                    val height = radius * 1.15f * factor * beat
                    val x = center.x + (index - 1) * (barWidth + gap)
                    drawLine(
                        color = primary,
                        start = Offset(x, center.y - height / 2f),
                        end = Offset(x, center.y + height / 2f),
                        strokeWidth = barWidth,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                // 轨道点：沿环走一圈，暗示“移动”
                val angle = markProgress.value * 2f * PI.toFloat() - (PI / 2f).toFloat()
                drawCircle(
                    color = primary,
                    radius = radius * 0.12f,
                    center = Offset(
                        center.x + cos(angle) * radius * 1.32f,
                        center.y + sin(angle) * radius * 1.32f,
                    ),
                )
            }

            Box(modifier = Modifier.height(22.dp))
            Text(
                text = "Geostep",
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f + 0.65f * textProgress.value),
                modifier = Modifier.graphicsLayer { translationY = (1f - textProgress.value) * 18f },
                textAlign = TextAlign.Center,
            )
            Box(modifier = Modifier.height(6.dp))
            Text(
                text = "定位 · 步频 · 轨迹",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f * textProgress.value),
                letterSpacing = 4.sp,
            )
        }
    }
}

/** 供其他页面复用的强调渐变，保持品牌一致。 */
internal fun accentBrush(primary: Color, tertiary: Color): Brush =
    Brush.linearGradient(listOf(primary, tertiary))

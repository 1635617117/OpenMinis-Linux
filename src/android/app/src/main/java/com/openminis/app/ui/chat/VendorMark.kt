package com.openminis.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small vendor mark for group-chat speakers. These are original geometric
 * cues, not copied trademark artwork, so the row stays offline and recognizable.
 */
@Composable
internal fun VendorMark(
    vendor: String,
    fallbackName: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val paint = vendorPaint(vendor)
    if (paint == null) {
        Box(
            modifier = modifier
                .size(size)
                .background(fallbackVendorColor(fallbackName), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallbackName.take(1),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    Canvas(modifier.size(size)) {
        drawCircle(paint.background)
        paint.draw(this)
    }
}

private data class VendorPaint(
    val background: Color,
    val draw: DrawScope.() -> Unit,
)

private fun vendorPaint(vendor: String): VendorPaint? = when (vendor) {
    "openai" -> VendorPaint(Color(0xFF111111)) { blossom() }
    "anthropic" -> VendorPaint(Color(0xFFD97757)) { rays(8) }
    "gemini" -> VendorPaint(Color.White) { sparkle(Color(0xFF4285F4)) }
    "deepseek" -> VendorPaint(Color(0xFF4D6BFE)) { wave() }
    "qwen" -> VendorPaint(Color(0xFF615CED)) { ringQ() }
    "kimi" -> VendorPaint(Color(0xFF111111)) { crescent() }
    "doubao" -> VendorPaint(Color(0xFFFF5A36)) { bean() }
    "xai" -> VendorPaint(Color(0xFF111111)) { cross() }
    "openrouter" -> VendorPaint(Color(0xFF00A3C4)) { fork() }
    "mistral" -> VendorPaint(Color(0xFFFF7000)) { chevrons() }
    "meta" -> VendorPaint(Color(0xFF0668E1)) { loop() }
    "zhipu" -> VendorPaint(Color(0xFF1E4BFF)) { diamond() }
    "minimax" -> VendorPaint(Color(0xFF7B61FF)) { peaks() }
    "groq" -> VendorPaint(Color(0xFFF55036)) { chip() }
    "cohere" -> VendorPaint(Color(0xFF39594D)) { arcC() }
    "perplexity" -> VendorPaint(Color(0xFF20808D)) { rays(6) }
    "hunyuan" -> VendorPaint(Color(0xFF0052D9)) { diamond() }
    "ernie" -> VendorPaint(Color(0xFF2932E1)) { bean() }
    "baichuan" -> VendorPaint(Color(0xFF1A6DFF)) { wave() }
    "stepfun" -> VendorPaint(Color(0xFF111111)) { peaks() }
    "internlm" -> VendorPaint(Color(0xFF0F766E)) { chip() }
    else -> null
}

private fun DrawScope.blossom() {
    val ring = size.minDimension * 0.22f
    val radius = size.minDimension * 0.13f
    repeat(6) { index ->
        val angle = index * PI / 3.0
        drawCircle(
            color = Color.White,
            radius = radius,
            center = Offset(
                center.x + (cos(angle) * ring).toFloat(),
                center.y + (sin(angle) * ring).toFloat(),
            ),
        )
    }
}

private fun DrawScope.rays(count: Int) {
    val stroke = Stroke(width = size.minDimension * 0.08f, cap = StrokeCap.Round)
    val inner = size.minDimension * 0.16f
    val outer = size.minDimension * 0.34f
    repeat(count) { index ->
        val angle = index * 2.0 * PI / count - PI / 2.0
        drawLine(
            color = Color.White,
            start = Offset(center.x + (cos(angle) * inner).toFloat(), center.y + (sin(angle) * inner).toFloat()),
            end = Offset(center.x + (cos(angle) * outer).toFloat(), center.y + (sin(angle) * outer).toFloat()),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.sparkle(color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - size.minDimension * 0.34f)
        lineTo(center.x + size.minDimension * 0.08f, center.y - size.minDimension * 0.08f)
        lineTo(center.x + size.minDimension * 0.34f, center.y)
        lineTo(center.x + size.minDimension * 0.08f, center.y + size.minDimension * 0.08f)
        lineTo(center.x, center.y + size.minDimension * 0.34f)
        lineTo(center.x - size.minDimension * 0.08f, center.y + size.minDimension * 0.08f)
        lineTo(center.x - size.minDimension * 0.34f, center.y)
        lineTo(center.x - size.minDimension * 0.08f, center.y - size.minDimension * 0.08f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.wave() {
    val path = Path().apply {
        moveTo(size.minDimension * 0.16f, size.minDimension * 0.58f)
        quadraticTo(size.minDimension * 0.32f, size.minDimension * 0.28f, size.minDimension * 0.50f, size.minDimension * 0.50f)
        quadraticTo(size.minDimension * 0.68f, size.minDimension * 0.72f, size.minDimension * 0.84f, size.minDimension * 0.40f)
    }
    drawPath(path, Color.White, style = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round))
}

private fun DrawScope.ringQ() {
    drawCircle(
        color = Color.White,
        radius = size.minDimension * 0.22f,
        style = Stroke(width = size.minDimension * 0.08f),
    )
    drawLine(
        color = Color.White,
        start = Offset(center.x + size.minDimension * 0.08f, center.y + size.minDimension * 0.08f),
        end = Offset(center.x + size.minDimension * 0.24f, center.y + size.minDimension * 0.24f),
        strokeWidth = size.minDimension * 0.08f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.crescent() {
    drawCircle(Color.White, radius = size.minDimension * 0.26f, center = center)
    drawCircle(
        Color(0xFF111111),
        radius = size.minDimension * 0.22f,
        center = Offset(center.x + size.minDimension * 0.12f, center.y - size.minDimension * 0.04f),
    )
}

private fun DrawScope.bean() {
    drawOval(
        color = Color.White,
        topLeft = Offset(size.minDimension * 0.30f, size.minDimension * 0.24f),
        size = Size(size.minDimension * 0.40f, size.minDimension * 0.52f),
    )
}

private fun DrawScope.cross() {
    val inset = size.minDimension * 0.30f
    val stroke = size.minDimension * 0.09f
    drawLine(Color.White, Offset(inset, inset), Offset(size.minDimension - inset, size.minDimension - inset), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(size.minDimension - inset, inset), Offset(inset, size.minDimension - inset), stroke, StrokeCap.Round)
}

private fun DrawScope.fork() {
    val stroke = size.minDimension * 0.08f
    drawLine(
        Color.White,
        Offset(size.minDimension * 0.22f, center.y),
        Offset(size.minDimension * 0.62f, center.y),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Color.White,
        Offset(size.minDimension * 0.62f, center.y),
        Offset(size.minDimension * 0.78f, size.minDimension * 0.30f),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        Color.White,
        Offset(size.minDimension * 0.62f, center.y),
        Offset(size.minDimension * 0.78f, size.minDimension * 0.70f),
        stroke,
        StrokeCap.Round,
    )
}

private fun DrawScope.chevrons() {
    val stroke = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round)
    listOf(0.34f, 0.50f, 0.66f).forEach { y ->
        val path = Path().apply {
            moveTo(size.minDimension * 0.30f, size.minDimension * (y - 0.08f))
            lineTo(size.minDimension * 0.50f, size.minDimension * y)
            lineTo(size.minDimension * 0.70f, size.minDimension * (y - 0.08f))
        }
        drawPath(path, Color.White, style = stroke)
    }
}

private fun DrawScope.loop() {
    drawCircle(
        Color.White,
        radius = size.minDimension * 0.14f,
        center = Offset(center.x - size.minDimension * 0.10f, center.y),
        style = Stroke(width = size.minDimension * 0.07f),
    )
    drawCircle(
        Color.White,
        radius = size.minDimension * 0.14f,
        center = Offset(center.x + size.minDimension * 0.10f, center.y),
        style = Stroke(width = size.minDimension * 0.07f),
    )
}

private fun DrawScope.diamond() {
    val path = Path().apply {
        moveTo(center.x, size.minDimension * 0.24f)
        lineTo(size.minDimension * 0.74f, center.y)
        lineTo(center.x, size.minDimension * 0.76f)
        lineTo(size.minDimension * 0.26f, center.y)
        close()
    }
    drawPath(path, Color.White)
}

private fun DrawScope.peaks() {
    val path = Path().apply {
        moveTo(size.minDimension * 0.22f, size.minDimension * 0.68f)
        lineTo(size.minDimension * 0.36f, size.minDimension * 0.32f)
        lineTo(size.minDimension * 0.50f, size.minDimension * 0.58f)
        lineTo(size.minDimension * 0.64f, size.minDimension * 0.32f)
        lineTo(size.minDimension * 0.78f, size.minDimension * 0.68f)
    }
    drawPath(path, Color.White, style = Stroke(width = size.minDimension * 0.08f, cap = StrokeCap.Round))
}

private fun DrawScope.chip() {
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(size.minDimension * 0.28f, size.minDimension * 0.28f),
        size = Size(size.minDimension * 0.44f, size.minDimension * 0.44f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.08f),
    )
}

private fun DrawScope.arcC() {
    drawArc(
        color = Color.White,
        startAngle = 40f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(size.minDimension * 0.28f, size.minDimension * 0.28f),
        size = Size(size.minDimension * 0.44f, size.minDimension * 0.44f),
        style = Stroke(width = size.minDimension * 0.08f, cap = StrokeCap.Round),
    )
}

private fun fallbackVendorColor(name: String): Color {
    val palette = listOf(
        Color(0xFF5B8DEF),
        Color(0xFF3FA36A),
        Color(0xFFD4654A),
        Color(0xFF8A6AD6),
        Color(0xFFC48A2A),
        Color(0xFF3A9CA8),
    )
    return palette[name.hashCode().ushr(1) % palette.size]
}

package com.ambiorix.pay.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// PALETAS METÁLICAS PREASIGNADAS (Cero asignaciones de objetos por fotograma)
// ============================================================================

// 1. Aleación Plateada / Platino (Cuando el acumulado es de 300 [$300.000] hacia abajo)
private val SilverSpecular = Color(0xFFFFFFFF)
private val SilverBright = Color(0xFFE2E8F0)
private val SilverMid = Color(0xFF94A3B8)
private val SilverDeep = Color(0xFF475569)
private val SilverShadow = Color(0xFF0F172A)

// 2. Aleación Dorada 24K (Cuando el acumulado supera los 300 [$300.000] hasta 600 [$600.000])
private val GoldSpecular = Color(0xFFFEF9C3)
private val GoldBright = Color(0xFFFBBF24)
private val GoldMid = Color(0xFFD97706)
private val GoldDeep = Color(0xFF92400E)
private val GoldShadow = Color(0xFF271705)

// 3. Aleación Morada / Amatista Imperial (Cuando el acumulado supera los 600 [> $600.000])
private val PurpleSpecular = Color(0xFFF5F3FF)
private val PurpleBright = Color(0xFFC084FC)
private val PurpleMid = Color(0xFF9333EA)
private val PurpleDeep = Color(0xFF581C87)
private val PurpleShadow = Color(0xFF1E0B36)

// 4. Aleación Roja Rubí / Carmesí (Sincronizada cuando está activo el aviso de batería baja <= 20%)
private val RedSpecular = Color(0xFFFEF2F2)
private val RedBright = Color(0xFFF87171)
private val RedMid = Color(0xFFDC2626)
private val RedDeep = Color(0xFF991B1B)
private val RedShadow = Color(0xFF3B0707)

// 5. Aleación Verde Esmeralda (Cuando se verifica un pago entrante)
private val EmeraldSpecular = Color(0xFFECFDF5)
private val EmeraldBright = Color(0xFF6EE7B7)
private val EmeraldMid = Color(0xFF10B981)
private val EmeraldDeep = Color(0xFF047857)
private val EmeraldShadow = Color(0xFF022C22)

/**
 * Determina si el nivel metálico Morado (> 600 / > $600.000 COP) está activo.
 */
fun isAmbiorixPurpleTier(dailyTotalAmount: Long): Boolean {
    return dailyTotalAmount > 600_000L || (dailyTotalAmount in 601L..999L)
}

/**
 * Determina si el nivel metálico Dorado (> 300 hasta 600 / $300.001..$600.000 COP) está activo.
 */
fun isAmbiorixGoldTier(dailyTotalAmount: Long): Boolean {
    return !isAmbiorixPurpleTier(dailyTotalAmount) &&
            (dailyTotalAmount > 300_000L || (dailyTotalAmount in 301L..600L))
}

/**
 * Caché geométrica ultraligera que usa `PathFillType.EvenOdd` con arcos analíticos directos,
 * eliminando por completo las costosas operaciones booleanas `PathOperation.Intersect` y
 * `PathOperation.Difference` en la CPU.
 */
class LogoGeometryCache {
    val outerCirclePath = Path()
    val morphMaskPath = Path().apply {
        fillType = PathFillType.EvenOdd
    }
    val cutoutsPath = Path()
    var lastDiameterPx: Float = -1f
    var lastQuantizedMorph: Int = -1
}

/**
 * Animación interactiva optimizada del logo de Ambiorix Pay:
 * - Cero recomposiciones de Compose por fotograma (lectura diferida a las fases de `graphicsLayer` y `DrawScope`).
 * - Cero operaciones booleanas `PathOperation` (usa geometría analítica directa con `PathFillType.EvenOdd`).
 * - Cadencia adaptativa ligera para máximo rendimiento en dispositivos Android modestos.
 */
@Composable
fun AmbiorixInteractiveLogo(
    isPaymentConfirmed: Boolean,
    dailyTotalAmount: Long = 0L,
    isLowBatteryWarning: Boolean = false,
    isServiceOperational: Boolean = true,
    size: Dp = 276.dp,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isScreenResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val isPurpleTarget = isAmbiorixPurpleTier(dailyTotalAmount)
    val isGoldTarget = isAmbiorixGoldTier(dailyTotalAmount)

    // Fase continua [0f..1f) leída únicamente dentro de graphicsLayer y DrawScope (cero recomposiciones)
    var cycleProgress by remember { mutableFloatStateOf(0.35f) }

    val goldTransition = remember { Animatable(if (isGoldTarget || isPurpleTarget) 1f else 0f) }
    val purpleTransition = remember { Animatable(if (isPurpleTarget) 1f else 0f) }
    val redTransition = remember { Animatable(if (isLowBatteryWarning) 1f else 0f) }

    val greenTransition = remember { Animatable(0f) }
    val confirmationBounceScale = remember { Animatable(1f) }
    val shockwaveProgress = remember { Animatable(0f) }

    LaunchedEffect(isGoldTarget, isPurpleTarget, reducedMotion) {
        launch {
            goldTransition.animateTo(
                targetValue = if (isGoldTarget || isPurpleTarget) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 150 else 550,
                    easing = FastOutSlowInEasing
                )
            )
        }
        launch {
            purpleTransition.animateTo(
                targetValue = if (isPurpleTarget) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 150 else 550,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    LaunchedEffect(isLowBatteryWarning, reducedMotion) {
        redTransition.animateTo(
            targetValue = if (isLowBatteryWarning) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (reducedMotion) 120 else 320,
                easing = FastOutSlowInEasing
            )
        )
    }

    LaunchedEffect(isPaymentConfirmed, reducedMotion) {
        if (isPaymentConfirmed) {
            launch {
                greenTransition.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = if (reducedMotion) 100 else 180, easing = FastOutSlowInEasing)
                )
            }
            if (!reducedMotion) {
                launch {
                    confirmationBounceScale.animateTo(
                        targetValue = 1.14f,
                        animationSpec = keyframes {
                            durationMillis = 420
                            1.00f at 0
                            0.92f at 60 with FastOutSlowInEasing
                            1.24f at 200 with FastOutSlowInEasing
                            0.98f at 320 with FastOutSlowInEasing
                            1.14f at 420 with FastOutSlowInEasing
                        }
                    )
                }
                launch {
                    shockwaveProgress.snapTo(0f)
                    shockwaveProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 440, easing = FastOutSlowInEasing)
                    )
                }
            } else {
                confirmationBounceScale.snapTo(1f)
                shockwaveProgress.snapTo(1f)
            }
        } else {
            launch {
                greenTransition.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = if (reducedMotion) 150 else 420, easing = FastOutSlowInEasing)
                )
            }
            launch {
                confirmationBounceScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                shockwaveProgress.snapTo(0f)
            }
        }
    }

    // Bucle de animación ligero: se pausa automáticamente cuando la pantalla de confirmación ya cubrió la vista
    LaunchedEffect(isScreenResumed, reducedMotion, isServiceOperational, isPaymentConfirmed) {
        if (!isScreenResumed || reducedMotion || !isServiceOperational) {
            if (reducedMotion) {
                cycleProgress = 0.50f
            }
            return@LaunchedEffect
        }

        if (isPaymentConfirmed) {
            // Mientras la pantalla verde de confirmación está activa, tras los primeros 450ms
            // pausamos el ciclo de fondo para liberar 100% la CPU/GPU
            delay(460L)
            return@LaunchedEffect
        }

        val cycleDurationMs = 7600L
        var lastFrameMs = -1L

        while (true) {
            withInfiniteAnimationFrameMillis { frameTimeMs ->
                if (lastFrameMs < 0L) {
                    lastFrameMs = frameTimeMs
                }
                val deltaMs = (frameTimeMs - lastFrameMs).coerceIn(0L, 64L)
                // Limitar actualizaciones innecesarias en pantallas de 120Hz (~45fps efectivos)
                if (deltaMs >= 20L) {
                    lastFrameMs = frameTimeMs
                    val next = (cycleProgress + deltaMs.toFloat() / cycleDurationMs.toFloat()) % 1.0f
                    cycleProgress = if (next < 0f) next + 1f else next
                }
            }
        }
    }

    val geometryCache = remember { LogoGeometryCache() }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = cycleProgress
                    val greenFactor = greenTransition.value
                    val bounceScale = confirmationBounceScale.value
                    val rotationRadians = (progress * 2.0 * PI).toFloat()

                    val tiltY = if (reducedMotion) 0f else (sin(rotationRadians.toDouble()).toFloat() * 14f * (1f - 0.45f * greenFactor))
                    val tiltX = if (reducedMotion) 0f else (cos(rotationRadians.toDouble()).toFloat() * 6f * (1f - 0.45f * greenFactor))
                    val breathingScale = if (reducedMotion) 1f else (1f + 0.02f * sin((rotationRadians * 2f).toDouble()).toFloat())

                    scaleX = breathingScale * bounceScale
                    scaleY = breathingScale * bounceScale
                    rotationY = tiltY
                    rotationX = tiltX
                    cameraDistance = 15f * density
                }
        ) {
            val progress = cycleProgress
            val greenFactor = greenTransition.value
            val rawWave = 0.5f * (1f - cos(2.0 * PI * progress.toDouble()).toFloat())
            val baseMorph = if (reducedMotion) 1f else smoothOrganicMorph(rawWave)
            val morphFraction = (baseMorph + (1f - baseMorph) * greenFactor).coerceIn(0f, 1f)
            val rotationRadians = (progress * 2.0 * PI).toFloat()

            drawSculptedAmbiorixSphereLogo(
                cache = geometryCache,
                morphFraction = morphFraction,
                rotationRadians = rotationRadians,
                goldFactor = goldTransition.value,
                purpleFactor = purpleTransition.value,
                redFactor = redTransition.value,
                greenFactor = greenFactor,
                shockwave = shockwaveProgress.value
            )
        }
    }
}

/**
 * Renderiza en un solo lienzo optimizado la esfera metálica 3D y el monograma "AAA" de Ambiorix.
 */
fun DrawScope.drawSculptedAmbiorixSphereLogo(
    cache: LogoGeometryCache,
    morphFraction: Float,
    rotationRadians: Float,
    goldFactor: Float,
    purpleFactor: Float = 0f,
    redFactor: Float = 0f,
    greenFactor: Float = 0f,
    shockwave: Float = 0f
) {
    val canvasW = this.size.width
    val canvasH = this.size.height
    val minDim = minOf(canvasW, canvasH)
    val center = Offset(canvasW * 0.5f, canvasH * 0.5f)
    val sphereRadius = minDim * 0.37f

    // 1. Interpolar paleta activa
    val goldOrSilverSpec = lerpColor(SilverSpecular, GoldSpecular, goldFactor)
    val goldOrSilverBright = lerpColor(SilverBright, GoldBright, goldFactor)
    val goldOrSilverMid = lerpColor(SilverMid, GoldMid, goldFactor)
    val goldOrSilverDeep = lerpColor(SilverDeep, GoldDeep, goldFactor)
    val goldOrSilverShadow = lerpColor(SilverShadow, GoldShadow, goldFactor)

    val tierSpecular = lerpColor(goldOrSilverSpec, PurpleSpecular, purpleFactor)
    val tierBright = lerpColor(goldOrSilverBright, PurpleBright, purpleFactor)
    val tierMid = lerpColor(goldOrSilverMid, PurpleMid, purpleFactor)
    val tierDeep = lerpColor(goldOrSilverDeep, PurpleDeep, purpleFactor)
    val tierShadow = lerpColor(goldOrSilverShadow, PurpleShadow, purpleFactor)

    val baseSpecular = lerpColor(tierSpecular, RedSpecular, redFactor)
    val baseBright = lerpColor(tierBright, RedBright, redFactor)
    val baseMid = lerpColor(tierMid, RedMid, redFactor)
    val baseDeep = lerpColor(tierDeep, RedDeep, redFactor)
    val baseShadow = lerpColor(tierShadow, RedShadow, redFactor)

    val activeSpecular = lerpColor(baseSpecular, EmeraldSpecular, greenFactor)
    val activeBright = lerpColor(baseBright, EmeraldBright, greenFactor)
    val activeMid = lerpColor(baseMid, EmeraldMid, greenFactor)
    val activeDeep = lerpColor(baseDeep, EmeraldDeep, greenFactor)
    val activeShadow = lerpColor(baseShadow, EmeraldShadow, greenFactor)

    // 2. Halo metálico ambiental exterior
    val haloRadius = sphereRadius * (1.34f + 0.08f * redFactor + 0.20f * greenFactor)
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to activeBright.copy(alpha = 0.22f + 0.12f * redFactor + 0.22f * greenFactor),
            0.6f to activeMid.copy(alpha = 0.10f + 0.08f * redFactor + 0.16f * greenFactor),
            1.0f to Color.Transparent,
            center = center,
            radius = haloRadius
        ),
        radius = haloRadius,
        center = center
    )

    // 3. Ondas expansivas verdes al confirmar un pago
    if (shockwave > 0.01f) {
        val waveRadius1 = sphereRadius * (1.0f + shockwave * 1.55f)
        val waveAlpha1 = (1f - shockwave).coerceIn(0f, 1f)
        drawCircle(
            color = EmeraldBright.copy(alpha = 0.85f * waveAlpha1),
            radius = waveRadius1,
            center = center,
            style = Stroke(width = minDim * 0.036f * (1f - shockwave * 0.4f))
        )
    }

    // 4. Actualizar la geometría en caché cuantizada (sin PathOperation booleano)
    val sphereDiameter = sphereRadius * 2f
    val quantizedMorph = (morphFraction * 100f).toInt().coerceIn(0, 100)
    if (abs(cache.lastDiameterPx - sphereDiameter) > 0.5f ||
        cache.lastQuantizedMorph != quantizedMorph
    ) {
        buildFastEvenOddMonogramPath(
            cache = cache,
            center = center,
            radius = sphereRadius,
            morph = quantizedMorph / 100f
        )
    }

    // 5. Sombra interna del núcleo esférico (un solo círculo simple)
    if (morphFraction > 0.03f) {
        drawCircle(
            color = activeShadow.copy(alpha = 0.72f * morphFraction),
            radius = sphereRadius * 0.96f,
            center = center
        )
    }

    val cosRot = cos(rotationRadians.toDouble()).toFloat()
    val sinRot = sin(rotationRadians.toDouble()).toFloat()

    // 6. Extrusión lateral 3D ligera (una sola pasada con color sólido dentro del recorte circular)
    clipPath(cache.outerCirclePath) {
        if (morphFraction > 0.05f) {
            val extrudeOffsetX = -sinRot * sphereRadius * 0.048f * morphFraction
            val extrudeOffsetY = cosRot * sphereRadius * 0.028f * morphFraction
            withTransform({
                translate(left = extrudeOffsetX, top = extrudeOffsetY)
            }) {
                drawPath(
                    path = cache.morphMaskPath,
                    color = activeDeep
                )
            }
        }

        // 7. Cara frontal esculpida usando EvenOdd (sin necesidad de PathOperation)
        val lightOffsetX = center.x - sphereRadius * 0.32f * cosRot
        val lightOffsetY = center.y - sphereRadius * 0.34f * sinRot

        clipPath(cache.morphMaskPath) {
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to baseSpecular,
                    0.24f to baseBright,
                    0.58f to baseMid,
                    0.85f to baseDeep,
                    1.00f to baseShadow,
                    center = Offset(lightOffsetX, lightOffsetY),
                    radius = sphereRadius * 1.38f
                ),
                radius = sphereRadius,
                center = center
            )

            // Cuando hay confirmación de pago, el verde esmeralda nace desde el centro del mismo logo
            if (greenFactor > 0.005f) {
                val greenBirthRadius = (sphereRadius * 1.45f * greenFactor).coerceAtLeast(1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0.00f to EmeraldSpecular,
                        0.28f to EmeraldBright,
                        0.65f to EmeraldMid,
                        0.92f to EmeraldDeep,
                        1.00f to Color.Transparent,
                        center = center,
                        radius = greenBirthRadius
                    ),
                    radius = sphereRadius,
                    center = center
                )
            }

            // Meridianos 3D sutiles cuando está cerca de la forma esférica
            val meridianAlpha = ((1f - morphFraction * 0.88f).coerceIn(0f, 1f)) * 0.38f
            if (meridianAlpha > 0.03f) {
                val meridianStroke = minDim * 0.006f
                for (i in 0 until 2) {
                    val phase = rotationRadians + (i * PI.toFloat() / 2f)
                    val rx = sphereRadius * abs(cos(phase.toDouble())).toFloat().coerceAtLeast(0.06f)
                    drawOval(
                        color = activeSpecular.copy(alpha = meridianAlpha),
                        topLeft = Offset(center.x - rx, center.y - sphereRadius),
                        size = Size(rx * 2f, sphereRadius * 2f),
                        style = Stroke(width = meridianStroke)
                    )
                }
            }
        }

        // 8. Bisel metálico sobre los bordes esculpidos
        if (morphFraction > 0.04f) {
            val bevelAlpha = (morphFraction * 0.85f).coerceIn(0f, 1f)
            drawPath(
                path = cache.cutoutsPath,
                color = activeSpecular.copy(alpha = bevelAlpha * 0.65f),
                style = Stroke(
                    width = minDim * 0.0065f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }

    // 9. Borde exterior de la esfera y anillo orbital en rotación
    drawCircle(
        color = activeBright.copy(alpha = 0.85f),
        radius = sphereRadius,
        center = center,
        style = Stroke(width = minDim * 0.008f)
    )

    val ringRotationDeg = (rotationRadians * 180f / PI.toFloat())
    rotate(degrees = ringRotationDeg, pivot = center) {
        drawCircle(
            brush = Brush.sweepGradient(
                0.00f to activeSpecular,
                0.30f to activeBright,
                0.60f to activeDeep,
                1.00f to activeSpecular,
                center = center
            ),
            radius = sphereRadius * 1.07f,
            center = center,
            style = Stroke(width = minDim * (0.012f + 0.006f * greenFactor))
        )
    }

    val orbitRadius = sphereRadius * 1.07f
    drawCircle(
        color = activeSpecular,
        radius = minDim * 0.013f,
        center = Offset(
            center.x + orbitRadius * cosRot,
            center.y + orbitRadius * sinRot
        )
    )
    drawCircle(
        color = activeBright,
        radius = minDim * 0.010f,
        center = Offset(
            center.x - orbitRadius * cosRot,
            center.y - orbitRadius * sinRot
        )
    )
}

/**
 * Construye la máscara `EvenOdd` del monograma "AAA" directamente con arcos analíticos,
 * sin usar `PathOperation.Intersect` ni `PathOperation.Difference`.
 */
private fun buildFastEvenOddMonogramPath(
    cache: LogoGeometryCache,
    center: Offset,
    radius: Float,
    morph: Float
) {
    cache.lastDiameterPx = radius * 2f
    cache.lastQuantizedMorph = (morph * 100f).toInt()

    val outerRect = Rect(
        left = center.x - radius,
        top = center.y - radius,
        right = center.x + radius,
        bottom = center.y + radius
    )

    cache.outerCirclePath.reset()
    cache.outerCirclePath.addOval(outerRect)

    cache.cutoutsPath.reset()
    cache.morphMaskPath.reset()
    cache.morphMaskPath.fillType = PathFillType.EvenOdd
    cache.morphMaskPath.addRect(
        Rect(
            left = center.x - radius * 1.15f,
            top = center.y - radius * 1.15f,
            right = center.x + radius * 1.15f,
            bottom = center.y + radius * 1.15f
        )
    )

    if (morph <= 0.005f) {
        return
    }

    val ease = morph * morph * (3f - 2f * morph)
    val cx = center.x
    val cy = center.y

    // 1. Dos canales verticales de separación entre las 3 "A"
    val gapHalfW = radius * 0.0715f * ease
    val gapHalfH = radius * 1.05f * (ease * 1.08f).coerceAtMost(1f)
    val gapCorner = CornerRadius(radius * 0.015f * ease, radius * 0.015f * ease)

    val g1Cx = cx - radius * 0.3455f
    cache.cutoutsPath.addRoundRect(
        RoundRect(
            rect = Rect(g1Cx - gapHalfW, cy - gapHalfH, g1Cx + gapHalfW, cy + gapHalfH),
            cornerRadius = gapCorner
        )
    )

    val g2Cx = cx + radius * 0.3455f
    cache.cutoutsPath.addRoundRect(
        RoundRect(
            rect = Rect(g2Cx - gapHalfW, cy - gapHalfH, g2Cx + gapHalfW, cy + gapHalfH),
            cornerRadius = gapCorner
        )
    )

    // 2. Ventana superior y ranura inferior de la "A" central
    val cHoleHalfW = radius * 0.112f * ease
    val crossbarTopY = cy - radius * 0.083f
    val cTopH = radius * 0.679f * ease
    val cCorner = CornerRadius(radius * 0.022f * ease, radius * 0.022f * ease)

    if (cTopH > 0.5f) {
        cache.cutoutsPath.addRoundRect(
            RoundRect(
                rect = Rect(cx - cHoleHalfW, crossbarTopY - cTopH, cx + cHoleHalfW, crossbarTopY),
                cornerRadius = cCorner
            )
        )
    }

    val cBotTop = cy + radius * (1.02f - 0.937f * ease)
    val cBotBottom = cy + radius * 1.06f
    if (cBotBottom > cBotTop) {
        cache.cutoutsPath.addRoundRect(
            RoundRect(
                rect = Rect(cx - cHoleHalfW, cBotTop, cx + cHoleHalfW, cBotBottom),
                cornerRadius = cCorner
            )
        )
    }

    // 3. Ventanas superiores en forma de "D" de las "A" laterales (con arco analítico directo)
    val xStem = radius * 0.583f
    val yBar = radius * 0.083f
    val rInner = radius * (0.583f + 0.239f * ease)

    if (rInner > xStem + 0.5f) {
        val dyStem = sqrt((rInner * rInner - xStem * xStem).coerceAtLeast(0f))
        if (dyStem > yBar + 0.5f) {
            val dxBar = sqrt((rInner * rInner - yBar * yBar).coerceAtLeast(0f))
            val sinBar = (yBar / rInner).coerceAtMost(0.999f)
            val cosStem = (xStem / rInner).coerceAtMost(0.999f)
            val rInnerRect = Rect(cx - rInner, cy - rInner, cx + rInner, cy + rInner)

            val degBar = (asin(sinBar.toDouble()) * 180.0 / PI).toFloat()
            val degStem = (acos(cosStem.toDouble()) * 180.0 / PI).toFloat()

            // Ventana superior izquierda
            val leftStartDeg = 180f + degBar
            val leftSweepDeg = degStem - degBar
            cache.cutoutsPath.moveTo(cx - xStem, cy - yBar)
            cache.cutoutsPath.lineTo(cx - dxBar, cy - yBar)
            cache.cutoutsPath.arcTo(rInnerRect, leftStartDeg, leftSweepDeg, false)
            cache.cutoutsPath.close()

            // Ventana superior derecha
            val rightStartDeg = -degBar
            val rightSweepDeg = -(degStem - degBar)
            cache.cutoutsPath.moveTo(cx + xStem, cy - yBar)
            cache.cutoutsPath.lineTo(cx + dxBar, cy - yBar)
            cache.cutoutsPath.arcTo(rInnerRect, rightStartDeg, rightSweepDeg, false)
            cache.cutoutsPath.close()
        }
    }

    // 4. Ventanas inferiores laterales + terminación de las patas curvas exteriores en y = +0.50*R
    val yLowerTop = cy + radius * (1.02f - 0.937f * ease)
    val yCutInner = cy + radius * (1.02f - 0.53f * ease)
    val yCutOuter = cy + radius * (1.04f - 0.49f * ease)
    val yBottom = cy + radius * 1.08f
    val rInnerLower = radius * 0.822f

    if (yBottom > yLowerTop + 0.5f) {
        val dyTop = (yLowerTop - cy).coerceAtLeast(0f)
        val dyCut = (yCutInner - cy).coerceAtLeast(dyTop)

        if (dyTop < rInnerLower * 0.98f) {
            val sinTop = (dyTop / rInnerLower).coerceAtMost(0.995f)
            val sinCut = (dyCut / rInnerLower).coerceAtMost(0.995f)
            val degTop = (asin(sinTop.toDouble()) * 180.0 / PI).toFloat()
            val degCut = (asin(sinCut.toDouble()) * 180.0 / PI).toFloat()
            val lowerArchRect = Rect(cx - rInnerLower, cy - rInnerLower, cx + rInnerLower, cy + rInnerLower)

            // Corte inferior izquierdo
            cache.cutoutsPath.moveTo(cx - xStem, yLowerTop)
            cache.cutoutsPath.arcTo(lowerArchRect, 180f - degTop, -(degCut - degTop), false)
            cache.cutoutsPath.lineTo(cx - radius * 1.08f, yCutOuter)
            cache.cutoutsPath.lineTo(cx - radius * 1.08f, yBottom)
            cache.cutoutsPath.lineTo(cx - xStem, yBottom)
            cache.cutoutsPath.close()

            // Corte inferior derecho
            cache.cutoutsPath.moveTo(cx + xStem, yLowerTop)
            cache.cutoutsPath.arcTo(lowerArchRect, degTop, degCut - degTop, false)
            cache.cutoutsPath.lineTo(cx + radius * 1.08f, yCutOuter)
            cache.cutoutsPath.lineTo(cx + radius * 1.08f, yBottom)
            cache.cutoutsPath.lineTo(cx + xStem, yBottom)
            cache.cutoutsPath.close()
        }
    }

    cache.morphMaskPath.addPath(cache.cutoutsPath)
}

private fun smoothOrganicMorph(rawWave: Float): Float {
    val x = rawWave.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (stop.red - start.red) * f,
        green = start.green + (stop.green - start.green) * f,
        blue = start.blue + (stop.blue - start.blue) * f,
        alpha = start.alpha + (stop.alpha - start.alpha) * f
    )
}

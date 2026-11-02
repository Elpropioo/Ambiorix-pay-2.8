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
import androidx.compose.ui.graphics.PathOperation
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
import com.ambiorix.pay.ui.theme.SuccessGreen
import com.ambiorix.pay.ui.theme.SuccessGreenDark
import com.ambiorix.pay.ui.theme.SuccessGreenLight
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

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
 * Caché geométrica reutilizable para esculpir en 3D la esfera hacia el monograma real "AAA"
 * sin crear instancias de Path en cada fotograma.
 */
class LogoGeometryCache {
    val outerCirclePath = Path()
    val innerArchCirclePath = Path()
    val morphMaskPath = Path()
    val allCutoutsPath = Path()
    val sideCutoutsRawPath = Path()
    val sideCutoutsClippedPath = Path()
    var lastDiameterPx: Float = -1f
    var lastMorphFraction: Float = -1f
}

/**
 * Animación interactiva integrada del logo de Ambiorix Pay:
 *
 * - Integración 100% orgánica (sin imagen plana sobrepuesta):
 *   La propia esfera metálica 3D se esculpe de forma continua y líquida en las tres columnas
 *   "AAA" del monograma original de Ambiorix, compartiendo iluminación especular, biseles 3D,
 *   extrusión lateral de metal y rotación suave.
 * - Regla de Niveles Metálicos (Plateado / Dorado / Morado):
 *   - De 300 ($300.000 COP) para abajo: **Plateado / Platino**.
 *   - Más de 300 hasta 600 ($300.001 a $600.000 COP): **Dorado 24K**.
 *   - Más de 600 (> $600.000 COP): **Morado Amatista Metálico**.
 * - Sincronización de Batería Baja (`isLowBatteryWarning = true`):
 *   - Mientras esté activo el aviso rojo de batería baja (<= 20% sin cargador), el logo animado
 *     se sincroniza y se pone en **Rojo Metálico Carmesí** igual que el aviso, regresando a su
 *     color normal apenas se conecte el cargador.
 * - Verificación de pago (`isPaymentConfirmed = true`):
 *   Sin importar el nivel actual, al confirmar un pago cambia rápidamente a
 *   **Verde Esmeralda**, rebota con energía y da paso a la expansión de pantalla verde.
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

    // Fase continua [0f..1f)
    var cycleProgress by remember { mutableFloatStateOf(0.35f) }

    // Transiciones suaves entre Plateado (0f), Dorado (>300), Morado (>600) y Rojo (Aviso de batería baja)
    val goldTransition = remember { Animatable(if (isGoldTarget || isPurpleTarget) 1f else 0f) }
    val purpleTransition = remember { Animatable(if (isPurpleTarget) 1f else 0f) }
    val redTransition = remember { Animatable(if (isLowBatteryWarning) 1f else 0f) }

    // Transición reactiva al evento real de pago confirmado (0f = metal normal, 1f = verde confirmación)
    val greenTransition = remember { Animatable(0f) }
    val confirmationBounceScale = remember { Animatable(1f) }
    val shockwaveProgress = remember { Animatable(0f) }

    LaunchedEffect(isGoldTarget, isPurpleTarget, reducedMotion) {
        launch {
            goldTransition.animateTo(
                targetValue = if (isGoldTarget || isPurpleTarget) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 150 else 680,
                    easing = FastOutSlowInEasing
                )
            )
        }
        launch {
            purpleTransition.animateTo(
                targetValue = if (isPurpleTarget) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 150 else 680,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    LaunchedEffect(isLowBatteryWarning, reducedMotion) {
        redTransition.animateTo(
            targetValue = if (isLowBatteryWarning) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (reducedMotion) 120 else 360,
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
                        targetValue = 1.15f,
                        animationSpec = keyframes {
                            durationMillis = 460
                            1.00f at 0
                            0.90f at 65 with FastOutSlowInEasing
                            1.28f at 220 with FastOutSlowInEasing
                            0.97f at 350 with FastOutSlowInEasing
                            1.15f at 460 with FastOutSlowInEasing
                        }
                    )
                }
                launch {
                    shockwaveProgress.snapTo(0f)
                    shockwaveProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
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
                    animationSpec = tween(durationMillis = if (reducedMotion) 150 else 500, easing = FastOutSlowInEasing)
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

    // Bucle de animación continua ligado a los fotogramas nativos de Android
    LaunchedEffect(isScreenResumed, reducedMotion, isServiceOperational) {
        if (!isScreenResumed || reducedMotion || !isServiceOperational) {
            if (reducedMotion) {
                cycleProgress = 0.50f
            }
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
                lastFrameMs = frameTimeMs

                val next = (cycleProgress + deltaMs.toFloat() / cycleDurationMs.toFloat()) % 1.0f
                cycleProgress = if (next < 0f) next + 1f else next
            }
        }
    }

    // Onda sinusoidal continua y orgánica (C² diferenciable, sin pausas bruscas ni cortes):
    // Fluye suavemente de Esfera (0.0) -> Logo Esculpido 3D (1.0) -> Esfera (0.0)
    val rawWave = 0.5f * (1f - cos(2.0 * PI * cycleProgress.toDouble()).toFloat())
    val baseMorph = if (reducedMotion) 1f else smoothOrganicMorph(rawWave)
    val morphFraction = (baseMorph + (1f - baseMorph) * greenTransition.value).coerceIn(0f, 1f)

    val rotationRadians = (cycleProgress * 2.0 * PI).toFloat()
    val goldFactor = goldTransition.value
    val purpleFactor = purpleTransition.value
    val redFactor = redTransition.value
    val greenFactor = greenTransition.value
    val bounceScale = confirmationBounceScale.value
    val wave = shockwaveProgress.value

    val geometryCache = remember { LogoGeometryCache() }

    // Rotación 3D fluida: la esfera/logo gira suavemente en el espacio mostrando su espesor metálico
    val tiltY = if (reducedMotion) 0f else (sin(rotationRadians.toDouble()).toFloat() * 16f * (1f - 0.45f * greenFactor))
    val tiltX = if (reducedMotion) 0f else (cos(rotationRadians.toDouble()).toFloat() * 7f * (1f - 0.45f * greenFactor))
    val breathingScale = if (reducedMotion) 1f else (1f + 0.025f * sin((rotationRadians * 2f).toDouble()).toFloat())

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = breathingScale * bounceScale
                    scaleY = breathingScale * bounceScale
                    rotationY = tiltY
                    rotationX = tiltX
                    cameraDistance = 15f * density
                }
        ) {
            drawSculptedAmbiorixSphereLogo(
                cache = geometryCache,
                morphFraction = morphFraction,
                rotationRadians = rotationRadians,
                goldFactor = goldFactor,
                purpleFactor = purpleFactor,
                redFactor = redFactor,
                greenFactor = greenFactor,
                shockwave = wave
            )
        }
    }
}

/**
 * Renderiza en un solo lienzo integrado la esfera metálica 3D que se esculpe orgánicamente
 * en el monograma "AAA" de Ambiorix con espesor 3D, bisel luminoso y reflejos especulares.
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

    // 1. Interpolar paleta activa: Plateado (<=300) <-> Dorado (>300) <-> Morado (>600) <-> Rojo (Batería baja) <-> Verde Esmeralda (Confirmado)
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
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                activeBright.copy(alpha = 0.24f + 0.14f * redFactor + 0.26f * greenFactor),
                activeMid.copy(alpha = 0.14f + 0.10f * redFactor + 0.22f * greenFactor),
                Color.Transparent
            ),
            center = center,
            radius = sphereRadius * (1.38f + 0.10f * redFactor + 0.24f * greenFactor)
        ),
        radius = sphereRadius * (1.38f + 0.10f * redFactor + 0.24f * greenFactor),
        center = center
    )

    // 3. Ondas expansivas verdes al confirmar un pago
    if (shockwave > 0.01f) {
        val waveRadius1 = sphereRadius * (1.0f + shockwave * 1.65f)
        val waveAlpha1 = (1f - shockwave).coerceIn(0f, 1f)
        drawCircle(
            color = EmeraldBright.copy(alpha = 0.85f * waveAlpha1),
            radius = waveRadius1,
            center = center,
            style = Stroke(width = minDim * 0.040f * (1f - shockwave * 0.4f))
        )

        val wave2 = ((shockwave - 0.16f) / 0.84f).coerceIn(0f, 1f)
        if (wave2 > 0f) {
            val waveRadius2 = sphereRadius * (1.0f + wave2 * 1.32f)
            val waveAlpha2 = (1f - wave2).coerceIn(0f, 1f)
            drawCircle(
                color = Color.White.copy(alpha = 0.70f * waveAlpha2),
                radius = waveRadius2,
                center = center,
                style = Stroke(width = minDim * 0.022f)
            )
        }
    }

    // 4. Actualizar la geometría esculpida Esfera -> Monograma AAA en caché
    val sphereDiameter = sphereRadius * 2f
    if (abs(cache.lastDiameterPx - sphereDiameter) > 0.4f ||
        abs(cache.lastMorphFraction - morphFraction) > 0.0015f
    ) {
        buildIntegratedSphereToMonogramPath(
            cache = cache,
            center = center,
            radius = sphereRadius,
            morph = morphFraction
        )
    }

    // 5. Sombra interna del núcleo esférico (visible a través de las ranuras esculpidas del logo AAA
    // para que el logo se sienta tallado dentro de la esfera 3D y no pegado encima)
    if (morphFraction > 0.02f) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    activeShadow.copy(alpha = 0.82f * morphFraction),
                    activeDeep.copy(alpha = 0.38f * morphFraction),
                    Color.Transparent
                ),
                center = center,
                radius = sphereRadius * 0.98f
            ),
            radius = sphereRadius * 0.96f,
            center = center
        )
    }

    // 6. Capa de extrusión 3D lateral (espesor metálico de las columnas "AAA" cuando la esfera rota)
    val extrudeOffsetX = -sin(rotationRadians.toDouble()).toFloat() * sphereRadius * 0.055f * morphFraction
    val extrudeOffsetY = cos(rotationRadians.toDouble()).toFloat() * sphereRadius * 0.032f * morphFraction
    if (morphFraction > 0.04f) {
        withTransform({
            translate(left = extrudeOffsetX, top = extrudeOffsetY)
        }) {
            clipPath(cache.morphMaskPath) {
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(activeDeep, activeShadow, activeMid),
                        start = Offset(center.x - sphereRadius, center.y - sphereRadius),
                        end = Offset(center.x + sphereRadius, center.y + sphereRadius)
                    ),
                    radius = sphereRadius,
                    center = center
                )
            }
        }
    }

    // 7. Cara frontal esculpida de la esfera / monograma AAA con iluminación esférica 3D continua
    val lightOffsetX = center.x - sphereRadius * 0.32f * cos(rotationRadians.toDouble()).toFloat()
    val lightOffsetY = center.y - sphereRadius * 0.34f * sin(rotationRadians.toDouble()).toFloat()

    clipPath(cache.morphMaskPath) {
        // Sombreado esférico 3D base (Plateado, Dorado, Morado o Rojo)
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to baseSpecular,
                0.22f to baseBright,
                0.56f to baseMid,
                0.84f to baseDeep,
                1.00f to baseShadow,
                center = Offset(lightOffsetX, lightOffsetY),
                radius = sphereRadius * 1.38f
            ),
            radius = sphereRadius,
            center = center
        )

        // Si hay confirmación de pago, el color Verde Esmeralda nace desde el centro del mismo logo
        // y se expande radialmente por toda su estructura 3D sin superponer otro logo encima
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

        // Contra-luz especular inferior para dar volumen metálico líquido
        val rimLightX = center.x + sphereRadius * 0.45f * cos(rotationRadians.toDouble()).toFloat()
        val rimLightY = center.y + sphereRadius * 0.45f * sin(rotationRadians.toDouble()).toFloat()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    activeBright.copy(alpha = 0.55f),
                    Color.Transparent
                ),
                center = Offset(rimLightX, rimLightY),
                radius = sphereRadius * 0.90f
            ),
            radius = sphereRadius,
            center = center
        )

        // Meridianos 3D que giran con la esfera y se funden suavemente con las líneas del logo AAA
        val meridianAlpha = ((1f - morphFraction * 0.88f).coerceIn(0.08f, 1f)) * 0.42f
        val meridianStroke = minDim * 0.0065f
        for (i in 0 until 3) {
            val phase = rotationRadians + (i * PI.toFloat() / 3f)
            val rx = sphereRadius * abs(cos(phase.toDouble())).toFloat().coerceAtLeast(0.05f)
            drawOval(
                color = activeSpecular.copy(alpha = meridianAlpha),
                topLeft = Offset(center.x - rx, center.y - sphereRadius),
                size = Size(rx * 2f, sphereRadius * 2f),
                style = Stroke(width = meridianStroke)
            )
        }

        // Destello especular diagonal que recorre el metal esculpido
        val sheenPhase = ((rotationRadians / (2f * PI.toFloat())) * 2f) % 1f
        val sheenX = (center.x - sphereRadius * 1.4f) + (sphereRadius * 2.8f * sheenPhase)
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    activeSpecular.copy(alpha = 0.38f),
                    activeBright.copy(alpha = 0.24f),
                    Color.Transparent
                ),
                start = Offset(sheenX - sphereRadius * 0.35f, center.y - sphereRadius),
                end = Offset(sheenX + sphereRadius * 0.35f, center.y + sphereRadius)
            ),
            start = Offset(sheenX + sphereRadius * 0.3f, center.y - sphereRadius * 1.1f),
            end = Offset(sheenX - sphereRadius * 0.3f, center.y + sphereRadius * 1.1f),
            strokeWidth = sphereRadius * 0.52f,
            cap = StrokeCap.Round
        )
    }

    // 8. Bisel metálico brillante a lo largo de todos los bordes esculpidos del monograma AAA
    if (morphFraction > 0.03f) {
        val bevelAlpha = (morphFraction * 0.92f).coerceIn(0f, 1f)
        drawPath(
            path = cache.morphMaskPath,
            brush = Brush.sweepGradient(
                0.00f to activeSpecular.copy(alpha = bevelAlpha),
                0.25f to activeBright.copy(alpha = bevelAlpha * 0.85f),
                0.50f to activeDeep.copy(alpha = bevelAlpha * 0.70f),
                0.75f to activeBright.copy(alpha = bevelAlpha * 0.90f),
                1.00f to activeSpecular.copy(alpha = bevelAlpha),
                center = center
            ),
            style = Stroke(
                width = minDim * (0.0055f + 0.0045f * morphFraction),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }

    // 9. Anillo orbital metálico exterior en rotación continua
    val ringRotationDeg = (rotationRadians * 180f / PI.toFloat())
    rotate(degrees = ringRotationDeg, pivot = center) {
        drawCircle(
            brush = Brush.sweepGradient(
                0.00f to activeSpecular,
                0.25f to activeBright,
                0.50f to activeDeep,
                0.75f to activeMid,
                1.00f to activeSpecular,
                center = center
            ),
            radius = sphereRadius * 1.05f,
            center = center,
            style = Stroke(width = minDim * (0.013f + 0.008f * greenFactor))
        )
    }

    // Partículas orbitales sobre el anillo exterior
    val orbitRadius = sphereRadius * 1.12f
    drawCircle(
        color = activeSpecular.copy(alpha = 0.90f),
        radius = minDim * 0.013f,
        center = Offset(
            center.x + orbitRadius * cos(rotationRadians.toDouble()).toFloat(),
            center.y + orbitRadius * sin(rotationRadians.toDouble()).toFloat()
        )
    )
    drawCircle(
        color = activeBright.copy(alpha = 0.80f),
        radius = minDim * 0.010f,
        center = Offset(
            center.x + orbitRadius * cos((rotationRadians + PI).toDouble()).toFloat(),
            center.y + orbitRadius * sin((rotationRadians + PI).toDouble()).toFloat()
        )
    )
}

/**
 * Esculpe progresivamente la esfera completa (`morph = 0f`) hacia la geometría exacta
 * calibrada píxel a píxel del monograma circular "AAA" original de Ambiorix (`morph = 1f`).
 *
 * Proporciones exactas medidas del logo original (radio exterior R = 1.000):
 * - Grosor uniforme de todos los postes y arcos: ~0.166 * R
 * - Postes verticales de la "A" central: x en [-0.274*R, -0.112*R] y [+0.112*R, +0.274*R]
 * - Ventana central: x en [-0.112*R, +0.112*R], ventana superior y en [-0.762*R, -0.083*R], inferior y > +0.083*R
 * - Canales verticales de separación: x en [-0.417*R, -0.274*R] y [+0.274*R, +0.417*R]
 * - Postes interiores de las "A" laterales: x en [-0.583*R, -0.417*R] y [+0.417*R, +0.583*R]
 * - Arcos exteriores de las "A" laterales: radio interior 0.822*R a radio exterior 1.000*R,
 *   terminando con corte limpio en y = +0.50*R mientras los 4 postes interiores bajan hasta la base.
 */
private fun buildIntegratedSphereToMonogramPath(
    cache: LogoGeometryCache,
    center: Offset,
    radius: Float,
    morph: Float
) {
    val diameter = radius * 2f
    cache.lastDiameterPx = diameter
    cache.lastMorphFraction = morph

    cache.outerCirclePath.reset()
    cache.outerCirclePath.addOval(
        Rect(
            left = center.x - radius,
            top = center.y - radius,
            right = center.x + radius,
            bottom = center.y + radius
        )
    )

    cache.morphMaskPath.reset()
    cache.morphMaskPath.addPath(cache.outerCirclePath)

    if (morph <= 0.005f) {
        return
    }

    val m = morph.coerceIn(0f, 1f)
    cache.allCutoutsPath.reset()

    // 1. Dos canales verticales de separación entre las 3 "A":
    // Centros en ±0.3455 * R con semiancho = 0.0715 * R * m -> cubre [0.274*R, 0.417*R]
    val channelHalfW = radius * 0.0715f * m
    val leftChannelX = center.x - radius * 0.3455f
    val rightChannelX = center.x + radius * 0.3455f
    val channelCorner = CornerRadius(radius * 0.015f * m, radius * 0.015f * m)

    cache.allCutoutsPath.addRoundRect(
        RoundRect(
            rect = Rect(
                left = leftChannelX - channelHalfW,
                top = center.y - radius * 1.05f,
                right = leftChannelX + channelHalfW,
                bottom = center.y + radius * 1.05f
            ),
            cornerRadius = channelCorner
        )
    )
    cache.allCutoutsPath.addRoundRect(
        RoundRect(
            rect = Rect(
                left = rightChannelX - channelHalfW,
                top = center.y - radius * 1.05f,
                right = rightChannelX + channelHalfW,
                bottom = center.y + radius * 1.05f
            ),
            cornerRadius = channelCorner
        )
    )

    // 2. Columna Central "A": ventana superior y ranura inferior bajo el travesaño central
    val centerWindowHalfW = radius * 0.112f * m
    val crossbarTopY = center.y - radius * 0.083f
    val crossbarBottomY = center.y + radius * 0.083f
    val windowCorner = CornerRadius(radius * 0.022f * m, radius * 0.022f * m)

    // Ventana superior de la "A" central: desde y = -0.762*R hasta -0.083*R
    val centerTopH = radius * 0.679f * m
    if (centerTopH > 0.5f) {
        cache.allCutoutsPath.addRoundRect(
            RoundRect(
                rect = Rect(
                    left = center.x - centerWindowHalfW,
                    top = crossbarTopY - centerTopH,
                    right = center.x + centerWindowHalfW,
                    bottom = crossbarTopY
                ),
                cornerRadius = windowCorner
            )
        )
    }

    // Ranura inferior de la "A" central (se abre hasta la base de la esfera)
    val centerBottomH = radius * 0.98f * m
    if (centerBottomH > 0.5f) {
        cache.allCutoutsPath.addRoundRect(
            RoundRect(
                rect = Rect(
                    left = center.x - centerWindowHalfW,
                    top = crossbarBottomY,
                    right = center.x + centerWindowHalfW,
                    bottom = crossbarBottomY + centerBottomH
                ),
                cornerRadius = windowCorner
            )
        )
    }

    // 3. Columnas Izquierda y Derecha "A":
    // Sus ventanas internas están delimitadas exteriormente por el círculo concéntrico de radio 0.822 * R
    // e interiormente por el poste vertical de cada "A" lateral (en |x| = 0.583 * R).
    val sideInnerStemOffset = radius * 0.583f
    val innerArchRadius = radius * (0.583f + 0.239f * m)
    cache.innerArchCirclePath.reset()
    cache.innerArchCirclePath.addOval(
        Rect(
            left = center.x - innerArchRadius,
            top = center.y - innerArchRadius,
            right = center.x + innerArchRadius,
            bottom = center.y + innerArchRadius
        )
    )

    cache.sideCutoutsRawPath.reset()
    val sideSpanW = (innerArchRadius - sideInnerStemOffset + radius * 0.04f) * m
    val sideTopH = radius * 0.68f * m
    val sideBottomH = radius * 0.72f * m
    val sideCorner = CornerRadius(radius * 0.018f * m, radius * 0.018f * m)

    if (sideSpanW > 0.5f) {
        // Ventana superior de la "A" izquierda
        cache.sideCutoutsRawPath.addRoundRect(
            RoundRect(
                rect = Rect(
                    left = (center.x - sideInnerStemOffset) - sideSpanW,
                    top = crossbarTopY - sideTopH,
                    right = center.x - sideInnerStemOffset,
                    bottom = crossbarTopY
                ),
                cornerRadius = sideCorner
            )
        )
        // Ranura inferior de la "A" izquierda
        cache.sideCutoutsRawPath.addRoundRect(
            RoundRect(
                rect = Rect(
                    left = (center.x - sideInnerStemOffset) - sideSpanW,
                    top = crossbarBottomY,
                    right = center.x - sideInnerStemOffset,
                    bottom = crossbarBottomY + sideBottomH
                ),
                cornerRadius = sideCorner
            )
        )

        // Ventana superior de la "A" derecha
        cache.sideCutoutsRawPath.addRoundRect(
            RoundRect(
                rect = Rect(
                    left = center.x + sideInnerStemOffset,
                    top = crossbarTopY - sideTopH,
                    right = (center.x + sideInnerStemOffset) + sideSpanW,
                    bottom = crossbarTopY
                ),
                cornerRadius = sideCorner
            )
        )
        // Ranura inferior de la "A" derecha
        cache.sideCutoutsRawPath.addRoundRect(
            RoundRect(
                rect = Rect(
                    left = center.x + sideInnerStemOffset,
                    top = crossbarBottomY,
                    right = (center.x + sideInnerStemOffset) + sideSpanW,
                    bottom = crossbarBottomY + sideBottomH
                ),
                cornerRadius = sideCorner
            )
        )

        // Recortar las ventanas laterales con el arco concéntrico interior (0.822 * R)
        cache.sideCutoutsClippedPath.reset()
        cache.sideCutoutsClippedPath.op(
            cache.sideCutoutsRawPath,
            cache.innerArchCirclePath,
            PathOperation.Intersect
        )
        cache.allCutoutsPath.addPath(cache.sideCutoutsClippedPath)
    }

    // 4. Terminación inferior de las patas curvas exteriores (en y = +0.49*R .. +0.55*R)
    // Deja únicamente los 4 postes verticales interiores llegando hasta la base del círculo, igual al logo original
    val cutYInner = center.y + radius * (1.02f - 0.53f * m)
    val cutYOuter = center.y + radius * (1.04f - 0.49f * m)
    val cutYBottom = center.y + radius * 1.08f
    if (cutYBottom > cutYInner) {
        val leftStemX = center.x - sideInnerStemOffset
        val leftOuterX = center.x - radius * 1.08f
        cache.allCutoutsPath.moveTo(leftStemX, cutYInner)
        cache.allCutoutsPath.lineTo(leftOuterX, cutYOuter)
        cache.allCutoutsPath.lineTo(leftOuterX, cutYBottom)
        cache.allCutoutsPath.lineTo(leftStemX, cutYBottom)
        cache.allCutoutsPath.close()

        val rightStemX = center.x + sideInnerStemOffset
        val rightOuterX = center.x + radius * 1.08f
        cache.allCutoutsPath.moveTo(rightStemX, cutYInner)
        cache.allCutoutsPath.lineTo(rightOuterX, cutYOuter)
        cache.allCutoutsPath.lineTo(rightOuterX, cutYBottom)
        cache.allCutoutsPath.lineTo(rightStemX, cutYBottom)
        cache.allCutoutsPath.close()
    }

    // Restar todas las cavidades del monograma AAA al cuerpo de la esfera
    cache.morphMaskPath.op(cache.morphMaskPath, cache.allCutoutsPath, PathOperation.Difference)
}

/**
 * Curva suave de transición para que la esfera y el logo esculpido tengan presencia definida
 * pero nunca se detengan bruscamente.
 */
private fun smoothOrganicMorph(rawWave: Float): Float {
    val x = rawWave.coerceIn(0f, 1f)
    // Curva S suave de quinto orden (Perlin smootherstep)
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

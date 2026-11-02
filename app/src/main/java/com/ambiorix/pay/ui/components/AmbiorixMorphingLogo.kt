package com.ambiorix.pay.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ambiorix.pay.R
import com.ambiorix.pay.ui.theme.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animación interactiva profesional del logo de Ambiorix Pay.
 *
 * Comportamiento en reposo (Kiosco):
 * - Muestra una esfera metálica elegante con tonos plateados y dorados.
 * - Rota suavemente en 3D y se transforma progresivamente en el logo real de Ambiorix (monograma circular AAA).
 * - Mantiene un ciclo continuo, fluido y discreto usando interpolación trigonométrica sin saltos visibles.
 *
 * Comportamiento al confirmar un pago real ([isPaymentConfirmed] = true):
 * - Cambia rápidamente su paleta metálica a verde esmeralda ([SuccessGreen]).
 * - Ejecuta una expansión elástica con rebote sutil ([spring]) que da paso a la pantalla de confirmación.
 *
 * Optimización y accesibilidad:
 * - Cero creación de instancias de [Path] en el bucle de dibujo (se reutilizan con [remember] y [Path.reset]).
 * - Se pausa automáticamente cuando la actividad no está en estado [Lifecycle.State.RESUMED].
 * - Respeta las preferencias del sistema de reducción de movimiento ([rememberReducedMotionEnabled]).
 */
@Composable
fun AmbiorixMorphingLogo(
    isPaymentConfirmed: Boolean = false,
    modifier: Modifier = Modifier,
    logoSize: Dp = 270.dp
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isScreenResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    // Fase maestra continua [0f..1f] sin saltos al reiniciar gracias al mapeo trigonométrico cerrado
    val masterPhase = if (!reducedMotion && isScreenResumed) {
        val infiniteTransition = rememberInfiniteTransition(label = "AmbiorixSphereLogoCycle")
        val phase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 9600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "MasterPhase"
        )
        phase
    } else {
        // En modo de movimiento reducido o pantalla pausada, mostramos el logo en su punto armónico
        0.5f
    }

    // Cálculo suave y continuo de transformación Esfera (0f) <-> Logo Ambiorix (1f)
    // En phase = 0.0 -> 0f (Esfera pura)
    // En phase = 0.5 -> 1f (Logo real de Ambiorix completo)
    // En phase = 1.0 -> 0f (Regreso exacto a la esfera sin salto)
    val rawWave = ((1f - cos(masterPhase * 2.0 * PI)).toFloat() * 0.5f)
    // Suavizado de meseta para que tanto la esfera como el logo real se aprecien con claridad
    val morphProgress = if (isPaymentConfirmed) {
        1f
    } else {
        smoothStep(0.12f, 0.88f, rawWave)
    }

    // Ángulo de rotación continua en radianes (2 giros suaves por ciclo completo)
    val rotationAngle = (masterPhase * 4.0 * PI).toFloat()

    // Escala con rebote sutil cuando llega un pago confirmado
    val confirmationScale by animateFloatAsState(
        targetValue = if (isPaymentConfirmed) 1.24f else 1.0f,
        animationSpec = if (isPaymentConfirmed) {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            )
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "ConfirmationBounceScale"
    )

    // Transición rápida a verde cuando se confirma un pago real
    val colorTransition by animateFloatAsState(
        targetValue = if (isPaymentConfirmed) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isPaymentConfirmed) 180 else 380,
            easing = FastOutSlowInEasing
        ),
        label = "GreenColorShift"
    )

    val ambientGlowColor by animateColorAsState(
        targetValue = if (isPaymentConfirmed) SuccessGreen else GoldPrimary,
        animationSpec = tween(durationMillis = 220),
        label = "AmbientGlowColor"
    )

    // Paths pre-alocados fuera del DrawScope para evitar asignaciones en cada frame
    val sphereClipPath = remember { Path() }
    val leftPillarPath = remember { Path() }
    val centerPillarPath = remember { Path() }
    val rightPillarPath = remember { Path() }
    val meridianPath = remember { Path() }

    // Recurso real del logo de Ambiorix en el proyecto
    val logoPainter = painterResource(id = R.drawable.ambiorix_logo)

    Box(
        modifier = modifier
            .size(logoSize)
            .graphicsLayer {
                scaleX = confirmationScale
                scaleY = confirmationScale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val minDim = size.minDimension
            val center = Offset(canvasWidth * 0.5f, canvasHeight * 0.5f)
            val baseRadius = minDim * 0.39f

            // Respiración sutil del halo exterior
            val breathing = 1f + 0.035f * sin(rotationAngle * 0.5f)
            val radius = baseRadius * breathing

            // 1. Halo ambiental exterior (plateado/dorado en espera, verde esmeralda en confirmación)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ambientGlowColor.copy(alpha = 0.28f + 0.18f * colorTransition),
                        ambientGlowColor.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * (1.42f + 0.18f * colorTransition)
                ),
                radius = radius * (1.42f + 0.18f * colorTransition),
                center = center
            )

            // Recorte circular maestro de la esfera / monograma
            sphereClipPath.reset()
            sphereClipPath.addOval(
                Rect(
                    left = center.x - radius,
                    top = center.y - radius,
                    right = center.x + radius,
                    bottom = center.y + radius
                )
            )

            // Colores interpolados entre la esfera metálica (plata + oro) y el estado confirmado (verde esmeralda)
            val silverHighlight = lerp(Color(0xFFF8FAFC), Color(0xFFA7F3D0), colorTransition)
            val silverMid = lerp(Color(0xFFCBD5E1), SuccessGreenLight, colorTransition)
            val goldBright = lerp(Color(0xFFFDE68A), Color(0xFF6EE7B7), colorTransition)
            val goldCore = lerp(Color(0xFFD4AF37), SuccessGreen, colorTransition)
            val metallicDeep = lerp(Color(0xFF334155), SuccessGreenDark, colorTransition)
            val metallicShadow = lerp(Color(0xFF0F172A), Color(0xFF064E3B), colorTransition)

            // 2. Cuerpo de la esfera metálica 3D (se atenúa progresivamente al revelar las 3 columnas del logo)
            val sphereBodyAlpha = (1f - morphProgress * 0.82f).coerceIn(0f, 1f)
            if (sphereBodyAlpha > 0.01f) {
                // Desplazamiento dinámico del punto especular para simular rotación de la esfera
                val lightOffsetX = center.x - radius * 0.28f * cos(rotationAngle)
                val lightOffsetY = center.y - radius * 0.28f * sin(rotationAngle * 0.5f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            silverHighlight.copy(alpha = sphereBodyAlpha),
                            goldBright.copy(alpha = sphereBodyAlpha * 0.95f),
                            silverMid.copy(alpha = sphereBodyAlpha * 0.9f),
                            goldCore.copy(alpha = sphereBodyAlpha * 0.88f),
                            metallicDeep.copy(alpha = sphereBodyAlpha * 0.95f),
                            metallicShadow.copy(alpha = sphereBodyAlpha)
                        ),
                        center = Offset(lightOffsetX, lightOffsetY),
                        radius = radius * 1.35f
                    ),
                    radius = radius,
                    center = center
                )

                // Meridianos metálicos rotatorios que convergen hacia las 3 columnas verticales de Ambiorix
                val meridianAlpha = (1f - morphProgress * 0.9f).coerceIn(0f, 1f)
                clipPath(sphereClipPath) {
                    for (i in 0 until 3) {
                        val phaseOffset = rotationAngle + (i * PI / 1.5).toFloat()
                        val rx = radius * abs(cos(phaseOffset)).coerceAtLeast(0.08f)
                        meridianPath.reset()
                        meridianPath.addOval(
                            Rect(
                                left = center.x - rx,
                                top = center.y - radius,
                                right = center.x + rx,
                                bottom = center.y + radius
                            )
                        )
                        drawPath(
                            path = meridianPath,
                            color = if (i % 2 == 0) {
                                goldBright.copy(alpha = 0.34f * meridianAlpha)
                            } else {
                                silverHighlight.copy(alpha = 0.28f * meridianAlpha)
                            },
                            style = Stroke(width = minDim * 0.012f)
                        )
                    }
                }
            }

            // 3. Transformación progresiva hacia las 3 columnas "AAA" del logo de Ambiorix
            // A medida que morphProgress avanza de 0f a 1f, la esfera esculpe las 3 columnas y sus aberturas
            if (morphProgress > 0.02f) {
                clipPath(sphereClipPath) {
                    val pillarAlpha = smoothStep(0.05f, 0.75f, morphProgress)
                    // Separación entre columnas que nace desde 0 (esfera compacta) hasta el ancho real del monograma
                    val gapWidth = radius * 0.115f * morphProgress
                    val centerHalfWidth = radius * (0.26f - 0.04f * (1f - morphProgress))

                    val leftColRight = center.x - centerHalfWidth - gapWidth
                    val centerColLeft = center.x - centerHalfWidth
                    val centerColRight = center.x + centerHalfWidth
                    val rightColLeft = center.x + centerHalfWidth + gapWidth

                    val colTop = center.y - radius
                    val colBottom = center.y + radius
                    val colHeight = radius * 2f

                    // Pincel metálico dorado/plateado (o verde esmeralda al confirmar pago) con reflejo vivo
                    val shimmerSweep = radius * 1.4f * sin(rotationAngle)
                    val monogramBrush = Brush.linearGradient(
                        colors = listOf(
                            goldCore.copy(alpha = pillarAlpha),
                            goldBright.copy(alpha = pillarAlpha),
                            silverHighlight.copy(alpha = pillarAlpha),
                            goldBright.copy(alpha = pillarAlpha),
                            goldCore.copy(alpha = pillarAlpha)
                        ),
                        start = Offset(center.x - radius + shimmerSweep, center.y - radius),
                        end = Offset(center.x + radius + shimmerSweep, center.y + radius)
                    )

                    // Columna Izquierda "A"
                    leftPillarPath.reset()
                    leftPillarPath.addRect(
                        Rect(
                            left = center.x - radius,
                            top = colTop,
                            right = leftColRight,
                            bottom = colBottom
                        )
                    )
                    drawPath(path = leftPillarPath, brush = monogramBrush)

                    // Columna Central "A"
                    centerPillarPath.reset()
                    centerPillarPath.addRect(
                        Rect(
                            left = centerColLeft,
                            top = colTop,
                            right = centerColRight,
                            bottom = colBottom
                        )
                    )
                    drawPath(path = centerPillarPath, brush = monogramBrush)

                    // Columna Derecha "A"
                    rightPillarPath.reset()
                    rightPillarPath.addRect(
                        Rect(
                            left = rightColLeft,
                            top = colTop,
                            right = center.x + radius,
                            bottom = colBottom
                        )
                    )
                    drawPath(path = rightPillarPath, brush = monogramBrush)

                    // Ventanas interiores de cada "A" que se esculpen suavemente con morphProgress
                    val cutoutScale = smoothStep(0.2f, 0.9f, morphProgress)
                    if (cutoutScale > 0.01f) {
                        val innerWindowWidth = (centerHalfWidth * 0.72f) * cutoutScale
                        val upperWindowTop = center.y - radius * 0.56f * cutoutScale
                        val upperWindowBottom = center.y - radius * 0.10f * cutoutScale
                        val lowerWindowTop = center.y + radius * 0.08f * cutoutScale
                        val lowerWindowBottom = center.y + radius * 1.02f

                        // Ventanas de la "A" central
                        drawRoundRect(
                            color = DarkBackground.copy(alpha = cutoutScale),
                            topLeft = Offset(center.x - innerWindowWidth * 0.5f, upperWindowTop),
                            size = Size(innerWindowWidth, (upperWindowBottom - upperWindowTop).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(innerWindowWidth * 0.35f)
                        )
                        drawRoundRect(
                            color = DarkBackground.copy(alpha = cutoutScale),
                            topLeft = Offset(center.x - innerWindowWidth * 0.5f, lowerWindowTop),
                            size = Size(innerWindowWidth, (lowerWindowBottom - lowerWindowTop).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(innerWindowWidth * 0.25f)
                        )

                        // Ventanas de las "A" laterales
                        val sideWindowWidth = (radius * 0.24f) * cutoutScale
                        val leftWindowCenter = leftColRight - radius * 0.26f
                        val rightWindowCenter = rightColLeft + radius * 0.26f

                        // Izquierda superior e inferior
                        drawRoundRect(
                            color = DarkBackground.copy(alpha = cutoutScale),
                            topLeft = Offset(leftWindowCenter - sideWindowWidth * 0.5f, upperWindowTop + radius * 0.08f),
                            size = Size(sideWindowWidth, (upperWindowBottom - upperWindowTop - radius * 0.08f).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(sideWindowWidth * 0.4f)
                        )
                        drawRoundRect(
                            color = DarkBackground.copy(alpha = cutoutScale),
                            topLeft = Offset(leftWindowCenter - sideWindowWidth * 0.5f, lowerWindowTop),
                            size = Size(sideWindowWidth, (colHeight * 0.34f * cutoutScale).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(sideWindowWidth * 0.3f)
                        )

                        // Derecha superior e inferior
                        drawRoundRect(
                            color = DarkBackground.copy(alpha = cutoutScale),
                            topLeft = Offset(rightWindowCenter - sideWindowWidth * 0.5f, upperWindowTop + radius * 0.08f),
                            size = Size(sideWindowWidth, (upperWindowBottom - upperWindowTop - radius * 0.08f).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(sideWindowWidth * 0.4f)
                        )
                        drawRoundRect(
                            color = DarkBackground.copy(alpha = cutoutScale),
                            topLeft = Offset(rightWindowCenter - sideWindowWidth * 0.5f, lowerWindowTop),
                            size = Size(sideWindowWidth, (colHeight * 0.34f * cutoutScale).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(sideWindowWidth * 0.3f)
                        )
                    }
                }
            }

            // 4. Anillo orbital especular fino que envuelve la esfera/logo
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        silverHighlight.copy(alpha = 0.65f * (1f - morphProgress * 0.5f)),
                        goldCore.copy(alpha = 0.35f),
                        Color.Transparent,
                        goldBright.copy(alpha = 0.55f),
                        silverHighlight.copy(alpha = 0.65f * (1f - morphProgress * 0.5f))
                    ),
                    center = center
                ),
                radius = radius * 1.02f,
                center = center,
                style = Stroke(width = minDim * 0.008f)
            )
        }

        // 5. Superposición fiel del asset oficial de Ambiorix (R.drawable.ambiorix_logo)
        // Se funde suavemente en la cima de la transformación con una ligera rotación 3D y tinte verde al confirmar pago
        val realLogoAlpha = smoothStep(0.38f, 0.92f, morphProgress)
        if (realLogoAlpha > 0.01f) {
            val subtleYRotation = (1f - morphProgress) * 28f * sin(rotationAngle)
            val tintColor = if (colorTransition > 0.01f) {
                lerp(GoldLight, SuccessGreenLight, colorTransition)
            } else {
                null
            }

            Image(
                painter = logoPainter,
                contentDescription = "Logo Ambiorix Pay",
                contentScale = ContentScale.Fit,
                colorFilter = tintColor?.let { ColorFilter.tint(it, BlendMode.Modulate) },
                modifier = Modifier
                    .size(logoSize * 0.84f)
                    .graphicsLayer {
                        alpha = realLogoAlpha
                        rotationY = subtleYRotation
                        cameraDistance = 14f * density
                    }
            )
        }
    }
}

/**
 * Interpolación Hermite suave [0f..1f] libre de asignaciones.
 */
private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

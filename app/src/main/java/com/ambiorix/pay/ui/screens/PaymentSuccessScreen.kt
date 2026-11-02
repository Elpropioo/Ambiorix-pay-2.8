package com.ambiorix.pay.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambiorix.pay.data.Payment
import com.ambiorix.pay.ui.components.rememberReducedMotionEnabled
import com.ambiorix.pay.ui.theme.SuccessGreen
import com.ambiorix.pay.ui.theme.SuccessGreenDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Pantalla de confirmación de pago conectada al único logo 3D de Ambiorix Pay (sin superponer otro logo encima):
 *
 * Fase 1 (0..420 ms): El único logo 3D esculpido en `KioskMainScreen` hace nacer el color verde esmeralda
 *                     desde su propio núcleo, emite ondas luminosas y realiza su rebote elástico sin ningún
 *                     duplicado superpuesto.
 * Fase 2 (420..900 ms): Desde el mismo centro del logo verde nace y se expande radialmente la pantalla verde
 *                       que cubre toda la vista y revela el importe exacto y el nombre real del pagador.
 * Fase 3 (Retención): Permanece visible durante el tiempo configurado.
 * Fase 4 (Salida): La pantalla verde se contrae suavemente de regreso hacia el mismo logo central.
 */
@Composable
fun PaymentSuccessScreen(
    payment: Payment,
    durationSeconds: Int,
    onTimeout: () -> Unit
) {
    val reducedMotion = rememberReducedMotionEnabled()

    val progress = remember(payment.id) { Animatable(1.0f) }

    // Expansión radial verde que nace directamente desde el centro del logo original (0f -> 1f)
    val revealProgress = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.0f) }
    val contentAlpha = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.0f) }
    val contentScale = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.88f) }

    // Insignia superior de verificación (Checkmark) en la pantalla verde
    val checkScale = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.0f) }
    val ringScale = remember(payment.id) { Animatable(0.82f) }
    val ringAlpha = remember(payment.id) { Animatable(if (reducedMotion) 0.0f else 0.85f) }

    LaunchedEffect(payment.id, reducedMotion) {
        if (!reducedMotion) {
            // FASE 1 (0..420ms): Dejar que el verde nazca dentro del mismo logo central de KioskMainScreen
            // sin dibujar ningún otro logo encima
            delay(420)

            // FASE 2: La onda verde nace desde el centro del mismo logo y se expande a toda la pantalla
            launch {
                revealProgress.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                delay(90)
                contentAlpha.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                )
            }
            launch {
                delay(90)
                contentScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                delay(140)
                checkScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            launch {
                delay(140)
                ringScale.animateTo(
                    targetValue = 1.55f,
                    animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
                )
            }
            launch {
                delay(140)
                ringAlpha.animateTo(
                    targetValue = 0.0f,
                    animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
                )
            }
        }

        // FASE 3: Cuenta regresiva visible mientras se muestran el importe exacto y el pagador real
        val holdMillis = ((durationSeconds * 1000) - if (reducedMotion) 0 else 780).coerceAtLeast(1800)
        progress.snapTo(1.0f)
        progress.animateTo(
            targetValue = 0.0f,
            animationSpec = tween(
                durationMillis = holdMillis,
                easing = LinearEasing
            )
        )

        // FASE 4: Retorno suave contrayendo la pantalla verde de vuelta hacia el centro del mismo logo
        if (!reducedMotion) {
            launch {
                contentAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                )
            }
            launch {
                checkScale.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
                )
            }
            revealProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
            )
        }
        onTimeout()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Capa 1: Esfera verde que nace directamente desde el centro del logo original y se expande a toda la pantalla
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.5f, h * 0.5f)
            val maxRadius = hypot(w * 0.62f, h * 0.62f)

            val rev = revealProgress.value.coerceAtLeast(0f)
            if (rev > 0.002f) {
                val currentRadius = (maxRadius * rev).coerceAtLeast(1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SuccessGreen,
                            SuccessGreen,
                            SuccessGreenDark
                        ),
                        center = center,
                        radius = currentRadius
                    ),
                    radius = currentRadius,
                    center = center
                )

                if (rev < 0.97f) {
                    drawCircle(
                        color = Color.White.copy(alpha = (1f - rev.coerceIn(0f, 1f)) * 0.75f),
                        radius = currentRadius,
                        center = center,
                        style = Stroke(width = 6.dp.toPx())
                    )
                }
            }
        }

        // Capa 2: Contenido legible de confirmación por encima de la expansión verde (sin superponer otro logo 3D)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .graphicsLayer {
                    alpha = contentAlpha.value
                    scaleX = contentScale.value
                    scaleY = contentScale.value
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                // Insignia limpia de verificación en la cabecera superior
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(104.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(ringScale.value)
                            .alpha(ringAlpha.value)
                            .clip(CircleShape)
                            .border(3.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(84.dp)
                            .scale(checkScale.value)
                            .clip(CircleShape)
                            .background(SuccessGreenDark.copy(alpha = 0.92f))
                            .border(3.dp, Color.White, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Confirmado",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "TRANSACCIÓN CONFIRMADA",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "PAGO RECIBIDO",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                val formatted = payment.formattedAmount
                val fontSize = when {
                    formatted.length <= 8 -> 68.sp
                    formatted.length <= 11 -> 52.sp
                    else -> 40.sp
                }

                Text(
                    text = formatted,
                    color = Color.White,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    lineHeight = fontSize * 1.06f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                val cleanSender = payment.senderName?.trim()?.takeIf { it.isNotEmpty() }
                if (cleanSender != null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White.copy(alpha = 0.18f),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ENVIADO POR",
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.6.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = cleanSender,
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = 0.22f)
                ) {
                    val channelSuffix = payment.channel?.takeIf {
                        it.isNotBlank() && !payment.appName.contains(it, ignoreCase = true)
                    }?.let { " • $it" }.orEmpty()
                    Text(
                        text = "Detectado por ${payment.appName}$channelSuffix",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Hora: ${payment.formattedTime}",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                LinearProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Volviendo a espera activa...",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            }
        }
    }
}


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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ambiorix.pay.data.Payment
import com.ambiorix.pay.ui.components.LogoGeometryCache
import com.ambiorix.pay.ui.components.drawSculptedAmbiorixSphereLogo
import com.ambiorix.pay.ui.components.rememberReducedMotionEnabled
import com.ambiorix.pay.ui.theme.SuccessGreen
import com.ambiorix.pay.ui.theme.SuccessGreenDark
import com.ambiorix.pay.ui.theme.SuccessGreenLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot

/**
 * Pantalla de confirmación de pago conectada a la coreografía 3D del logo de Ambiorix Pay:
 *
 * Fase 1 (0..460 ms): El logo 3D esculpido de Ambiorix en el centro cambia a verde esmeralda vivo,
 *                     emite ondas luminosas y realiza un rebote elástico visible en el centro.
 * Fase 2 (460..940 ms): Desde el logo verde central se expande una esfera verde con rebote sutil
 *                       que cubre toda la pantalla, mientras el propio logo 3D esculpido se eleva
 *                       suavemente hacia la insignia superior y revela el importe exacto y el
 *                       nombre real del pagador.
 * Fase 3 (Retención): Permanece visible con el logo 3D esculpido rotando suavemente en la cabecera.
 * Fase 4 (Salida): El logo desciende de regreso al centro mientras la pantalla verde se contrae
 *                  suavemente hacia la animación normal del logo (Plateado <= 300 o Dorado > 300).
 */
@Composable
fun PaymentSuccessScreen(
    payment: Payment,
    durationSeconds: Int,
    onTimeout: () -> Unit
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isScreenResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val progress = remember(payment.id) { Animatable(1.0f) }

    // Fase 1: Rebote elástico del logo 3D verde en el centro y ondas de choque
    val centerLogoBounce = remember(payment.id) { Animatable(if (reducedMotion) 1f else 0.90f) }
    val centerShockwave = remember(payment.id) { Animatable(0f) }

    // Fase 2: Expansión radial verde y elevación del logo 3D desde el centro (0f) hasta la cabecera (1f)
    val revealProgress = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.0f) }
    val logoFlightProgress = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.0f) }
    val contentAlpha = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.0f) }
    val contentScale = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.86f) }

    // Aparición del Checkmark cuando el logo aterriza en la cabecera
    val checkScale = remember(payment.id) { Animatable(if (reducedMotion) 1.0f else 0.0f) }
    val ringScale = remember(payment.id) { Animatable(0.82f) }
    val ringAlpha = remember(payment.id) { Animatable(if (reducedMotion) 0.0f else 0.85f) }

    val geometryCache = remember { LogoGeometryCache() }

    val rotationRadians = if (isScreenResumed && !reducedMotion) {
        val infinite = rememberInfiniteTransition(label = "ConfirmedLogo3DRotation")
        val rad by infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "RotRad"
        )
        rad
    } else {
        0.8f
    }

    LaunchedEffect(payment.id, reducedMotion) {
        if (!reducedMotion) {
            // FASE 1 (0..460ms): El logo 3D esculpido cambia a verde esmeralda en el centro y rebota
            launch {
                centerShockwave.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing)
                )
            }
            centerLogoBounce.animateTo(
                targetValue = 1.18f,
                animationSpec = keyframes {
                    durationMillis = 460
                    0.90f at 0
                    1.32f at 210 with FastOutSlowInEasing
                    0.98f at 340 with FastOutSlowInEasing
                    1.18f at 460 with FastOutSlowInEasing
                }
            )

            // FASE 2 (460..940ms): La esfera verde se expande desde el logo central a toda la pantalla
            // mientras el logo 3D esculpido sube con rebote sutil a la cabecera de confirmación
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
                logoFlightProgress.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                delay(110)
                contentAlpha.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                )
            }
            launch {
                delay(110)
                contentScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                delay(240)
                checkScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            launch {
                delay(180)
                ringScale.animateTo(
                    targetValue = 1.58f,
                    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
                )
            }
            launch {
                delay(180)
                ringAlpha.animateTo(
                    targetValue = 0.0f,
                    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
                )
            }
        }

        // FASE 3: Cuenta regresiva visible mientras se muestran el importe exacto y el pagador real
        val holdMillis = ((durationSeconds * 1000) - if (reducedMotion) 0 else 820).coerceAtLeast(1800)
        progress.snapTo(1.0f)
        progress.animateTo(
            targetValue = 0.0f,
            animationSpec = tween(
                durationMillis = holdMillis,
                easing = LinearEasing
            )
        )

        // FASE 4: Retorno suave contrayendo la pantalla verde y el logo 3D de vuelta al centro
        if (!reducedMotion) {
            launch {
                contentAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing)
                )
            }
            launch {
                checkScale.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
                )
            }
            launch {
                logoFlightProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                )
            }
            revealProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
            )
        }
        onTimeout()
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val screenHeight = maxHeight
        val targetTopOffset = -(screenHeight * 0.365f)
        val flight = logoFlightProgress.value.coerceIn(0f, 1.2f)

        // Capa 1: Esfera verde que nace en el radio exacto del logo central y se expande a toda la pantalla
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.5f, h * 0.5f)
            val baseLogoRadius = minOf(w, h) * 0.25f
            val maxRadius = hypot(w * 0.60f, h * 0.60f)

            val rev = revealProgress.value.coerceAtLeast(0f)
            if (rev > 0.002f) {
                val currentRadius = baseLogoRadius * 0.72f + (maxRadius - baseLogoRadius * 0.72f) * rev
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SuccessGreen,
                            SuccessGreen,
                            SuccessGreenDark
                        ),
                        center = center,
                        radius = currentRadius.coerceAtLeast(1f)
                    ),
                    radius = currentRadius,
                    center = center
                )

                if (rev < 0.97f) {
                    drawCircle(
                        color = Color.White.copy(alpha = (1f - rev.coerceIn(0f, 1f)) * 0.75f),
                        radius = currentRadius,
                        center = center,
                        style = Stroke(width = 7.dp.toPx())
                    )
                }
            }
        }

        // Capa 2: Contenido legible de confirmación por encima de los efectos visuales
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
                Spacer(modifier = Modifier.size(138.dp))

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

        // Capa 3: El propio Logo 3D Esculpido de Ambiorix en Verde Esmeralda:
        // En Fase 1 está en el centro (250.dp), se tiñe de verde esmeralda vivo y rebota.
        // En Fase 2 se eleva con rebote hacia la cabecera y revela el Checkmark.
        val currentLogoScale = (1f - 0.48f * flight.coerceIn(0f, 1f)) *
                (if (flight < 0.25f) centerLogoBounce.value else 1f)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(y = targetTopOffset * flight)
                .size(250.dp)
                .graphicsLayer {
                    scaleX = currentLogoScale
                    scaleY = currentLogoScale
                }
        ) {
            if (flight > 0.5f) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(ringScale.value)
                        .alpha(ringAlpha.value)
                        .clip(CircleShape)
                        .border(4.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSculptedAmbiorixSphereLogo(
                    cache = geometryCache,
                    morphFraction = 1f,
                    rotationRadians = rotationRadians,
                    goldFactor = 1f,
                    greenFactor = 1f,
                    shockwave = centerShockwave.value
                )
            }

            // Checkmark que emerge suavemente cuando el logo 3D llega a la cabecera
            if (checkScale.value > 0.02f) {
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
                        modifier = Modifier.size(50.dp)
                    )
                }
            }
        }
    }
}

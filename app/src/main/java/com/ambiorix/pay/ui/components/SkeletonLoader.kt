package com.ambiorix.pay.ui.components

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ambiorix.pay.ui.theme.DarkBackground
import com.ambiorix.pay.ui.theme.DarkBorder
import com.ambiorix.pay.ui.theme.DarkSurface
import com.ambiorix.pay.ui.theme.DarkSurfaceElevated

/**
 * Detecta si el usuario tiene activada la reducción de movimiento o desactivadas las animaciones en Android.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale == 0.0f
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Pincel de esqueleto de carga sobre fondo oscuro AMOLED.
 * Respeta el ciclo de vida de la pantalla y las preferencias de reducción de movimiento.
 */
@Composable
fun rememberSkeletonBrush(): Brush {
    val reducedMotion = rememberReducedMotionEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isScreenResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val baseColor = Color(0xFF1E293B)
    val highlightColor = Color(0xFF334155)

    if (reducedMotion || !isScreenResumed) {
        return Brush.linearGradient(listOf(baseColor, baseColor))
    }

    val transition = rememberInfiniteTransition(label = "SkeletonShimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerOffset"
    )

    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 280f, 180f)
    )
}

@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    brush: Brush = rememberSkeletonBrush()
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}

/**
 * Esqueleto de carga completo para la pantalla de Historial, Calendario, Gráfico de Barras y Registros.
 * Comunica visualmente la estructura de la pantalla mientras existe una operación de carga real.
 */
@Composable
fun HistoryScreenSkeleton(
    modifier: Modifier = Modifier
) {
    val brush = rememberSkeletonBrush()
    val barHeights = remember {
        listOf(34.dp, 58.dp, 26.dp, 82.dp, 46.dp, 94.dp, 64.dp, 38.dp, 72.dp, 50.dp, 88.dp, 30.dp)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Esqueleto de Tarjeta de Acumulado Diario
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SkeletonBlock(
                    modifier = Modifier
                        .width(140.dp)
                        .height(12.dp),
                    brush = brush
                )
                SkeletonBlock(
                    modifier = Modifier
                        .width(230.dp)
                        .height(28.dp),
                    brush = brush
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        brush = brush
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        brush = brush
                    )
                }
            }
        }

        // 2. Esqueleto de Gráfico de Barras de Movimiento Diario
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .width(150.dp)
                            .height(14.dp),
                        brush = brush
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .width(90.dp)
                            .height(14.dp),
                        brush = brush
                    )
                }

                // Barras de distintas alturas simulando el movimiento diario
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    barHeights.forEach { h: Dp ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            SkeletonBlock(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(h),
                                shape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 2.dp),
                                brush = brush
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            SkeletonBlock(
                                modifier = Modifier
                                    .width(12.dp)
                                    .height(8.dp),
                                shape = RoundedCornerShape(3.dp),
                                brush = brush
                            )
                        }
                    }
                }
            }
        }

        // 3. Esqueleto de Calendario Mensual
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonBlock(modifier = Modifier.size(28.dp), shape = CircleShape, brush = brush)
                    SkeletonBlock(modifier = Modifier.width(130.dp).height(16.dp), brush = brush)
                    SkeletonBlock(modifier = Modifier.size(28.dp), shape = CircleShape, brush = brush)
                }
                repeat(4) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        repeat(7) {
                            SkeletonBlock(
                                modifier = Modifier.size(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                brush = brush
                            )
                        }
                    }
                }
            }
        }

        // 4. Esqueleto de Listado de Registros del Día
        repeat(2) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBlock(modifier = Modifier.width(180.dp).height(16.dp), brush = brush)
                        SkeletonBlock(modifier = Modifier.width(120.dp).height(12.dp), brush = brush)
                    }
                    SkeletonBlock(modifier = Modifier.width(64.dp).height(14.dp), brush = brush)
                }
            }
        }
    }
}

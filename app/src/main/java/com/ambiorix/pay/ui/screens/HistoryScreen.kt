package com.ambiorix.pay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambiorix.pay.data.*
import com.ambiorix.pay.ui.components.HistoryScreenSkeleton
import com.ambiorix.pay.ui.theme.*

private val WEEKDAY_HEADERS = listOf("L", "M", "M", "J", "V", "S", "D")

@Composable
fun HistoryScreen(
    payments: List<Payment>,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onRetryLoad: () -> Unit = {},
    onBackClick: () -> Unit,
    onClearHistory: () -> Unit
) {
    var showConfirmDelete by remember { mutableStateOf(false) }

    var todayKey by remember { mutableStateOf(PaymentHistoryCalculator.getTodayDayKey()) }
    var displayedYear by remember { mutableIntStateOf(todayKey.year) }
    var displayedMonth by remember { mutableIntStateOf(todayKey.month) }
    var selectedDayKey by remember { mutableStateOf(todayKey) }

    // Cierre de día automático a medianoche (00:00:00 America/Bogota):
    // Si la pantalla permanece abierta al cambiar de día, avanza automáticamente al nuevo día en $ 0.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            val currentToday = PaymentHistoryCalculator.getTodayDayKey()
            if (currentToday != todayKey) {
                val wasOnToday = (selectedDayKey == todayKey)
                todayKey = currentToday
                if (wasOnToday) {
                    displayedYear = currentToday.year
                    displayedMonth = currentToday.month
                    selectedDayKey = currentToday
                }
            }
        }
    }

    // Recalcular resumen mensual y diario de forma reactiva cuando cambien los pagos o la fecha seleccionada
    val monthlySummary = remember(payments, displayedYear, displayedMonth) {
        PaymentHistoryCalculator.calculateMonthlySummary(
            allPayments = payments,
            year = displayedYear,
            month = displayedMonth
        )
    }

    val dailySummary = remember(payments, selectedDayKey) {
        PaymentHistoryCalculator.calculateDailySummary(
            allPayments = payments,
            selectedDay = selectedDayKey
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Historial y Ganancias",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Calendario mensual y movimiento diario",
                            color = TextGray,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Atrás",
                            tint = TextWhite
                        )
                    }
                },
                actions = {
                    if (selectedDayKey != todayKey || displayedYear != todayKey.year || displayedMonth != todayKey.month) {
                        TextButton(
                            onClick = {
                                displayedYear = todayKey.year
                                displayedMonth = todayKey.month
                                selectedDayKey = todayKey
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Today,
                                contentDescription = "Ir a hoy",
                                tint = GoldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Hoy",
                                color = GoldPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (payments.isNotEmpty()) {
                        IconButton(onClick = { showConfirmDelete = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Borrar historial",
                                tint = DangerRed
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    HistoryScreenSkeleton()
                }
                errorMessage != null -> {
                    HistoryErrorState(
                        errorMessage = errorMessage,
                        onRetryClick = onRetryLoad
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 1. ACUMULADO DIARIO DESTACADO (PARTE SUPERIOR)
                        item(key = "daily_accumulated_header") {
                            DailyAccumulatedCard(
                                dailySummary = dailySummary,
                                isToday = selectedDayKey == todayKey
                            )
                        }

                        // 2. CALENDARIO MENSUAL INTERACTIVO
                        item(key = "monthly_calendar_card") {
                            MonthlyCalendarCard(
                                monthlySummary = monthlySummary,
                                todayKey = todayKey,
                                selectedDayKey = selectedDayKey,
                                onPreviousMonth = {
                                    val (prevY, prevM) = PaymentHistoryCalculator.previousMonth(displayedYear, displayedMonth)
                                    displayedYear = prevY
                                    displayedMonth = prevM
                                    val prevSummary = PaymentHistoryCalculator.calculateMonthlySummary(payments, prevY, prevM)
                                    val targetDay = selectedDayKey.dayOfMonth.coerceAtMost(prevSummary.daysInMonth)
                                    selectedDayKey = DayKey(prevY, prevM, targetDay)
                                },
                                onNextMonth = {
                                    val (nextY, nextM) = PaymentHistoryCalculator.nextMonth(displayedYear, displayedMonth)
                                    displayedYear = nextY
                                    displayedMonth = nextM
                                    val nextSummary = PaymentHistoryCalculator.calculateMonthlySummary(payments, nextY, nextM)
                                    val targetDay = selectedDayKey.dayOfMonth.coerceAtMost(nextSummary.daysInMonth)
                                    selectedDayKey = DayKey(nextY, nextM, targetDay)
                                },
                                onSelectDay = { dayOfMonth ->
                                    selectedDayKey = DayKey(displayedYear, displayedMonth, dayOfMonth)
                                }
                            )
                        }

                        // 3. GRÁFICO DE BARRAS: MOVIMIENTO DIARIO Y RESUMEN DEL MES
                        item(key = "daily_movement_chart_card") {
                            DailyMovementBarChartCard(
                                monthlySummary = monthlySummary,
                                dailySummary = dailySummary,
                                selectedDayKey = selectedDayKey,
                                onSelectDayFromBar = { dayKey ->
                                    displayedYear = dayKey.year
                                    displayedMonth = dayKey.month
                                    selectedDayKey = dayKey
                                }
                            )
                        }

                        // 4. ENCABEZADO DEL DETALLE DE PAGOS DEL DÍA SELECCIONADO
                        item(key = "day_payments_section_title") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Pagos del ${dailySummary.formattedShortDate}",
                                    color = TextWhite,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = if (dailySummary.paymentCount == 1) "1 confirmado" else "${dailySummary.paymentCount} confirmados",
                                    color = if (dailySummary.paymentCount > 0) SuccessGreenLight else TextMuted,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // 5. LISTADO CRONOLÓGICO DE PAGOS DEL DÍA O ESTADO VACÍO
                        if (dailySummary.payments.isEmpty()) {
                            item(key = "empty_day_state") {
                                EmptySelectedDayCard(
                                    formattedDateTitle = dailySummary.formattedDateTitle
                                )
                            }
                        } else {
                            items(
                                items = dailySummary.payments,
                                key = { it.id }
                            ) { payment ->
                                PaymentHistoryDetailItem(payment = payment)
                            }
                        }
                    }
                }
            }
        }

        if (showConfirmDelete) {
            AlertDialog(
                onDismissRequest = { showConfirmDelete = false },
                title = {
                    Text(
                        text = "¿Borrar todo el historial?",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Esta acción eliminará permanentemente los pagos guardados en este dispositivo.",
                        color = TextGray
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearHistory()
                            showConfirmDelete = false
                        }
                    ) {
                        Text("Eliminar", color = DangerRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDelete = false }) {
                        Text("Cancelar", color = TextWhite)
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

/**
 * Tarjeta superior obligatoria que destaca el Acumulado Diario del día seleccionado.
 */
@Composable
private fun DailyAccumulatedCard(
    dailySummary: DailySummary,
    isToday: Boolean
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.5.dp,
            color = if (dailySummary.totalAmount > 0L) GoldPrimary.copy(alpha = 0.75f) else DarkBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isToday) "Fecha seleccionada: Hoy (${dailySummary.formattedDateTitle})"
                    else "Fecha seleccionada: ${dailySummary.formattedDateTitle}",
                    color = GoldPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "TOTAL RECIBIDO HOY: ${dailySummary.formattedTotalAmount}",
                color = if (dailySummary.totalAmount > 0L) SuccessGreenLight else TextWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.3.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (dailySummary.paymentCount == 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkBackground.copy(alpha = 0.65f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Sin pagos confirmados en esta fecha. Selecciona otro día con indicador en el calendario o espera una nueva transferencia.",
                        color = TextGray,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = "Pagos confirmados",
                                color = TextGray,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${dailySummary.paymentCount}",
                                color = TextWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = "Promedio por pago",
                                color = TextGray,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = dailySummary.formattedAverageAmount,
                                color = GoldLight,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Calendario mensual convencional con navegación entre meses, día actual resaltado,
 * e indicadores discretos para días con pagos registrados.
 */
@Composable
private fun MonthlyCalendarCard(
    monthlySummary: MonthlySummary,
    todayKey: DayKey,
    selectedDayKey: DayKey,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDay: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Barra de navegación de mes y año
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPreviousMonth,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceElevated)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Mes anterior",
                        tint = TextWhite
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = GoldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = monthlySummary.monthTitle,
                        color = TextWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }

                IconButton(
                    onClick = onNextMonth,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceElevated)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Mes siguiente",
                        tint = TextWhite
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Cabecera de días de la semana (Lun a Dom)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                WEEKDAY_HEADERS.forEach { dayLabel ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLabel,
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filas de semanas del calendario
            val weeks = monthlySummary.calendarGridCells.chunked(7)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                weeks.forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        week.forEach { dayNumber ->
                            if (dayNumber == null) {
                                Spacer(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                )
                            } else {
                                val cellKey = DayKey(monthlySummary.year, monthlySummary.month, dayNumber)
                                val isSelected = cellKey == selectedDayKey
                                val isToday = cellKey == todayKey
                                val hasPayments = monthlySummary.daysWithPayments.contains(dayNumber)

                                val bgColor = when {
                                    isSelected -> GoldPrimary
                                    hasPayments -> DarkSurfaceElevated
                                    else -> DarkBackground.copy(alpha = 0.45f)
                                }

                                val borderColor = when {
                                    isSelected -> GoldPrimary
                                    isToday -> GoldPrimary.copy(alpha = 0.85f)
                                    hasPayments -> SuccessGreen.copy(alpha = 0.45f)
                                    else -> DarkBorderSubtle
                                }

                                val textColor = when {
                                    isSelected -> DarkBackground
                                    hasPayments -> TextWhite
                                    isToday -> GoldLight
                                    else -> TextMuted
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(bgColor)
                                        .border(
                                            width = if (isToday || isSelected) 1.5.dp else 1.dp,
                                            color = borderColor,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onSelectDay(dayNumber) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = dayNumber.toString(),
                                            color = textColor,
                                            fontWeight = if (isSelected || hasPayments || isToday) FontWeight.ExtraBold else FontWeight.Medium,
                                            fontSize = 13.sp
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        // Indicador discreto en los días con pagos
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        isSelected && hasPayments -> DarkBackground
                                                        hasPayments -> SuccessGreenLight
                                                        else -> Color.Transparent
                                                    }
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Leyenda discreta del calendario
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(SuccessGreenLight)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Día con pagos", color = TextGray, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(GoldPrimary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Día seleccionado", color = TextGray, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Gráfico de barras oscuro y compacto del movimiento diario del mes + 3 indicadores de resumen mensual.
 */
@Composable
private fun DailyMovementBarChartCard(
    monthlySummary: MonthlySummary,
    dailySummary: DailySummary,
    selectedDayKey: DayKey,
    onSelectDayFromBar: (DayKey) -> Unit
) {
    val barScrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Cabecera del gráfico y valor legible del día seleccionado
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = null,
                        tint = GoldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Movimiento Diario",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = monthlySummary.monthTitle,
                            color = TextGray,
                            fontSize = 11.sp
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Día ${selectedDayKey.dayOfMonth}",
                        color = GoldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = dailySummary.formattedTotalAmount,
                        color = if (dailySummary.totalAmount > 0L) SuccessGreenLight else TextGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Área de barras con desplazamiento horizontal para evitar superposición en pantallas pequeñas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkBackground.copy(alpha = 0.7f))
                    .border(1.dp, DarkBorderSubtle, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(barScrollState),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    monthlySummary.dailyBars.forEach { barItem ->
                        val isSelected = barItem.dayKey == selectedDayKey
                        val hasIncome = barItem.totalAmount > 0L
                        val maxBarHeight = 92.dp
                        val computedHeight = if (hasIncome) {
                            (maxBarHeight * barItem.heightFraction).coerceAtLeast(10.dp)
                        } else {
                            4.dp
                        }

                        val barColor = when {
                            isSelected -> GoldPrimary
                            hasIncome -> Color(0xFF64748B)
                            else -> Color(0xFF1E293B)
                        }

                        Column(
                            modifier = Modifier
                                .width(26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectDayFromBar(barItem.dayKey) }
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(maxBarHeight)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(if (isSelected) 16.dp else 13.dp)
                                        .height(computedHeight)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 5.dp,
                                                topEnd = 5.dp,
                                                bottomStart = 2.dp,
                                                bottomEnd = 2.dp
                                            )
                                        )
                                        .background(barColor)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = barItem.dayOfMonth.toString(),
                                color = when {
                                    isSelected -> GoldPrimary
                                    hasIncome -> TextWhite
                                    else -> TextMuted
                                },
                                fontSize = 10.sp,
                                fontWeight = if (isSelected || hasIncome) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Resumen del mes (3 indicadores reales)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonthMetricSmallBox(
                    label = "Total del mes",
                    value = monthlySummary.formattedTotalMonthAmount,
                    valueColor = SuccessGreenLight,
                    modifier = Modifier.weight(1f)
                )
                MonthMetricSmallBox(
                    label = "Mayor ingreso",
                    value = monthlySummary.formattedBestDayLabel,
                    valueColor = GoldLight,
                    modifier = Modifier.weight(1f)
                )
                MonthMetricSmallBox(
                    label = "Pagos del mes",
                    value = "${monthlySummary.totalMonthPaymentCount}",
                    valueColor = TextWhite,
                    modifier = Modifier.weight(0.8f)
                )
            }
        }
    }
}

@Composable
private fun MonthMetricSmallBox(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                color = TextGray,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = valueColor,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Estado vacío cuando el día seleccionado no registra pagos confirmados.
 */
@Composable
private fun EmptySelectedDayCard(
    formattedDateTitle: String
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.ReceiptLong,
                contentDescription = "Sin pagos",
                tint = TextMuted,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Sin movimientos registrados",
                color = TextWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "No se registraron pagos confirmados el $formattedDateTitle.",
                color = TextGray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Ítem individual del detalle cronológico del día seleccionado.
 * Incluye:
 * 1. Hora exacta de recepción con segundos (ej. 19:11:42)
 * 2. Valor exacto recibido
 * 3. Nombre completo del remitente (cuando esté disponible en la notificación)
 * 4. Fecha del pago
 * 5. Identificador interno del registro
 */
@Composable
private fun PaymentHistoryDetailItem(payment: Payment) {
    val senderLabel = payment.senderName?.takeIf { it.isNotBlank() } ?: payment.appName

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Línea principal: Hora exacta (HH:mm:ss) — $Monto — Nombre del remitente
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = payment.formattedExactTime,
                        color = GoldLight,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = " — ",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    Text(
                        text = payment.formattedAmount,
                        color = SuccessGreenLight,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Text(
                    text = payment.appName,
                    color = GoldPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Nombre completo del remitente destacado
            Text(
                text = if (!payment.senderName.isNullOrBlank()) {
                    "Remitente: ${payment.senderName}"
                } else {
                    "Origen: ${payment.appName} (remitente no incluido en notificación)"
                },
                color = if (!payment.senderName.isNullOrBlank()) TextWhite else TextGray,
                fontSize = 13.sp,
                fontWeight = if (!payment.senderName.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Metadatos inferiores: Fecha exacta e identificador interno del registro
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fecha: ${payment.formattedDate}" +
                            if (!payment.hasExactSeconds) " · Hora histórica sin seg." else "",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Text(
                    text = "ID: #${payment.displayInternalId}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun HistoryErrorState(
    errorMessage: String,
    onRetryClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = "Error",
            tint = DangerRedLight,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Error al cargar el historial",
            color = TextWhite,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = errorMessage,
            color = TextGray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetryClick,
            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = DarkBackground),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reintentar", fontWeight = FontWeight.Bold)
        }
    }
}

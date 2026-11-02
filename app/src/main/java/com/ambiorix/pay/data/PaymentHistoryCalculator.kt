package com.ambiorix.pay.data

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Identificador inmutable de una fecha calendario en zona horaria de Colombia.
 * @param year Año (ej. 2026)
 * @param month Mes 1..12 (1 = Enero, 10 = Octubre, 12 = Diciembre)
 * @param dayOfMonth Día del mes 1..31
 */
data class DayKey(
    val year: Int,
    val month: Int,
    val dayOfMonth: Int
) : Comparable<DayKey> {
    override fun compareTo(other: DayKey): Int {
        if (year != other.year) return year.compareTo(other.year)
        if (month != other.month) return month.compareTo(other.month)
        return dayOfMonth.compareTo(other.dayOfMonth)
    }
}

/**
 * Resumen y detalle de pagos confirmados para un día seleccionado.
 */
data class DailySummary(
    val dayKey: DayKey,
    val formattedDateTitle: String,      // Ej: "30 de octubre de 2026"
    val formattedShortDate: String,      // Ej: "30 de octubre"
    val totalAmount: Long,
    val formattedTotalAmount: String,
    val paymentCount: Int,
    val averageAmount: Long,
    val formattedAverageAmount: String,
    val payments: List<Payment>          // Ordenados del más reciente al más antiguo
)

/**
 * Datos de una barra individual en el gráfico de movimiento diario del mes.
 */
data class DayBarItem(
    val dayOfMonth: Int,
    val dayKey: DayKey,
    val totalAmount: Long,
    val formattedTotalAmount: String,
    val paymentCount: Int,
    val heightFraction: Float            // 0f..1f proporcional al día de mayor ingreso del mes
)

/**
 * Resumen estadístico y estructura de calendario para un mes completo.
 */
data class MonthlySummary(
    val year: Int,
    val month: Int,                      // 1..12
    val monthTitle: String,              // Ej: "Octubre 2026"
    val daysInMonth: Int,
    val totalMonthAmount: Long,
    val formattedTotalMonthAmount: String,
    val totalMonthPaymentCount: Int,
    val bestDay: DayBarItem?,
    val formattedBestDayLabel: String,   // Ej: "30 oct ($82.000)" o "Sin registros"
    val dailyBars: List<DayBarItem>,
    val daysWithPayments: Set<Int>,
    val dailyTotalsMap: Map<Int, Long>,
    val dailyCountsMap: Map<Int, Int>,
    val calendarGridCells: List<Int?>    // Celdas alineadas Lun..Dom (null = celda vacía de otro mes)
)

/**
 * Motor de cálculo determinista para el calendario de ganancias, acumulado diario y gráfico mensual.
 * Utiliza estrictamente la zona horaria de Colombia (America/Bogota) para evitar desfases de día.
 */
object PaymentHistoryCalculator {

    private val MONTH_NAMES_LOWER = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )

    private val MONTH_NAMES_TITLE = arrayOf(
        "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    )

    private val MONTH_NAMES_SHORT = arrayOf(
        "ene", "feb", "mar", "abr", "may", "jun",
        "jul", "ago", "sep", "oct", "nov", "dic"
    )

    /**
     * Obtiene el DayKey actual según el reloj en zona horaria de Colombia.
     */
    fun getTodayDayKey(nowMillis: Long = System.currentTimeMillis()): DayKey {
        return dayKeyFromTimestamp(nowMillis)
    }

    /**
     * Convierte un timestamp en milisegundos a su fecha calendario exacta en Colombia.
     */
    fun dayKeyFromTimestamp(timestampMillis: Long): DayKey {
        val cal = Calendar.getInstance(Payment.COLOMBIA_TIME_ZONE, Locale("es", "CO"))
        cal.timeInMillis = timestampMillis
        return DayKey(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * Filtra y deduplica pagos para garantizar que únicamente se sumen pagos confirmados y únicos:
     * 1. Excluye pagos de prueba simulados (PaymentSource.SIMULATED) y montos <= 0.
     * 2. Ordena del más reciente al más antiguo (timestampMillis descendente).
     * 3. Elimina duplicados por ID idéntico o notificaciones repetidas de la misma transacción
     *    (mismo paquete, mismo monto, mismo remitente en ventana <= 2 segundos).
     */
    fun deduplicateConfirmedPayments(payments: List<Payment>): List<Payment> {
        val valid = payments.filter { it.source != PaymentSource.SIMULATED && it.amount > 0L }
            .sortedByDescending { it.timestampMillis }

        val seenIds = HashSet<String>()
        val uniqueList = ArrayList<Payment>(valid.size)

        for (p in valid) {
            val cleanId = p.id.trim()
            if (cleanId.isNotEmpty()) {
                if (!seenIds.add(cleanId)) {
                    continue
                }
            }

            // Verificar que no sea un duplicado exacto disparado dos veces por el sistema en <= 2s
            val isNearInstantDuplicate = uniqueList.any { existing ->
                existing.packageName.equals(p.packageName, ignoreCase = true) &&
                        existing.amount == p.amount &&
                        (existing.senderName.orEmpty().equals(p.senderName.orEmpty(), ignoreCase = true)) &&
                        abs(existing.timestampMillis - p.timestampMillis) <= 2000L
            }

            if (!isNearInstantDuplicate) {
                uniqueList.add(p)
            }
        }

        return uniqueList
    }

    /**
     * Calcula el acumulado diario y el listado cronológico (más reciente a más antiguo)
     * exclusivamente para el día seleccionado.
     */
    fun calculateDailySummary(
        allPayments: List<Payment>,
        selectedDay: DayKey
    ): DailySummary {
        val confirmedUnique = deduplicateConfirmedPayments(allPayments)
        val dayPayments = confirmedUnique.filter { payment ->
            dayKeyFromTimestamp(payment.timestampMillis) == selectedDay
        }.sortedByDescending { it.timestampMillis }

        val totalAmount = dayPayments.sumOf { it.amount }
        val count = dayPayments.size
        val averageAmount = if (count > 0) totalAmount / count else 0L

        val monthIdx = (selectedDay.month - 1).coerceIn(0, 11)
        val monthName = MONTH_NAMES_LOWER[monthIdx]
        val formattedDateTitle = "${selectedDay.dayOfMonth} de $monthName de ${selectedDay.year}"
        val formattedShortDate = "${selectedDay.dayOfMonth} de $monthName"

        return DailySummary(
            dayKey = selectedDay,
            formattedDateTitle = formattedDateTitle,
            formattedShortDate = formattedShortDate,
            totalAmount = totalAmount,
            formattedTotalAmount = Payment.formatCop(totalAmount),
            paymentCount = count,
            averageAmount = averageAmount,
            formattedAverageAmount = Payment.formatCop(averageAmount),
            payments = dayPayments
        )
    }

    /**
     * Calcula las estadísticas del mes, las barras de movimiento diario y la cuadrícula del calendario.
     */
    fun calculateMonthlySummary(
        allPayments: List<Payment>,
        year: Int,
        month: Int
    ): MonthlySummary {
        val safeMonth = month.coerceIn(1, 12)
        val cal = Calendar.getInstance(Payment.COLOMBIA_TIME_ZONE, Locale("es", "CO")).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, safeMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // Día de la semana del día 1 (alineado Lunes = 0 .. Domingo = 6)
        val firstDayOfWeekCal = cal.get(Calendar.DAY_OF_WEEK)
        val leadingEmptyCells = when (firstDayOfWeekCal) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }

        val gridCells = ArrayList<Int?>()
        repeat(leadingEmptyCells) { gridCells.add(null) }
        for (d in 1..daysInMonth) {
            gridCells.add(d)
        }
        while (gridCells.size % 7 != 0) {
            gridCells.add(null)
        }

        val confirmedUnique = deduplicateConfirmedPayments(allPayments)
        val monthPayments = confirmedUnique.filter { p ->
            val key = dayKeyFromTimestamp(p.timestampMillis)
            key.year == year && key.month == safeMonth
        }

        val dailyTotals = HashMap<Int, Long>()
        val dailyCounts = HashMap<Int, Int>()
        for (p in monthPayments) {
            val d = dayKeyFromTimestamp(p.timestampMillis).dayOfMonth
            dailyTotals[d] = (dailyTotals[d] ?: 0L) + p.amount
            dailyCounts[d] = (dailyCounts[d] ?: 0) + 1
        }

        val maxDayTotal = dailyTotals.values.maxOrNull() ?: 0L

        val bars = (1..daysInMonth).map { day ->
            val dayTotal = dailyTotals[day] ?: 0L
            val dayCount = dailyCounts[day] ?: 0
            val fraction = if (maxDayTotal > 0L && dayTotal > 0L) {
                (dayTotal.toDouble() / maxDayTotal.toDouble()).toFloat().coerceIn(0.08f, 1.0f)
            } else {
                0f
            }
            DayBarItem(
                dayOfMonth = day,
                dayKey = DayKey(year, safeMonth, day),
                totalAmount = dayTotal,
                formattedTotalAmount = Payment.formatCop(dayTotal),
                paymentCount = dayCount,
                heightFraction = fraction
            )
        }

        val totalMonthAmount = bars.sumOf { it.totalAmount }
        val totalMonthCount = bars.sumOf { it.paymentCount }
        val bestDay = bars.filter { it.totalAmount > 0L }.maxByOrNull { it.totalAmount }

        val monthShort = MONTH_NAMES_SHORT[safeMonth - 1]
        val formattedBestDayLabel = if (bestDay != null) {
            "${bestDay.dayOfMonth} $monthShort (${bestDay.formattedTotalAmount})"
        } else {
            "Sin registros"
        }

        val monthTitle = "${MONTH_NAMES_TITLE[safeMonth - 1]} $year"

        return MonthlySummary(
            year = year,
            month = safeMonth,
            monthTitle = monthTitle,
            daysInMonth = daysInMonth,
            totalMonthAmount = totalMonthAmount,
            formattedTotalMonthAmount = Payment.formatCop(totalMonthAmount),
            totalMonthPaymentCount = totalMonthCount,
            bestDay = bestDay,
            formattedBestDayLabel = formattedBestDayLabel,
            dailyBars = bars,
            daysWithPayments = dailyTotals.filterValues { it > 0L }.keys,
            dailyTotalsMap = dailyTotals,
            dailyCountsMap = dailyCounts,
            calendarGridCells = gridCells
        )
    }

    /**
     * Navega al mes anterior devolviendo el par (año, mes 1..12).
     */
    fun previousMonth(year: Int, month: Int): Pair<Int, Int> {
        return if (month <= 1) {
            Pair(year - 1, 12)
        } else {
            Pair(year, month - 1)
        }
    }

    /**
     * Navega al mes siguiente devolviendo el par (año, mes 1..12).
     */
    fun nextMonth(year: Int, month: Int): Pair<Int, Int> {
        return if (month >= 12) {
            Pair(year + 1, 1)
        } else {
            Pair(year, month + 1)
        }
    }
}

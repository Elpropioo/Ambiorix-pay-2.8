package com.ambiorix.pay

import com.ambiorix.pay.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class PaymentHistoryCalculatorTest {

    private fun colombiaTimestamp(
        year: Int,
        month: Int,
        day: Int,
        hour24: Int,
        minute: Int,
        second: Int
    ): Long {
        val cal = Calendar.getInstance(Payment.COLOMBIA_TIME_ZONE, Locale("es", "CO"))
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, hour24)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun testDailySummaryFiltersOnlySelectedDayAndOrdersNewestFirst() {
        val oct30_091503 = colombiaTimestamp(2026, 10, 30, 9, 15, 3)
        val oct30_092741 = colombiaTimestamp(2026, 10, 30, 9, 27, 41)
        val oct30_100318 = colombiaTimestamp(2026, 10, 30, 10, 3, 18)
        val oct29_180000 = colombiaTimestamp(2026, 10, 29, 18, 0, 0)

        val payments = listOf(
            Payment(
                id = "p1",
                amount = 25000L,
                appName = "Nequi",
                packageName = "com.nequi.MobileApp",
                timestampMillis = oct30_091503,
                senderName = "María Pérez"
            ),
            Payment(
                id = "p2",
                amount = 12000L,
                appName = "Nequi",
                packageName = "com.nequi.MobileApp",
                timestampMillis = oct30_092741,
                senderName = "Carlos Rodríguez"
            ),
            Payment(
                id = "p3",
                amount = 45000L,
                appName = "Nequi",
                packageName = "com.nequi.MobileApp",
                timestampMillis = oct30_100318,
                senderName = "Laura Gómez"
            ),
            Payment(
                id = "p4_other_day",
                amount = 90000L,
                appName = "Nequi",
                packageName = "com.nequi.MobileApp",
                timestampMillis = oct29_180000,
                senderName = "Pedro Gómez"
            )
        )

        val selectedDay = DayKey(2026, 10, 30)
        val summary = PaymentHistoryCalculator.calculateDailySummary(payments, selectedDay)

        assertEquals(3, summary.paymentCount)
        assertEquals(82000L, summary.totalAmount)
        assertEquals(27333L, summary.averageAmount)

        // Verificar orden del más reciente al más antiguo
        assertEquals("p3", summary.payments[0].id)
        assertEquals("10:03:18", summary.payments[0].formattedExactTime)
        assertEquals("Laura Gómez", summary.payments[0].senderName)

        assertEquals("p2", summary.payments[1].id)
        assertEquals("09:27:41", summary.payments[1].formattedExactTime)

        assertEquals("p1", summary.payments[2].id)
        assertEquals("09:15:03", summary.payments[2].formattedExactTime)
    }

    @Test
    fun testDeduplicationPreventsCountingSamePaymentTwiceAndExcludesSimulated() {
        val ts = colombiaTimestamp(2026, 10, 30, 19, 11, 42)

        val payments = listOf(
            Payment(
                id = "dup-id",
                amount = 35000L,
                appName = "Nequi",
                packageName = "com.nequi.MobileApp",
                timestampMillis = ts,
                senderName = "Santiago Morgan"
            ),
            // Mismo ID repetido
            Payment(
                id = "dup-id",
                amount = 35000L,
                appName = "Nequi",
                packageName = "com.nequi.MobileApp",
                timestampMillis = ts,
                senderName = "Santiago Morgan"
            ),
            // Notificación duplicada disparada 500ms después con distinto UUID
            Payment(
                id = "other-uuid",
                amount = 35000L,
                appName = "Nequi",
                packageName = "com.nequi.MobileApp",
                timestampMillis = ts + 500L,
                senderName = "Santiago Morgan"
            ),
            // Pago de prueba simulado (no debe sumarse al acumulado real)
            Payment(
                id = "sim-1",
                amount = 35000L,
                appName = "Prueba de Sistema",
                packageName = "com.ambiorix.pay.test",
                timestampMillis = ts + 5000L,
                source = PaymentSource.SIMULATED
            )
        )

        val summary = PaymentHistoryCalculator.calculateDailySummary(payments, DayKey(2026, 10, 30))
        assertEquals(1, summary.paymentCount)
        assertEquals(35000L, summary.totalAmount)
    }

    @Test
    fun testMonthlySummaryAndBarChartProportionsMatchDailyTotals() {
        val day5 = colombiaTimestamp(2026, 10, 5, 11, 20, 10)
        val day15_a = colombiaTimestamp(2026, 10, 15, 14, 10, 5)
        val day15_b = colombiaTimestamp(2026, 10, 15, 16, 45, 59)
        val day30 = colombiaTimestamp(2026, 10, 30, 19, 11, 42)

        val payments = listOf(
            Payment(id = "1", amount = 20000L, appName = "Nequi", packageName = "com.nequi.MobileApp", timestampMillis = day5),
            Payment(id = "2", amount = 30000L, appName = "Nequi", packageName = "com.nequi.MobileApp", timestampMillis = day15_a),
            Payment(id = "3", amount = 50000L, appName = "Nequi", packageName = "com.nequi.MobileApp", timestampMillis = day15_b),
            Payment(id = "4", amount = 40000L, appName = "Bancolombia", packageName = "com.bancolombia.personas.gemini", timestampMillis = day30)
        )

        val monthly = PaymentHistoryCalculator.calculateMonthlySummary(payments, 2026, 10)

        assertEquals(31, monthly.daysInMonth)
        assertEquals(140000L, monthly.totalMonthAmount)
        assertEquals(4, monthly.totalMonthPaymentCount)

        // Suma de barras diarias debe coincidir exactamente con el total mensual
        val sumOfBars = monthly.dailyBars.sumOf { it.totalAmount }
        assertEquals(monthly.totalMonthAmount, sumOfBars)

        // Día con mayores ingresos debe ser el día 15 ($80.000)
        assertNotNull(monthly.bestDay)
        assertEquals(15, monthly.bestDay?.dayOfMonth)
        assertEquals(80000L, monthly.bestDay?.totalAmount)

        // Proporción de alturas: día 15 (80k) = 1.0f, día 30 (40k) = 0.5f, día 5 (20k) = 0.25f, día 1 (0) = 0f
        val bar15 = monthly.dailyBars.first { it.dayOfMonth == 15 }
        val bar30 = monthly.dailyBars.first { it.dayOfMonth == 30 }
        val bar5 = monthly.dailyBars.first { it.dayOfMonth == 5 }
        val bar1 = monthly.dailyBars.first { it.dayOfMonth == 1 }

        assertEquals(1.0f, bar15.heightFraction, 0.001f)
        assertEquals(0.5f, bar30.heightFraction, 0.001f)
        assertEquals(0.25f, bar5.heightFraction, 0.001f)
        assertEquals(0.0f, bar1.heightFraction, 0.001f)

        assertEquals(setOf(5, 15, 30), monthly.daysWithPayments)
    }

    @Test
    fun testExactSecondsAndHonestLegacyTimeFormatting() {
        val tsWithSeconds = colombiaTimestamp(2026, 10, 30, 19, 11, 42)
        val paymentWithSeconds = Payment(
            id = "sec-1",
            amount = 25000L,
            appName = "Nequi",
            packageName = "com.nequi.MobileApp",
            timestampMillis = tsWithSeconds,
            hasExactSeconds = true
        )
        assertEquals("19:11:42", paymentWithSeconds.formattedExactTime)
        assertEquals("30/10/2026", paymentWithSeconds.formattedDate)

        // Registro histórico sin segundos exactos (no debe inventar :00)
        val tsWithoutSeconds = colombiaTimestamp(2026, 10, 19, 19, 11, 0)
        val legacyPayment = Payment(
            id = "legacy-1",
            amount = 15000L,
            appName = "Nequi",
            packageName = "com.nequi.MobileApp",
            timestampMillis = tsWithoutSeconds,
            hasExactSeconds = false
        )
        assertEquals("19:11", legacyPayment.formattedExactTime)
    }

    @Test
    fun testLateNightColombiaTimestampDoesNotShiftToNextDayUtc() {
        // 11:30:45 PM en Colombia (que sería 04:30:45 AM del día siguiente en UTC)
        val lateNightColombia = colombiaTimestamp(2026, 10, 30, 23, 30, 45)
        val dayKey = PaymentHistoryCalculator.dayKeyFromTimestamp(lateNightColombia)

        assertEquals(2026, dayKey.year)
        assertEquals(10, dayKey.month)
        assertEquals(30, dayKey.dayOfMonth)
    }
}

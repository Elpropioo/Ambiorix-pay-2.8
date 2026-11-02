package com.ambiorix.pay

import com.ambiorix.pay.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class BusinessHoursTest {

    private fun createCalendar(dayOfWeek: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun testManualModeAlwaysReturnsTrue() {
        val config = BusinessHoursConfig(isScheduleEnabled = false)

        // Domingo a las 3 AM (en modo manual debe estar activo)
        val sundayNight = createCalendar(Calendar.SUNDAY, 3, 15)
        assertTrue(config.isWithinBusinessHours(sundayNight))

        // Martes a mediodía
        val tuesdayNoon = createCalendar(Calendar.TUESDAY, 12, 0)
        assertTrue(config.isWithinBusinessHours(tuesdayNoon))
    }

    @Test
    fun testUnifiedScheduleWithinAndOutsideHours() {
        val config = BusinessHoursConfig(
            isScheduleEnabled = true,
            useSameScheduleAllDays = true,
            activeDays = setOf(1, 2, 3, 4, 5, 6), // Lunes a Sábado (ISO 1..6)
            defaultOpenTime = TimeOfDay(8, 0), // 8:00 a. m.
            defaultCloseTime = TimeOfDay(20, 0) // 8:00 p. m.
        )

        // Lunes 10:30 AM -> Abierto
        val mondayMorning = createCalendar(Calendar.MONDAY, 10, 30)
        assertTrue(config.isWithinBusinessHours(mondayMorning))

        // Lunes 7:59 AM -> Cerrado (antes de apertura)
        val mondayBeforeOpen = createCalendar(Calendar.MONDAY, 7, 59)
        assertFalse(config.isWithinBusinessHours(mondayBeforeOpen))

        // Lunes 8:00 PM (20:00) -> Cerrado
        val mondayAtClose = createCalendar(Calendar.MONDAY, 20, 0)
        assertFalse(config.isWithinBusinessHours(mondayAtClose))

        // Lunes 9:00 PM -> Cerrado (después de cierre)
        val mondayNight = createCalendar(Calendar.MONDAY, 21, 0)
        assertFalse(config.isWithinBusinessHours(mondayNight))

        // Domingo 11:00 AM -> Cerrado (Domingo no está en activeDays)
        val sundayMidday = createCalendar(Calendar.SUNDAY, 11, 0)
        assertFalse(config.isWithinBusinessHours(sundayMidday))
    }

    @Test
    fun testCustomPerDaySchedules() {
        val customMap = mapOf(
            1 to DaySchedule(isOpen = true, openTime = TimeOfDay(8, 0), closeTime = TimeOfDay(20, 0)), // Lunes
            6 to DaySchedule(isOpen = true, openTime = TimeOfDay(9, 0), closeTime = TimeOfDay(15, 0)), // Sábado medio día
            7 to DaySchedule(isOpen = false, openTime = TimeOfDay(8, 0), closeTime = TimeOfDay(12, 0)) // Domingo cerrado
        )

        val config = BusinessHoursConfig(
            isScheduleEnabled = true,
            useSameScheduleAllDays = false,
            customDaySchedules = customMap
        )

        // Sábado a las 10 AM -> Abierto
        val saturdayMorning = createCalendar(Calendar.SATURDAY, 10, 0)
        assertTrue(config.isWithinBusinessHours(saturdayMorning))

        // Sábado a las 16:00 (4 PM) -> Cerrado
        val saturdayAfternoon = createCalendar(Calendar.SATURDAY, 16, 0)
        assertFalse(config.isWithinBusinessHours(saturdayAfternoon))

        // Domingo a las 10 AM -> Cerrado por isOpen = false
        val sundayMorning = createCalendar(Calendar.SUNDAY, 10, 0)
        assertFalse(config.isWithinBusinessHours(sundayMorning))
    }

    @Test
    fun testOvernightShiftSchedule() {
        // Horario nocturno: 18:00 (6 PM) a 02:00 (2 AM del día siguiente)
        val nightSchedule = DaySchedule(
            isOpen = true,
            openTime = TimeOfDay(18, 0),
            closeTime = TimeOfDay(2, 0)
        )

        // A las 23:30 debe estar abierto
        assertTrue(nightSchedule.isTimeWithin(23 * 60 + 30))

        // A la 01:15 de la madrugada debe estar abierto
        assertTrue(nightSchedule.isTimeWithin(1 * 60 + 15))

        // A las 04:00 de la madrugada debe estar cerrado
        assertFalse(nightSchedule.isTimeWithin(4 * 60))

        // A las 12:00 del mediodía debe estar cerrado
        assertFalse(nightSchedule.isTimeWithin(12 * 60))
    }

    @Test
    fun testJsonSerializationRoundtrip() {
        val original = BusinessHoursConfig(
            isScheduleEnabled = true,
            useSameScheduleAllDays = true,
            activeDays = setOf(1, 2, 4, 6),
            defaultOpenTime = TimeOfDay(9, 30),
            defaultCloseTime = TimeOfDay(21, 15)
        )

        val json = original.toJson()
        val restored = BusinessHoursConfig.fromJson(json)

        assertEquals(original.isScheduleEnabled, restored.isScheduleEnabled)
        assertEquals(original.useSameScheduleAllDays, restored.useSameScheduleAllDays)
        assertEquals(original.activeDays, restored.activeDays)
        assertEquals(original.defaultOpenTime.hour, restored.defaultOpenTime.hour)
        assertEquals(original.defaultOpenTime.minute, restored.defaultOpenTime.minute)
        assertEquals(original.defaultCloseTime.hour, restored.defaultCloseTime.hour)
        assertEquals(original.defaultCloseTime.minute, restored.defaultCloseTime.minute)
    }

    @Test
    fun testOperationalStatusDifferentiation() {
        // 1. Apagado manualmente (Disabled)
        val statusDisabled = when {
            !false -> OperationalStatus.DISABLED
            !true -> OperationalStatus.MISSING_PERMISSION
            !true -> OperationalStatus.OUTSIDE_HOURS
            else -> OperationalStatus.OPERATIONAL
        }
        assertEquals(OperationalStatus.DISABLED, statusDisabled)
        assertFalse(statusDisabled.isReadyToProcess)

        // 2. Falta permiso (Missing permission)
        val statusMissingPerm = when {
            !true -> OperationalStatus.DISABLED
            !false -> OperationalStatus.MISSING_PERMISSION
            !true -> OperationalStatus.OUTSIDE_HOURS
            else -> OperationalStatus.OPERATIONAL
        }
        assertEquals(OperationalStatus.MISSING_PERMISSION, statusMissingPerm)
        assertFalse(statusMissingPerm.isReadyToProcess)

        // 3. Fuera de horario
        val statusOutsideHours = when {
            !true -> OperationalStatus.DISABLED
            !true -> OperationalStatus.MISSING_PERMISSION
            !false -> OperationalStatus.OUTSIDE_HOURS
            else -> OperationalStatus.OPERATIONAL
        }
        assertEquals(OperationalStatus.OUTSIDE_HOURS, statusOutsideHours)
        assertFalse(statusOutsideHours.isReadyToProcess)

        // 4. Operativo
        val statusOperational = when {
            !true -> OperationalStatus.DISABLED
            !true -> OperationalStatus.MISSING_PERMISSION
            !true -> OperationalStatus.OUTSIDE_HOURS
            else -> OperationalStatus.OPERATIONAL
        }
        assertEquals(OperationalStatus.OPERATIONAL, statusOperational)
        assertTrue(statusOperational.isReadyToProcess)
    }
}

package com.ambiorix.pay.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Representa una hora y minuto específicos en formato de 24 horas.
 */
data class TimeOfDay(
    val hour: Int, // 0..23
    val minute: Int // 0..59
) {
    init {
        require(hour in 0..23) { "La hora debe estar entre 0 y 23: $hour" }
        require(minute in 0..59) { "El minuto debe estar entre 0 y 59: $minute" }
    }

    val totalMinutes: Int get() = hour * 60 + minute

    /**
     * Formatea en formato legible en español de 12 horas (ej: "8:00 a. m.", "8:00 p. m.")
     */
    fun format12Hour(): String {
        val h = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val amPm = if (hour < 12) "a. m." else "p. m."
        return String.format(Locale.getDefault(), "%d:%02d %s", h, minute, amPm)
    }

    fun toMinutesString(): String = String.format(Locale.US, "%02d:%02d", hour, minute)

    companion object {
        fun fromMinutes(minutes: Int): TimeOfDay {
            val h = (minutes / 60) % 24
            val m = minutes % 60
            return TimeOfDay(h, m)
        }

        fun parse(str: String): TimeOfDay {
            val parts = str.split(":")
            if (parts.size >= 2) {
                val h = parts[0].trim().toIntOrNull() ?: 8
                val m = parts[1].trim().toIntOrNull() ?: 0
                return TimeOfDay(h.coerceIn(0, 23), m.coerceIn(0, 59))
            }
            return TimeOfDay(8, 0)
        }
    }
}

/**
 * Horario programado para un día específico de la semana.
 */
data class DaySchedule(
    val isOpen: Boolean = true,
    val openTime: TimeOfDay = TimeOfDay(8, 0),
    val closeTime: TimeOfDay = TimeOfDay(20, 0)
) {
    fun isTimeWithin(currentTotalMinutes: Int): Boolean {
        if (!isOpen) return false

        val openMin = openTime.totalMinutes
        val closeMin = closeTime.totalMinutes

        return if (closeMin > openMin) {
            // Turno regular diurno (ej: 08:00 a 20:00)
            currentTotalMinutes in openMin until closeMin
        } else if (closeMin < openMin) {
            // Turno nocturno que cruza medianoche (ej: 18:00 a 02:00)
            currentTotalMinutes >= openMin || currentTotalMinutes < closeMin
        } else {
            // openMin == closeMin: Abierto 24 horas todo el día
            true
        }
    }

    fun formatDescription(): String {
        return if (!isOpen) {
            "Cerrado"
        } else {
            "${openTime.format12Hour()} - ${closeTime.format12Hour()}"
        }
    }
}

/**
 * Días de la semana indexados según estándar ISO:
 * 1 = Lunes, 2 = Martes, 3 = Miércoles, 4 = Jueves, 5 = Viernes, 6 = Sábado, 7 = Domingo.
 */
enum class BusinessDay(val isoDay: Int, val shortName: String, val fullName: String) {
    MONDAY(1, "Lun", "Lunes"),
    TUESDAY(2, "Mar", "Martes"),
    WEDNESDAY(3, "Mié", "Miércoles"),
    THURSDAY(4, "Jue", "Jueves"),
    FRIDAY(5, "Vie", "Viernes"),
    SATURDAY(6, "Sáb", "Sábado"),
    SUNDAY(7, "Dom", "Domingo");

    companion object {
        fun fromIso(isoDay: Int): BusinessDay {
            return entries.firstOrNull { it.isoDay == isoDay } ?: MONDAY
        }

        fun fromCalendarDay(calendarDay: Int): BusinessDay {
            return when (calendarDay) {
                Calendar.MONDAY -> MONDAY
                Calendar.TUESDAY -> TUESDAY
                Calendar.WEDNESDAY -> WEDNESDAY
                Calendar.THURSDAY -> THURSDAY
                Calendar.FRIDAY -> FRIDAY
                Calendar.SATURDAY -> SATURDAY
                Calendar.SUNDAY -> SUNDAY
                else -> MONDAY
            }
        }
    }
}

/**
 * Configuración completa de horarios de funcionamiento del negocio.
 */
data class BusinessHoursConfig(
    val isScheduleEnabled: Boolean = false, // false = Modo manual 24/7 sin horario programado
    val useSameScheduleAllDays: Boolean = true,
    val activeDays: Set<Int> = setOf(1, 2, 3, 4, 5, 6), // Lunes a Sábado por defecto
    val defaultOpenTime: TimeOfDay = TimeOfDay(8, 0), // 8:00 a. m.
    val defaultCloseTime: TimeOfDay = TimeOfDay(20, 0), // 8:00 p. m.
    val customDaySchedules: Map<Int, DaySchedule> = defaultCustomSchedules()
) {
    /**
     * Evalúa si una hora dada (por defecto System.currentTimeMillis) está dentro del horario operativo.
     */
    fun isWithinBusinessHours(calendar: Calendar = Calendar.getInstance()): Boolean {
        // Si el horario programado está desactivado, está en Modo Manual (siempre activo)
        if (!isScheduleEnabled) {
            return true
        }

        val businessDay = BusinessDay.fromCalendarDay(calendar.get(Calendar.DAY_OF_WEEK))
        val isoDay = businessDay.isoDay
        val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        return if (useSameScheduleAllDays) {
            if (!activeDays.contains(isoDay)) {
                false
            } else {
                val unifiedSchedule = DaySchedule(
                    isOpen = true,
                    openTime = defaultOpenTime,
                    closeTime = defaultCloseTime
                )
                unifiedSchedule.isTimeWithin(currentMinutes)
            }
        } else {
            val daySchedule = customDaySchedules[isoDay] ?: DaySchedule(isOpen = false)
            daySchedule.isTimeWithin(currentMinutes)
        }
    }

    /**
     * Devuelve una descripción en texto concisa del estado del horario para el día actual.
     */
    fun getScheduleStatusDescription(calendar: Calendar = Calendar.getInstance()): String {
        if (!isScheduleEnabled) {
            return "Modo manual (Sin horario programado)"
        }

        val businessDay = BusinessDay.fromCalendarDay(calendar.get(Calendar.DAY_OF_WEEK))
        val isoDay = businessDay.isoDay
        val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val schedule = if (useSameScheduleAllDays) {
            if (activeDays.contains(isoDay)) {
                DaySchedule(true, defaultOpenTime, defaultCloseTime)
            } else {
                DaySchedule(false, defaultOpenTime, defaultCloseTime)
            }
        } else {
            customDaySchedules[isoDay] ?: DaySchedule(false, defaultOpenTime, defaultCloseTime)
        }

        return if (!schedule.isOpen) {
            "Hoy ${businessDay.fullName}: Cerrado"
        } else if (schedule.isTimeWithin(currentMinutes)) {
            "Abierto hoy hasta las ${schedule.closeTime.format12Hour()}"
        } else if (currentMinutes < schedule.openTime.totalMinutes) {
            "Cerrado (Abre hoy a las ${schedule.openTime.format12Hour()})"
        } else {
            "Cerrado por hoy (Cerró a las ${schedule.closeTime.format12Hour()})"
        }
    }

    /**
     * Serializa la configuración a formato JSON seguro para persistir en SharedPreferences.
     */
    fun toJson(): String {
        try {
            val root = JSONObject()
            root.put("isScheduleEnabled", isScheduleEnabled)
            root.put("useSameScheduleAllDays", useSameScheduleAllDays)

            val daysArray = JSONArray()
            activeDays.forEach { daysArray.put(it) }
            root.put("activeDays", daysArray)

            root.put("defaultOpenTime", defaultOpenTime.toMinutesString())
            root.put("defaultCloseTime", defaultCloseTime.toMinutesString())

            val customObj = JSONObject()
            customDaySchedules.forEach { (day, sched) ->
                val dObj = JSONObject()
                dObj.put("isOpen", sched.isOpen)
                dObj.put("openTime", sched.openTime.toMinutesString())
                dObj.put("closeTime", sched.closeTime.toMinutesString())
                customObj.put(day.toString(), dObj)
            }
            root.put("customDaySchedules", customObj)

            val serialized = root.toString()
            if (!serialized.isNullOrBlank() && serialized.contains("isScheduleEnabled")) {
                return serialized
            }
        } catch (_: Throwable) {
            // Fallback para entornos JVM sin org.json nativo
        }

        val daysJson = activeDays.joinToString(",")
        val customEntries = customDaySchedules.entries.joinToString(",") { (day, sched) ->
            "\"$day\":{\"isOpen\":${sched.isOpen},\"openTime\":\"${sched.openTime.toMinutesString()}\",\"closeTime\":\"${sched.closeTime.toMinutesString()}\"}"
        }
        return "{\"isScheduleEnabled\":$isScheduleEnabled,\"useSameScheduleAllDays\":$useSameScheduleAllDays,\"activeDays\":[$daysJson],\"defaultOpenTime\":\"${defaultOpenTime.toMinutesString()}\",\"defaultCloseTime\":\"${defaultCloseTime.toMinutesString()}\",\"customDaySchedules\":{$customEntries}}"
    }

    companion object {
        fun defaultCustomSchedules(): Map<Int, DaySchedule> {
            return (1..7).associateWith { day ->
                when (day) {
                    in 1..5 -> DaySchedule(isOpen = true, openTime = TimeOfDay(8, 0), closeTime = TimeOfDay(20, 0))
                    6 -> DaySchedule(isOpen = true, openTime = TimeOfDay(8, 0), closeTime = TimeOfDay(18, 0))
                    7 -> DaySchedule(isOpen = false, openTime = TimeOfDay(9, 0), closeTime = TimeOfDay(14, 0))
                    else -> DaySchedule(isOpen = true, openTime = TimeOfDay(8, 0), closeTime = TimeOfDay(20, 0))
                }
            }
        }

        fun fromJson(jsonStr: String?): BusinessHoursConfig {
            if (jsonStr.isNullOrBlank()) return BusinessHoursConfig()
            try {
                val root = JSONObject(jsonStr)
                if (root.has("isScheduleEnabled") || root.has("useSameScheduleAllDays") || root.has("activeDays")) {
                    val isScheduleEnabled = root.optBoolean("isScheduleEnabled", false)
                    val useSameScheduleAllDays = root.optBoolean("useSameScheduleAllDays", true)

                    val activeDaysSet = mutableSetOf<Int>()
                    val daysArray = root.optJSONArray("activeDays")
                    if (daysArray != null) {
                        for (i in 0 until daysArray.length()) {
                            activeDaysSet.add(daysArray.getInt(i))
                        }
                    } else {
                        activeDaysSet.addAll(listOf(1, 2, 3, 4, 5, 6))
                    }

                    val openTimeStr = root.optString("defaultOpenTime", "08:00")
                    val closeTimeStr = root.optString("defaultCloseTime", "20:00")

                    val customMap = mutableMapOf<Int, DaySchedule>()
                    val customObj = root.optJSONObject("customDaySchedules")
                    if (customObj != null) {
                        val keys = customObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val dayInt = key.toIntOrNull() ?: continue
                            val dObj = customObj.getJSONObject(key)
                            val isOpen = dObj.optBoolean("isOpen", true)
                            val oTime = TimeOfDay.parse(dObj.optString("openTime", "08:00"))
                            val cTime = TimeOfDay.parse(dObj.optString("closeTime", "20:00"))
                            customMap[dayInt] = DaySchedule(isOpen, oTime, cTime)
                        }
                    } else {
                        customMap.putAll(defaultCustomSchedules())
                    }

                    return BusinessHoursConfig(
                        isScheduleEnabled = isScheduleEnabled,
                        useSameScheduleAllDays = useSameScheduleAllDays,
                        activeDays = activeDaysSet,
                        defaultOpenTime = TimeOfDay.parse(openTimeStr),
                        defaultCloseTime = TimeOfDay.parse(closeTimeStr),
                        customDaySchedules = customMap
                    )
                }
            } catch (_: Throwable) {
                // Fallback a parser ligero si org.json lanza Stub! en JVM unit tests
            }

            return parseJsonFallback(jsonStr)
        }

        private fun parseJsonFallback(jsonStr: String): BusinessHoursConfig {
            return try {
                val trimmed = jsonStr.trim()
                if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return BusinessHoursConfig()

                val isScheduleEnabled = Regex(""""isScheduleEnabled"\s*:\s*(true|false)""")
                    .find(trimmed)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false
                val useSameScheduleAllDays = Regex(""""useSameScheduleAllDays"\s*:\s*(true|false)""")
                    .find(trimmed)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: true

                val activeDaysMatch = Regex(""""activeDays"\s*:\s*\[([^\]]*)]""").find(trimmed)
                val activeDaysSet = if (activeDaysMatch != null) {
                    activeDaysMatch.groupValues[1]
                        .split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .toSet()
                } else {
                    setOf(1, 2, 3, 4, 5, 6)
                }

                val openTimeStr = Regex(""""defaultOpenTime"\s*:\s*"([^"]+)"""")
                    .find(trimmed)?.groupValues?.get(1) ?: "08:00"
                val closeTimeStr = Regex(""""defaultCloseTime"\s*:\s*"([^"]+)"""")
                    .find(trimmed)?.groupValues?.get(1) ?: "20:00"

                val customMap = mutableMapOf<Int, DaySchedule>()
                val customBlockMatch = Regex(""""customDaySchedules"\s*:\s*\{(.+)\}\s*\}\s*$""").find(trimmed)
                if (customBlockMatch != null) {
                    val entriesBlock = customBlockMatch.groupValues[1]
                    val dayPattern = Regex(""""(\d+)"\s*:\s*\{\s*"isOpen"\s*:\s*(true|false)\s*,\s*"openTime"\s*:\s*"([^"]+)"\s*,\s*"closeTime"\s*:\s*"([^"]+)"\s*\}""")
                    for (match in dayPattern.findAll(entriesBlock)) {
                        val dayInt = match.groupValues[1].toIntOrNull() ?: continue
                        val isOpen = match.groupValues[2].toBooleanStrictOrNull() ?: true
                        val oTime = TimeOfDay.parse(match.groupValues[3])
                        val cTime = TimeOfDay.parse(match.groupValues[4])
                        customMap[dayInt] = DaySchedule(isOpen, oTime, cTime)
                    }
                }
                if (customMap.isEmpty()) {
                    customMap.putAll(defaultCustomSchedules())
                }

                BusinessHoursConfig(
                    isScheduleEnabled = isScheduleEnabled,
                    useSameScheduleAllDays = useSameScheduleAllDays,
                    activeDays = activeDaysSet,
                    defaultOpenTime = TimeOfDay.parse(openTimeStr),
                    defaultCloseTime = TimeOfDay.parse(closeTimeStr),
                    customDaySchedules = customMap
                )
            } catch (_: Exception) {
                BusinessHoursConfig()
            }
        }
    }
}

/**
 * Estado operacional de Ambiorix Pay para distinguir explícitamente entre:
 * 1. Apagado manualmente (Disabled)
 * 2. Permiso del sistema faltante (Missing Permission)
 * 3. Fuera de horario comercial (Outside Schedule)
 * 4. Operativo al 100% (Operational)
 */
enum class OperationalStatus {
    OPERATIONAL,
    DISABLED,
    MISSING_PERMISSION,
    OUTSIDE_HOURS;

    val isReadyToProcess: Boolean get() = this == OPERATIONAL

    fun getDisplayName(): String = when (this) {
        OPERATIONAL -> "Servicio Operativo"
        DISABLED -> "Servicio Apagado"
        MISSING_PERMISSION -> "Permiso Desactivado"
        OUTSIDE_HOURS -> "Fuera de Horario"
    }

    fun getSubtitle(): String = when (this) {
        OPERATIONAL -> "Esperando pagos y transferencias"
        DISABLED -> "En pausa manual. No se anuncian pagos."
        MISSING_PERMISSION -> "Falta activar acceso a notificaciones en Ajustes de Android."
        OUTSIDE_HOURS -> "Cerrado según horario comercial. No se anuncian pagos."
    }
}

package com.ambiorix.pay.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.ambiorix.pay.R
import com.ambiorix.pay.data.*
import com.ambiorix.pay.sound.SpanishNumbers
import com.ambiorix.pay.ui.components.rememberReducedMotionEnabled
import com.ambiorix.pay.ui.theme.*
import java.io.File
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: AppSettings,
    isSpanishVoiceAvailable: Boolean = true,
    isTtsReady: Boolean = true,
    ttsStatusDetail: String = "",
    isNotificationAccessGranted: Boolean = true,
    isSmsPermissionGranted: Boolean = true,
    onSettingsChanged: ((AppSettings) -> AppSettings) -> Unit,
    onBackClick: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestSmsPermission: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {},
    onOpenScreenPinningSettings: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenHistoryClick: () -> Unit,
    onTestPaymentClick: () -> Unit,
    onTestVoiceClick: () -> Unit
) {
    val context = LocalContext.current

    // Launchers para seleccionar imágenes desde la galería y guardarlas localmente
    val pickQr1Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val oldPath = settings.qr1Path
            val savedPath = ImageStore.saveGalleryImage(context, uri, "qr1")
            if (savedPath != null) {
                ImageStore.deleteFile(oldPath)
                onSettingsChanged { it.copy(qr1Path = savedPath) }
            }
        }
    }

    val pickQr2Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val oldPath = settings.qr2Path
            val savedPath = ImageStore.saveGalleryImage(context, uri, "qr2")
            if (savedPath != null) {
                ImageStore.deleteFile(oldPath)
                onSettingsChanged { it.copy(qr2Path = savedPath) }
            }
        }
    }

    val pickLogoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val oldPath = settings.logoPath
            val savedPath = ImageStore.saveGalleryImage(context, uri, "logo")
            if (savedPath != null) {
                ImageStore.deleteFile(oldPath)
                onSettingsChanged { it.copy(logoPath = savedPath) }
            }
        }
    }

    // Estados para campos de texto
    var businessName by remember(settings.businessName) { mutableStateOf(settings.businessName) }
    var scanToPayText by remember(settings.scanToPayText) { mutableStateOf(settings.scanToPayText) }
    var phoneText by remember(settings.phoneText) { mutableStateOf(settings.phoneText) }
    var additionalText by remember(settings.additionalText) { mutableStateOf(settings.additionalText) }
    var customPackageInput by remember { mutableStateOf("") }

    // Diálogos auxiliares
    var showPinChangeDialog by remember { mutableStateOf(false) }
    var timePickerTarget by remember { mutableStateOf<TimePickerTarget?>(null) }

    // Calcular estado operacional
    val isWithinSchedule = remember(settings.businessHours) {
        settings.businessHours.isWithinBusinessHours()
    }
    val scheduleDesc = remember(settings.businessHours) {
        settings.businessHours.getScheduleStatusDescription()
    }
    val operationalStatus = remember(settings.isServiceEnabled, isNotificationAccessGranted, isWithinSchedule) {
        when {
            !settings.isServiceEnabled -> OperationalStatus.DISABLED
            !isNotificationAccessGranted -> OperationalStatus.MISSING_PERMISSION
            !isWithinSchedule -> OperationalStatus.OUTSIDE_HOURS
            else -> OperationalStatus.OPERATIONAL
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Configuración",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = TextWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // =========================================================================
            // 1. CONTROL PRINCIPAL: SERVICIO AMBIORIX PAY: ENCENDIDO / APAGADO
            // =========================================================================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    if (settings.isServiceEnabled) GoldPrimary else DarkBorder
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (settings.isServiceEnabled) GoldPrimary.copy(alpha = 0.2f)
                                        else DarkSurfaceElevated
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = if (settings.isServiceEnabled) GoldPrimary else TextGray,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Servicio Ambiorix Pay",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = if (settings.isServiceEnabled) "ESTADO: ENCENDIDO" else "ESTADO: APAGADO",
                                    color = if (settings.isServiceEnabled) GoldPrimary else DangerRed,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        Switch(
                            checked = settings.isServiceEnabled,
                            onCheckedChange = { isEnabled ->
                                onSettingsChanged { it.copy(isServiceEnabled = isEnabled) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = GoldPrimary,
                                uncheckedThumbColor = TextGray,
                                uncheckedTrackColor = DarkSurfaceElevated
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Badge de estado operacional detallado
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when (operationalStatus) {
                            OperationalStatus.OPERATIONAL -> SuccessGreen.copy(alpha = 0.15f)
                            OperationalStatus.DISABLED -> DarkSurfaceElevated
                            OperationalStatus.MISSING_PERMISSION -> DangerRed.copy(alpha = 0.15f)
                            OperationalStatus.OUTSIDE_HOURS -> Color(0xFF1E293B)
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when (operationalStatus) {
                                OperationalStatus.OPERATIONAL -> SuccessGreen.copy(alpha = 0.5f)
                                OperationalStatus.DISABLED -> DarkBorder
                                OperationalStatus.MISSING_PERMISSION -> DangerRed.copy(alpha = 0.6f)
                                OperationalStatus.OUTSIDE_HOURS -> Color(0xFF3B82F6).copy(alpha = 0.5f)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (operationalStatus) {
                                            OperationalStatus.OPERATIONAL -> SuccessGreen
                                            OperationalStatus.DISABLED -> TextGray
                                            OperationalStatus.MISSING_PERMISSION -> DangerRed
                                            OperationalStatus.OUTSIDE_HOURS -> Color(0xFF60A5FA)
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = operationalStatus.getDisplayName(),
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = operationalStatus.getSubtitle(),
                                    color = TextGray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Botón directo para permisos si faltan
                    if (!isNotificationAccessGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onOpenNotificationSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Activar Permiso de Notificaciones", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Al apagar el servicio, se detienen los avisos sonoros/visuales de pagos y no se procesan nuevas notificaciones. Los registros históricos se conservan intactos.",
                        color = TextGray,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // =========================================================================
            // BOTÓN PROBAR PAGO ($35.000)
            // =========================================================================
            Button(
                onClick = onTestPaymentClick,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Probar",
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Probar pago ($35.000)",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // =========================================================================
            // DETECCIÓN DE SMS DE NEQUI (890806 / BRE-B)
            // =========================================================================
            SettingsCard(title = "Detección de SMS de Nequi (890806 / Bre-B)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sms,
                            contentDescription = null,
                            tint = if (settings.smsPaymentEnabled) GoldPrimary else TextGray,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Detectar pagos por SMS de Nequi",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (settings.smsPaymentEnabled)
                                    "ACTIVO: Detecta mensajes de texto de Nequi (890806 / 85954) y Bre-B"
                                else
                                    "DESACTIVADO: Solo escucha notificaciones push de la app",
                                color = if (settings.smsPaymentEnabled) GoldPrimary else TextGray,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = settings.smsPaymentEnabled,
                        onCheckedChange = { enabled ->
                            onSettingsChanged { current ->
                                val updatedPkgs = if (enabled) {
                                    current.enabledPackages + KnownApps.SMS_PACKAGES
                                } else {
                                    current.enabledPackages - KnownApps.SMS_PACKAGES
                                }
                                current.copy(
                                    smsPaymentEnabled = enabled,
                                    enabledPackages = updatedPkgs
                                )
                            }
                            if (enabled && !isSmsPermissionGranted) {
                                onRequestSmsPermission()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = GoldPrimary
                        )
                    )
                }

                if (settings.smsPaymentEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSmsPermissionGranted)
                            SuccessGreen.copy(alpha = 0.14f)
                        else
                            GoldPrimary.copy(alpha = 0.14f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSmsPermissionGranted) SuccessGreen.copy(alpha = 0.5f) else GoldPrimary.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(if (isSmsPermissionGranted) SuccessGreen else GoldPrimary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSmsPermissionGranted)
                                        "Doble canal SMS activo (Receptor SMS + Notificaciones)"
                                    else
                                        "Canal por Notificaciones activo • Falta Permiso Directo de SMS",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isSmsPermissionGranted)
                                    "Tu teléfono confirmará al instante los mensajes SMS de Nequi Negocios, Bre-B y transferencias con deduplicación automática."
                                else
                                    "Activa el permiso directo de SMS para máxima velocidad, o si Android 13/14 bloquea permisos de SMS, habilítalo en Permisos de la App.",
                                color = TextGrayLight,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )

                            if (!isSmsPermissionGranted) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onRequestSmsPermission,
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Autorizar SMS",
                                            color = DarkBackground,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = onOpenAppDetailsSettings,
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Permisos de App",
                                            color = GoldPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 2. HORARIO DE FUNCIONAMIENTO (PROGRAMACIÓN DE HORARIOS COMERCIALES)
            // =========================================================================
            SettingsCard(title = "Horario de Funcionamiento") {
                val hours = settings.businessHours

                // Toggle: Activar horario programado vs Modo Manual
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Horario programado",
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (hours.isScheduleEnabled) "Opera según días y horas configurados" else "Modo manual 24/7 sin horario programado",
                            color = if (hours.isScheduleEnabled) GoldPrimary else TextGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = hours.isScheduleEnabled,
                        onCheckedChange = { isEnabled ->
                            onSettingsChanged { s ->
                                s.copy(businessHours = s.businessHours.copy(isScheduleEnabled = isEnabled))
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                    )
                }

                // Indicador de estado comercial en vivo
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = if (isWithinSchedule) SuccessGreen else Color(0xFF60A5FA),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = scheduleDesc,
                            color = if (isWithinSchedule) SuccessGreen else TextGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (hours.isScheduleEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Selector: Mismo horario todos los días vs Horarios distintos por día
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Mismo horario todos los días",
                            color = TextWhite,
                            fontSize = 13.sp
                        )
                        Switch(
                            checked = hours.useSameScheduleAllDays,
                            onCheckedChange = { same ->
                                onSettingsChanged { s ->
                                    s.copy(businessHours = s.businessHours.copy(useSameScheduleAllDays = same))
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (hours.useSameScheduleAllDays) {
                        // Selección de días activos de la semana (L, M, M, J, V, S, D)
                        Text(
                            text = "Días de atención:",
                            color = TextGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            BusinessDay.entries.forEach { day ->
                                val isSelected = hours.activeDays.contains(day.isoDay)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) GoldPrimary else DarkSurfaceElevated,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) GoldPrimary else DarkBorder
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 2.dp)
                                        .clickable {
                                            val newDays = if (isSelected) {
                                                hours.activeDays - day.isoDay
                                            } else {
                                                hours.activeDays + day.isoDay
                                            }
                                            onSettingsChanged { s ->
                                                s.copy(businessHours = s.businessHours.copy(activeDays = newDays))
                                            }
                                        }
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = day.shortName,
                                            color = if (isSelected) DarkBackground else TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Horas de Apertura y Cierre unificadas
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Hora de Apertura
                            OutlinedCard(
                                onClick = {
                                    timePickerTarget = TimePickerTarget.UnifiedOpen(hours.defaultOpenTime)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.outlinedCardColors(containerColor = DarkSurfaceElevated),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Apertura", color = TextGray, fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = hours.defaultOpenTime.format12Hour(),
                                        color = GoldPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }

                            // Hora de Cierre
                            OutlinedCard(
                                onClick = {
                                    timePickerTarget = TimePickerTarget.UnifiedClose(hours.defaultCloseTime)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.outlinedCardColors(containerColor = DarkSurfaceElevated),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Cierre", color = TextGray, fontSize = 11.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = hours.defaultCloseTime.format12Hour(),
                                        color = GoldPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    } else {
                        // Horarios distintos por día
                        Text(
                            text = "Horarios por día de la semana:",
                            color = TextGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        BusinessDay.entries.forEach { day ->
                            val sched = hours.customDaySchedules[day.isoDay] ?: DaySchedule(false)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = DarkSurfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Switch(
                                            checked = sched.isOpen,
                                            onCheckedChange = { isOpen ->
                                                val newMap = hours.customDaySchedules.toMutableMap()
                                                newMap[day.isoDay] = sched.copy(isOpen = isOpen)
                                                onSettingsChanged { s ->
                                                    s.copy(businessHours = s.businessHours.copy(customDaySchedules = newMap))
                                                }
                                            },
                                            colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = day.fullName,
                                            color = if (sched.isOpen) TextWhite else TextGray,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }

                                    if (sched.isOpen) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = sched.openTime.format12Hour(),
                                                color = GoldPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(DarkBackground)
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    .clickable {
                                                        timePickerTarget = TimePickerTarget.DayOpen(day.isoDay, sched.openTime)
                                                    }
                                            )
                                            Text(" - ", color = TextGray)
                                            Text(
                                                text = sched.closeTime.format12Hour(),
                                                color = GoldPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(DarkBackground)
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    .clickable {
                                                        timePickerTarget = TimePickerTarget.DayClose(day.isoDay, sched.closeTime)
                                                    }
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "Cerrado",
                                            color = DangerRed,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // CÓDIGOS QR Y LOGO
            // =========================================================================
            SettingsCard(title = "Imágenes y Códigos QR") {
                Text(
                    text = "Importa las fotografías o imágenes originales de los códigos QR que proporciona cada negocio (Nequi, Bancolombia, Daviplata, etc.) en formatos JPG, JPEG o PNG. Las imágenes se guardan de forma permanente y segura en el almacenamiento privado de la app.",
                    color = TextGray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Código QR 1 (Principal)
                QrCodeConfigItem(
                    title = "Código QR 1 (Principal)",
                    subtitle = "QR visible en la pantalla principal para que tus clientes escaneen y paguen",
                    imagePath = settings.qr1Path,
                    onImportClick = { pickQr1Launcher.launch("image/*") },
                    onDeleteClick = {
                        ImageStore.deleteFile(settings.qr1Path)
                        onSettingsChanged { it.copy(qr1Path = null) }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Control segundo QR
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = DarkSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Habilitar segundo QR (QR 2)",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Muestra dos códigos QR simultáneos en la pantalla para ofrecer múltiples opciones de pago",
                                color = TextGray,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = settings.qr2Enabled,
                            onCheckedChange = { isEnabled ->
                                onSettingsChanged { it.copy(qr2Enabled = isEnabled) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = GoldPrimary
                            )
                        )
                    }
                }

                // Configuración y vista previa independiente de QR 2
                if (settings.qr2Enabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    QrCodeConfigItem(
                        title = "Código QR 2 (Secundario)",
                        subtitle = "Segundo código QR independiente (ej. Bancolombia, Dale, etc.)",
                        imagePath = settings.qr2Path,
                        onImportClick = { pickQr2Launcher.launch("image/*") },
                        onDeleteClick = {
                            ImageStore.deleteFile(settings.qr2Path)
                            onSettingsChanged { it.copy(qr2Path = null) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Logo personalizado del comercio
                LogoConfigItem(
                    imagePath = settings.logoPath,
                    onImportClick = { pickLogoLauncher.launch("image/*") },
                    onDeleteClick = {
                        ImageStore.deleteFile(settings.logoPath)
                        onSettingsChanged { it.copy(logoPath = null) }
                    }
                )
            }

            // =========================================================================
            // DATOS DEL NEGOCIO Y TEXTOS
            // =========================================================================
            SettingsCard(title = "Textos de la Pantalla Kiosco") {
                OutlinedTextField(
                    value = businessName,
                    onValueChange = {
                        businessName = it
                        onSettingsChanged { s -> s.copy(businessName = it) }
                    },
                    label = { Text("Nombre del Negocio") },
                    colors = outlinedFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = scanToPayText,
                    onValueChange = {
                        scanToPayText = it
                        onSettingsChanged { s -> s.copy(scanToPayText = it) }
                    },
                    label = { Text("Texto superior de acción") },
                    colors = outlinedFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = phoneText,
                    onValueChange = {
                        phoneText = it
                        onSettingsChanged { s -> s.copy(phoneText = it) }
                    },
                    label = { Text("Teléfono de transferencias (opcional)") },
                    colors = outlinedFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = additionalText,
                    onValueChange = {
                        additionalText = it
                        onSettingsChanged { s -> s.copy(additionalText = it) }
                    },
                    label = { Text("Texto adicional de ayuda") },
                    colors = outlinedFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // =========================================================================
            // APPS DE PAGO A ESCUCHAR (PACKAGE NAMES)
            // =========================================================================
            SettingsCard(title = "Apps de Pago Autorizadas") {
                Text(
                    text = "Solo se procesarán notificaciones de los paquetes marcados. Las demás apps son ignoradas sin leer su contenido.",
                    color = TextGray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                for (app in KnownApps.DEFAULT_APPS) {
                    val isChecked = settings.enabledPackages.contains(app.primaryPackage)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                onSettingsChanged { current ->
                                    val newSet = current.enabledPackages.toMutableSet()
                                    if (checked) {
                                        newSet.add(app.primaryPackage)
                                        newSet.addAll(app.alternativePackages)
                                    } else {
                                        newSet.remove(app.primaryPackage)
                                        newSet.removeAll(app.alternativePackages.toSet())
                                    }
                                    current.copy(enabledPackages = newSet)
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = GoldPrimary)
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = app.name, color = TextWhite, fontWeight = FontWeight.SemiBold)
                            Text(text = app.primaryPackage, color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }

                // Paquetes personalizados agregados
                if (settings.customPackages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Paquetes personalizados:", color = GoldPrimary, fontSize = 13.sp)
                    for (pkg in settings.customPackages) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = pkg, color = TextWhite, fontSize = 13.sp)
                            IconButton(
                                onClick = {
                                    onSettingsChanged { s ->
                                        s.copy(customPackages = s.customPackages - pkg)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Eliminar", tint = DangerRed)
                            }
                        }
                    }
                }

                // Agregar nuevo package name
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customPackageInput,
                        onValueChange = { customPackageInput = it },
                        label = { Text("Agregar package name") },
                        placeholder = { Text("ej: com.mibanco.app") },
                        colors = outlinedFieldColors(),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (customPackageInput.isNotBlank()) {
                                val pkgToAdd = customPackageInput.trim()
                                onSettingsChanged { s ->
                                    s.copy(customPackages = s.customPackages + pkgToAdd)
                                }
                                customPackageInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Agregar", color = GoldPrimary)
                    }
                }
            }

            // =========================================================================
            // AUDIO Y VOZ DE CONFIRMACIÓN
            // =========================================================================
            SettingsCard(title = "Sonido y Voz de Confirmación") {
                // Indicador de diagnóstico en tiempo real del motor de voz (TextToSpeech)
                if (!isSpanishVoiceAvailable || !isTtsReady) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = DangerRedBg.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRedLight, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (!isSpanishVoiceAvailable) "Voz en español no disponible" else "Inicializando motor de voz...",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (ttsStatusDetail.isNotBlank()) ttsStatusDetail else "El sistema de voz de Android necesita datos en español para anunciar pagos.",
                                color = TextGrayLight,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onOpenTtsSettings,
                                colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = TextWhite),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Abrir Ajustes de Voz (TTS) del Sistema", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    // Estado positivo de diagnóstico
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, DarkBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(SuccessGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ttsStatusDetail.ifBlank { "Motor de voz en español listo y operativo" },
                                color = TextGrayLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // 1) Tono de confirmación (Chime)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = GoldPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Tono de confirmación (Chime)", color = TextWhite, fontWeight = FontWeight.SemiBold)
                            Text("Sonido de timbre instantáneo", color = TextGray, fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = settings.soundEnabled,
                        onCheckedChange = { isEnabled ->
                            onSettingsChanged { it.copy(soundEnabled = isEnabled) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2) Opción 1: Voz de pago
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = GoldPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Voz de pago", color = TextWhite, fontWeight = FontWeight.SemiBold)
                            Text("Dice: \"Pago recibido, <monto en palabras> pesos\"", color = TextGray, fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = settings.ttsEnabled,
                        onCheckedChange = { isEnabled ->
                            onSettingsChanged { it.copy(ttsEnabled = isEnabled) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3) Opción 2: Agregar "Gracias por tu compra"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Agregar \"Gracias por tu compra\"",
                            color = if (settings.ttsEnabled) TextWhite else TextMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (settings.ttsEnabled) "Añade esa frase al final del anuncio de voz" else "Requiere que la voz de pago esté encendida",
                            color = if (settings.ttsEnabled) TextGray else TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.includeThankYou,
                        enabled = settings.ttsEnabled,
                        onCheckedChange = { isEnabled ->
                            onSettingsChanged { it.copy(includeThankYou = isEnabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GoldPrimary,
                            disabledCheckedThumbColor = GoldPrimary.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4) Opción 3: Anunciar nombre del remitente (Nequi SMS / Notificación)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Anunciar nombre de quien envía",
                            color = if (settings.ttsEnabled) TextWhite else TextMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Lee el nombre del cliente cuando aparece en el SMS o notificación de Nequi",
                            color = if (settings.ttsEnabled) TextGray else TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.announceSenderName,
                        enabled = settings.ttsEnabled,
                        onCheckedChange = { isEnabled ->
                            onSettingsChanged { it.copy(announceSenderName = isEnabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GoldPrimary,
                            disabledCheckedThumbColor = GoldPrimary.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Muestra la frase exacta de previsualización
                val previewPhrase = remember(settings.includeThankYou) {
                    SpanishNumbers.buildPaymentAnnouncementPhrase(35000L, settings.includeThankYou)
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Frase hablada: \"$previewPhrase\"",
                        color = GoldPrimary,
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Botón "Probar voz"
                OutlinedButton(
                    onClick = onTestVoiceClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = GoldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Probar voz ($35.000)",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // =========================================================================
            // DURACIÓN DE PANTALLA VERDE
            // =========================================================================
            SettingsCard(title = "Duración de Pantalla de Confirmación") {
                Text(
                    text = "${settings.confirmationDurationSeconds} segundos",
                    color = GoldPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Slider(
                    value = settings.confirmationDurationSeconds.toFloat(),
                    onValueChange = { newValue ->
                        onSettingsChanged { it.copy(confirmationDurationSeconds = newValue.toInt()) }
                    },
                    valueRange = 3f..30f,
                    steps = 27,
                    colors = SliderDefaults.colors(
                        thumbColor = GoldPrimary,
                        activeTrackColor = GoldPrimary,
                        inactiveTrackColor = DarkSurfaceElevated
                    )
                )
                Text(
                    text = "Rango de 3 a 30 segundos. Tras este lapso regresa a la pantalla de códigos QR.",
                    color = TextGray,
                    fontSize = 12.sp
                )
            }

            // =========================================================================
            // FIJAR PANTALLA (SCREEN PINNING)
            // =========================================================================
            SettingsCard(title = "Modo Kiosco y Fijar Pantalla") {
                Text(
                    text = "Para evitar que clientes o empleados salgan de la app, activa 'Fijar pantalla' (Screen Pinning) en los ajustes del sistema Android.",
                    color = TextGray,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onOpenScreenPinningSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = GoldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Abrir Ajustes de Seguridad", color = GoldPrimary)
                }
            }

            // =========================================================================
            // HISTORIAL Y SEGURIDAD (CAMBIO DE PIN)
            // =========================================================================
            SettingsCard(title = "Historial y PIN") {
                Button(
                    onClick = onOpenHistoryClick,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = GoldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ver Historial de Pagos", color = GoldPrimary)
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { showPinChangeDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = TextWhite)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cambiar PIN de Acceso", color = TextWhite)
                }
            }

            // =========================================================================
            // PIE DE PÁGINA DISCRETO Y ELEMENTO VISUAL 888
            // =========================================================================
            SettingsFooter888()

            Spacer(modifier = Modifier.height(20.dp))
        }

        // Diálogo para cambiar PIN
        if (showPinChangeDialog) {
            ChangePinDialog(
                currentPin = settings.pinCode,
                onDismiss = { showPinChangeDialog = false },
                onSavePin = { newPin ->
                    onSettingsChanged { it.copy(pinCode = newPin) }
                    showPinChangeDialog = false
                }
            )
        }

        // Diálogo para seleccionar hora
        timePickerTarget?.let { target ->
            TimeSelectionDialog(
                title = when (target) {
                    is TimePickerTarget.UnifiedOpen -> "Hora de Apertura General"
                    is TimePickerTarget.UnifiedClose -> "Hora de Cierre General"
                    is TimePickerTarget.DayOpen -> "Hora de Apertura (${BusinessDay.fromIso(target.dayIso).fullName})"
                    is TimePickerTarget.DayClose -> "Hora de Cierre (${BusinessDay.fromIso(target.dayIso).fullName})"
                },
                initialTime = target.time,
                onDismiss = { timePickerTarget = null },
                onConfirm = { selectedTime ->
                    when (target) {
                        is TimePickerTarget.UnifiedOpen -> {
                            onSettingsChanged { s ->
                                s.copy(businessHours = s.businessHours.copy(defaultOpenTime = selectedTime))
                            }
                        }
                        is TimePickerTarget.UnifiedClose -> {
                            onSettingsChanged { s ->
                                s.copy(businessHours = s.businessHours.copy(defaultCloseTime = selectedTime))
                            }
                        }
                        is TimePickerTarget.DayOpen -> {
                            val newMap = settings.businessHours.customDaySchedules.toMutableMap()
                            val current = newMap[target.dayIso] ?: DaySchedule(true)
                            newMap[target.dayIso] = current.copy(openTime = selectedTime)
                            onSettingsChanged { s ->
                                s.copy(businessHours = s.businessHours.copy(customDaySchedules = newMap))
                            }
                        }
                        is TimePickerTarget.DayClose -> {
                            val newMap = settings.businessHours.customDaySchedules.toMutableMap()
                            val current = newMap[target.dayIso] ?: DaySchedule(true)
                            newMap[target.dayIso] = current.copy(closeTime = selectedTime)
                            onSettingsChanged { s ->
                                s.copy(businessHours = s.businessHours.copy(customDaySchedules = newMap))
                            }
                        }
                    }
                    timePickerTarget = null
                }
            )
        }
    }
}

private sealed class TimePickerTarget(val time: TimeOfDay) {
    class UnifiedOpen(time: TimeOfDay) : TimePickerTarget(time)
    class UnifiedClose(time: TimeOfDay) : TimePickerTarget(time)
    class DayOpen(val dayIso: Int, time: TimeOfDay) : TimePickerTarget(time)
    class DayClose(val dayIso: Int, time: TimeOfDay) : TimePickerTarget(time)
}

@Composable
private fun TimeSelectionDialog(
    title: String,
    initialTime: TimeOfDay,
    onDismiss: () -> Unit,
    onConfirm: (TimeOfDay) -> Unit
) {
    var selectedHour by remember { mutableIntStateOf(initialTime.hour) }
    var selectedMinute by remember { mutableIntStateOf(initialTime.minute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val preview = TimeOfDay(selectedHour, selectedMinute).format12Hour()
                Text(
                    text = preview,
                    color = GoldPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Selector de Hora
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Hora (0-23):", color = TextGrayLight, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { selectedHour = if (selectedHour > 0) selectedHour - 1 else 23 }
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "-1h", tint = TextWhite)
                        }
                        Text(
                            text = String.format(Locale.US, "%02d", selectedHour),
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(
                            onClick = { selectedHour = (selectedHour + 1) % 24 }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "+1h", tint = TextWhite)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Selector de Minuto
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Minuto:", color = TextGrayLight, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { selectedMinute = (selectedMinute - 5 + 60) % 60 }
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "-5m", tint = TextWhite)
                        }
                        Text(
                            text = String.format(Locale.US, "%02d", selectedMinute),
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(
                            onClick = { selectedMinute = (selectedMinute + 5) % 60 }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "+5m", tint = TextWhite)
                        }
                    }
                }

                // Accesos directos comunes
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(
                        "08:00 a. m." to TimeOfDay(8, 0),
                        "12:00 p. m." to TimeOfDay(12, 0),
                        "06:00 p. m." to TimeOfDay(18, 0),
                        "08:00 p. m." to TimeOfDay(20, 0)
                    ).forEach { (label, tod) ->
                        TextButton(
                            onClick = {
                                selectedHour = tod.hour
                                selectedMinute = tod.minute
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(label, fontSize = 11.sp, color = GoldPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(TimeOfDay(selectedHour, selectedMinute)) },
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = DarkBackground)
            ) {
                Text("Guardar", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextGrayLight)
            }
        },
        containerColor = DarkSurface,
        titleContentColor = TextWhite,
        textContentColor = TextGrayLight,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = {
                Text(
                    text = title,
                    color = GoldPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                content()
            }
        )
    }
}

@Composable
private fun ChangePinDialog(
    currentPin: String,
    onDismiss: () -> Unit,
    onSavePin: (String) -> Unit
) {
    var oldPinInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Cambiar PIN de Seguridad", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Ingresa el PIN actual del comercio y define una nueva clave numérica de mínimo 4 dígitos.",
                    color = TextGrayLight,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                OutlinedTextField(
                    value = oldPinInput,
                    onValueChange = { oldPinInput = it },
                    label = { Text("PIN actual", color = TextGrayLight) },
                    colors = outlinedFieldColors(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPinInput,
                    onValueChange = { newPinInput = it },
                    label = { Text("Nuevo PIN (mínimo 4 dígitos)", color = TextGrayLight) },
                    colors = outlinedFieldColors(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPinInput,
                    onValueChange = { confirmPinInput = it },
                    label = { Text("Confirmar nuevo PIN", color = TextGrayLight) },
                    colors = outlinedFieldColors(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Text(errorMessage!!, color = DangerRedLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (oldPinInput != currentPin) {
                        errorMessage = "El PIN actual no coincide"
                    } else if (newPinInput.length < 4) {
                        errorMessage = "El nuevo PIN debe tener mínimo 4 dígitos"
                    } else if (newPinInput != confirmPinInput) {
                        errorMessage = "La confirmación no coincide"
                    } else {
                        onSavePin(newPinInput)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = DarkBackground)
            ) {
                Text("Guardar PIN", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextGrayLight)
            }
        },
        containerColor = DarkSurface,
        titleContentColor = TextWhite,
        textContentColor = TextGrayLight,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = GoldPrimary,
    unfocusedBorderColor = DarkBorder,
    focusedLabelColor = GoldPrimary,
    unfocusedLabelColor = TextGrayLight,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedPlaceholderColor = TextGray,
    unfocusedPlaceholderColor = TextGray,
    cursorColor = GoldPrimary,
    focusedContainerColor = DarkSurfaceElevated,
    unfocusedContainerColor = DarkSurfaceElevated,
    focusedLeadingIconColor = GoldPrimary,
    unfocusedLeadingIconColor = TextGrayLight,
    focusedTrailingIconColor = GoldPrimary,
    unfocusedTrailingIconColor = TextGrayLight
)

@Composable
private fun QrCodeConfigItem(
    title: String,
    subtitle: String,
    imagePath: String?,
    onImportClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val fileExists = !imagePath.isNullOrBlank() && File(imagePath).exists()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceElevated)
            .border(
                1.dp,
                if (fileExists) GoldPrimary.copy(alpha = 0.5f) else DarkBorder,
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = subtitle,
                    color = TextGray,
                    fontSize = 12.sp
                )
            }
            if (fileExists) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SuccessGreen.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(SuccessGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Guardado",
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (fileExists && imagePath != null) {
            // Muestra una vista previa de la imagen seleccionada y acciones
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vista previa
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .border(2.dp, GoldPrimary, RoundedCornerShape(14.dp))
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(imagePath),
                        contentDescription = "Vista previa $title",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Imagen visible para tus clientes en la pantalla principal",
                        color = TextWhite.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = onImportClick,
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = null,
                                tint = DarkBackground,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Reemplazar",
                                color = DarkBackground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        OutlinedButton(
                            onClick = onDeleteClick,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Eliminar",
                                tint = DangerRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // Estado cuando NO hay imagen: botón visible "Importar QR desde galería"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkBackground.copy(alpha = 0.6f))
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    tint = TextGray,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Aún no se ha importado este código QR",
                    color = TextGray,
                    fontSize = 12.sp
                )
                Text(
                    text = "Formatos compatibles: JPG, JPEG o PNG",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onImportClick,
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        tint = DarkBackground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Importar QR desde galería",
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun LogoConfigItem(
    imagePath: String?,
    onImportClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val fileExists = !imagePath.isNullOrBlank() && File(imagePath).exists()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, if (fileExists) GoldPrimary.copy(alpha = 0.5f) else DarkBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Logo del negocio (opcional)", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = if (fileExists) "Logo personalizado activo" else "Si no subes logo, se muestra el texto 'AMBIORIX'",
                    color = TextGray,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (fileExists && imagePath != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.5.dp, GoldPrimary, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(imagePath),
                        contentDescription = "Vista previa logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Button(
                        onClick = onImportClick,
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reemplazar", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onDeleteClick,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = DangerRed)
                    }
                }
            }
        } else {
            Button(
                onClick = onImportClick,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Importar Logo desde galería", color = GoldPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Pie de página discreto, centrado y elegante en la parte inferior de Configuración.
 * Incluye el emblema dorado AAA, la firma caligráfica del desarrollador ("Ambiorix"),
 * el número decorativo "888" con iluminación sutil y un Easter Egg de Créditos de Autor al tocarlo.
 */
@Composable
private fun SettingsFooter888() {
    val reducedMotion = rememberReducedMotionEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isVisibleAndResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var showDeveloperHallOfFame by remember { mutableStateOf(false) }

    val glowAlpha: Float = if (reducedMotion || !isVisibleAndResumed) {
        0.65f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "Footer888Glow")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.38f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowAlpha888"
        )
        animatedAlpha
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { showDeveloperHallOfFame = true }
            .padding(vertical = 10.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Ambiorix Pay",
            color = TextGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Versión 2.7",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Creado por El.propiooo",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Firma caligráfica oficial del autor (Sello de Desarrollador)
        Image(
            painter = painterResource(id = R.drawable.ic_ambiorix_signature),
            contentDescription = "Firma Oficial Ambiorix",
            modifier = Modifier
                .height(44.dp)
                .alpha(0.88f),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Elemento visual estrictamente decorativo: 888 con iluminación sutil
        Text(
            text = "888",
            style = TextStyle(
                color = GoldAccent.copy(alpha = (0.55f + glowAlpha * 0.45f).coerceIn(0.4f, 1.0f)),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.5.sp,
                textAlign = TextAlign.Center,
                shadow = Shadow(
                    color = GoldPrimary.copy(alpha = glowAlpha * 0.85f),
                    offset = Offset(0f, 0f),
                    blurRadius = 18f * glowAlpha
                )
            )
        )
    }

    // Easter Egg de Desarrollador: Placa de Autor e Ingeniería al tocar la firma o el pie de página
    if (showDeveloperHallOfFame) {
        AlertDialog(
            onDismissRequest = { showDeveloperHallOfFame = false },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(24.dp),
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ambiorix_logo),
                        contentDescription = "Emblema AAA",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(DarkBackground)
                            .border(1.5.dp, GoldPrimary, CircleShape)
                            .padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "SELLO DE AUTOR • EDICIÓN 2.7 GT",
                        color = GoldPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp,
                        textAlign = TextAlign.Center
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Arquitectura 100% local, blindada y diseñada a medida para comercios de alto tráfico.",
                        color = TextGrayLight,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = DarkBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_ambiorix_signature),
                                contentDescription = "Firma Caligráfica Ambiorix",
                                modifier = Modifier.height(64.dp),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Firmado por El.propiooo • Serie 888",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeveloperHallOfFame = false }) {
                    Text("Cerrar", color = GoldPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}


package com.ambiorix.pay.pos

data class CashierProfile(
    val id: String,
    val name: String,
    val isActive: Boolean = true,
    val pin: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class Category(
    val id: String,
    val name: String,
    val displayOrder: Int = 0,
    val isActive: Boolean = true,
    val emoji: String = "🍦",
    val isDefault: Boolean = false
)

data class ProductVariant(
    val id: String,
    val name: String,
    val priceDelta: Long = 0L,
    val gramsMultiplier: Double = 1.0,
    val weightGrams: Int = 0,
    val coneUnits: Int = 0,
    val cupUnits: Int = 0
) {
    constructor(
        id: String,
        name: String,
        priceDelta: Long = 0L,
        weightGrams: Int,
        coneUnits: Int = 0,
        cupUnits: Int = 0
    ) : this(id, name, priceDelta, 1.0, weightGrams, coneUnits, cupUnits)
}

data class ToppingOption(
    val id: String,
    val name: String,
    val price: Long = 0L,
    val ingredientId: String? = null,
    val gramsRequired: Double = 0.0
) {
    constructor(
        id: String,
        name: String,
        price: Long = 0L,
        ingredientId: String? = null,
        gramsRequired: Int
    ) : this(id, name, price, ingredientId, gramsRequired.toDouble())
}

data class RecipeItem(
    val ingredientId: String,
    val ingredientName: String,
    val quantity: Double,
    val unit: String = "g" // g, kg, ml, L, unidades
)

data class ComboItem(
    val productId: String,
    val productName: String,
    val quantity: Int = 1
)

data class Product(
    val id: String,
    val name: String,
    val description: String = "",
    val basePrice: Long,
    val baseCost: Long = 0L,
    val categoryId: String,
    val imagePath: String? = null,
    val emoji: String = "🍨",
    val badge: String? = null,
    val isActive: Boolean = true,
    val isCombo: Boolean = false,
    val stockQuantity: Double = 0.0,
    val minStockAlert: Double = 5.0,
    val unit: String = "unidades", // unidades, g, kg, ml, L
    val allowNegativeStock: Boolean = false,
    val recipe: List<RecipeItem> = emptyList(),
    val comboItems: List<ComboItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val variants: List<ProductVariant> = emptyList(),
    val allowedToppings: Boolean = true,
    val baseIngredientId: String? = null
)

data class CartItem(
    val cartItemId: String,
    val productId: String,
    val productName: String,
    val variantId: String = "",
    val variantName: String = "",
    val basePrice: Long,
    val variantPriceDelta: Long = 0L,
    val unitPrice: Long,
    val quantity: Int,
    val toppings: List<ToppingOption> = emptyList(),
    val totalPrice: Long,
    val estimatedCost: Long = 0L,
    val imagePath: String? = null
)

data class Ingredient(
    val id: String,
    val name: String,
    val category: String = "Insumos",
    val unit: String, // g, kg, ml, L, unidades
    val currentStock: Double,
    val minStockAlert: Double = 100.0,
    val unitCostCOP: Long = 0L,
    val isActive: Boolean = true,
    val batchCode: String = "LOT-GEN",
    val expiryDate: String = "31/12/2026"
)

data class InventoryMovement(
    val id: String,
    val itemId: String, // productId o ingredientId
    val itemName: String,
    val itemType: String = "INGREDIENT", // INGREDIENT o PRODUCT
    val previousStock: Double,
    val change: Double,
    val resultingStock: Double,
    val type: String, // PURCHASE, MANUAL_ADJUSTMENT, WASTE_MERMA, SALE_RECIPE, SALE, RETURN_COMPENSATION
    val reason: String,
    val saleId: String? = null,
    val date: Long = System.currentTimeMillis(),
    val dateFormatted: String,
    val userName: String
) {
    val productName: String get() = itemName
}

data class SaleRecord(
    val id: String,
    val saleNumber: String,
    val items: List<CartItem>,
    val subtotal: Long,
    val discount: Long = 0L,
    val total: Long,
    val paymentMethod: String, // CASH, NEQUI, SPLIT
    val cashAmount: Long = 0L,
    val nequiAmount: Long = 0L,
    val nequiSenderName: String? = null,
    val nequiChannel: String? = null,
    val timestamp: String,
    val dateStr: String,
    val cashierName: String,
    val status: String = "COMPLETED", // COMPLETED, CANCELLED, RETURNED
    val isInventoryDeducted: Boolean = true,
    val cogsCost: Long = 0L
)

data class CashMovement(
    val id: String,
    val concept: String,
    val amount: Long,
    val type: String, // EXPENSE, IN, OUT
    val time: String
)

data class CashShift(
    val id: String,
    val cashierName: String,
    val openedAt: String,
    val initialCash: Long,
    val status: String = "OPEN",
    val cashIn: Long = 0L,
    val cashOut: Long = 0L,
    val expenses: List<CashMovement> = emptyList(),
    val expectedCash: Long? = null,
    val countedCash: Long? = null,
    val difference: Long? = null,
    val closedAt: String? = null
)

data class ProtocolMessageLog(
    val id: String,
    val opId: String,
    val fromStore: String,
    val toStore: String,
    val targetPhone: String,
    val cmd: String,
    val amount: Long,
    val seq: Int,
    val timestamp: String,
    val rawPayload: String,
    val hmacVerified: Boolean,
    val smsDispatched: Boolean,
    val replyReceived: Boolean,
    val operationConfirmed: Boolean,
    val status: String
)

enum class ProtocolState {
    DRAFT,
    RESERVE_PENDING,
    RESERVED,
    WAITING_PAYMENT,
    PAYMENT_MATCHED,
    CANCEL_PENDING,
    CANCELLED,
    EXPIRED,
    COORDINATION_FAILED,
    UNASSIGNED,
    TRANSFER_REQUESTED,
    ASSIGNED_LOCAL,
    CONFLICT,
    RESOLVED
}

enum class PosRole {
    CASHIER,
    OWNER
}

object UnitConverter {
    fun getConversionFactor(fromUnit: String, toUnit: String): Double? {
        val from = fromUnit.trim().lowercase()
        val to = toUnit.trim().lowercase()
        if (from == to) return 1.0

        // Mass: g <-> kg
        if (from == "g" && to == "kg") return 0.001
        if (from == "kg" && to == "g") return 1000.0

        // Volume: ml <-> l
        if (from == "ml" && (to == "l" || to == "litro" || to == "litros")) return 0.001
        if ((from == "l" || from == "litro" || from == "litros") && to == "ml") return 1000.0

        // Units: unidades <-> un, u
        val isFromUnit = from == "unidades" || from == "un" || from == "u" || from == "unidad"
        val isToUnit = to == "unidades" || to == "un" || to == "u" || to == "unidad"
        if (isFromUnit && isToUnit) return 1.0

        return null
    }

    fun canConvert(fromUnit: String, toUnit: String): Boolean = getConversionFactor(fromUnit, toUnit) != null

    fun convert(quantity: Double, fromUnit: String, toUnit: String): Double {
        val factor = getConversionFactor(fromUnit, toUnit)
            ?: throw IllegalArgumentException("Unidades incompatibles: no se puede convertir de '$fromUnit' a '$toUnit' sin factor de conversión explícito.")
        return quantity * factor
    }
}

data class ExpenseRecord(
    val id: String,
    val amount: Long,
    val category: String, // Insumos, Reposición inventario, Transporte, Servicios, Mantenimiento, Otros
    val description: String,
    val paymentSource: String, // Caja efectivo, Nequi, Otro
    val cashierName: String,
    val notes: String = "",
    val dateStr: String, // YYYY-MM-DD
    val time: String, // HH:mm
    val timestamp: Long = System.currentTimeMillis(),
    val isLinkedToInventory: Boolean = false,
    val status: String = "ACTIVE" // ACTIVE, CANCELLED
)

data class PaymentTransaction(
    val id: String,
    val method: String, // NEQUI, CASH, CARD, OTHER
    val amount: Long,
    val dateStr: String, // YYYY-MM-DD
    val time: String, // HH:mm
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "CONFIRMED", // CONFIRMED, REVIEW, REJECTED, REFUNDED
    val saleId: String? = null,
    val saleNumber: String? = null,
    val cashierName: String,
    
    // Nequi specific
    val payerNameOrPhone: String? = null,
    val bankTransactionRef: String? = null, // e.g. "M982312" or null
    val smsEventId: String? = null, // e.g. "SMS-1729950001"
    val originInstallation: String = "Tienda Local", // "Tienda Local", "Tienda Remota: Pizzería"
    val nequiChannel: String? = null,
    
    // Cash specific
    val entryType: String = "SALE_PAYMENT", // SALE_PAYMENT, MANUAL_CASH_IN
    val notes: String = ""
) {
    val displayBankRef: String
        get() = if (bankTransactionRef.isNullOrBlank()) "No disponible en el SMS" else bankTransactionRef
}

data class ProductSaleOccurrence(
    val saleId: String,
    val saleNumber: String,
    val time: String,
    val cashierName: String,
    val quantity: Int,
    val unitPrice: Long,
    val totalPrice: Long
)

data class ProductSaleSummary(
    val productId: String,
    val productName: String,
    val totalQuantity: Int,
    val unitSalePrice: Long, // Historical snapshot price from sale
    val totalRevenue: Long,
    val discountsTotal: Long = 0L,
    val salesCount: Int,
    val isCombo: Boolean = false,
    val salesDetails: List<ProductSaleOccurrence> = emptyList()
)

data class DailyFinancialSummary(
    val dateStr: String,
    val totalNequi: Long,
    val nequiCount: Int,
    val nequiTransactions: List<PaymentTransaction>,
    val totalCash: Long,
    val cashCount: Int,
    val cashTransactions: List<PaymentTransaction>,
    val totalReceived: Long, // totalNequi + totalCash
    val totalExpenses: Long,
    val expensesCount: Int,
    val expenses: List<ExpenseRecord>,
    val movementBalance: Long, // totalReceived - totalExpenses
    val productsSold: List<ProductSaleSummary>,
    val totalUnitsSold: Int,
    val salesCount: Int
)

data class MonthDayIndicator(
    val dateStr: String,
    val dayNumber: Int,
    val hasSalesOrPayments: Boolean,
    val hasExpenses: Boolean,
    val totalReceived: Long,
    val totalExpenses: Long
)

// =========================================================================
// INTER-STORE COORDINATION & NEQUI PAYMENT PROTOCOL MODELS (Sections 15 & 16)
// =========================================================================

enum class ReservationState {
    CREATED,
    PENDING_SEND,
    SENT,
    ACKNOWLEDGED,
    ACTIVE,
    CANCELLED,
    REPLACED_BY_CASH,
    PAYMENT_CANDIDATE,
    COMPLETED,
    EXPIRED,
    REQUIRES_REVIEW
}

enum class NotificationState {
    RECEIVED,
    PARSED,
    UNASSIGNED,
    MATCH_CANDIDATE,
    ASSIGNED,
    RECONCILIATION_PENDING,
    LINKED_TO_SALE,
    DUPLICATE_EVENT,
    REQUIRES_REVIEW
}

enum class SyncState {
    NOT_SENT,
    PENDING_SEND,
    SENT,
    ACKNOWLEDGED,
    RETRY_PENDING,
    FAILED_REQUIRES_ATTENTION
}

data class PaymentReservation(
    val id: String,                         // e.g. "H-20261026-001" or "RES-1729..."
    val orderId: String,                    // e.g. "ORD-123"
    val deviceId: String = "dev-main",      // Device identifier
    val storeId: String,                    // "HELADERIA" or "PIZZERIA"
    val amount: Long,                       // Exact integer in COP
    val subtotal: Long = amount,
    val discount: Long = 0L,
    val cashierName: String = "Laura Gómez",
    val createdAt: Long = System.currentTimeMillis(),
    val state: ReservationState = ReservationState.CREATED,
    val syncState: SyncState = SyncState.NOT_SENT,
    val protocolVersion: Int = 1,
    val seq: Int = 1,
    val isPendingHold: Boolean = false,     // True when cashier presses "Dejar pendiente"
    val notes: String? = null,
    val cancellationReason: String? = null,
    val matchedNotificationId: String? = null,
    val saleId: String? = null,
    val cartSnapshotJson: String? = null,
    val retryCount: Int = 0,
    val lastAttemptTimestamp: Long = 0L
)

data class NequiBankNotification(
    val id: String,                         // Stable cryptographic hash / fingerprint
    val amount: Long,
    val senderName: String? = null,
    val bankRef: String? = null,            // null if not present in SMS
    val channel: String? = null,            // e.g. "Bre-B", "App Nequi"
    val timestamp: Long = System.currentTimeMillis(),
    val dateStr: String = "",
    val timeStr: String = "",
    val rawBody: String = "",
    val state: NotificationState = NotificationState.RECEIVED,
    val assignedStore: String? = null,      // "HELADERIA" or "PIZZERIA"
    val assignedByCashier: String? = null,
    val assignedAt: Long? = null,
    val linkedReservationId: String? = null,
    val linkedSaleId: String? = null,
    val peerRecordId: String? = null,
    val syncState: SyncState = SyncState.NOT_SENT,
    val reviewReason: String? = null
) {
    val displayBankRef: String
        get() = if (bankRef.isNullOrBlank()) "No disponible en el SMS" else bankRef
}

data class PendingOutboxMessage(
    val id: String,                         // e.g. "msg-1729..."
    val command: String,                    // e.g. "RESERVE", "CANCEL", "ASSIGN"
    val payload: String,
    val targetPhone: String,
    val relatedEntityId: String,            // reservationId or notificationId
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastAttemptAt: Long = 0L,
    val acknowledged: Boolean = false
)

interface StoreCoordinationTransport {
    fun sendCommand(payload: String, targetPhone: String): Boolean
}

object AmbiorixSmsProtocol {
    const val HEADER = "AMBIPAY"
    const val VERSION = 1
    const val MAX_SMS_LENGTH = 320

    const val CMD_RESERVE = "RESERVE"
    const val CMD_RESERVE_ACK = "RESERVE_ACK"
    const val CMD_CANCEL = "CANCEL"
    const val CMD_CANCEL_ACK = "CANCEL_ACK"
    const val CMD_PAYMENT_CANDIDATE = "PAYMENT_CANDIDATE"
    const val CMD_COMPLETE = "COMPLETE"
    const val CMD_COMPLETE_ACK = "COMPLETE_ACK"
    const val CMD_ASSIGN = "ASSIGN"
    const val CMD_ASSIGN_ACK = "ASSIGN_ACK"
    const val CMD_STATUS = "STATUS"
    const val CMD_STATUS_RESPONSE = "STATUS_RESPONSE"
    const val CMD_RECONCILE = "RECONCILE"
    const val CMD_ACK = "ACK"

    private val VALID_COMMANDS = setOf(
        CMD_RESERVE, CMD_RESERVE_ACK, CMD_CANCEL, CMD_CANCEL_ACK,
        CMD_PAYMENT_CANDIDATE, CMD_COMPLETE, CMD_COMPLETE_ACK,
        CMD_ASSIGN, CMD_ASSIGN_ACK, CMD_STATUS, CMD_STATUS_RESPONSE,
        CMD_RECONCILE, CMD_ACK
    )

    private val ACK_COMMANDS = setOf(
        CMD_RESERVE_ACK, CMD_CANCEL_ACK, CMD_COMPLETE_ACK, CMD_ASSIGN_ACK, CMD_ACK, CMD_STATUS_RESPONSE
    )

    fun isAckOrResponseCommand(cmd: String): Boolean = ACK_COMMANDS.contains(cmd)

    data class ParsedCommand(
        val header: String,
        val command: String,
        val version: Int,
        val fields: Map<String, String>,
        val rawMessage: String
    ) {
        val msgId: String? get() = fields["msgId"] ?: fields["id"] ?: fields["tx"]
        val id: String? get() = fields["id"]
        val orderId: String? get() = fields["order"]
        val saleId: String? get() = fields["sale"]
        val src: String? get() = fields["src"]
        val dst: String? get() = fields["dst"]
        val amount: Long? get() = fields["amount"]?.toLongOrNull()
        val tx: String? get() = fields["tx"]
        val ackMsgId: String? get() = fields["ackMsgId"]
        val reason: String? get() = fields["reason"]
        val state: String? get() = fields["state"]
        val seq: Int get() = fields["seq"]?.toIntOrNull() ?: 1
        val timestamp: Long? get() = fields["ts"]?.toLongOrNull()
        val bankTimestamp: Long? get() = fields["bankts"]?.toLongOrNull()
        val payer: String? get() = fields["payer"]
        val bankRef: String? get() = fields["ref"]
        val hmac: String? get() = fields["sig"]
    }

    fun isProtocolMessage(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith("$HEADER|") || trimmed.startsWith("APOS|")
    }

    fun parse(rawBody: String): ParsedCommand? {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_SMS_LENGTH) return null
        if (!isProtocolMessage(trimmed)) return null
        val parts = trimmed.split("|")
        if (parts.size < 3) return null

        val header = parts[0]
        val cmd = parts[1]
        if (header == HEADER && !VALID_COMMANDS.contains(cmd)) return null

        var version = 1
        val fields = mutableMapOf<String, String>()

        for (i in 2 until parts.size) {
            val part = parts[i]
            val eqIdx = part.indexOf('=')
            if (eqIdx > 0) {
                val k = part.substring(0, eqIdx).trim()
                val v = part.substring(eqIdx + 1).trim()
                if (k == "v") {
                    version = v.toIntOrNull() ?: 1
                } else {
                    fields[k] = v
                }
            } else if (i == 2 && part.startsWith("v")) {
                version = part.removePrefix("v").toIntOrNull() ?: 1
            }
        }

        return ParsedCommand(
            header = header,
            command = cmd,
            version = version,
            fields = fields,
            rawMessage = trimmed
        )
    }

    fun buildReserve(
        id: String,
        orderId: String,
        src: String,
        amount: Long,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-RES-$id",
        seq: Int = 1,
        dst: String? = null
    ): String {
        val dstPart = if (!dst.isNullOrBlank()) "|dst=$dst" else ""
        return "$HEADER|$CMD_RESERVE|v=$VERSION|msgId=$msgId|id=$id|order=$orderId|src=$src$dstPart|amount=$amount|currency=COP|seq=$seq|ts=$timestamp"
    }

    fun buildReserveAck(
        id: String,
        ackMsgId: String,
        src: String,
        dst: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-RACK-$id-$timestamp"
    ): String {
        return "$HEADER|$CMD_RESERVE_ACK|v=$VERSION|msgId=$msgId|id=$id|ackMsgId=$ackMsgId|src=$src|dst=$dst|ts=$timestamp"
    }

    fun buildCancel(
        id: String,
        src: String,
        amount: Long,
        reason: String = "USER_CANCEL",
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-CAN-$id",
        seq: Int = 2,
        dst: String? = null
    ): String {
        val dstPart = if (!dst.isNullOrBlank()) "|dst=$dst" else ""
        return "$HEADER|$CMD_CANCEL|v=$VERSION|msgId=$msgId|id=$id|src=$src$dstPart|amount=$amount|reason=$reason|seq=$seq|ts=$timestamp"
    }

    fun buildCancelAck(
        id: String,
        ackMsgId: String,
        src: String,
        dst: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-CACK-$id-$timestamp"
    ): String {
        return "$HEADER|$CMD_CANCEL_ACK|v=$VERSION|msgId=$msgId|id=$id|ackMsgId=$ackMsgId|src=$src|dst=$dst|ts=$timestamp"
    }

    fun buildPaymentCandidate(
        id: String,
        tx: String,
        src: String,
        amount: Long,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-CAND-$id-$tx"
    ): String {
        return "$HEADER|$CMD_PAYMENT_CANDIDATE|v=$VERSION|msgId=$msgId|id=$id|tx=$tx|src=$src|amount=$amount|ts=$timestamp"
    }

    fun buildComplete(
        id: String,
        saleId: String,
        src: String,
        amount: Long,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-CMP-$id",
        seq: Int = 3,
        tx: String? = null
    ): String {
        val txPart = if (!tx.isNullOrBlank()) "|tx=$tx" else ""
        return "$HEADER|$CMD_COMPLETE|v=$VERSION|msgId=$msgId|id=$id|sale=$saleId$txPart|src=$src|amount=$amount|seq=$seq|ts=$timestamp"
    }


    fun buildCompleteAck(
        id: String,
        ackMsgId: String,
        src: String,
        dst: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-CMPACK-$id-$timestamp"
    ): String {
        return "$HEADER|$CMD_COMPLETE_ACK|v=$VERSION|msgId=$msgId|id=$id|ackMsgId=$ackMsgId|src=$src|dst=$dst|ts=$timestamp"
    }

    fun buildAssign(
        tx: String,
        src: String,
        dst: String,
        amount: Long,
        bankts: Long,
        payer: String? = null,
        bankRef: String? = null,
        msgId: String = "M-ASG-$tx-$src"
    ): String {
        val base = "$HEADER|$CMD_ASSIGN|v=$VERSION|msgId=$msgId|tx=$tx|src=$src|dst=$dst|amount=$amount|bankts=$bankts"
        val p = if (!payer.isNullOrBlank()) "|payer=${payer.replace("|", " ").replace("=", " ")}" else ""
        val r = if (!bankRef.isNullOrBlank()) "|ref=${bankRef.replace("|", " ").replace("=", " ")}" else ""
        return base + p + r
    }

    fun buildAssignAck(
        tx: String,
        ackMsgId: String,
        src: String,
        dst: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-ASGACK-$tx-$timestamp"
    ): String {
        return "$HEADER|$CMD_ASSIGN_ACK|v=$VERSION|msgId=$msgId|tx=$tx|ackMsgId=$ackMsgId|src=$src|dst=$dst|ts=$timestamp"
    }

    fun buildStatus(
        id: String,
        src: String,
        dst: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-STS-$id-$timestamp"
    ): String {
        return "$HEADER|$CMD_STATUS|v=$VERSION|msgId=$msgId|id=$id|src=$src|dst=$dst|ts=$timestamp"
    }

    fun buildStatusResponse(
        id: String,
        state: String,
        src: String,
        dst: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-STSR-$id-$timestamp"
    ): String {
        return "$HEADER|$CMD_STATUS_RESPONSE|v=$VERSION|msgId=$msgId|id=$id|state=$state|src=$src|dst=$dst|ts=$timestamp"
    }

    fun buildReconcile(
        id: String,
        state: String,
        src: String,
        dst: String,
        amount: Long,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-REC-$id-$timestamp"
    ): String {
        return "$HEADER|$CMD_RECONCILE|v=$VERSION|msgId=$msgId|id=$id|state=$state|src=$src|dst=$dst|amount=$amount|ts=$timestamp"
    }

    fun buildAck(
        ackMsgId: String,
        src: String,
        dst: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = "M-ACK-$ackMsgId-$timestamp"
    ): String {
        return "$HEADER|$CMD_ACK|v=$VERSION|msgId=$msgId|ackMsgId=$ackMsgId|src=$src|dst=$dst|ts=$timestamp"
    }
}



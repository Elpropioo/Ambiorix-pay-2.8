package com.ambiorix.pay.pos

import android.content.Context
import android.content.SharedPreferences
import android.telephony.SmsManager
import com.ambiorix.pay.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface PosStorage {
    fun getString(key: String, defaultValue: String?): String?
    fun putString(key: String, value: String)
}

class SharedPreferencesStorage(private val prefs: SharedPreferences) : PosStorage {
    override fun getString(key: String, defaultValue: String?): String? = prefs.getString(key, defaultValue)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

class InMemoryPosStorage : PosStorage {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String, defaultValue: String?): String? = map[key] ?: defaultValue
    override fun putString(key: String, value: String) {
        map[key] = value
    }
}

data class ResolvedIngredientConsumption(
    val ingredientId: String,
    val ingredientName: String,
    val consumedQuantity: Double, // In ingredient's base unit
    val unit: String
)

data class ResolvedProductConsumption(
    val productId: String,
    val productName: String,
    val quantity: Int
)

class PosRepository(
    private val context: Context?,
    private val storage: PosStorage
) {

    private val settingsRepo: SettingsRepository? = context?.let { SettingsRepository.getInstance(it) }

    // Cashiers
    private val _cashiers = MutableStateFlow<List<CashierProfile>>(emptyList())
    val cashiers: StateFlow<List<CashierProfile>> = _cashiers.asStateFlow()

    private val _activeCashier = MutableStateFlow(
        CashierProfile(id = "cashier-1", name = "Laura Gómez", isActive = true)
    )
    val activeCashier: StateFlow<CashierProfile> = _activeCashier.asStateFlow()

    // Categories
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    // Products & Combos
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    // Ingredients (Raw Materials Inventory)
    private val _ingredients = MutableStateFlow<List<Ingredient>>(emptyList())
    val ingredients: StateFlow<List<Ingredient>> = _ingredients.asStateFlow()

    // Toppings & Additionals
    private val _toppings = MutableStateFlow<List<ToppingOption>>(emptyList())
    val toppings: StateFlow<List<ToppingOption>> = _toppings.asStateFlow()

    // Inventory Movements (Audit Log)
    private val _inventoryMovements = MutableStateFlow<List<InventoryMovement>>(emptyList())
    val inventoryMovements: StateFlow<List<InventoryMovement>> = _inventoryMovements.asStateFlow()

    // Sales History
    private val _sales = MutableStateFlow<List<SaleRecord>>(emptyList())
    val sales: StateFlow<List<SaleRecord>> = _sales.asStateFlow()

    // Expenses (Gastos)
    private val _expenses = MutableStateFlow<List<ExpenseRecord>>(emptyList())
    val expenses: StateFlow<List<ExpenseRecord>> = _expenses.asStateFlow()

    // Payment Transactions (Nequi, Cash, etc.)
    private val _paymentTransactions = MutableStateFlow<List<PaymentTransaction>>(emptyList())
    val paymentTransactions: StateFlow<List<PaymentTransaction>> = _paymentTransactions.asStateFlow()

    // Processed Sale IDs for Idempotency
    private val _processedSaleIds = mutableSetOf<String>()

    // Processed Protocol Message IDs for SMS Idempotency
    private val _processedProtocolMsgIds = mutableSetOf<String>()

    // Multi-Reservation & Bank Notification Coordination State (Sections 4-21)
    private val _reservations = MutableStateFlow<List<PaymentReservation>>(emptyList())
    val reservations: StateFlow<List<PaymentReservation>> = _reservations.asStateFlow()

    private val _bankNotifications = MutableStateFlow<List<NequiBankNotification>>(emptyList())
    val bankNotifications: StateFlow<List<NequiBankNotification>> = _bankNotifications.asStateFlow()

    private val _outboxMessages = MutableStateFlow<List<PendingOutboxMessage>>(emptyList())
    val outboxMessages: StateFlow<List<PendingOutboxMessage>> = _outboxMessages.asStateFlow()

    // Pending remote assignments when ASSIGN arrives before bank notification (Section 14.2)
    private val _pendingRemoteAssignments = MutableStateFlow<List<AmbiorixSmsProtocol.ParsedCommand>>(emptyList())
    val pendingRemoteAssignments: StateFlow<List<AmbiorixSmsProtocol.ParsedCommand>> = _pendingRemoteAssignments.asStateFlow()

    // Store Identity Configuration (HELADERIA vs PIZZERIA)
    private var configuredLocalStoreId: String = "HELADERIA"
    private var configuredPeerStoreId: String = "PIZZERIA"
    private var configuredPeerPhone: String = "3000000000"
    private var configuredPeerEnabled: Boolean = true

    // Pluggable Transport Layer (Section 21: Habla y Escucha)
    private val defaultAndroidTransport = object : StoreCoordinationTransport {
        override fun sendCommand(payload: String, targetPhone: String): Boolean {
            val cleanPhone = targetPhone.trim().replace(Regex("[^0-9+]"), "")
            if (cleanPhone.isBlank() || cleanPhone == "3000000000") return false
            val ctx = context ?: return false
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.SEND_SMS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return try {
                val smsManager: SmsManager = ctx.getSystemService(SmsManager::class.java)
                    ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                val parsed = AmbiorixSmsProtocol.parse(payload)
                val msgId = parsed?.msgId ?: "msg-${System.currentTimeMillis()}"
                val entityId = parsed?.id ?: parsed?.tx ?: ""
                val sentIntent = android.content.Intent(ACTION_SMS_SENT).apply {
                    setPackage(ctx.packageName)
                    putExtra(EXTRA_MSG_ID, msgId)
                    putExtra(EXTRA_ENTITY_ID, entityId)
                }
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                val reqCodeBase = (msgId.hashCode() and 0x3FFFFFFF)
                val parts = smsManager.divideMessage(payload)
                if (parts != null && parts.size > 1) {
                    val sentIntents = ArrayList<android.app.PendingIntent>(parts.size)
                    for (i in parts.indices) {
                        if (i == parts.lastIndex) {
                            sentIntents.add(
                                android.app.PendingIntent.getBroadcast(
                                    ctx,
                                    reqCodeBase + i,
                                    sentIntent,
                                    flags
                                )
                            )
                        } else {
                            sentIntents.add(
                                android.app.PendingIntent.getBroadcast(
                                    ctx,
                                    reqCodeBase + i,
                                    android.content.Intent(ACTION_SMS_PART_SENT).setPackage(ctx.packageName),
                                    flags
                                )
                            )
                        }
                    }
                    smsManager.sendMultipartTextMessage(cleanPhone, null, parts, sentIntents, null)
                } else {
                    val pi = android.app.PendingIntent.getBroadcast(
                        ctx,
                        reqCodeBase,
                        sentIntent,
                        flags
                    )
                    smsManager.sendTextMessage(cleanPhone, null, payload, pi, null)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    var coordinationTransport: StoreCoordinationTransport = defaultAndroidTransport

    private fun isAsyncAndroidTransport(): Boolean {
        return context != null && coordinationTransport === defaultAndroidTransport
    }

    // Current Cash Shift
    private val _cashShift = MutableStateFlow(
        CashShift(
            id = "shift-${System.currentTimeMillis()}",
            cashierName = "Laura Gómez",
            openedAt = getCurrentTime(),
            initialCash = 150000L,
            status = "OPEN"
        )
    )
    val cashShift: StateFlow<CashShift> = _cashShift.asStateFlow()

    // Protocol State Machine (P2P SMS)
    private val _protocolState = MutableStateFlow(ProtocolState.DRAFT)
    val protocolState: StateFlow<ProtocolState> = _protocolState.asStateFlow()

    private val _activeOpId = MutableStateFlow<String?>(null)
    val activeOpId: StateFlow<String?> = _activeOpId.asStateFlow()

    private val _smsLogs = MutableStateFlow<List<ProtocolMessageLog>>(emptyList())
    val smsLogs: StateFlow<List<ProtocolMessageLog>> = _smsLogs.asStateFlow()

    private val _unassignedPayments = MutableStateFlow<List<SaleRecord>>(emptyList())
    val unassignedPayments: StateFlow<List<SaleRecord>> = _unassignedPayments.asStateFlow()

    private fun nextUniqueId(prefix: String): String {
        return "$prefix-${System.currentTimeMillis()}-${idSequence.incrementAndGet()}"
    }

    init {
        loadData()
        registerSmsSentReceiverIfNeeded()
    }

    private fun registerSmsSentReceiverIfNeeded() {
        val ctx = context?.applicationContext ?: return
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    if (intent?.action != ACTION_SMS_SENT) return
                    val msgId = intent.getStringExtra(EXTRA_MSG_ID).orEmpty()
                    val entityId = intent.getStringExtra(EXTRA_ENTITY_ID).orEmpty()
                    val isSuccess = (resultCode == android.app.Activity.RESULT_OK)
                    onSmsSendResult(
                        msgId = msgId,
                        relatedEntityId = entityId,
                        isSuccess = isSuccess,
                        errorCode = resultCode
                    )
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                ctx,
                receiver,
                android.content.IntentFilter(ACTION_SMS_SENT),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // 1. CASHIER MANAGEMENT
    // =========================================================================

    fun updateCashierName(cashierId: String, newName: String): Result<CashierProfile> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre de la cajera no puede estar vacío"))
        }

        val currentList = _cashiers.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == cashierId }
        val updatedCashier = if (index >= 0) {
            val updated = currentList[index].copy(name = trimmed)
            currentList[index] = updated
            updated
        } else {
            val created = CashierProfile(id = cashierId, name = trimmed)
            currentList.add(created)
            created
        }

        _cashiers.value = currentList
        if (_activeCashier.value.id == cashierId) {
            _activeCashier.value = updatedCashier
            _cashShift.value = _cashShift.value.copy(cashierName = trimmed)
        }

        saveCashiers()
        return Result.success(updatedCashier)
    }

    fun createCashier(name: String, pin: String? = null): Result<CashierProfile> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre de la cajera no puede estar vacío"))
        }
        val newCashier = CashierProfile(
            id = nextUniqueId("cashier"),
            name = trimmed,
            pin = pin
        )
        _cashiers.value = _cashiers.value + newCashier
        saveCashiers()
        return Result.success(newCashier)
    }

    fun selectCashier(cashierId: String): Boolean {
        val cashier = _cashiers.value.find { it.id == cashierId } ?: return false
        _activeCashier.value = cashier
        _cashShift.value = _cashShift.value.copy(cashierName = cashier.name)
        storage.putString(KEY_ACTIVE_CASHIER_ID, cashierId)
        return true
    }

    // =========================================================================
    // 2. CATEGORY MANAGEMENT
    // =========================================================================

    fun createCategory(name: String, emoji: String = "🍕"): Result<Category> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre de la categoría no puede estar vacío"))
        }
        val id = nextUniqueId("cat")
        val newCat = Category(
            id = id,
            name = trimmed,
            emoji = emoji,
            displayOrder = _categories.value.size
        )
        _categories.value = _categories.value + newCat
        saveCategories()
        return Result.success(newCat)
    }

    fun updateCategory(id: String, newName: String, newEmoji: String = "🍕", isActive: Boolean = true): Result<Category> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre de la categoría no puede estar vacío"))
        }
        val current = _categories.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) {
            return Result.failure(NoSuchElementException("Categoría no encontrada"))
        }
        val updated = current[index].copy(name = trimmed, emoji = newEmoji, isActive = isActive)
        current[index] = updated
        _categories.value = current
        saveCategories()
        return Result.success(updated)
    }

    fun deleteCategory(id: String, reassignToCategoryId: String): Result<Boolean> {
        if (id == reassignToCategoryId) {
            return Result.failure(IllegalArgumentException("La categoría de reasignación debe ser diferente"))
        }
        val targetCat = _categories.value.find { it.id == reassignToCategoryId }
            ?: return Result.failure(IllegalArgumentException("La categoría de reasignación no existe"))

        val catToDelete = _categories.value.find { it.id == id }
            ?: return Result.failure(NoSuchElementException("Categoría no encontrada"))

        if (_categories.value.size <= 1) {
            return Result.failure(IllegalStateException("No se puede eliminar la única categoría existente"))
        }

        // Reassign products safely without dropping them
        val updatedProducts = _products.value.map { prod ->
            if (prod.categoryId == id) {
                prod.copy(categoryId = targetCat.id, updatedAt = System.currentTimeMillis())
            } else prod
        }
        _products.value = updatedProducts

        _categories.value = _categories.value.filter { it.id != id }

        saveProducts()
        saveCategories()
        return Result.success(true)
    }

    fun getProductCountForCategory(categoryId: String): Int {
        return _products.value.count { it.categoryId == categoryId }
    }

    fun getDefaultCategory(): Category {
        return _categories.value.firstOrNull { it.isDefault }
            ?: _categories.value.firstOrNull()
            ?: Category("productos", "Productos", 0, true, "🍕", true)
    }

    // =========================================================================
    // 3. INGREDIENTS INVENTORY MANAGEMENT
    // =========================================================================

    fun createIngredient(
        name: String,
        unit: String,
        initialStock: Double,
        minStockAlert: Double = 100.0,
        unitCostCOP: Long = 0L,
        category: String = "Insumos"
    ): Result<Ingredient> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre del ingrediente es obligatorio"))
        }
        val id = nextUniqueId("ing")
        val ing = Ingredient(
            id = id,
            name = trimmed,
            category = category,
            unit = unit,
            currentStock = initialStock,
            minStockAlert = minStockAlert,
            unitCostCOP = unitCostCOP,
            isActive = true
        )
        _ingredients.value = _ingredients.value + ing

        if (initialStock > 0.0) {
            logMovement(
                itemId = id,
                itemName = trimmed,
                itemType = "INGREDIENT",
                previousStock = 0.0,
                change = initialStock,
                resultingStock = initialStock,
                type = "PURCHASE",
                reason = "Inventario inicial de ingrediente",
                userName = _activeCashier.value.name
            )
        }

        saveIngredients()
        return Result.success(ing)
    }

    fun createIngredient(
        name: String,
        unit: String,
        initialStock: Double,
        unitCostCOP: Long
    ): Result<Ingredient> {
        return createIngredient(
            name = name,
            unit = unit,
            initialStock = initialStock,
            minStockAlert = 100.0,
            unitCostCOP = unitCostCOP
        )
    }

    fun updateIngredient(
        id: String,
        name: String,
        unit: String,
        minStockAlert: Double,
        unitCostCOP: Long,
        isActive: Boolean = true,
        category: String? = null
    ): Result<Ingredient> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre no puede estar vacío"))
        }
        val current = _ingredients.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return Result.failure(NoSuchElementException("Ingrediente no encontrado"))

        val existing = current[index]
        val updated = existing.copy(
            name = trimmed,
            category = category?.trim()?.takeIf { it.isNotEmpty() } ?: existing.category,
            unit = unit,
            minStockAlert = minStockAlert,
            unitCostCOP = unitCostCOP,
            isActive = isActive
        )
        current[index] = updated
        _ingredients.value = current
        saveIngredients()
        return Result.success(updated)
    }

    fun createTopping(
        name: String,
        price: Long,
        ingredientId: String? = null,
        gramsRequired: Double = 0.0
    ): Result<ToppingOption> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre del topping no puede estar vacío"))
        }
        if (price < 0L) {
            return Result.failure(IllegalArgumentException("El precio del topping no puede ser negativo"))
        }
        val id = nextUniqueId("top")
        val topping = ToppingOption(
            id = id,
            name = trimmed,
            price = price,
            ingredientId = ingredientId?.takeIf { it.isNotBlank() },
            gramsRequired = maxOf(0.0, gramsRequired)
        )
        _toppings.value = _toppings.value + topping
        saveToppings()
        return Result.success(topping)
    }

    fun updateTopping(
        id: String,
        name: String,
        price: Long,
        ingredientId: String? = null,
        gramsRequired: Double = 0.0
    ): Result<ToppingOption> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El nombre del topping no puede estar vacío"))
        }
        if (price < 0L) {
            return Result.failure(IllegalArgumentException("El precio del topping no puede ser negativo"))
        }
        val current = _toppings.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return Result.failure(NoSuchElementException("Topping no encontrado"))
        val updated = current[index].copy(
            name = trimmed,
            price = price,
            ingredientId = ingredientId?.takeIf { it.isNotBlank() },
            gramsRequired = maxOf(0.0, gramsRequired)
        )
        current[index] = updated
        _toppings.value = current
        saveToppings()
        return Result.success(updated)
    }

    fun deleteTopping(id: String): Result<Boolean> {
        val current = _toppings.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (!removed) return Result.failure(NoSuchElementException("Topping no encontrado"))
        _toppings.value = current
        saveToppings()
        return Result.success(true)
    }

    fun adjustIngredientStock(
        ingredientId: String,
        newStock: Double,
        reason: String,
        userName: String,
        type: String = "MANUAL_ADJUSTMENT",
        allowNegative: Boolean = false
    ): Result<InventoryMovement> {
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) {
            return Result.failure(IllegalArgumentException("El motivo del ajuste es obligatorio"))
        }
        val trimmedUser = userName.trim()
        if (trimmedUser.isEmpty()) {
            return Result.failure(IllegalArgumentException("El usuario auditor es obligatorio"))
        }
        if (!allowNegative && newStock < 0.0) {
            return Result.failure(IllegalArgumentException("El inventario resultante no puede ser negativo"))
        }

        val current = _ingredients.value.toMutableList()
        val index = current.indexOfFirst { it.id == ingredientId }
        if (index < 0) return Result.failure(NoSuchElementException("Ingrediente no encontrado"))

        val ing = current[index]
        val prev = ing.currentStock
        val delta = newStock - prev

        current[index] = ing.copy(currentStock = newStock)
        _ingredients.value = current

        val mov = logMovement(
            itemId = ing.id,
            itemName = ing.name,
            itemType = "INGREDIENT",
            previousStock = prev,
            change = delta,
            resultingStock = newStock,
            type = type,
            reason = trimmedReason,
            userName = trimmedUser
        )

        saveIngredients()
        return Result.success(mov)
    }

    fun recordIngredientPurchase(
        ingredientId: String,
        quantity: Double,
        unit: String,
        supplierOrNote: String,
        userName: String
    ): Result<InventoryMovement> {
        if (quantity <= 0.0) return Result.failure(IllegalArgumentException("La cantidad debe ser mayor a cero"))
        val ing = _ingredients.value.find { it.id == ingredientId }
            ?: return Result.failure(NoSuchElementException("Ingrediente no encontrado"))

        val convertedQty = try {
            UnitConverter.convert(quantity, unit, ing.unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val newStock = ing.currentStock + convertedQty
        val note = if (supplierOrNote.isNotBlank()) supplierOrNote.trim() else "Compra recibida"
        return adjustIngredientStock(
            ingredientId = ingredientId,
            newStock = newStock,
            reason = "Entrada compra ($quantity $unit): $note",
            userName = userName,
            type = "PURCHASE"
        )
    }

    fun recordIngredientWaste(
        ingredientId: String,
        quantity: Double,
        unit: String,
        reason: String,
        userName: String
    ): Result<InventoryMovement> {
        if (quantity <= 0.0) return Result.failure(IllegalArgumentException("La cantidad de merma debe ser mayor a cero"))
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) return Result.failure(IllegalArgumentException("El motivo de merma es obligatorio"))

        val ing = _ingredients.value.find { it.id == ingredientId }
            ?: return Result.failure(NoSuchElementException("Ingrediente no encontrado"))

        val convertedQty = try {
            UnitConverter.convert(quantity, unit, ing.unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val newStock = maxOf(0.0, ing.currentStock - convertedQty)
        return adjustIngredientStock(
            ingredientId = ingredientId,
            newStock = newStock,
            reason = "Merma ($quantity $unit): $trimmedReason",
            userName = userName,
            type = "WASTE_MERMA"
        )
    }

    // =========================================================================
    // 4. PRODUCT, RECIPE & COMBO MANAGEMENT
    // =========================================================================

    fun createProduct(
        name: String,
        basePrice: Long,
        categoryId: String,
        description: String = "",
        imagePath: String? = null,
        emoji: String = "🍕",
        stockQuantity: Double = 0.0,
        unit: String = "unidades",
        baseCost: Long = 0L,
        minStockAlert: Double = 5.0,
        badge: String? = null,
        isCombo: Boolean = false,
        recipe: List<RecipeItem> = emptyList(),
        comboItems: List<ComboItem> = emptyList(),
        allowNegativeStock: Boolean = false,
        variants: List<ProductVariant> = emptyList(),
        allowedToppings: Boolean = false
    ): Result<Product> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("El nombre del producto no puede estar vacío"))
        if (basePrice < 0) return Result.failure(IllegalArgumentException("El precio no puede ser negativo"))

        val catExists = _categories.value.any { it.id == categoryId }
        val finalCategoryId = if (catExists) categoryId else getDefaultCategory().id

        val id = nextUniqueId("prod")
        val now = System.currentTimeMillis()

        // Calculate estimated cost from recipe if recipe provided and baseCost is 0
        val computedCost = if (baseCost > 0L) baseCost else calculateRecipeCost(recipe)

        val product = Product(
            id = id,
            name = trimmed,
            description = description.trim(),
            basePrice = basePrice,
            baseCost = computedCost,
            categoryId = finalCategoryId,
            imagePath = imagePath,
            emoji = emoji,
            badge = badge,
            isActive = true,
            isCombo = isCombo,
            stockQuantity = stockQuantity,
            minStockAlert = minStockAlert,
            unit = unit,
            allowNegativeStock = allowNegativeStock,
            recipe = recipe,
            comboItems = comboItems,
            createdAt = now,
            updatedAt = now,
            variants = variants,
            allowedToppings = allowedToppings
        )

        _products.value = _products.value + product

        if (stockQuantity > 0.0 && !isCombo && recipe.isEmpty()) {
            logMovement(
                itemId = id,
                itemName = trimmed,
                itemType = "PRODUCT",
                previousStock = 0.0,
                change = stockQuantity,
                resultingStock = stockQuantity,
                type = "PURCHASE",
                reason = "Inventario inicial de producto",
                userName = _activeCashier.value.name
            )
        }

        saveProducts()
        return Result.success(product)
    }

    fun updateProduct(
        id: String,
        name: String,
        basePrice: Long,
        categoryId: String,
        description: String = "",
        imagePath: String? = null,
        emoji: String = "🍨",
        unit: String = "unidades",
        baseCost: Long = 0L,
        minStockAlert: Double = 5.0,
        isActive: Boolean = true,
        badge: String? = null,
        isCombo: Boolean = false,
        recipe: List<RecipeItem>? = null,
        comboItems: List<ComboItem>? = null,
        allowNegativeStock: Boolean = false,
        variants: List<ProductVariant>? = null,
        allowedToppings: Boolean? = null
    ): Result<Product> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("El nombre del producto no puede estar vacío"))
        if (basePrice < 0) return Result.failure(IllegalArgumentException("El precio no puede ser negativo"))

        val current = _products.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return Result.failure(NoSuchElementException("Producto no encontrado"))

        val existing = current[index]
        val finalRecipe = recipe ?: existing.recipe
        val finalComboItems = comboItems ?: existing.comboItems
        val finalVariants = variants ?: existing.variants
        val finalAllowedToppings = allowedToppings ?: existing.allowedToppings

        // Prevent circular combo
        if (isCombo) {
            for (ci in finalComboItems) {
                if (hasCircularCombo(id, ci.productId)) {
                    return Result.failure(IllegalArgumentException("Referencia circular detectada: el combo no puede contenerse a sí mismo"))
                }
            }
        }

        val computedCost = if (baseCost > 0L) baseCost else calculateRecipeCost(finalRecipe)

        val updated = existing.copy(
            name = trimmed,
            basePrice = basePrice,
            categoryId = categoryId,
            description = description.trim(),
            imagePath = imagePath ?: existing.imagePath,
            emoji = emoji,
            unit = unit,
            baseCost = computedCost,
            minStockAlert = minStockAlert,
            isActive = isActive,
            badge = badge,
            isCombo = isCombo,
            recipe = finalRecipe,
            comboItems = finalComboItems,
            allowNegativeStock = allowNegativeStock,
            variants = finalVariants,
            allowedToppings = finalAllowedToppings,
            updatedAt = System.currentTimeMillis()
        )
        current[index] = updated
        _products.value = current
        saveProducts()
        return Result.success(updated)
    }

    fun updateProductRecipe(productId: String, recipe: List<RecipeItem>): Result<Product> {
        val current = _products.value.toMutableList()
        val index = current.indexOfFirst { it.id == productId }
        if (index < 0) return Result.failure(NoSuchElementException("Producto no encontrado"))

        val p = current[index]
        val computedCost = calculateRecipeCost(recipe)
        val updated = p.copy(
            recipe = recipe,
            baseCost = if (computedCost > 0) computedCost else p.baseCost,
            updatedAt = System.currentTimeMillis()
        )
        current[index] = updated
        _products.value = current
        saveProducts()
        return Result.success(updated)
    }

    fun duplicateProductRecipe(sourceProductId: String, targetProductId: String): Result<Product> {
        val source = _products.value.find { it.id == sourceProductId }
            ?: return Result.failure(NoSuchElementException("Producto de origen no encontrado"))
        return updateProductRecipe(targetProductId, source.recipe)
    }

    fun updateProductCombo(productId: String, comboItems: List<ComboItem>): Result<Product> {
        // Validate circular references
        for (item in comboItems) {
            if (hasCircularCombo(productId, item.productId)) {
                return Result.failure(IllegalArgumentException("Referencia circular detectada con producto '${item.productName}'"))
            }
        }

        val current = _products.value.toMutableList()
        val index = current.indexOfFirst { it.id == productId }
        if (index < 0) return Result.failure(NoSuchElementException("Producto no encontrado"))

        val p = current[index]
        val updated = p.copy(
            isCombo = true,
            comboItems = comboItems,
            updatedAt = System.currentTimeMillis()
        )
        current[index] = updated
        _products.value = current
        saveProducts()
        return Result.success(updated)
    }

    fun hasCircularCombo(parentProductId: String, candidateChildId: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
        if (parentProductId == candidateChildId) return true
        if (!visited.add(candidateChildId)) return false

        val childProd = _products.value.find { it.id == candidateChildId } ?: return false
        if (childProd.isCombo) {
            for (grandChild in childProd.comboItems) {
                if (hasCircularCombo(parentProductId, grandChild.productId, visited)) return true
            }
        }
        return false
    }

    fun calculateRecipeCost(recipe: List<RecipeItem>): Long {
        var totalCost = 0.0
        for (item in recipe) {
            val ing = _ingredients.value.find { it.id == item.ingredientId }
            if (ing != null) {
                try {
                    val convertedQty = UnitConverter.convert(item.quantity, item.unit, ing.unit)
                    totalCost += convertedQty * ing.unitCostCOP
                } catch (e: Exception) {
                    totalCost += item.quantity * ing.unitCostCOP
                }
            }
        }
        return totalCost.toLong()
    }

    fun updateProductPrice(productId: String, newPrice: Long): Result<Product> {
        if (newPrice < 0) return Result.failure(IllegalArgumentException("El precio no puede ser negativo"))
        val current = _products.value.toMutableList()
        val index = current.indexOfFirst { it.id == productId }
        if (index < 0) return Result.failure(NoSuchElementException("Producto no encontrado"))
        val updated = current[index].copy(basePrice = newPrice, updatedAt = System.currentTimeMillis())
        current[index] = updated
        _products.value = current
        saveProducts()
        return Result.success(updated)
    }

    fun updateProductImage(productId: String, newImagePath: String?): Result<Product> {
        val current = _products.value.toMutableList()
        val index = current.indexOfFirst { it.id == productId }
        if (index < 0) return Result.failure(NoSuchElementException("Producto no encontrado"))
        val updated = current[index].copy(imagePath = newImagePath, updatedAt = System.currentTimeMillis())
        current[index] = updated
        _products.value = current
        saveProducts()
        return Result.success(updated)
    }

    fun toggleProductActive(productId: String, isActive: Boolean): Result<Product> {
        val current = _products.value.toMutableList()
        val index = current.indexOfFirst { it.id == productId }
        if (index < 0) return Result.failure(NoSuchElementException("Producto no encontrado"))
        val updated = current[index].copy(isActive = isActive, updatedAt = System.currentTimeMillis())
        current[index] = updated
        _products.value = current
        saveProducts()
        return Result.success(updated)
    }

    fun deleteProduct(productId: String): Result<Boolean> {
        val current = _products.value
        val prod = current.find { it.id == productId }
            ?: return Result.failure(NoSuchElementException("Producto no encontrado"))

        // Check if product exists in historical sales
        val usedInSales = _sales.value.any { s -> s.items.any { it.productId == productId } }
        if (usedInSales) {
            // Soft delete to protect historical sales integrity
            return toggleProductActive(productId, isActive = false).map { true }
        }

        _products.value = current.filter { it.id != productId }
        saveProducts()
        return Result.success(true)
    }

    fun getActiveProducts(): List<Product> {
        return _products.value.filter { it.isActive }
    }

    fun searchProducts(query: String, categoryId: String? = null): List<Product> {
        val q = query.trim().lowercase(Locale.getDefault())
        return _products.value.filter { prod ->
            val matchesCat = categoryId == null || categoryId == "todos" || prod.categoryId == categoryId
            val matchesQuery = q.isEmpty() || prod.name.lowercase(Locale.getDefault()).contains(q) || prod.description.lowercase(Locale.getDefault()).contains(q)
            matchesCat && matchesQuery
        }
    }

    // =========================================================================
    // 5. INVENTORY MOVEMENTS AUDIT & MANUAL STOCK ADJUSTMENTS
    // =========================================================================

    fun adjustStock(
        productId: String,
        newStock: Double,
        reason: String,
        userName: String,
        type: String = "MANUAL_ADJUSTMENT",
        allowNegative: Boolean = false
    ): Result<InventoryMovement> {
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) return Result.failure(IllegalArgumentException("El motivo del ajuste es obligatorio"))
        val trimmedUser = userName.trim()
        if (trimmedUser.isEmpty()) return Result.failure(IllegalArgumentException("El nombre del usuario que autoriza el ajuste es obligatorio"))
        if (!allowNegative && newStock < 0.0) return Result.failure(IllegalArgumentException("El inventario resultante no puede ser negativo"))

        val current = _products.value.toMutableList()
        val index = current.indexOfFirst { it.id == productId }
        if (index < 0) return Result.failure(NoSuchElementException("Producto no encontrado"))

        val prod = current[index]
        val prev = prod.stockQuantity
        val delta = newStock - prev

        val updatedProd = prod.copy(stockQuantity = newStock, updatedAt = System.currentTimeMillis())
        current[index] = updatedProd
        _products.value = current

        val movement = logMovement(
            itemId = prod.id,
            itemName = prod.name,
            itemType = "PRODUCT",
            previousStock = prev,
            change = delta,
            resultingStock = newStock,
            type = type,
            reason = trimmedReason,
            userName = trimmedUser
        )

        saveProducts()
        return Result.success(movement)
    }

    fun recordPurchaseEntry(
        productId: String,
        quantity: Double,
        supplierOrNote: String,
        userName: String
    ): Result<InventoryMovement> {
        if (quantity <= 0.0) return Result.failure(IllegalArgumentException("La cantidad ingresada debe ser mayor a cero"))
        val prod = _products.value.find { it.id == productId }
            ?: return Result.failure(NoSuchElementException("Producto no encontrado"))

        val newStock = prod.stockQuantity + quantity
        val note = if (supplierOrNote.isNotBlank()) supplierOrNote.trim() else "Ingreso de compra"
        return adjustStock(
            productId = productId,
            newStock = newStock,
            reason = "Entrada de compra: $note",
            userName = userName,
            type = "PURCHASE"
        )
    }

    fun recordWaste(
        productId: String,
        quantity: Double,
        reason: String,
        userName: String
    ): Result<InventoryMovement> {
        if (quantity <= 0.0) return Result.failure(IllegalArgumentException("La cantidad de merma debe ser mayor a cero"))
        val prod = _products.value.find { it.id == productId }
            ?: return Result.failure(NoSuchElementException("Producto no encontrado"))

        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) return Result.failure(IllegalArgumentException("El motivo de la merma o baja es obligatorio"))

        val newStock = maxOf(0.0, prod.stockQuantity - quantity)
        return adjustStock(
            productId = productId,
            newStock = newStock,
            reason = "Baja / Merma: $trimmedReason",
            userName = userName,
            type = "WASTE_MERMA"
        )
    }

    private fun logMovement(
        itemId: String,
        itemName: String,
        itemType: String,
        previousStock: Double,
        change: Double,
        resultingStock: Double,
        type: String,
        reason: String,
        userName: String,
        saleId: String? = null
    ): InventoryMovement {
        val now = System.currentTimeMillis()
        val movement = InventoryMovement(
            id = nextUniqueId("mov"),
            itemId = itemId,
            itemName = itemName,
            itemType = itemType,
            previousStock = previousStock,
            change = change,
            resultingStock = resultingStock,
            type = type,
            reason = reason,
            saleId = saleId,
            date = now,
            dateFormatted = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()).format(Date(now)),
            userName = userName
        )
        _inventoryMovements.value = listOf(movement) + _inventoryMovements.value
        saveMovements()
        return movement
    }

    // =========================================================================
    // 6. ATOMIC & IDEMPOTENT SALE INVENTORY DEDUCTION
    // =========================================================================

    /**
     * Resolves all ingredient and direct product requirements recursively for cart items.
     * Combos resolve child products; products resolve recipes; toppings resolve ingredients.
     */
    fun resolveSaleRequirements(items: List<CartItem>): Pair<List<ResolvedIngredientConsumption>, List<ResolvedProductConsumption>> {
        val ingredientMap = mutableMapOf<String, Double>() // ingredientId -> total quantity in ingredient's base unit
        val productMap = mutableMapOf<String, Int>()       // productId -> total units

        fun resolveProductDeduction(productId: String, multiplier: Int, visited: MutableSet<String>) {
            if (!visited.add(productId)) return // circular reference guard
            val prod = _products.value.find { it.id == productId } ?: return

            if (prod.isCombo && prod.comboItems.isNotEmpty()) {
                // Recursively resolve combo items
                for (ci in prod.comboItems) {
                    resolveProductDeduction(ci.productId, ci.quantity * multiplier, visited.toMutableSet())
                }
            } else if (prod.recipe.isNotEmpty()) {
                // Resolve product recipe ingredients
                for (rec in prod.recipe) {
                    val ing = _ingredients.value.find { it.id == rec.ingredientId }
                    if (ing != null) {
                        val baseQty = try {
                            UnitConverter.convert(rec.quantity, rec.unit, ing.unit)
                        } catch (e: Exception) {
                            rec.quantity
                        }
                        val totalRequired = baseQty * multiplier
                        ingredientMap[ing.id] = (ingredientMap[ing.id] ?: 0.0) + totalRequired
                    }
                }
            } else {
                // Standalone direct product
                productMap[prod.id] = (productMap[prod.id] ?: 0) + multiplier
            }
        }

        for (item in items) {
            resolveProductDeduction(item.productId, item.quantity, mutableSetOf())

            // Process item toppings
            for (t in item.toppings) {
                if (t.ingredientId != null && t.gramsRequired > 0) {
                    val ing = _ingredients.value.find { it.id == t.ingredientId }
                    if (ing != null) {
                        val converted = try {
                            UnitConverter.convert(t.gramsRequired.toDouble(), "g", ing.unit)
                        } catch (e: Exception) {
                            t.gramsRequired.toDouble()
                        }
                        val total = converted * item.quantity
                        ingredientMap[ing.id] = (ingredientMap[ing.id] ?: 0.0) + total
                    }
                }
            }
        }

        val resolvedIngs = ingredientMap.map { (id, qty) ->
            val ing = _ingredients.value.first { it.id == id }
            ResolvedIngredientConsumption(id, ing.name, qty, ing.unit)
        }

        val resolvedProds = productMap.map { (id, qty) ->
            val prod = _products.value.first { it.id == id }
            ResolvedProductConsumption(id, prod.name, qty)
        }

        return Pair(resolvedIngs, resolvedProds)
    }

    /**
     * Completes a sale with idempotent, atomic inventory deduction.
     */
    @Synchronized
    fun completeSale(
        items: List<CartItem>,
        subtotal: Long,
        discount: Long,
        total: Long,
        paymentMethod: String,
        cashierName: String,
        senderName: String? = null,
        channel: String? = null,
        saleId: String? = null
    ): Result<SaleRecord> {
        val finalSaleId = saleId ?: nextUniqueId("sale")

        // Idempotency check: prevent duplicate deduction & duplicate sale registration
        if (_processedSaleIds.contains(finalSaleId) || _sales.value.any { it.id == finalSaleId }) {
            val existing = _sales.value.find { it.id == finalSaleId }
            if (existing != null) {
                _processedSaleIds.add(finalSaleId)
                return Result.success(existing)
            }
        }

        val (requiredIngredients, requiredProducts) = resolveSaleRequirements(items)

        // 1. Validate ingredient stock
        val currentIngs = _ingredients.value.associateBy { it.id }.toMutableMap()
        for (req in requiredIngredients) {
            val ing = currentIngs[req.ingredientId]
                ?: return Result.failure(IllegalStateException("Ingrediente '${req.ingredientName}' no existe en inventario"))
            if (ing.currentStock < req.consumedQuantity) {
                return Result.failure(
                    IllegalStateException("Stock insuficiente de '${ing.name}': disponible ${ing.currentStock} ${ing.unit}, requerido ${req.consumedQuantity} ${ing.unit}")
                )
            }
        }

        // 2. Validate standalone product stock (unless allowNegativeStock is true)
        val currentProds = _products.value.associateBy { it.id }.toMutableMap()
        for (req in requiredProducts) {
            val prod = currentProds[req.productId]
                ?: return Result.failure(IllegalStateException("Producto '${req.productName}' no encontrado"))
            if (!prod.allowNegativeStock && prod.stockQuantity < req.quantity) {
                return Result.failure(
                    IllegalStateException("Stock insuficiente del producto '${prod.name}': disponible ${prod.stockQuantity.toInt()}, requerido ${req.quantity}")
                )
            }
        }

        // 3. Atomically apply deductions
        val saleNo = "V-${(_sales.value.size + 101).toString().padStart(5, '0')}"
        val cogs = items.sumOf { it.estimatedCost }

        // Deduct ingredients
        for (req in requiredIngredients) {
            val ing = currentIngs[req.ingredientId]!!
            val newStock = maxOf(0.0, ing.currentStock - req.consumedQuantity)
            currentIngs[req.ingredientId] = ing.copy(currentStock = newStock)
            logMovement(
                itemId = ing.id,
                itemName = ing.name,
                itemType = "INGREDIENT",
                previousStock = ing.currentStock,
                change = -req.consumedQuantity,
                resultingStock = newStock,
                type = "SALE_RECIPE",
                reason = "Receta venta $saleNo",
                userName = cashierName,
                saleId = finalSaleId
            )
        }
        _ingredients.value = currentIngs.values.toList()
        saveIngredients()

        // Deduct standalone products
        for (req in requiredProducts) {
            val prod = currentProds[req.productId]!!
            val newStock = prod.stockQuantity - req.quantity
            currentProds[req.productId] = prod.copy(stockQuantity = newStock, updatedAt = System.currentTimeMillis())
            logMovement(
                itemId = prod.id,
                itemName = prod.name,
                itemType = "PRODUCT",
                previousStock = prod.stockQuantity,
                change = -(req.quantity.toDouble()),
                resultingStock = newStock,
                type = "SALE",
                reason = "Venta $saleNo",
                userName = cashierName,
                saleId = finalSaleId
            )
        }
        _products.value = currentProds.values.toList()
        saveProducts()

        // 4. Create Sale Record
        val sale = SaleRecord(
            id = finalSaleId,
            saleNumber = saleNo,
            items = items,
            subtotal = subtotal,
            discount = discount,
            total = total,
            paymentMethod = paymentMethod,
            cashAmount = if (paymentMethod == "CASH") total else 0L,
            nequiAmount = if (paymentMethod == "NEQUI") total else 0L,
            nequiSenderName = senderName,
            nequiChannel = channel,
            timestamp = getCurrentTime(),
            dateStr = getCurrentDate(),
            cashierName = cashierName,
            status = "COMPLETED",
            isInventoryDeducted = true,
            cogsCost = cogs
        )

        _sales.value = listOf(sale) + _sales.value
        _processedSaleIds.add(finalSaleId)
        saveSales()

        // Update cash shift and record payment transaction(s)
        if (paymentMethod == "CASH") {
            _cashShift.value = _cashShift.value.copy(
                cashIn = _cashShift.value.cashIn + total
            )
            recordPaymentTransaction(
                method = "CASH",
                amount = total,
                saleId = finalSaleId,
                saleNumber = saleNo,
                cashierName = cashierName,
                entryType = "SALE_PAYMENT",
                dateStr = getTodayDateKey(),
                time = getCurrentTime()
            )
        } else if (paymentMethod == "NEQUI") {
            recordPaymentTransaction(
                method = "NEQUI",
                amount = total,
                saleId = finalSaleId,
                saleNumber = saleNo,
                cashierName = cashierName,
                payerNameOrPhone = senderName,
                bankTransactionRef = null,
                smsEventId = "SMS-${System.currentTimeMillis()}",
                originInstallation = "Tienda Local",
                nequiChannel = channel,
                dateStr = getTodayDateKey(),
                time = getCurrentTime()
            )
        } else if (paymentMethod == "SPLIT") {
            if (sale.cashAmount > 0L) {
                _cashShift.value = _cashShift.value.copy(
                    cashIn = _cashShift.value.cashIn + sale.cashAmount
                )
                recordPaymentTransaction(
                    method = "CASH",
                    amount = sale.cashAmount,
                    saleId = finalSaleId,
                    saleNumber = saleNo,
                    cashierName = cashierName,
                    entryType = "SALE_PAYMENT",
                    dateStr = getTodayDateKey(),
                    time = getCurrentTime()
                )
            }
            if (sale.nequiAmount > 0L) {
                recordPaymentTransaction(
                    method = "NEQUI",
                    amount = sale.nequiAmount,
                    saleId = finalSaleId,
                    saleNumber = saleNo,
                    cashierName = cashierName,
                    payerNameOrPhone = senderName,
                    bankTransactionRef = null,
                    smsEventId = "SMS-${System.currentTimeMillis()}",
                    originInstallation = "Tienda Local",
                    nequiChannel = channel,
                    dateStr = getTodayDateKey(),
                    time = getCurrentTime()
                )
            }
        }

        return Result.success(sale)
    }

    /**
     * Annulls / returns a confirmed sale and registers compensatory inventory movements.
     */
    @Synchronized
    fun returnSale(saleId: String, reason: String, userName: String): Result<SaleRecord> {
        val currentSales = _sales.value.toMutableList()
        val index = currentSales.indexOfFirst { it.id == saleId }
        if (index < 0) return Result.failure(NoSuchElementException("Venta no encontrada"))

        val sale = currentSales[index]
        if (sale.status == "RETURNED") return Result.failure(IllegalStateException("La venta ya ha sido devuelta"))

        val trimmedReason = reason.trim().ifEmpty { "Devolución / Anulación de venta" }

        // Compensate inventory
        val (requiredIngredients, requiredProducts) = resolveSaleRequirements(sale.items)

        // Restore ingredients
        val currentIngs = _ingredients.value.associateBy { it.id }.toMutableMap()
        for (req in requiredIngredients) {
            val ing = currentIngs[req.ingredientId]
            if (ing != null) {
                val newStock = ing.currentStock + req.consumedQuantity
                currentIngs[req.ingredientId] = ing.copy(currentStock = newStock)
                logMovement(
                    itemId = ing.id,
                    itemName = ing.name,
                    itemType = "INGREDIENT",
                    previousStock = ing.currentStock,
                    change = req.consumedQuantity,
                    resultingStock = newStock,
                    type = "RETURN_COMPENSATION",
                    reason = "Compensación por devolución ${sale.saleNumber}: $trimmedReason",
                    userName = userName,
                    saleId = saleId
                )
            }
        }
        _ingredients.value = currentIngs.values.toList()
        saveIngredients()

        // Restore standalone products
        val currentProds = _products.value.associateBy { it.id }.toMutableMap()
        for (req in requiredProducts) {
            val prod = currentProds[req.productId]
            if (prod != null) {
                val newStock = prod.stockQuantity + req.quantity
                currentProds[req.productId] = prod.copy(stockQuantity = newStock, updatedAt = System.currentTimeMillis())
                logMovement(
                    itemId = prod.id,
                    itemName = prod.name,
                    itemType = "PRODUCT",
                    previousStock = prod.stockQuantity,
                    change = req.quantity.toDouble(),
                    resultingStock = newStock,
                    type = "RETURN_COMPENSATION",
                    reason = "Compensación por devolución ${sale.saleNumber}: $trimmedReason",
                    userName = userName,
                    saleId = saleId
                )
            }
        }
        _products.value = currentProds.values.toList()
        saveProducts()

        val updatedSale = sale.copy(status = "RETURNED")
        currentSales[index] = updatedSale
        _sales.value = currentSales

        return Result.success(updatedSale)
    }

    fun addCashExpense(concept: String, amount: Long) {
        val expense = CashMovement(
            id = "exp-${System.currentTimeMillis()}",
            concept = concept,
            amount = amount,
            type = "EXPENSE",
            time = getCurrentTime()
        )
        _cashShift.value = _cashShift.value.copy(
            expenses = _cashShift.value.expenses + expense
        )
    }

    fun adjustCashBase(
        newBaseAmount: Long,
        reason: String,
        userName: String = _activeCashier.value.name
    ): Result<CashShift> {
        if (newBaseAmount < 0L) {
            return Result.failure(IllegalArgumentException("La nueva base de caja no puede ser negativa"))
        }
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) {
            return Result.failure(IllegalArgumentException("El motivo del ajuste de base es obligatorio"))
        }
        val actor = userName.trim().ifEmpty { _activeCashier.value.name }
        val movement = CashMovement(
            id = nextUniqueId("base"),
            concept = "Ajuste de base ($actor): $trimmedReason",
            amount = newBaseAmount,
            type = "BASE_ADJUSTMENT",
            time = getCurrentTime()
        )
        val updated = _cashShift.value.copy(
            initialCash = newBaseAmount,
            expenses = _cashShift.value.expenses + movement
        )
        _cashShift.value = updated
        return Result.success(updated)
    }

    fun performBlindClose(countedCash: Long): CashShift {
        val current = _cashShift.value
        val totalExpenses = current.expenses
            .filter { it.type == "EXPENSE" || it.type == "OUT" }
            .sumOf { it.amount }
        val expected = current.initialCash + current.cashIn - totalExpenses
        val diff = countedCash - expected

        val closed = current.copy(
            status = "CLOSED",
            expectedCash = expected,
            countedCash = countedCash,
            difference = diff,
            closedAt = getCurrentTime()
        )
        _cashShift.value = closed
        return closed
    }

    // =========================================================================
    // 7. P2P SMS PROTOCOL DISPATCHER
    // =========================================================================

    fun sendProtocolCommand(cmd: String, amount: Long): Boolean {
        val settings = settingsRepo?.getSettings() ?: return false
        val targetPhone = settings.peerStorePhone.trim()

        if (!settings.peerStoreEnabled || targetPhone.isEmpty()) return false

        val opId = _activeOpId.value ?: "op-${System.currentTimeMillis().toString().takeLast(6)}"
        _activeOpId.value = opId

        val seq = _smsLogs.value.size + 1
        val ts = System.currentTimeMillis()
        val prePayload = "APOS|v1|msg-$ts|$opId|$cmd|$seq|${settings.localStoreId}|${settings.peerStoreId}|$ts|120|$amount"
        val hmac = generateHmacSha256(prePayload, settings.peerSharedSecret)
        val fullPayload = "$prePayload|$hmac"

        var sent = false
        try {
            if (context != null &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.SEND_SMS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val smsManager: SmsManager = context.getSystemService(SmsManager::class.java)
                    ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                smsManager.sendTextMessage(targetPhone, null, fullPayload, null, null)
                sent = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sent = false
        }

        val log = ProtocolMessageLog(
            id = "msg-$ts",
            opId = opId,
            fromStore = settings.localStoreName,
            toStore = settings.peerStoreName,
            targetPhone = targetPhone,
            cmd = cmd,
            amount = amount,
            seq = seq,
            timestamp = getCurrentTime(),
            rawPayload = fullPayload,
            hmacVerified = true,
            smsDispatched = sent,
            replyReceived = false,
            operationConfirmed = false,
            status = if (sent) "DISPATCHED" else "FAILED"
        )
        _smsLogs.value = listOf(log) + _smsLogs.value

        if (cmd == "RESERVE") {
            _protocolState.value = ProtocolState.RESERVE_PENDING
        } else if (cmd == "CANCEL") {
            _protocolState.value = ProtocolState.CANCEL_PENDING
        }

        return sent
    }

    fun setProtocolState(state: ProtocolState) {
        _protocolState.value = state
        if (state == ProtocolState.DRAFT || state == ProtocolState.CANCELLED || state == ProtocolState.RESOLVED) {
            _activeOpId.value = null
        }
    }

    private fun generateHmacSha256(data: String, key: String): String {
        return try {
            val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(secretKey)
            val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            "hmac_mock_sig"
        }
    }

    fun getCurrentTime(): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    }

    fun getCurrentDate(): String {
        return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
    }

    fun getNormalizedDateKey(dateInput: String): String {
        val trimmed = dateInput.trim()
        if (trimmed.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) return trimmed
        if (trimmed.matches(Regex("""^\d{1,2}/\d{1,2}/\d{4}$"""))) {
            val parts = trimmed.split("/")
            val day = parts[0].padStart(2, '0')
            val month = parts[1].padStart(2, '0')
            val year = parts[2]
            return "$year-$month-$day"
        }
        return trimmed
    }

    fun getTodayDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    // =========================================================================
    // FINANCIAL HISTORY, EXPENSES & CALENDAR
    // =========================================================================

    @Synchronized
    fun recordPaymentTransaction(
        method: String,
        amount: Long,
        saleId: String? = null,
        saleNumber: String? = null,
        cashierName: String = _activeCashier.value.name,
        payerNameOrPhone: String? = null,
        bankTransactionRef: String? = null,
        smsEventId: String? = null,
        originInstallation: String = "Tienda Local",
        nequiChannel: String? = null,
        entryType: String = "SALE_PAYMENT",
        notes: String = "",
        dateStr: String? = null,
        time: String? = null,
        status: String = "CONFIRMED"
    ): PaymentTransaction {
        val finalDate = dateStr?.let { getNormalizedDateKey(it) } ?: getTodayDateKey()
        val finalTime = time ?: getCurrentTime()

        // Idempotency guard: prevent duplicate payment transaction for the same saleId + method
        if (saleId != null && entryType == "SALE_PAYMENT") {
            val existingForSale = _paymentTransactions.value.find {
                it.saleId == saleId && it.method == method && it.entryType == "SALE_PAYMENT"
            }
            if (existingForSale != null) return existingForSale
        }

        val tx = PaymentTransaction(
            id = nextUniqueId("tx"),
            method = method,
            amount = amount,
            dateStr = finalDate,
            time = finalTime,
            timestamp = System.currentTimeMillis(),
            status = status,
            saleId = saleId,
            saleNumber = saleNumber,
            cashierName = cashierName,
            payerNameOrPhone = payerNameOrPhone,
            bankTransactionRef = bankTransactionRef,
            smsEventId = smsEventId ?: if (method == "NEQUI") nextUniqueId("SMS") else null,
            originInstallation = originInstallation,
            nequiChannel = nequiChannel,
            entryType = entryType,
            notes = notes
        )
        _paymentTransactions.value = listOf(tx) + _paymentTransactions.value
        savePaymentTransactions()
        return tx
    }

    @Synchronized
    fun recordExpense(
        amount: Long,
        category: String,
        description: String,
        paymentSource: String = "Caja efectivo",
        cashierName: String = _activeCashier.value.name,
        notes: String = "",
        dateStr: String? = null,
        isLinkedToInventory: Boolean = false
    ): Result<ExpenseRecord> {
        if (amount <= 0L) {
            return Result.failure(IllegalArgumentException("El monto del gasto debe ser mayor a 0"))
        }
        if (description.isBlank()) {
            return Result.failure(IllegalArgumentException("La descripción del gasto es obligatoria"))
        }
        val finalDate = dateStr?.let { getNormalizedDateKey(it) } ?: getTodayDateKey()
        val expense = ExpenseRecord(
            id = nextUniqueId("exp"),
            amount = amount,
            category = category.trim(),
            description = description.trim(),
            paymentSource = paymentSource,
            cashierName = cashierName,
            notes = notes.trim(),
            dateStr = finalDate,
            time = getCurrentTime(),
            timestamp = System.currentTimeMillis(),
            isLinkedToInventory = isLinkedToInventory,
            status = "ACTIVE"
        )
        _expenses.value = listOf(expense) + _expenses.value
        saveExpenses()
        return Result.success(expense)
    }

    @Synchronized
    fun annulExpense(expenseId: String, reason: String, userName: String): Result<ExpenseRecord> {
        val current = _expenses.value.toMutableList()
        val idx = current.indexOfFirst { it.id == expenseId }
        if (idx < 0) return Result.failure(NoSuchElementException("Gasto no encontrado"))
        val existing = current[idx]
        val updated = existing.copy(
            status = "CANCELLED",
            notes = if (existing.notes.isBlank()) "Anulado por $userName: $reason" else "${existing.notes} | Anulado por $userName: $reason"
        )
        current[idx] = updated
        _expenses.value = current
        saveExpenses()
        return Result.success(updated)
    }

    fun getDailyFinancialSummary(dateInput: String): DailyFinancialSummary {
        val normalizedDate = getNormalizedDateKey(dateInput)

        // 1. Nequi transactions for this day
        val dayNequiTxns = _paymentTransactions.value.filter {
            it.method == "NEQUI" && getNormalizedDateKey(it.dateStr) == normalizedDate
        }
        val totalNequi = dayNequiTxns.filter { it.status == "CONFIRMED" }.sumOf { it.amount }

        // 2. Cash transactions for this day
        val dayCashTxns = _paymentTransactions.value.filter {
            it.method == "CASH" && getNormalizedDateKey(it.dateStr) == normalizedDate
        }
        val totalCash = dayCashTxns.filter { it.status == "CONFIRMED" }.sumOf { it.amount }

        // Formula: Total recibido = Total Nequi + Total efectivo
        val totalReceived = totalNequi + totalCash

        // 3. Expenses for this day
        val dayExpenses = _expenses.value.filter {
            getNormalizedDateKey(it.dateStr) == normalizedDate
        }
        val totalExpenses = dayExpenses.filter { it.status == "ACTIVE" }.sumOf { it.amount }

        // Formula: Balance de movimientos = Total recibido - Total gastos
        val movementBalance = totalReceived - totalExpenses

        // 4. Products sold on this date (Lo que se vendió ese día)
        // Grouped strictly from completed sales on that date.
        // Rule: Combos are recorded as the combo itself without inflating individual ingredients or components.
        val daySales = _sales.value.filter {
            it.status == "COMPLETED" && getNormalizedDateKey(it.dateStr) == normalizedDate
        }

        val productOccurrences = mutableMapOf<String, MutableList<Pair<SaleRecord, CartItem>>>()
        for (sale in daySales) {
            for (item in sale.items) {
                productOccurrences.getOrPut(item.productId) { mutableListOf() }.add(Pair(sale, item))
            }
        }

        val allProductsMap = _products.value.associateBy { it.id }

        val productsSoldList = productOccurrences.map { (prodId, pairs) ->
            val firstItem = pairs.first().second
            val isCombo = allProductsMap[prodId]?.isCombo ?: (firstItem.cartItemId.startsWith("combo"))
            val totalQty = pairs.sumOf { it.second.quantity }
            val totalRev = pairs.sumOf { it.second.totalPrice }
            val occurrences = pairs.map { (sale, item) ->
                ProductSaleOccurrence(
                    saleId = sale.id,
                    saleNumber = sale.saleNumber,
                    time = sale.timestamp,
                    cashierName = sale.cashierName,
                    quantity = item.quantity,
                    unitPrice = item.unitPrice,
                    totalPrice = item.totalPrice
                )
            }
            ProductSaleSummary(
                productId = prodId,
                productName = firstItem.productName,
                totalQuantity = totalQty,
                unitSalePrice = firstItem.unitPrice, // Historical snapshot price
                totalRevenue = totalRev,
                discountsTotal = 0L,
                salesCount = pairs.map { it.first.id }.distinct().size,
                isCombo = isCombo,
                salesDetails = occurrences
            )
        }.sortedByDescending { it.totalRevenue }

        val totalUnits = productsSoldList.sumOf { it.totalQuantity }

        return DailyFinancialSummary(
            dateStr = normalizedDate,
            totalNequi = totalNequi,
            nequiCount = dayNequiTxns.size,
            nequiTransactions = dayNequiTxns,
            totalCash = totalCash,
            cashCount = dayCashTxns.size,
            cashTransactions = dayCashTxns,
            totalReceived = totalReceived,
            totalExpenses = totalExpenses,
            expensesCount = dayExpenses.size,
            expenses = dayExpenses,
            movementBalance = movementBalance,
            productsSold = productsSoldList,
            totalUnitsSold = totalUnits,
            salesCount = daySales.size
        )
    }

    fun getMonthCalendarDays(year: Int, month: Int): List<MonthDayIndicator> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(year, month - 1, 1)
        val maxDays = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

        val indicators = mutableListOf<MonthDayIndicator>()
        for (day in 1..maxDays) {
            val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
            val summary = getDailyFinancialSummary(dateStr)
            indicators.add(
                MonthDayIndicator(
                    dateStr = dateStr,
                    dayNumber = day,
                    hasSalesOrPayments = summary.totalReceived > 0L || summary.salesCount > 0,
                    hasExpenses = summary.totalExpenses > 0L,
                    totalReceived = summary.totalReceived,
                    totalExpenses = summary.totalExpenses
                )
            )
        }
        return indicators
    }

    // =========================================================================
    // 8. PERSISTENCE HELPERS
    // =========================================================================

    private fun loadData() {
        // Load Cashiers
        val cashiersJson = storage.getString(KEY_CASHIERS, null)
        val loadedCashiers = if (!cashiersJson.isNullOrBlank()) {
            decodeCashiers(cashiersJson)
        } else {
            defaultCashiers()
        }
        _cashiers.value = loadedCashiers

        val activeId = storage.getString(KEY_ACTIVE_CASHIER_ID, null)
        val foundActive = loadedCashiers.find { it.id == activeId } ?: loadedCashiers.firstOrNull()
        if (foundActive != null) {
            _activeCashier.value = foundActive
            _cashShift.value = _cashShift.value.copy(cashierName = foundActive.name)
        }

        // Load Categories
        val catsJson = storage.getString(KEY_CATEGORIES, null)
        _categories.value = if (!catsJson.isNullOrBlank()) {
            decodeCategories(catsJson)
        } else {
            defaultCategories()
        }

        // Load Ingredients
        val ingsJson = storage.getString(KEY_INGREDIENTS, null)
        _ingredients.value = if (!ingsJson.isNullOrBlank()) {
            decodeIngredients(ingsJson)
        } else {
            defaultIngredients()
        }

        // Load Toppings
        val toppingsJson = storage.getString(KEY_TOPPINGS, null)
        _toppings.value = if (!toppingsJson.isNullOrBlank()) {
            decodeToppings(toppingsJson)
        } else {
            defaultToppings()
        }

        // Load Products & Combos
        val prodsJson = storage.getString(KEY_PRODUCTS, null)
        _products.value = if (!prodsJson.isNullOrBlank()) {
            decodeProducts(prodsJson)
        } else {
            defaultProducts()
        }

        // Load Movements
        val movsJson = storage.getString(KEY_MOVEMENTS, null)
        if (!movsJson.isNullOrBlank()) {
            _inventoryMovements.value = decodeMovements(movsJson)
        }

        // Load Sales
        val salesJson = storage.getString(KEY_SALES, null)
        _sales.value = if (!salesJson.isNullOrBlank()) {
            decodeSales(salesJson)
        } else {
            defaultSales()
        }
        _processedSaleIds.clear()
        _processedSaleIds.addAll(_sales.value.map { it.id })

        // Load Expenses
        val expensesJson = storage.getString(KEY_EXPENSES, null)
        _expenses.value = if (!expensesJson.isNullOrBlank()) {
            decodeExpenses(expensesJson)
        } else {
            defaultExpenses()
        }

        // Load Payment Transactions
        val paymentsJson = storage.getString(KEY_PAYMENT_TRANSACTIONS, null)
        _paymentTransactions.value = if (!paymentsJson.isNullOrBlank()) {
            decodePaymentTransactions(paymentsJson)
        } else {
            defaultPaymentTransactions()
        }

        // Load Store Configuration
        storage.getString(KEY_LOCAL_STORE_ID, null)?.takeIf { it.isNotBlank() }?.let { configuredLocalStoreId = it }
        storage.getString(KEY_PEER_STORE_ID, null)?.takeIf { it.isNotBlank() }?.let { configuredPeerStoreId = it }
        storage.getString(KEY_PEER_PHONE, null)?.takeIf { it.isNotBlank() }?.let { configuredPeerPhone = it }

        // Load Reservations
        val resJson = storage.getString(KEY_RESERVATIONS, null)
        if (!resJson.isNullOrBlank()) {
            _reservations.value = decodeReservations(resJson)
        }

        // Load Bank Notifications
        val notifJson = storage.getString(KEY_BANK_NOTIFICATIONS, null)
        if (!notifJson.isNullOrBlank()) {
            _bankNotifications.value = decodeBankNotifications(notifJson)
        }

        // Load Outbox Queue
        val outboxJson = storage.getString(KEY_OUTBOX_MESSAGES, null)
        if (!outboxJson.isNullOrBlank()) {
            _outboxMessages.value = decodeOutboxMessages(outboxJson)
        }

        // Load Processed Protocol Message IDs
        val procMsgs = storage.getString(KEY_PROCESSED_MSG_IDS, null)
        _processedProtocolMsgIds.clear()
        if (!procMsgs.isNullOrBlank()) {
            _processedProtocolMsgIds.addAll(procMsgs.split(",").filter { it.isNotBlank() })
        }
    }

    private fun saveCashiers() {
        storage.putString(KEY_CASHIERS, encodeCashiers(_cashiers.value))
        storage.putString(KEY_ACTIVE_CASHIER_ID, _activeCashier.value.id)
    }

    private fun saveCategories() {
        storage.putString(KEY_CATEGORIES, encodeCategories(_categories.value))
    }

    private fun saveIngredients() {
        storage.putString(KEY_INGREDIENTS, encodeIngredients(_ingredients.value))
    }

    private fun saveToppings() {
        storage.putString(KEY_TOPPINGS, encodeToppings(_toppings.value))
    }

    private fun saveProducts() {
        storage.putString(KEY_PRODUCTS, encodeProducts(_products.value))
    }

    private fun saveMovements() {
        storage.putString(KEY_MOVEMENTS, encodeMovements(_inventoryMovements.value.take(300)))
    }

    fun saveSales() {
        storage.putString(KEY_SALES, encodeSales(_sales.value.take(200)))
    }

    fun saveExpenses() {
        storage.putString(KEY_EXPENSES, encodeExpenses(_expenses.value.take(300)))
    }

    fun savePaymentTransactions() {
        storage.putString(KEY_PAYMENT_TRANSACTIONS, encodePaymentTransactions(_paymentTransactions.value.take(500)))
    }

    fun saveReservations() {
        storage.putString(KEY_RESERVATIONS, encodeReservations(_reservations.value.take(300)))
    }

    fun saveBankNotifications() {
        storage.putString(KEY_BANK_NOTIFICATIONS, encodeBankNotifications(_bankNotifications.value.take(300)))
    }

    fun saveOutboxMessages() {
        storage.putString(KEY_OUTBOX_MESSAGES, encodeOutboxMessages(_outboxMessages.value.take(300)))
    }

    private fun saveProcessedProtocolMsgIds() {
        storage.putString(KEY_PROCESSED_MSG_IDS, _processedProtocolMsgIds.take(500).joinToString(","))
    }

    // =========================================================================
    // 9. NEQUI RESERVATIONS, P2P SMS COORDINATION & MANUAL ASSIGNMENT (Sections 4-21)
    // =========================================================================

    fun configureStore(
        localStoreId: String,
        peerStoreId: String,
        peerPhone: String = "3000000000",
        enabled: Boolean = true
    ) {
        configuredLocalStoreId = localStoreId.trim().uppercase()
        configuredPeerStoreId = peerStoreId.trim().uppercase()
        configuredPeerPhone = peerPhone.trim()
        configuredPeerEnabled = enabled
        storage.putString(KEY_LOCAL_STORE_ID, configuredLocalStoreId)
        storage.putString(KEY_PEER_STORE_ID, configuredPeerStoreId)
        storage.putString(KEY_PEER_PHONE, configuredPeerPhone)
    }

    fun getLocalStoreId(): String {
        val settings = settingsRepo?.getSettings()
        val fromSettings = settings?.localStoreId
        if (!fromSettings.isNullOrBlank() && fromSettings != "STORE_01") {
            return fromSettings.trim().uppercase()
        }
        val peerName = settings?.peerStoreName?.uppercase() ?: ""
        val localName = "${settings?.businessName.orEmpty()} ${settings?.localStoreName.orEmpty()}".uppercase()
        return when {
            localName.contains("PIZZ") || peerName.contains("HELAD") -> "PIZZERIA"
            localName.contains("HELAD") || peerName.contains("PIZZ") -> "HELADERIA"
            else -> configuredLocalStoreId
        }
    }

    fun getPeerStoreId(): String {
        val settings = settingsRepo?.getSettings()
        val fromSettings = settings?.peerStoreId
        if (!fromSettings.isNullOrBlank() && fromSettings != "STORE_02") {
            return fromSettings.trim().uppercase()
        }
        return if (getLocalStoreId().equals("PIZZERIA", ignoreCase = true)) {
            "HELADERIA"
        } else {
            "PIZZERIA"
        }
    }

    fun getPeerPhone(): String {
        val fromSettings = settingsRepo?.getSettings()?.peerStorePhone
        return if (!fromSettings.isNullOrBlank()) fromSettings.trim() else configuredPeerPhone
    }

    @Synchronized
    fun createNequiReservation(
        amount: Long,
        orderId: String = nextUniqueId("ORD"),
        items: List<CartItem> = emptyList(),
        subtotal: Long = amount,
        discount: Long = 0L,
        cashierName: String = _activeCashier.value.name,
        reservationId: String? = null
    ): Result<PaymentReservation> {
        if (amount <= 0L) {
            return Result.failure(IllegalArgumentException("El valor de la reserva debe ser mayor a 0"))
        }

        val storePrefix = getLocalStoreId().take(1).ifEmpty { "H" }
        val finalResId = reservationId ?: "$storePrefix-${System.currentTimeMillis()}-${idSequence.incrementAndGet()}"

        // Idempotent if reservationId already exists
        val existing = _reservations.value.find { it.id == finalResId }
        if (existing != null) {
            return Result.success(existing)
        }

        val now = System.currentTimeMillis()
        val cartJson = encodeCartItemsList(items)

        // 1. Save reservation persistently BEFORE attempting SMS send (Section 4)
        val initialRes = PaymentReservation(
            id = finalResId,
            orderId = orderId,
            deviceId = "dev-${getLocalStoreId().lowercase()}",
            storeId = getLocalStoreId(),
            amount = amount,
            subtotal = subtotal,
            discount = discount,
            cashierName = cashierName,
            createdAt = now,
            state = ReservationState.ACTIVE,
            syncState = SyncState.PENDING_SEND,
            protocolVersion = AmbiorixSmsProtocol.VERSION,
            seq = 1,
            isPendingHold = false,
            cartSnapshotJson = cartJson
        )

        _reservations.value = listOf(initialRes) + _reservations.value
        saveReservations()

        // 2. Build RESERVE SMS and dispatch via transport
        val msgId = "M-RES-$finalResId"
        val payload = AmbiorixSmsProtocol.buildReserve(
            id = finalResId,
            orderId = orderId,
            src = getLocalStoreId(),
            amount = amount,
            timestamp = now,
            msgId = msgId,
            seq = 1,
            dst = getPeerStoreId()
        )

        val sent = enqueueAndDispatchOutbox(
            msgId = msgId,
            command = AmbiorixSmsProtocol.CMD_RESERVE,
            payload = payload,
            relatedEntityId = finalResId
        )

        val currentRes = _reservations.value.find { it.id == finalResId } ?: initialRes
        val updatedSyncState = when {
            currentRes.syncState == SyncState.ACKNOWLEDGED -> SyncState.ACKNOWLEDGED
            !sent -> SyncState.RETRY_PENDING
            isAsyncAndroidTransport() -> SyncState.PENDING_SEND
            else -> SyncState.SENT
        }
        val updatedRes = currentRes.copy(
            syncState = updatedSyncState,
            lastAttemptTimestamp = now,
            retryCount = if (sent) 0 else 1
        )
        updateReservationInList(updatedRes)
        _activeOpId.value = finalResId
        _protocolState.value = ProtocolState.WAITING_PAYMENT

        return Result.success(updatedRes)
    }

    @Synchronized
    fun leaveReservationPending(reservationId: String): Result<PaymentReservation> {
        val existing = _reservations.value.find { it.id == reservationId }
            ?: return Result.failure(NoSuchElementException("Reserva no encontrada"))

        if (existing.state == ReservationState.CANCELLED || existing.state == ReservationState.REPLACED_BY_CASH) {
            return Result.failure(IllegalStateException("No se puede dejar pendiente una reserva cancelada"))
        }

        val updated = existing.copy(isPendingHold = true)
        updateReservationInList(updated)
        return Result.success(updated)
    }

    fun getRecoverableReservations(): List<PaymentReservation> {
        val localStore = getLocalStoreId()
        return _reservations.value.filter {
            it.storeId == localStore &&
                (it.state == ReservationState.ACTIVE ||
                    it.state == ReservationState.PAYMENT_CANDIDATE ||
                    it.state == ReservationState.CREATED ||
                    it.state == ReservationState.SENT ||
                    it.state == ReservationState.ACKNOWLEDGED)
        }
    }

    fun recoverReservationCart(reservationId: String): List<CartItem> {
        val res = _reservations.value.find { it.id == reservationId } ?: return emptyList()
        val json = res.cartSnapshotJson ?: return emptyList()
        return decodeCartItemsList(json)
    }

    @Synchronized
    fun cancelNequiReservation(
        reservationId: String,
        reason: String = "USER_CANCEL"
    ): Result<PaymentReservation> {
        val existing = _reservations.value.find { it.id == reservationId }
            ?: return Result.failure(NoSuchElementException("Reserva no encontrada"))

        if (existing.state == ReservationState.COMPLETED) {
            return Result.failure(IllegalStateException("La reserva ya fue completada"))
        }
        if (existing.state == ReservationState.CANCELLED) {
            return Result.success(existing)
        }

        val now = System.currentTimeMillis()
        val nextSeq = existing.seq + 1
        val cancelled = existing.copy(
            state = ReservationState.CANCELLED,
            cancellationReason = reason,
            seq = nextSeq,
            syncState = SyncState.PENDING_SEND,
            isPendingHold = false
        )
        updateReservationInList(cancelled)

        val msgId = "M-CAN-$reservationId-$nextSeq"
        val payload = AmbiorixSmsProtocol.buildCancel(
            id = reservationId,
            src = getLocalStoreId(),
            amount = existing.amount,
            reason = reason,
            timestamp = now,
            msgId = msgId,
            seq = nextSeq,
            dst = getPeerStoreId()
        )

        val sent = enqueueAndDispatchOutbox(
            msgId = msgId,
            command = AmbiorixSmsProtocol.CMD_CANCEL,
            payload = payload,
            relatedEntityId = reservationId
        )

        val currentAfterCancel = _reservations.value.find { it.id == reservationId } ?: cancelled
        val finalRes = currentAfterCancel.copy(
            syncState = when {
                currentAfterCancel.syncState == SyncState.ACKNOWLEDGED -> SyncState.ACKNOWLEDGED
                !sent -> SyncState.RETRY_PENDING
                isAsyncAndroidTransport() -> SyncState.PENDING_SEND
                else -> SyncState.SENT
            },
            lastAttemptTimestamp = now
        )
        updateReservationInList(finalRes)
        if (_activeOpId.value == reservationId) {
            _protocolState.value = ProtocolState.CANCELLED
        }
        return Result.success(finalRes)
    }

    @Synchronized
    fun switchNequiReservationToCash(reservationId: String): Result<PaymentReservation> {
        val existing = _reservations.value.find { it.id == reservationId }
            ?: return Result.failure(NoSuchElementException("Reserva no encontrada"))

        if (existing.state == ReservationState.COMPLETED) {
            return Result.failure(IllegalStateException("La reserva ya fue completada por Nequi"))
        }
        if (existing.state == ReservationState.REPLACED_BY_CASH) {
            return Result.success(existing)
        }

        val now = System.currentTimeMillis()
        val nextSeq = existing.seq + 1
        val replaced = existing.copy(
            state = ReservationState.REPLACED_BY_CASH,
            cancellationReason = "REPLACED_BY_CASH",
            seq = nextSeq,
            syncState = SyncState.PENDING_SEND,
            isPendingHold = false
        )
        updateReservationInList(replaced)

        val msgId = "M-CAN-$reservationId-$nextSeq"
        val payload = AmbiorixSmsProtocol.buildCancel(
            id = reservationId,
            src = getLocalStoreId(),
            amount = existing.amount,
            reason = "REPLACED_BY_CASH",
            timestamp = now,
            msgId = msgId,
            seq = nextSeq,
            dst = getPeerStoreId()
        )

        val sent = enqueueAndDispatchOutbox(
            msgId = msgId,
            command = AmbiorixSmsProtocol.CMD_CANCEL,
            payload = payload,
            relatedEntityId = reservationId
        )

        val currentAfterSwitch = _reservations.value.find { it.id == reservationId } ?: replaced
        val finalRes = currentAfterSwitch.copy(
            syncState = when {
                currentAfterSwitch.syncState == SyncState.ACKNOWLEDGED -> SyncState.ACKNOWLEDGED
                !sent -> SyncState.RETRY_PENDING
                isAsyncAndroidTransport() -> SyncState.PENDING_SEND
                else -> SyncState.SENT
            },
            lastAttemptTimestamp = now
        )
        updateReservationInList(finalRes)
        return Result.success(finalRes)
    }

    @Synchronized
    fun completeCashSaleFromReservation(
        reservationId: String,
        cashierName: String = _activeCashier.value.name
    ): Result<SaleRecord> {
        val resAfterSwitch = switchNequiReservationToCash(reservationId).getOrElse {
            val current = _reservations.value.find { r -> r.id == reservationId }
                ?: return Result.failure(it)
            current
        }

        val deterministicSaleId = "sale-cash-$reservationId"
        if (_processedSaleIds.contains(deterministicSaleId)) {
            val existingSale = _sales.value.find { it.id == deterministicSaleId }
            if (existingSale != null) return Result.success(existingSale)
        }

        val items = recoverReservationCart(reservationId)
        val saleResult = completeSale(
            items = items,
            subtotal = resAfterSwitch.subtotal,
            discount = resAfterSwitch.discount,
            total = resAfterSwitch.amount,
            paymentMethod = "CASH",
            cashierName = cashierName,
            saleId = deterministicSaleId
        )

        if (saleResult.isSuccess) {
            val sale = saleResult.getOrThrow()
            updateReservationInList(resAfterSwitch.copy(saleId = sale.id))
        }
        return saleResult
    }

    @Synchronized
    fun retryPendingOutboxMessages(): Int {
        var successCount = 0
        val now = System.currentTimeMillis()
        val updatedOutbox = _outboxMessages.value.map { msg ->
            if (msg.acknowledged) {
                msg
            } else {
                val sent = coordinationTransport.sendCommand(msg.payload, msg.targetPhone)
                if (sent) {
                    successCount++
                    val targetSyncState = if (isAsyncAndroidTransport()) SyncState.PENDING_SEND else SyncState.SENT
                    // Update related reservation or notification syncState
                    _reservations.value.find { it.id == msg.relatedEntityId }?.let { r ->
                        if (r.syncState == SyncState.RETRY_PENDING || r.syncState == SyncState.PENDING_SEND || r.syncState == SyncState.NOT_SENT) {
                            updateReservationInList(r.copy(syncState = targetSyncState, lastAttemptTimestamp = now, retryCount = msg.retryCount + 1))
                        }
                    }
                    _bankNotifications.value.find { it.id == msg.relatedEntityId }?.let { n ->
                        if (n.syncState == SyncState.RETRY_PENDING || n.syncState == SyncState.PENDING_SEND || n.syncState == SyncState.NOT_SENT) {
                            updateBankNotificationInList(n.copy(syncState = targetSyncState))
                        }
                    }
                    msg.copy(retryCount = msg.retryCount + 1, lastAttemptAt = now)
                } else {
                    msg.copy(retryCount = msg.retryCount + 1, lastAttemptAt = now)
                }
            }
        }
        _outboxMessages.value = updatedOutbox
        saveOutboxMessages()
        return successCount
    }

    @Synchronized
    fun onSmsSendResult(msgId: String, relatedEntityId: String, isSuccess: Boolean, errorCode: Int = 0) {
        val now = System.currentTimeMillis()
        if (isSuccess) {
            _reservations.value.find { it.id == relatedEntityId }?.let { r ->
                if (r.syncState != SyncState.ACKNOWLEDGED) {
                    updateReservationInList(r.copy(syncState = SyncState.SENT, lastAttemptTimestamp = now))
                }
            }
            _bankNotifications.value.find { it.id == relatedEntityId }?.let { n ->
                if (n.syncState != SyncState.ACKNOWLEDGED) {
                    updateBankNotificationInList(n.copy(syncState = SyncState.SENT))
                }
            }
        } else {
            _outboxMessages.value = _outboxMessages.value.map { m ->
                if (m.id == msgId || (msgId.isEmpty() && m.relatedEntityId == relatedEntityId && !m.acknowledged)) {
                    m.copy(retryCount = m.retryCount + 1, lastAttemptAt = now)
                } else m
            }
            saveOutboxMessages()
            _reservations.value.find { it.id == relatedEntityId }?.let { r ->
                if (r.syncState != SyncState.ACKNOWLEDGED) {
                    updateReservationInList(r.copy(syncState = SyncState.RETRY_PENDING, lastAttemptTimestamp = now, retryCount = r.retryCount + 1))
                }
            }
            _bankNotifications.value.find { it.id == relatedEntityId }?.let { n ->
                if (n.syncState != SyncState.ACKNOWLEDGED) {
                    updateBankNotificationInList(n.copy(syncState = SyncState.RETRY_PENDING))
                }
            }
        }
    }

    @Synchronized
    fun processIncomingCoordinationSms(rawBody: String, senderPhone: String = getPeerPhone()): Boolean {
        val parsed = AmbiorixSmsProtocol.parse(rawBody) ?: return false

        // Remember peer phone if received from a real cellular number and not yet configured
        if (senderPhone.isNotBlank() && getPeerPhone().isBlank()) {
            configuredPeerPhone = senderPhone.trim()
            storage.putString(KEY_PEER_PHONE, configuredPeerPhone)
        }

        // Validate origin store is not self
        val parsedSrc = parsed.src
        if (parsedSrc != null && parsedSrc.equals(getLocalStoreId(), ignoreCase = true) && !AmbiorixSmsProtocol.isAckOrResponseCommand(parsed.command)) {
            val isOwnLocalEntity = (parsed.id != null && _reservations.value.any { it.id == parsed.id && it.deviceId.startsWith("dev-") }) ||
                (parsed.tx != null && _outboxMessages.value.any { it.relatedEntityId == parsed.tx })
            if (isOwnLocalEntity || context == null) {
                return false
            }
            // On a real Android device, if a remote peer SMS arrives with src == default localStoreId (because localStoreId wasn't customized),
            // automatically adopt the opposite store identity (PIZZERIA <-> HELADERIA) so the coordination SMS and ACK work seamlessly.
            val oppositeStore = if (parsedSrc.equals("HELADERIA", ignoreCase = true)) "PIZZERIA" else "HELADERIA"
            configuredLocalStoreId = oppositeStore
            configuredPeerStoreId = parsedSrc.uppercase()
            storage.putString(KEY_LOCAL_STORE_ID, configuredLocalStoreId)
            storage.putString(KEY_PEER_STORE_ID, configuredPeerStoreId)
        }

        val incomingMsgId = parsed.msgId ?: "${parsed.command}-${parsed.id ?: parsed.tx ?: parsed.timestamp}"
        val alreadyProcessed = _processedProtocolMsgIds.contains(incomingMsgId)

        // Prevent infinite ACK loops: ACK/response commands never generate another ACK
        if (AmbiorixSmsProtocol.isAckOrResponseCommand(parsed.command)) {
            if (alreadyProcessed) return true
            _processedProtocolMsgIds.add(incomingMsgId)
            saveProcessedProtocolMsgIds()
            handleAckOrResponseCommand(parsed)
            return true
        }

        // For non-ACK commands: if duplicate SMS delivery, re-send technical ACK without duplicating state changes
        if (alreadyProcessed) {
            sendAckForCommand(parsed, senderPhone)
            return true
        }

        _processedProtocolMsgIds.add(incomingMsgId)
        saveProcessedProtocolMsgIds()

        when (parsed.command) {
            AmbiorixSmsProtocol.CMD_RESERVE -> {
                val resId = parsed.id ?: return false
                val amount = parsed.amount ?: return false
                if (amount <= 0L) return false
                val originStore = parsed.src ?: getPeerStoreId()
                val orderId = parsed.orderId ?: "ORD-$resId"
                val ts = parsed.timestamp ?: System.currentTimeMillis()

                val existing = _reservations.value.find { it.id == resId }
                // Terminal state protection (Test 19 & 43): old RESERVE cannot reactivate CANCELLED / REPLACED_BY_CASH / COMPLETED
                if (existing != null) {
                    if (existing.state == ReservationState.CANCELLED ||
                        existing.state == ReservationState.REPLACED_BY_CASH ||
                        existing.state == ReservationState.COMPLETED ||
                        parsed.seq < existing.seq
                    ) {
                        sendAckForCommand(parsed, senderPhone)
                        return true
                    }
                } else {
                    val remoteRes = PaymentReservation(
                        id = resId,
                        orderId = orderId,
                        deviceId = "peer-${originStore.lowercase()}",
                        storeId = originStore,
                        amount = amount,
                        createdAt = ts,
                        state = ReservationState.ACTIVE,
                        syncState = SyncState.ACKNOWLEDGED,
                        protocolVersion = parsed.version,
                        seq = parsed.seq
                    )
                    _reservations.value = listOf(remoteRes) + _reservations.value
                    saveReservations()
                }
                sendAckForCommand(parsed, senderPhone)
            }

            AmbiorixSmsProtocol.CMD_CANCEL -> {
                val resId = parsed.id ?: return false
                val originStore = parsed.src ?: getPeerStoreId()
                val amount = parsed.amount ?: 0L
                val reason = parsed.reason ?: "PEER_CANCEL"
                val ts = parsed.timestamp ?: System.currentTimeMillis()

                val existing = _reservations.value.find { it.id == resId }
                if (existing != null) {
                    if (existing.state != ReservationState.COMPLETED) {
                        val updated = existing.copy(
                            state = if (reason == "REPLACED_BY_CASH") ReservationState.REPLACED_BY_CASH else ReservationState.CANCELLED,
                            cancellationReason = reason,
                            seq = maxOf(existing.seq + 1, parsed.seq),
                            syncState = SyncState.ACKNOWLEDGED
                        )
                        updateReservationInList(updated)
                    }
                } else {
                    // Out-of-order arrival: CANCEL arrived before RESERVE! Record tombstoned CANCELLED reservation (Test 19 & 37)
                    val tombstone = PaymentReservation(
                        id = resId,
                        orderId = "ORD-$resId",
                        deviceId = "peer-${originStore.lowercase()}",
                        storeId = originStore,
                        amount = amount,
                        createdAt = ts,
                        state = if (reason == "REPLACED_BY_CASH") ReservationState.REPLACED_BY_CASH else ReservationState.CANCELLED,
                        cancellationReason = reason,
                        seq = maxOf(2, parsed.seq),
                        syncState = SyncState.ACKNOWLEDGED
                    )
                    _reservations.value = listOf(tombstone) + _reservations.value
                    saveReservations()
                }
                sendAckForCommand(parsed, senderPhone)
            }

            AmbiorixSmsProtocol.CMD_PAYMENT_CANDIDATE -> {
                val resId = parsed.id ?: return false
                val existing = _reservations.value.find { it.id == resId }
                if (existing != null && existing.state == ReservationState.ACTIVE) {
                    updateReservationInList(
                        existing.copy(
                            state = ReservationState.PAYMENT_CANDIDATE,
                            matchedNotificationId = parsed.tx
                        )
                    )
                }
                sendAckForCommand(parsed, senderPhone)
            }

            AmbiorixSmsProtocol.CMD_COMPLETE -> {
                val resId = parsed.id ?: return false
                val existing = _reservations.value.find { it.id == resId }
                if (existing != null) {
                    updateReservationInList(
                        existing.copy(
                            state = ReservationState.COMPLETED,
                            saleId = parsed.saleId ?: existing.saleId,
                            matchedNotificationId = parsed.tx ?: existing.matchedNotificationId,
                            seq = maxOf(existing.seq + 1, parsed.seq),
                            syncState = SyncState.ACKNOWLEDGED
                        )
                    )
                    if (!parsed.tx.isNullOrBlank()) {
                        _bankNotifications.value.find { it.id == parsed.tx }?.let { n ->
                            updateBankNotificationInList(
                                n.copy(
                                    state = NotificationState.ASSIGNED,
                                    assignedStore = existing.storeId,
                                    linkedReservationId = existing.id,
                                    syncState = SyncState.ACKNOWLEDGED
                                )
                            )
                        }
                    }
                }
                sendAckForCommand(parsed, senderPhone)
            }

            AmbiorixSmsProtocol.CMD_ASSIGN -> {
                val txId = parsed.tx ?: return false
                val targetStore = parsed.dst ?: getLocalStoreId()
                val amount = parsed.amount ?: 0L

                val localNotif = _bankNotifications.value.find {
                    it.id == txId || (it.amount == amount && it.state == NotificationState.UNASSIGNED)
                }

                if (localNotif == null) {
                    // Section 14.2 & Test 32: Destination must NOT invent a bank notification or sale if event not found
                    _pendingRemoteAssignments.value = listOf(parsed) + _pendingRemoteAssignments.value
                    val statusReq = AmbiorixSmsProtocol.buildStatus(
                        id = txId,
                        src = getLocalStoreId(),
                        dst = parsed.src ?: getPeerStoreId()
                    )
                    coordinationTransport.sendCommand(statusReq, senderPhone)
                } else {
                    // Check for incompatible assignment conflict (Section 14.3 & Test 35)
                    if (localNotif.assignedStore != null && !localNotif.assignedStore.equals(targetStore, ignoreCase = true)) {
                        val conflictNotif = localNotif.copy(
                            state = NotificationState.REQUIRES_REVIEW,
                            reviewReason = "Conflicto de asignación manual entre ${localNotif.assignedStore} y $targetStore"
                        )
                        updateBankNotificationInList(conflictNotif)
                        updatePaymentTransactionForNotification(conflictNotif, status = "REQUIRES_REVIEW")
                    } else {
                        val assignedNotif = localNotif.copy(
                            state = NotificationState.ASSIGNED,
                            assignedStore = targetStore,
                            assignedAt = System.currentTimeMillis(),
                            syncState = SyncState.ACKNOWLEDGED
                        )
                        updateBankNotificationInList(assignedNotif)
                        val newTxStatus = if (targetStore.equals(getLocalStoreId(), ignoreCase = true)) "CONFIRMED" else "ASSIGNED_OTHER"
                        updatePaymentTransactionForNotification(assignedNotif, status = newTxStatus)
                    }
                    sendAckForCommand(parsed, senderPhone)
                }
            }

            AmbiorixSmsProtocol.CMD_STATUS -> {
                val entityId = parsed.id ?: parsed.tx ?: return false
                val res = _reservations.value.find { it.id == entityId }
                val notif = _bankNotifications.value.find { it.id == entityId }
                val stateStr = res?.state?.name ?: notif?.state?.name ?: "UNKNOWN"
                val resp = AmbiorixSmsProtocol.buildStatusResponse(
                    id = entityId,
                    state = stateStr,
                    src = getLocalStoreId(),
                    dst = parsed.src ?: getPeerStoreId()
                )
                coordinationTransport.sendCommand(resp, senderPhone)
            }

            AmbiorixSmsProtocol.CMD_RECONCILE -> {
                val entityId = parsed.id ?: return false
                val remoteState = parsed.state ?: return false
                val res = _reservations.value.find { it.id == entityId }
                if (res != null) {
                    // Reconcile without allowing reactivation of cancelled/completed states
                    if (remoteState == ReservationState.CANCELLED.name && res.state == ReservationState.ACTIVE) {
                        updateReservationInList(res.copy(state = ReservationState.CANCELLED, syncState = SyncState.ACKNOWLEDGED))
                    } else if (remoteState == ReservationState.COMPLETED.name && res.state != ReservationState.COMPLETED) {
                        updateReservationInList(res.copy(state = ReservationState.COMPLETED, syncState = SyncState.ACKNOWLEDGED))
                    } else if (res.syncState != SyncState.ACKNOWLEDGED) {
                        updateReservationInList(res.copy(syncState = SyncState.ACKNOWLEDGED))
                    }
                }
                sendAckForCommand(parsed, senderPhone)
            }
        }
        return true
    }

    private fun handleAckOrResponseCommand(parsed: AmbiorixSmsProtocol.ParsedCommand) {
        val ackedMsgId = parsed.ackMsgId
        val entityId = parsed.id ?: parsed.tx

        if (ackedMsgId != null) {
            _outboxMessages.value = _outboxMessages.value.map { msg ->
                if (msg.id == ackedMsgId || msg.relatedEntityId == entityId) {
                    msg.copy(acknowledged = true)
                } else msg
            }
            saveOutboxMessages()
        }

        when (parsed.command) {
            AmbiorixSmsProtocol.CMD_RESERVE_ACK,
            AmbiorixSmsProtocol.CMD_CANCEL_ACK,
            AmbiorixSmsProtocol.CMD_COMPLETE_ACK -> {
                if (entityId != null) {
                    _reservations.value.find { it.id == entityId }?.let { res ->
                        updateReservationInList(res.copy(syncState = SyncState.ACKNOWLEDGED))
                    }
                }
            }
            AmbiorixSmsProtocol.CMD_ASSIGN_ACK -> {
                if (entityId != null) {
                    _bankNotifications.value.find { it.id == entityId }?.let { notif ->
                        updateBankNotificationInList(notif.copy(syncState = SyncState.ACKNOWLEDGED))
                    }
                }
            }
            AmbiorixSmsProtocol.CMD_STATUS_RESPONSE -> {
                if (entityId != null && parsed.state != null) {
                    _reservations.value.find { it.id == entityId }?.let { res ->
                        if (parsed.state == ReservationState.CANCELLED.name && res.state == ReservationState.ACTIVE) {
                            updateReservationInList(res.copy(state = ReservationState.CANCELLED, syncState = SyncState.ACKNOWLEDGED))
                        } else {
                            updateReservationInList(res.copy(syncState = SyncState.ACKNOWLEDGED))
                        }
                    }
                }
            }
            AmbiorixSmsProtocol.CMD_ACK -> {
                if (entityId != null) {
                    _reservations.value.find { it.id == entityId }?.let { res ->
                        updateReservationInList(res.copy(syncState = SyncState.ACKNOWLEDGED))
                    }
                    _bankNotifications.value.find { it.id == entityId }?.let { notif ->
                        updateBankNotificationInList(notif.copy(syncState = SyncState.ACKNOWLEDGED))
                    }
                }
            }
        }
    }

    private fun sendAckForCommand(parsed: AmbiorixSmsProtocol.ParsedCommand, targetPhone: String) {
        val ackMsgId = parsed.msgId ?: parsed.id ?: parsed.tx ?: "msg"
        val src = getLocalStoreId()
        val dst = parsed.src ?: getPeerStoreId()
        val payload = when (parsed.command) {
            AmbiorixSmsProtocol.CMD_RESERVE -> AmbiorixSmsProtocol.buildReserveAck(parsed.id ?: "", ackMsgId, src, dst)
            AmbiorixSmsProtocol.CMD_CANCEL -> AmbiorixSmsProtocol.buildCancelAck(parsed.id ?: "", ackMsgId, src, dst)
            AmbiorixSmsProtocol.CMD_COMPLETE -> AmbiorixSmsProtocol.buildCompleteAck(parsed.id ?: "", ackMsgId, src, dst)
            AmbiorixSmsProtocol.CMD_ASSIGN -> AmbiorixSmsProtocol.buildAssignAck(parsed.tx ?: "", ackMsgId, src, dst)
            else -> AmbiorixSmsProtocol.buildAck(ackMsgId, src, dst)
        }
        val replyTarget = targetPhone.ifBlank { getPeerPhone() }
        coordinationTransport.sendCommand(payload, replyTarget)
    }

    private fun enqueueAndDispatchOutbox(
        msgId: String,
        command: String,
        payload: String,
        relatedEntityId: String
    ): Boolean {
        val phone = getPeerPhone()
        val now = System.currentTimeMillis()
        val initialOutboxItem = PendingOutboxMessage(
            id = msgId,
            command = command,
            payload = payload,
            targetPhone = phone,
            relatedEntityId = relatedEntityId,
            createdAt = now,
            retryCount = 0,
            lastAttemptAt = now,
            acknowledged = false
        )
        _outboxMessages.value = listOf(initialOutboxItem) + _outboxMessages.value.filter { it.id != msgId }
        saveOutboxMessages()

        val sent = coordinationTransport.sendCommand(payload, phone)
        if (!sent) {
            _outboxMessages.value = _outboxMessages.value.map {
                if (it.id == msgId) it.copy(retryCount = 1) else it
            }
            saveOutboxMessages()
        }
        return sent
    }

    /**
     * Processes an incoming Nequi bank SMS or notification (Sections 6, 7, 12, 13).
     * Never treats internal Ambiorix coordination commands as bank payment evidence.
     */
    @Synchronized
    fun processNequiBankSms(
        rawBody: String,
        originatingAddress: String? = com.ambiorix.pay.sms.NequiSmsParser.KNOWN_NEQUI_SENDER,
        timestamp: Long = System.currentTimeMillis(),
        explicitBankRef: String? = null,
        explicitEventId: String? = null
    ): NequiBankNotification? {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return null

        // 1. Internal coordination SMS is NEVER a bank payment (Section 6 & Test 14)
        if (AmbiorixSmsProtocol.isProtocolMessage(trimmed)) {
            processIncomingCoordinationSms(trimmed, originatingAddress ?: getPeerPhone())
            return null
        }

        // Extract bank reference if explicitly present in SMS text (never invent if absent! Test 15)
        val extractedRef = explicitBankRef ?: extractBankRefFromText(trimmed)

        val parseResult = com.ambiorix.pay.sms.NequiSmsParser.parse(originatingAddress, trimmed)
        if (parseResult is com.ambiorix.pay.sms.SmsPaymentParseResult.Ignored) {
            // Check if it is a bank notification from Nequi without extractable amount (Section 7 & Test 10)
            val lower = trimmed.lowercase()
            val looksLikeNequiNotice = (originatingAddress == com.ambiorix.pay.sms.NequiSmsParser.KNOWN_NEQUI_SENDER || lower.contains("nequi")) &&
                (lower.contains("recibi") || lower.contains("pago") || lower.contains("transferencia") || lower.contains("enviaron") || lower.contains("movimiento") || lower.contains("abono")) &&
                !lower.contains("clave") && !lower.contains("codigo") && !lower.contains("código")


            if (!looksLikeNequiNotice) return null

            val unparsedId = explicitEventId ?: computeStableBankEventId(0L, null, extractedRef, trimmed, timestamp)
            val existingUnparsed = _bankNotifications.value.find { it.id == unparsedId }
            if (existingUnparsed != null) {
                return existingUnparsed.copy(state = NotificationState.DUPLICATE_EVENT)
            }

            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
            val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))
            val pendingNotif = NequiBankNotification(
                id = unparsedId,
                amount = 0L,
                senderName = null,
                bankRef = extractedRef,
                channel = null,
                timestamp = timestamp,
                dateStr = dateStr,
                timeStr = timeStr,
                rawBody = trimmed,
                state = NotificationState.REQUIRES_REVIEW,
                reviewReason = "No se pudo extraer un valor monetario válido del SMS"
            )
            _bankNotifications.value = listOf(pendingNotif) + _bankNotifications.value
            saveBankNotifications()
            return pendingNotif
        }

        val parsedPayment = (parseResult as com.ambiorix.pay.sms.SmsPaymentParseResult.Success).payment
        val amount = parsedPayment.amount
        val senderName = parsedPayment.senderName
        val channel = parsedPayment.channel

        val eventId = explicitEventId ?: computeStableBankEventId(amount, senderName, extractedRef, trimmed, timestamp)

        // 2. Duplicate notification check (Test 12: same notification never produces two sales)
        val existingNotif = _bankNotifications.value.find { it.id == eventId }
        if (existingNotif != null) {
            return existingNotif.copy(state = NotificationState.DUPLICATE_EVENT)
        }

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))

        // 3. Evaluate active reservations matching exact amount
        val activeStates = setOf(
            ReservationState.CREATED,
            ReservationState.PENDING_SEND,
            ReservationState.SENT,
            ReservationState.ACKNOWLEDGED,
            ReservationState.ACTIVE,
            ReservationState.PAYMENT_CANDIDATE
        )
        val matchingActive = _reservations.value.filter {
            it.amount == amount && activeStates.contains(it.state)
        }

        // Case A: Multiple active reservations with the same amount -> AMBIGUOUS (Section 5 & Test 7)
        if (matchingActive.size > 1) {
            matchingActive.forEach { r ->
                updateReservationInList(r.copy(state = ReservationState.REQUIRES_REVIEW))
            }
            val ambiguousNotif = NequiBankNotification(
                id = eventId,
                amount = amount,
                senderName = senderName,
                bankRef = extractedRef,
                channel = channel,
                timestamp = timestamp,
                dateStr = dateStr,
                timeStr = timeStr,
                rawBody = trimmed,
                state = NotificationState.REQUIRES_REVIEW,
                reviewReason = "Ambigüedad: existen ${matchingActive.size} reservas activas por $$amount"
            )
            _bankNotifications.value = listOf(ambiguousNotif) + _bankNotifications.value
            saveBankNotifications()

            recordPaymentTransaction(
                method = "NEQUI",
                amount = amount,
                payerNameOrPhone = senderName,
                bankTransactionRef = extractedRef,
                smsEventId = eventId,
                originInstallation = "Revisión Manual (Reserva Ambigua)",
                nequiChannel = channel,
                dateStr = dateStr,
                time = timeStr,
                status = "UNASSIGNED"
            )
            return ambiguousNotif
        }

        // Case B: Single active reservation matches exact amount (Test 8)
        if (matchingActive.size == 1) {
            val candidate = matchingActive.first()

            if (candidate.deviceId.startsWith("dev-") || candidate.storeId.equals(getLocalStoreId(), ignoreCase = true)) {
                // Belongs to THIS store -> confirm sale atomically & idempotently once
                val deterministicSaleId = "sale-${candidate.id}"
                val items = recoverReservationCart(candidate.id)
                val saleRecord = if (items.isNotEmpty()) {
                    completeSale(
                        items = items,
                        subtotal = candidate.subtotal,
                        discount = candidate.discount,
                        total = candidate.amount,
                        paymentMethod = "NEQUI",
                        cashierName = candidate.cashierName,
                        senderName = senderName,
                        channel = channel,
                        saleId = deterministicSaleId
                    ).getOrNull()
                } else {
                    // Reservation created directly without cart items: register sale & payment idempotently
                    if (!_processedSaleIds.contains(deterministicSaleId)) {
                        val saleNo = "V-${(_sales.value.size + 101).toString().padStart(5, '0')}"
                        val directSale = SaleRecord(
                            id = deterministicSaleId,
                            saleNumber = saleNo,
                            items = emptyList(),
                            subtotal = candidate.subtotal,
                            discount = candidate.discount,
                            total = candidate.amount,
                            paymentMethod = "NEQUI",
                            cashAmount = 0L,
                            nequiAmount = candidate.amount,
                            nequiSenderName = senderName,
                            nequiChannel = channel,
                            timestamp = timeStr,
                            dateStr = dateStr,
                            cashierName = candidate.cashierName,
                            status = "COMPLETED",
                            isInventoryDeducted = true
                        )
                        _sales.value = listOf(directSale) + _sales.value
                        _processedSaleIds.add(deterministicSaleId)
                        saveSales()
                        recordPaymentTransaction(
                            method = "NEQUI",
                            amount = amount,
                            saleId = deterministicSaleId,
                            saleNumber = saleNo,
                            cashierName = candidate.cashierName,
                            payerNameOrPhone = senderName,
                            bankTransactionRef = extractedRef,
                            smsEventId = eventId,
                            originInstallation = getLocalStoreId(),
                            nequiChannel = channel,
                            dateStr = dateStr,
                            time = timeStr,
                            status = "CONFIRMED"
                        )
                        directSale
                    } else {
                        _sales.value.find { it.id == deterministicSaleId }
                    }
                }

                // Update payment transaction with actual bankRef & eventId if created by completeSale
                if (saleRecord != null) {
                    _paymentTransactions.value = _paymentTransactions.value.map { tx ->
                        if (tx.saleId == saleRecord.id && tx.method == "NEQUI") {
                            tx.copy(
                                bankTransactionRef = extractedRef,
                                smsEventId = eventId,
                                payerNameOrPhone = senderName,
                                originInstallation = getLocalStoreId()
                            )
                        } else tx
                    }
                    savePaymentTransactions()
                }

                val completedRes = candidate.copy(
                    state = ReservationState.COMPLETED,
                    matchedNotificationId = eventId,
                    saleId = saleRecord?.id ?: deterministicSaleId,
                    isPendingHold = false,
                    seq = candidate.seq + 1
                )
                updateReservationInList(completedRes)

                val linkedNotif = NequiBankNotification(
                    id = eventId,
                    amount = amount,
                    senderName = senderName,
                    bankRef = extractedRef,
                    channel = channel,
                    timestamp = timestamp,
                    dateStr = dateStr,
                    timeStr = timeStr,
                    rawBody = trimmed,
                    state = NotificationState.LINKED_TO_SALE,
                    assignedStore = getLocalStoreId(),
                    linkedReservationId = candidate.id,
                    linkedSaleId = saleRecord?.id ?: deterministicSaleId,
                    syncState = SyncState.SENT
                )
                _bankNotifications.value = listOf(linkedNotif) + _bankNotifications.value
                saveBankNotifications()

                // Notify peer store that reservation is COMPLETE
                val cmpMsgId = "M-CMP-${candidate.id}"
                val cmpPayload = AmbiorixSmsProtocol.buildComplete(
                    id = candidate.id,
                    saleId = saleRecord?.id ?: deterministicSaleId,
                    src = getLocalStoreId(),
                    amount = amount,
                    timestamp = timestamp,
                    msgId = cmpMsgId,
                    seq = completedRes.seq,
                    tx = eventId
                )
                enqueueAndDispatchOutbox(cmpMsgId, AmbiorixSmsProtocol.CMD_COMPLETE, cmpPayload, candidate.id)
                _protocolState.value = ProtocolState.PAYMENT_MATCHED
                return linkedNotif
            } else {
                // Reservation belongs to PEER store (e.g. Pizzería): do NOT claim or record local sale!
                val peerCandidateNotif = NequiBankNotification(
                    id = eventId,
                    amount = amount,
                    senderName = senderName,
                    bankRef = extractedRef,
                    channel = channel,
                    timestamp = timestamp,
                    dateStr = dateStr,
                    timeStr = timeStr,
                    rawBody = trimmed,
                    state = NotificationState.MATCH_CANDIDATE,
                    assignedStore = candidate.storeId,
                    linkedReservationId = candidate.id
                )
                _bankNotifications.value = listOf(peerCandidateNotif) + _bankNotifications.value
                saveBankNotifications()

                val candPayload = AmbiorixSmsProtocol.buildPaymentCandidate(
                    id = candidate.id,
                    tx = eventId,
                    src = getLocalStoreId(),
                    amount = amount,
                    timestamp = timestamp
                )
                coordinationTransport.sendCommand(candPayload, getPeerPhone())
                return peerCandidateNotif
            }
        }

        // Case B2: Peer store already sent COMPLETE with this exact notification eventId before bank SMS arrived locally
        val peerCompletedMatch = _reservations.value.find {
            it.state == ReservationState.COMPLETED &&
                !it.storeId.equals(getLocalStoreId(), ignoreCase = true) &&
                it.matchedNotificationId == eventId
        }
        if (peerCompletedMatch != null) {
            val peerCompletedNotif = NequiBankNotification(
                id = eventId,
                amount = amount,
                senderName = senderName,
                bankRef = extractedRef,
                channel = channel,
                timestamp = timestamp,
                dateStr = dateStr,
                timeStr = timeStr,
                rawBody = trimmed,
                state = NotificationState.ASSIGNED,
                assignedStore = peerCompletedMatch.storeId,
                linkedReservationId = peerCompletedMatch.id,
                syncState = SyncState.ACKNOWLEDGED
            )
            _bankNotifications.value = listOf(peerCompletedNotif) + _bankNotifications.value
            saveBankNotifications()
            return peerCompletedNotif
        }

        // Case C: Check recently CANCELLED or REPLACED_BY_CASH reservations of the same amount (Section 12 & Tests 20, 25, 44)
        val cancelledMatch = _reservations.value.find {
            it.amount == amount &&
                (it.state == ReservationState.CANCELLED || it.state == ReservationState.REPLACED_BY_CASH)
        }
        if (cancelledMatch != null) {
            // Do NOT reactivate reservation, do NOT create a second sale, do NOT deduct stock again!
            val lateNotif = NequiBankNotification(
                id = eventId,
                amount = amount,
                senderName = senderName,
                bankRef = extractedRef,
                channel = channel,
                timestamp = timestamp,
                dateStr = dateStr,
                timeStr = timeStr,
                rawBody = trimmed,
                state = NotificationState.RECONCILIATION_PENDING,
                linkedReservationId = cancelledMatch.id,
                reviewReason = "Pago tardío recibido tras reserva ${cancelledMatch.state.name}"
            )
            _bankNotifications.value = listOf(lateNotif) + _bankNotifications.value
            saveBankNotifications()

            recordPaymentTransaction(
                method = "NEQUI",
                amount = amount,
                payerNameOrPhone = senderName,
                bankTransactionRef = extractedRef,
                smsEventId = eventId,
                originInstallation = "Pago Tardío (Pendiente Conciliación)",
                nequiChannel = channel,
                dateStr = dateStr,
                time = timeStr,
                status = "RECONCILIATION_PENDING",
                notes = "Reserva previa ${cancelledMatch.id} (${cancelledMatch.state.name})"
            )
            return lateNotif
        }

        // Case D: No reservation matches -> UNASSIGNED (Section 13 & Tests 11, 28)
        val unassignedNotif = NequiBankNotification(
            id = eventId,
            amount = amount,
            senderName = senderName,
            bankRef = extractedRef,
            channel = channel,
            timestamp = timestamp,
            dateStr = dateStr,
            timeStr = timeStr,
            rawBody = trimmed,
            state = NotificationState.UNASSIGNED,
            reviewReason = "Recibido sin reserva activa"
        )
        _bankNotifications.value = listOf(unassignedNotif) + _bankNotifications.value
        saveBankNotifications()

        recordPaymentTransaction(
            method = "NEQUI",
            amount = amount,
            payerNameOrPhone = senderName,
            bankTransactionRef = extractedRef,
            smsEventId = eventId,
            originInstallation = "Pendiente de Asignación",
            nequiChannel = channel,
            dateStr = dateStr,
            time = timeStr,
            status = "UNASSIGNED"
        )
        return unassignedNotif
    }

    /**
     * Manual assignment to "Este local" from History (Section 14.1 & Tests 29, 35).
     */
    @Synchronized
    fun assignNotificationToLocalStore(
        notificationOrTxId: String,
        cashierName: String = _activeCashier.value.name
    ): Result<NequiBankNotification> {
        val notif = findNotificationByIdOrTxId(notificationOrTxId)
            ?: return Result.failure(NoSuchElementException("Notificación bancaria no encontrada"))

        val localStore = getLocalStoreId()
        // Idempotent if already assigned to this local store
        if (notif.assignedStore != null && notif.assignedStore.equals(localStore, ignoreCase = true) && notif.state == NotificationState.ASSIGNED) {
            return Result.success(notif)
        }

        // Conflict check if already assigned to peer store
        if (notif.assignedStore != null && !notif.assignedStore.equals(localStore, ignoreCase = true)) {
            val conflict = notif.copy(
                state = NotificationState.REQUIRES_REVIEW,
                reviewReason = "Conflicto: ya asignada a ${notif.assignedStore}"
            )
            updateBankNotificationInList(conflict)
            updatePaymentTransactionForNotification(conflict, status = "REQUIRES_REVIEW")
            return Result.failure(IllegalStateException("Conflicto de asignación: la transacción ya tenía asignación a ${notif.assignedStore}"))
        }

        val now = System.currentTimeMillis()
        val msgId = "M-ASG-${notif.id}-$localStore"
        val payload = AmbiorixSmsProtocol.buildAssign(
            tx = notif.id,
            src = localStore,
            dst = localStore,
            amount = notif.amount,
            bankts = notif.timestamp,
            payer = notif.senderName,
            bankRef = notif.bankRef,
            msgId = msgId
        )
        val preAssignedLocal = notif.copy(
            state = NotificationState.ASSIGNED,
            assignedStore = localStore,
            assignedByCashier = cashierName,
            assignedAt = now,
            syncState = SyncState.PENDING_SEND
        )
        updateBankNotificationInList(preAssignedLocal)

        val sent = enqueueAndDispatchOutbox(msgId, AmbiorixSmsProtocol.CMD_ASSIGN, payload, notif.id)
        val currentNotif = _bankNotifications.value.find { it.id == notif.id } ?: preAssignedLocal
        val updated = currentNotif.copy(
            syncState = when {
                currentNotif.syncState == SyncState.ACKNOWLEDGED -> SyncState.ACKNOWLEDGED
                !sent -> SyncState.RETRY_PENDING
                isAsyncAndroidTransport() -> SyncState.PENDING_SEND
                else -> SyncState.SENT
            }
        )
        updateBankNotificationInList(updated)
        updatePaymentTransactionForNotification(
            updated,
            status = if (updated.state == NotificationState.REQUIRES_REVIEW) "REQUIRES_REVIEW" else "CONFIRMED",
            originInstallation = "Asignado a $localStore por $cashierName"
        )
        return Result.success(updated)
    }

    /**
     * Manual assignment to "El otro local" from History (Section 14.2 & Tests 30-35).
     */
    @Synchronized
    fun assignNotificationToPeerStore(
        notificationOrTxId: String,
        cashierName: String = _activeCashier.value.name
    ): Result<NequiBankNotification> {
        val notif = findNotificationByIdOrTxId(notificationOrTxId)
            ?: return Result.failure(NoSuchElementException("Notificación bancaria no encontrada"))

        val localStore = getLocalStoreId()
        val peerStore = getPeerStoreId()

        // Idempotent if already assigned to peer store
        if (notif.assignedStore != null && notif.assignedStore.equals(peerStore, ignoreCase = true) && notif.state == NotificationState.ASSIGNED) {
            return Result.success(notif)
        }

        // Conflict if already assigned to local store
        if (notif.assignedStore != null && notif.assignedStore.equals(localStore, ignoreCase = true)) {
            val conflict = notif.copy(
                state = NotificationState.REQUIRES_REVIEW,
                reviewReason = "Conflicto: ya asignada localmente a $localStore"
            )
            updateBankNotificationInList(conflict)
            updatePaymentTransactionForNotification(conflict, status = "REQUIRES_REVIEW")
            return Result.failure(IllegalStateException("Conflicto de asignación: ya asignada a $localStore"))
        }

        val now = System.currentTimeMillis()
        val msgId = "M-ASG-${notif.id}-$peerStore"
        val payload = AmbiorixSmsProtocol.buildAssign(
            tx = notif.id,
            src = localStore,
            dst = peerStore,
            amount = notif.amount,
            bankts = notif.timestamp,
            payer = notif.senderName,
            bankRef = notif.bankRef,
            msgId = msgId
        )
        val preAssignedPeer = notif.copy(
            state = NotificationState.ASSIGNED,
            assignedStore = peerStore,
            assignedByCashier = cashierName,
            assignedAt = now,
            syncState = SyncState.PENDING_SEND
        )
        updateBankNotificationInList(preAssignedPeer)

        val sent = enqueueAndDispatchOutbox(msgId, AmbiorixSmsProtocol.CMD_ASSIGN, payload, notif.id)
        val currentNotif = _bankNotifications.value.find { it.id == notif.id } ?: preAssignedPeer
        val updated = currentNotif.copy(
            syncState = when {
                currentNotif.syncState == SyncState.ACKNOWLEDGED -> SyncState.ACKNOWLEDGED
                !sent -> SyncState.RETRY_PENDING
                isAsyncAndroidTransport() -> SyncState.PENDING_SEND
                else -> SyncState.SENT
            }
        )
        updateBankNotificationInList(updated)
        updatePaymentTransactionForNotification(
            updated,
            status = if (updated.state == NotificationState.REQUIRES_REVIEW) "REQUIRES_REVIEW" else "ASSIGNED_OTHER",
            originInstallation = "Asignado a $peerStore por $cashierName"
        )
        return Result.success(updated)
    }

    /**
     * Reconciles a late bank notification without duplicating any existing sale or inventory deduction (Section 12 & Test 44).
     */
    @Synchronized
    fun reconcileLateNotification(
        notificationId: String,
        resolutionNote: String,
        cashierName: String = _activeCashier.value.name
    ): Result<NequiBankNotification> {
        val notif = findNotificationByIdOrTxId(notificationId)
            ?: return Result.failure(NoSuchElementException("Notificación no encontrada"))

        val updated = notif.copy(
            state = NotificationState.ASSIGNED,
            assignedStore = getLocalStoreId(),
            assignedByCashier = cashierName,
            assignedAt = System.currentTimeMillis(),
            reviewReason = "Conciliado manualmente: $resolutionNote"
        )
        updateBankNotificationInList(updated)
        updatePaymentTransactionForNotification(
            updated,
            status = "RECONCILED_LATE",
            originInstallation = "Conciliado ($resolutionNote)"
        )
        return Result.success(updated)
    }

    /**
     * Sends a RECONCILE command for a reservation to synchronize state with the peer device (Section 15 & Test 42).
     */
    fun sendReconcileCommand(reservationId: String): Boolean {
        val res = _reservations.value.find { it.id == reservationId } ?: return false
        val payload = AmbiorixSmsProtocol.buildReconcile(
            id = res.id,
            state = res.state.name,
            src = getLocalStoreId(),
            dst = getPeerStoreId(),
            amount = res.amount
        )
        return coordinationTransport.sendCommand(payload, getPeerPhone())
    }

    private fun findNotificationByIdOrTxId(idOrTxId: String): NequiBankNotification? {
        val direct = _bankNotifications.value.find { it.id == idOrTxId }
        if (direct != null) return direct
        val tx = _paymentTransactions.value.find { it.id == idOrTxId || it.smsEventId == idOrTxId }
        if (tx?.smsEventId != null) {
            return _bankNotifications.value.find { it.id == tx.smsEventId }
        }
        return null
    }

    private fun updateReservationInList(updated: PaymentReservation) {
        val list = _reservations.value.toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            list[idx] = updated
        } else {
            list.add(0, updated)
        }
        _reservations.value = list
        saveReservations()
    }

    private fun updateBankNotificationInList(updated: NequiBankNotification) {
        val list = _bankNotifications.value.toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            list[idx] = updated
        } else {
            list.add(0, updated)
        }
        _bankNotifications.value = list
        saveBankNotifications()
    }

    private fun updatePaymentTransactionForNotification(
        notif: NequiBankNotification,
        status: String,
        originInstallation: String? = null
    ) {
        _paymentTransactions.value = _paymentTransactions.value.map { tx ->
            if (tx.smsEventId == notif.id || tx.id == notif.id) {
                tx.copy(
                    status = status,
                    originInstallation = originInstallation ?: tx.originInstallation,
                    cashierName = notif.assignedByCashier ?: tx.cashierName
                )
            } else tx
        }
        savePaymentTransactions()
    }

    private fun extractBankRefFromText(text: String): String? {
        val refRegex = Regex("(?i)(?:ref(?:erencia)?|comprobante|movimiento)\\s*[:#]?\\s*([A-Z0-9]{5,20})")
        val match = refRegex.find(text)
        return match?.groups?.get(1)?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun computeStableBankEventId(
        amount: Long,
        senderName: String?,
        bankRef: String?,
        rawBody: String,
        timestamp: Long
    ): String {
        val raw = "$amount|${senderName.orEmpty()}|${bankRef.orEmpty()}|$timestamp|${rawBody.trim()}"
        val hash = try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12).uppercase()
        } catch (e: Exception) {
            Math.abs(raw.hashCode()).toString()
        }
        return "TX-$hash"
    }

    private fun encodeCartItemsList(items: List<CartItem>): String {
        val sb = StringBuilder("[")
        items.forEachIndexed { idx, itm ->
            if (idx > 0) sb.append(",")
            sb.append("{\"cartItemId\":\"${escape(itm.cartItemId)}\",\"productId\":\"${escape(itm.productId)}\",\"productName\":\"${escape(itm.productName)}\",\"basePrice\":${itm.basePrice},\"unitPrice\":${itm.unitPrice},\"quantity\":${itm.quantity},\"totalPrice\":${itm.totalPrice},\"estimatedCost\":${itm.estimatedCost}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeCartItemsList(json: String): List<CartItem> {
        val itemsList = mutableListOf<CartItem>()
        if (!json.startsWith("[")) return itemsList
        for (itmRaw in splitJsonArray(json)) {
            val itmMap = parseSimpleJsonObject(itmRaw)
            val pId = itmMap["productId"] ?: continue
            val pName = itmMap["productName"] ?: "Producto"
            val bp = itmMap["basePrice"]?.toLongOrNull() ?: 0L
            val up = itmMap["unitPrice"]?.toLongOrNull() ?: bp
            val qty = itmMap["quantity"]?.toIntOrNull() ?: 1
            val tp = itmMap["totalPrice"]?.toLongOrNull() ?: (up * qty)
            val cost = itmMap["estimatedCost"]?.toLongOrNull() ?: 0L
            itemsList.add(
                CartItem(
                    cartItemId = itmMap["cartItemId"] ?: "item-$pId",
                    productId = pId,
                    productName = pName,
                    basePrice = bp,
                    unitPrice = up,
                    quantity = qty,
                    totalPrice = tp,
                    estimatedCost = cost
                )
            )
        }
        return itemsList
    }

    private fun encodeReservations(list: List<PaymentReservation>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, r ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(r.id)}\",\"orderId\":\"${escape(r.orderId)}\",\"deviceId\":\"${escape(r.deviceId)}\",\"storeId\":\"${escape(r.storeId)}\",\"amount\":${r.amount},\"subtotal\":${r.subtotal},\"discount\":${r.discount},\"cashierName\":\"${escape(r.cashierName)}\",\"createdAt\":${r.createdAt},\"state\":\"${r.state.name}\",\"syncState\":\"${r.syncState.name}\",\"protocolVersion\":${r.protocolVersion},\"seq\":${r.seq},\"isPendingHold\":${r.isPendingHold},\"cancellationReason\":${r.cancellationReason?.let { "\"${escape(it)}\"" } ?: "null"},\"matchedNotificationId\":${r.matchedNotificationId?.let { "\"${escape(it)}\"" } ?: "null"},\"saleId\":${r.saleId?.let { "\"${escape(it)}\"" } ?: "null"},\"cartSnapshotJson\":${r.cartSnapshotJson ?: "[]"}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeReservations(json: String): List<PaymentReservation> {
        val list = mutableListOf<PaymentReservation>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val orderId = map["orderId"] ?: "ORD-$id"
            val deviceId = map["deviceId"] ?: "dev-main"
            val storeId = map["storeId"] ?: "HELADERIA"
            val amount = map["amount"]?.toLongOrNull() ?: 0L
            val subtotal = map["subtotal"]?.toLongOrNull() ?: amount
            val discount = map["discount"]?.toLongOrNull() ?: 0L
            val cashierName = map["cashierName"] ?: "Laura Gómez"
            val createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()
            val state = try { ReservationState.valueOf(map["state"] ?: "ACTIVE") } catch (_: Exception) { ReservationState.ACTIVE }
            val syncState = try { SyncState.valueOf(map["syncState"] ?: "NOT_SENT") } catch (_: Exception) { SyncState.NOT_SENT }
            val seq = map["seq"]?.toIntOrNull() ?: 1
            val isPendingHold = map["isPendingHold"]?.toBoolean() ?: false
            val cancellationReason = map["cancellationReason"]?.takeIf { it != "null" }
            val matchedNotificationId = map["matchedNotificationId"]?.takeIf { it != "null" }
            val saleId = map["saleId"]?.takeIf { it != "null" }
            val cartJson = map["cartSnapshotJson"]?.takeIf { it != "null" }
            list.add(
                PaymentReservation(
                    id = id,
                    orderId = orderId,
                    deviceId = deviceId,
                    storeId = storeId,
                    amount = amount,
                    subtotal = subtotal,
                    discount = discount,
                    cashierName = cashierName,
                    createdAt = createdAt,
                    state = state,
                    syncState = syncState,
                    seq = seq,
                    isPendingHold = isPendingHold,
                    cancellationReason = cancellationReason,
                    matchedNotificationId = matchedNotificationId,
                    saleId = saleId,
                    cartSnapshotJson = cartJson
                )
            )
        }
        return list
    }

    private fun encodeBankNotifications(list: List<NequiBankNotification>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, n ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(n.id)}\",\"amount\":${n.amount},\"senderName\":${n.senderName?.let { "\"${escape(it)}\"" } ?: "null"},\"bankRef\":${n.bankRef?.let { "\"${escape(it)}\"" } ?: "null"},\"channel\":${n.channel?.let { "\"${escape(it)}\"" } ?: "null"},\"timestamp\":${n.timestamp},\"dateStr\":\"${escape(n.dateStr)}\",\"timeStr\":\"${escape(n.timeStr)}\",\"rawBody\":\"${escape(n.rawBody)}\",\"state\":\"${n.state.name}\",\"assignedStore\":${n.assignedStore?.let { "\"${escape(it)}\"" } ?: "null"},\"assignedByCashier\":${n.assignedByCashier?.let { "\"${escape(it)}\"" } ?: "null"},\"assignedAt\":${n.assignedAt ?: "null"},\"linkedReservationId\":${n.linkedReservationId?.let { "\"${escape(it)}\"" } ?: "null"},\"linkedSaleId\":${n.linkedSaleId?.let { "\"${escape(it)}\"" } ?: "null"},\"syncState\":\"${n.syncState.name}\",\"reviewReason\":${n.reviewReason?.let { "\"${escape(it)}\"" } ?: "null"}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeBankNotifications(json: String): List<NequiBankNotification> {
        val list = mutableListOf<NequiBankNotification>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val amount = map["amount"]?.toLongOrNull() ?: 0L
            val senderName = map["senderName"]?.takeIf { it != "null" }
            val bankRef = map["bankRef"]?.takeIf { it != "null" }
            val channel = map["channel"]?.takeIf { it != "null" }
            val timestamp = map["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
            val dateStr = map["dateStr"] ?: ""
            val timeStr = map["timeStr"] ?: ""
            val rawBody = map["rawBody"] ?: ""
            val state = try { NotificationState.valueOf(map["state"] ?: "UNASSIGNED") } catch (_: Exception) { NotificationState.UNASSIGNED }
            val assignedStore = map["assignedStore"]?.takeIf { it != "null" }
            val assignedByCashier = map["assignedByCashier"]?.takeIf { it != "null" }
            val assignedAt = map["assignedAt"]?.takeIf { it != "null" }?.toLongOrNull()
            val linkedReservationId = map["linkedReservationId"]?.takeIf { it != "null" }
            val linkedSaleId = map["linkedSaleId"]?.takeIf { it != "null" }
            val syncState = try { SyncState.valueOf(map["syncState"] ?: "NOT_SENT") } catch (_: Exception) { SyncState.NOT_SENT }
            val reviewReason = map["reviewReason"]?.takeIf { it != "null" }
            list.add(
                NequiBankNotification(
                    id = id,
                    amount = amount,
                    senderName = senderName,
                    bankRef = bankRef,
                    channel = channel,
                    timestamp = timestamp,
                    dateStr = dateStr,
                    timeStr = timeStr,
                    rawBody = rawBody,
                    state = state,
                    assignedStore = assignedStore,
                    assignedByCashier = assignedByCashier,
                    assignedAt = assignedAt,
                    linkedReservationId = linkedReservationId,
                    linkedSaleId = linkedSaleId,
                    syncState = syncState,
                    reviewReason = reviewReason
                )
            )
        }
        return list
    }

    private fun encodeOutboxMessages(list: List<PendingOutboxMessage>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(m.id)}\",\"command\":\"${escape(m.command)}\",\"payload\":\"${escape(m.payload)}\",\"targetPhone\":\"${escape(m.targetPhone)}\",\"relatedEntityId\":\"${escape(m.relatedEntityId)}\",\"createdAt\":${m.createdAt},\"retryCount\":${m.retryCount},\"lastAttemptAt\":${m.lastAttemptAt},\"acknowledged\":${m.acknowledged}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeOutboxMessages(json: String): List<PendingOutboxMessage> {
        val list = mutableListOf<PendingOutboxMessage>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val command = map["command"] ?: ""
            val payload = map["payload"] ?: ""
            val targetPhone = map["targetPhone"] ?: ""
            val relatedEntityId = map["relatedEntityId"] ?: ""
            val createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()
            val retryCount = map["retryCount"]?.toIntOrNull() ?: 0
            val lastAttemptAt = map["lastAttemptAt"]?.toLongOrNull() ?: 0L
            val acknowledged = map["acknowledged"]?.toBoolean() ?: false
            list.add(
                PendingOutboxMessage(
                    id = id,
                    command = command,
                    payload = payload,
                    targetPhone = targetPhone,
                    relatedEntityId = relatedEntityId,
                    createdAt = createdAt,
                    retryCount = retryCount,
                    lastAttemptAt = lastAttemptAt,
                    acknowledged = acknowledged
                )
            )
        }
        return list
    }

    private fun encodeExpenses(list: List<ExpenseRecord>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, e ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(e.id)}\",\"amount\":${e.amount},\"category\":\"${escape(e.category)}\",\"description\":\"${escape(e.description)}\",\"paymentSource\":\"${escape(e.paymentSource)}\",\"cashierName\":\"${escape(e.cashierName)}\",\"notes\":\"${escape(e.notes)}\",\"dateStr\":\"${escape(e.dateStr)}\",\"time\":\"${escape(e.time)}\",\"timestamp\":${e.timestamp},\"isLinkedToInventory\":${e.isLinkedToInventory},\"status\":\"${escape(e.status)}\"}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeExpenses(json: String): List<ExpenseRecord> {
        val result = mutableListOf<ExpenseRecord>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val amount = map["amount"]?.toLongOrNull() ?: 0L
            val cat = map["category"] ?: "Otros"
            val desc = map["description"] ?: ""
            val src = map["paymentSource"] ?: "Caja efectivo"
            val cashier = map["cashierName"] ?: "Laura Gómez"
            val notes = map["notes"] ?: ""
            val dateStr = map["dateStr"] ?: getTodayDateKey()
            val time = map["time"] ?: "12:00 PM"
            val ts = map["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
            val linked = map["isLinkedToInventory"]?.toBoolean() ?: false
            val status = map["status"] ?: "ACTIVE"
            result.add(
                ExpenseRecord(
                    id = id,
                    amount = amount,
                    category = cat,
                    description = desc,
                    paymentSource = src,
                    cashierName = cashier,
                    notes = notes,
                    dateStr = dateStr,
                    time = time,
                    timestamp = ts,
                    isLinkedToInventory = linked,
                    status = status
                )
            )
        }
        return result
    }

    private fun encodePaymentTransactions(list: List<PaymentTransaction>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(p.id)}\",\"method\":\"${escape(p.method)}\",\"amount\":${p.amount},\"dateStr\":\"${escape(p.dateStr)}\",\"time\":\"${escape(p.time)}\",\"timestamp\":${p.timestamp},\"status\":\"${escape(p.status)}\",\"saleId\":${p.saleId?.let { "\"${escape(it)}\"" } ?: "null"},\"saleNumber\":${p.saleNumber?.let { "\"${escape(it)}\"" } ?: "null"},\"cashierName\":\"${escape(p.cashierName)}\",\"payerNameOrPhone\":${p.payerNameOrPhone?.let { "\"${escape(it)}\"" } ?: "null"},\"bankTransactionRef\":${p.bankTransactionRef?.let { "\"${escape(it)}\"" } ?: "null"},\"smsEventId\":${p.smsEventId?.let { "\"${escape(it)}\"" } ?: "null"},\"originInstallation\":\"${escape(p.originInstallation)}\",\"nequiChannel\":${p.nequiChannel?.let { "\"${escape(it)}\"" } ?: "null"},\"entryType\":\"${escape(p.entryType)}\",\"notes\":\"${escape(p.notes)}\"}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodePaymentTransactions(json: String): List<PaymentTransaction> {
        val result = mutableListOf<PaymentTransaction>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val method = map["method"] ?: "CASH"
            val amount = map["amount"]?.toLongOrNull() ?: 0L
            val dateStr = map["dateStr"] ?: getTodayDateKey()
            val time = map["time"] ?: "12:00 PM"
            val ts = map["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
            val status = map["status"] ?: "CONFIRMED"
            val saleId = map["saleId"]?.takeIf { it != "null" }
            val saleNo = map["saleNumber"]?.takeIf { it != "null" }
            val cashier = map["cashierName"] ?: "Laura Gómez"
            val payer = map["payerNameOrPhone"]?.takeIf { it != "null" }
            val bankRef = map["bankTransactionRef"]?.takeIf { it != "null" && it.isNotBlank() }
            val smsId = map["smsEventId"]?.takeIf { it != "null" }
            val origin = map["originInstallation"] ?: "Tienda Local"
            val channel = map["nequiChannel"]?.takeIf { it != "null" }
            val entryType = map["entryType"] ?: "SALE_PAYMENT"
            val notes = map["notes"] ?: ""
            result.add(
                PaymentTransaction(
                    id = id,
                    method = method,
                    amount = amount,
                    dateStr = dateStr,
                    time = time,
                    timestamp = ts,
                    status = status,
                    saleId = saleId,
                    saleNumber = saleNo,
                    cashierName = cashier,
                    payerNameOrPhone = payer,
                    bankTransactionRef = bankRef,
                    smsEventId = smsId,
                    originInstallation = origin,
                    nequiChannel = channel,
                    entryType = entryType,
                    notes = notes
                )
            )
        }
        return result
    }

    private fun encodeSales(list: List<SaleRecord>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            val itemsJson = StringBuilder("[")
            s.items.forEachIndexed { idx, itm ->
                if (idx > 0) itemsJson.append(",")
                itemsJson.append("{\"cartItemId\":\"${escape(itm.cartItemId)}\",\"productId\":\"${escape(itm.productId)}\",\"productName\":\"${escape(itm.productName)}\",\"basePrice\":${itm.basePrice},\"unitPrice\":${itm.unitPrice},\"quantity\":${itm.quantity},\"totalPrice\":${itm.totalPrice},\"estimatedCost\":${itm.estimatedCost}}")
            }
            itemsJson.append("]")
            sb.append("{\"id\":\"${escape(s.id)}\",\"saleNumber\":\"${escape(s.saleNumber)}\",\"items\":$itemsJson,\"subtotal\":${s.subtotal},\"discount\":${s.discount},\"total\":${s.total},\"paymentMethod\":\"${escape(s.paymentMethod)}\",\"cashAmount\":${s.cashAmount},\"nequiAmount\":${s.nequiAmount},\"nequiSenderName\":${s.nequiSenderName?.let { "\"${escape(it)}\"" } ?: "null"},\"nequiChannel\":${s.nequiChannel?.let { "\"${escape(it)}\"" } ?: "null"},\"timestamp\":\"${escape(s.timestamp)}\",\"dateStr\":\"${escape(s.dateStr)}\",\"cashierName\":\"${escape(s.cashierName)}\",\"status\":\"${escape(s.status)}\",\"isInventoryDeducted\":${s.isInventoryDeducted},\"cogsCost\":${s.cogsCost}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeSales(json: String): List<SaleRecord> {
        val result = mutableListOf<SaleRecord>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val saleNo = map["saleNumber"] ?: "V-00000"
            val subtotal = map["subtotal"]?.toLongOrNull() ?: 0L
            val discount = map["discount"]?.toLongOrNull() ?: 0L
            val total = map["total"]?.toLongOrNull() ?: 0L
            val method = map["paymentMethod"] ?: "CASH"
            val cashAmt = map["cashAmount"]?.toLongOrNull() ?: 0L
            val nequiAmt = map["nequiAmount"]?.toLongOrNull() ?: 0L
            val sender = map["nequiSenderName"]?.takeIf { it != "null" }
            val ch = map["nequiChannel"]?.takeIf { it != "null" }
            val time = map["timestamp"] ?: "12:00 PM"
            val dateStr = map["dateStr"] ?: getTodayDateKey()
            val cashier = map["cashierName"] ?: "Laura Gómez"
            val status = map["status"] ?: "COMPLETED"
            val deducted = map["isInventoryDeducted"]?.toBoolean() ?: true
            val cogs = map["cogsCost"]?.toLongOrNull() ?: 0L

            val itemsList = mutableListOf<CartItem>()
            val itemsRaw = map["items"]
            if (itemsRaw != null && itemsRaw.startsWith("[")) {
                for (itmRaw in splitJsonArray(itemsRaw)) {
                    val itmMap = parseSimpleJsonObject(itmRaw)
                    val pId = itmMap["productId"] ?: continue
                    val pName = itmMap["productName"] ?: "Producto"
                    val bp = itmMap["basePrice"]?.toLongOrNull() ?: 0L
                    val up = itmMap["unitPrice"]?.toLongOrNull() ?: bp
                    val qty = itmMap["quantity"]?.toIntOrNull() ?: 1
                    val tp = itmMap["totalPrice"]?.toLongOrNull() ?: (up * qty)
                    val cost = itmMap["estimatedCost"]?.toLongOrNull() ?: 0L
                    itemsList.add(
                        CartItem(
                            cartItemId = itmMap["cartItemId"] ?: "item-$pId",
                            productId = pId,
                            productName = pName,
                            basePrice = bp,
                            unitPrice = up,
                            quantity = qty,
                            totalPrice = tp,
                            estimatedCost = cost
                        )
                    )
                }
            }

            result.add(
                SaleRecord(
                    id = id,
                    saleNumber = saleNo,
                    items = itemsList,
                    subtotal = subtotal,
                    discount = discount,
                    total = total,
                    paymentMethod = method,
                    cashAmount = cashAmt,
                    nequiAmount = nequiAmt,
                    nequiSenderName = sender,
                    nequiChannel = ch,
                    timestamp = time,
                    dateStr = dateStr,
                    cashierName = cashier,
                    status = status,
                    isInventoryDeducted = deducted,
                    cogsCost = cogs
                )
            )
        }
        return result
    }

    // --- JSON Encoding & Decoding Helpers ---

    private fun encodeCashiers(list: List<CashierProfile>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, c ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(c.id)}\",\"name\":\"${escape(c.name)}\",\"isActive\":${c.isActive},\"pin\":${c.pin?.let { "\"${escape(it)}\"" } ?: "null"},\"createdAt\":${c.createdAt}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeCashiers(json: String): List<CashierProfile> {
        val result = mutableListOf<CashierProfile>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val name = map["name"] ?: continue
            val isActive = map["isActive"]?.toBoolean() ?: true
            val pin = map["pin"]?.takeIf { it != "null" }
            val createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()
            result.add(CashierProfile(id, name, isActive, pin, createdAt))
        }
        return if (result.isNotEmpty()) result else defaultCashiers()
    }

    private fun encodeCategories(list: List<Category>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, c ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(c.id)}\",\"name\":\"${escape(c.name)}\",\"displayOrder\":${c.displayOrder},\"isActive\":${c.isActive},\"emoji\":\"${escape(c.emoji)}\",\"isDefault\":${c.isDefault}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeCategories(json: String): List<Category> {
        val result = mutableListOf<Category>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val name = map["name"] ?: continue
            val displayOrder = map["displayOrder"]?.toIntOrNull() ?: 0
            val isActive = map["isActive"]?.toBoolean() ?: true
            val emoji = map["emoji"] ?: "🍕"
            val isDefault = map["isDefault"]?.toBoolean() ?: false
            result.add(Category(id, name, displayOrder, isActive, emoji, isDefault))
        }
        return if (result.isNotEmpty()) result else defaultCategories()
    }

    private fun encodeIngredients(list: List<Ingredient>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, ing ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(ing.id)}\",\"name\":\"${escape(ing.name)}\",\"category\":\"${escape(ing.category)}\",\"unit\":\"${escape(ing.unit)}\",\"currentStock\":${ing.currentStock},\"minStockAlert\":${ing.minStockAlert},\"unitCostCOP\":${ing.unitCostCOP},\"isActive\":${ing.isActive},\"batchCode\":\"${escape(ing.batchCode)}\",\"expiryDate\":\"${escape(ing.expiryDate)}\"}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeIngredients(json: String): List<Ingredient> {
        val result = mutableListOf<Ingredient>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val name = map["name"] ?: continue
            val category = map["category"] ?: "Insumos"
            val unit = map["unit"] ?: "g"
            val currentStock = map["currentStock"]?.toDoubleOrNull() ?: 0.0
            val minStockAlert = map["minStockAlert"]?.toDoubleOrNull() ?: 100.0
            val unitCostCOP = map["unitCostCOP"]?.toLongOrNull() ?: 0L
            val isActive = map["isActive"]?.toBoolean() ?: true
            val batchCode = map["batchCode"] ?: "LOT-GEN"
            val expiryDate = map["expiryDate"] ?: "31/12/2026"
            result.add(Ingredient(id, name, category, unit, currentStock, minStockAlert, unitCostCOP, isActive, batchCode, expiryDate))
        }
        return if (result.isNotEmpty()) result else defaultIngredients()
    }

    private fun encodeToppings(list: List<ToppingOption>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, t ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(t.id)}\",\"name\":\"${escape(t.name)}\",\"price\":${t.price},\"ingredientId\":${t.ingredientId?.let { "\"${escape(it)}\"" } ?: "null"},\"gramsRequired\":${t.gramsRequired}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeToppings(json: String): List<ToppingOption> {
        val result = mutableListOf<ToppingOption>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val name = map["name"] ?: continue
            val price = map["price"]?.toLongOrNull() ?: 0L
            val ingredientId = map["ingredientId"]?.takeIf { it != "null" && it.isNotBlank() }
            val gramsRequired = map["gramsRequired"]?.toDoubleOrNull() ?: 0.0
            result.add(ToppingOption(id, name, price, ingredientId, gramsRequired))
        }
        return result
    }

    private fun encodeVariantsList(variants: List<ProductVariant>): String {
        val sb = StringBuilder("[")
        variants.forEachIndexed { idx, v ->
            if (idx > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(v.id)}\",\"name\":\"${escape(v.name)}\",\"priceDelta\":${v.priceDelta},\"gramsMultiplier\":${v.gramsMultiplier}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeVariantsList(json: String): List<ProductVariant> {
        val list = mutableListOf<ProductVariant>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val name = map["name"] ?: continue
            val priceDelta = map["priceDelta"]?.toLongOrNull() ?: 0L
            val gramsMultiplier = map["gramsMultiplier"]?.toDoubleOrNull() ?: 1.0
            list.add(ProductVariant(id, name, priceDelta, gramsMultiplier))
        }
        return list
    }

    private fun encodeProducts(list: List<Product>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            val recipeJson = encodeRecipeList(p.recipe)
            val comboJson = encodeComboList(p.comboItems)
            val variantsJson = encodeVariantsList(p.variants)
            sb.append("{\"id\":\"${escape(p.id)}\",\"name\":\"${escape(p.name)}\",\"description\":\"${escape(p.description)}\",\"basePrice\":${p.basePrice},\"baseCost\":${p.baseCost},\"categoryId\":\"${escape(p.categoryId)}\",\"imagePath\":${p.imagePath?.let { "\"${escape(it)}\"" } ?: "null"},\"emoji\":\"${escape(p.emoji)}\",\"badge\":${p.badge?.let { "\"${escape(it)}\"" } ?: "null"},\"isActive\":${p.isActive},\"isCombo\":${p.isCombo},\"stockQuantity\":${p.stockQuantity},\"minStockAlert\":${p.minStockAlert},\"unit\":\"${escape(p.unit)}\",\"allowNegativeStock\":${p.allowNegativeStock},\"recipe\":$recipeJson,\"comboItems\":$comboJson,\"variants\":$variantsJson,\"allowedToppings\":${p.allowedToppings},\"createdAt\":${p.createdAt},\"updatedAt\":${p.updatedAt},\"baseIngredientId\":${p.baseIngredientId?.let { "\"${escape(it)}\"" } ?: "null"}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeProducts(json: String): List<Product> {
        val result = mutableListOf<Product>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val name = map["name"] ?: continue
            val description = map["description"] ?: ""
            val basePrice = map["basePrice"]?.toLongOrNull() ?: 0L
            val baseCost = map["baseCost"]?.toLongOrNull() ?: 0L
            val categoryId = map["categoryId"] ?: "productos"
            val imagePath = map["imagePath"]?.takeIf { it != "null" }
            val emoji = map["emoji"] ?: "🍕"
            val badge = map["badge"]?.takeIf { it != "null" }
            val isActive = map["isActive"]?.toBoolean() ?: true
            val isCombo = map["isCombo"]?.toBoolean() ?: false
            val stockQuantity = map["stockQuantity"]?.toDoubleOrNull() ?: 0.0
            val minStockAlert = map["minStockAlert"]?.toDoubleOrNull() ?: 5.0
            val unit = map["unit"] ?: "unidades"
            val allowNegativeStock = map["allowNegativeStock"]?.toBoolean() ?: false
            val recipe = decodeRecipeList(map["recipe"] ?: "[]")
            val comboItems = decodeComboList(map["comboItems"] ?: "[]")
            val createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()
            val updatedAt = map["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis()
            val baseIngredientId = map["baseIngredientId"]?.takeIf { it != "null" }

            val defaultTemplate = defaultProducts().find { it.id == id }
            val decodedVariants = map["variants"]?.let { decodeVariantsList(it) } ?: emptyList()
            val variants = decodedVariants.ifEmpty { defaultTemplate?.variants ?: emptyList() }
            val allowedToppings = map["allowedToppings"]?.toBoolean() ?: (defaultTemplate?.allowedToppings ?: false)

            result.add(
                Product(
                    id = id,
                    name = name,
                    description = description,
                    basePrice = basePrice,
                    baseCost = baseCost,
                    categoryId = categoryId,
                    imagePath = imagePath,
                    emoji = emoji,
                    badge = badge,
                    isActive = isActive,
                    isCombo = isCombo,
                    stockQuantity = stockQuantity,
                    minStockAlert = minStockAlert,
                    unit = unit,
                    allowNegativeStock = allowNegativeStock,
                    recipe = recipe.ifEmpty { defaultTemplate?.recipe ?: emptyList() },
                    comboItems = comboItems.ifEmpty { defaultTemplate?.comboItems ?: emptyList() },
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    variants = variants,
                    allowedToppings = allowedToppings,
                    baseIngredientId = baseIngredientId
                )
            )
        }
        return if (result.isNotEmpty()) result else defaultProducts()
    }

    private fun encodeRecipeList(recipe: List<RecipeItem>): String {
        val sb = StringBuilder("[")
        recipe.forEachIndexed { idx, r ->
            if (idx > 0) sb.append(",")
            sb.append("{\"ingredientId\":\"${escape(r.ingredientId)}\",\"ingredientName\":\"${escape(r.ingredientName)}\",\"quantity\":${r.quantity},\"unit\":\"${escape(r.unit)}\"}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeRecipeList(json: String): List<RecipeItem> {
        val list = mutableListOf<RecipeItem>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["ingredientId"] ?: continue
            val name = map["ingredientName"] ?: ""
            val qty = map["quantity"]?.toDoubleOrNull() ?: 0.0
            val unit = map["unit"] ?: "g"
            list.add(RecipeItem(id, name, qty, unit))
        }
        return list
    }

    private fun encodeComboList(combo: List<ComboItem>): String {
        val sb = StringBuilder("[")
        combo.forEachIndexed { idx, c ->
            if (idx > 0) sb.append(",")
            sb.append("{\"productId\":\"${escape(c.productId)}\",\"productName\":\"${escape(c.productName)}\",\"quantity\":${c.quantity}}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeComboList(json: String): List<ComboItem> {
        val list = mutableListOf<ComboItem>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["productId"] ?: continue
            val name = map["productName"] ?: ""
            val qty = map["quantity"]?.toIntOrNull() ?: 1
            list.add(ComboItem(id, name, qty))
        }
        return list
    }

    private fun encodeMovements(list: List<InventoryMovement>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"${escape(m.id)}\",\"itemId\":\"${escape(m.itemId)}\",\"itemName\":\"${escape(m.itemName)}\",\"itemType\":\"${escape(m.itemType)}\",\"previousStock\":${m.previousStock},\"change\":${m.change},\"resultingStock\":${m.resultingStock},\"type\":\"${escape(m.type)}\",\"reason\":\"${escape(m.reason)}\",\"saleId\":${m.saleId?.let { "\"${escape(it)}\"" } ?: "null"},\"date\":${m.date},\"dateFormatted\":\"${escape(m.dateFormatted)}\",\"userName\":\"${escape(m.userName)}\"}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun decodeMovements(json: String): List<InventoryMovement> {
        val result = mutableListOf<InventoryMovement>()
        for (item in splitJsonArray(json)) {
            val map = parseSimpleJsonObject(item)
            val id = map["id"] ?: continue
            val itemId = map["itemId"] ?: continue
            val itemName = map["itemName"] ?: ""
            val itemType = map["itemType"] ?: "INGREDIENT"
            val previousStock = map["previousStock"]?.toDoubleOrNull() ?: 0.0
            val change = map["change"]?.toDoubleOrNull() ?: 0.0
            val resultingStock = map["resultingStock"]?.toDoubleOrNull() ?: 0.0
            val type = map["type"] ?: "MANUAL_ADJUSTMENT"
            val reason = map["reason"] ?: ""
            val saleId = map["saleId"]?.takeIf { it != "null" }
            val date = map["date"]?.toLongOrNull() ?: System.currentTimeMillis()
            val dateFormatted = map["dateFormatted"] ?: ""
            val userName = map["userName"] ?: ""
            result.add(
                InventoryMovement(
                    id = id,
                    itemId = itemId,
                    itemName = itemName,
                    itemType = itemType,
                    previousStock = previousStock,
                    change = change,
                    resultingStock = resultingStock,
                    type = type,
                    reason = reason,
                    saleId = saleId,
                    date = date,
                    dateFormatted = dateFormatted,
                    userName = userName
                )
            )
        }
        return result
    }

    private fun escape(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun splitJsonArray(json: String): List<String> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val content = trimmed.substring(1, trimmed.length - 1).trim()
        if (content.isEmpty()) return emptyList()

        val list = mutableListOf<String>()
        var depth = 0
        var inQuotes = false
        var isEscape = false
        val current = StringBuilder()

        for (ch in content) {
            if (isEscape) {
                current.append(ch)
                isEscape = false
                continue
            }
            if (ch == '\\') {
                current.append(ch)
                isEscape = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                current.append(ch)
                continue
            }
            if (!inQuotes) {
                if (ch == '{') depth++
                if (ch == '}') depth--
                if (ch == ',' && depth == 0) {
                    list.add(current.toString().trim())
                    current.clear()
                    continue
                }
            }
            current.append(ch)
        }
        if (current.isNotBlank()) {
            list.add(current.toString().trim())
        }
        return list
    }

    private fun parseSimpleJsonObject(json: String): Map<String, String> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
        val content = trimmed.substring(1, trimmed.length - 1).trim()
        if (content.isEmpty()) return emptyMap()

        val map = mutableMapOf<String, String>()
        var inQuotes = false
        var isEscape = false
        var inKey = true
        var depth = 0
        val currentKey = StringBuilder()
        val currentValue = StringBuilder()

        var i = 0
        while (i < content.length) {
            val ch = content[i]
            if (isEscape) {
                if (inKey) currentKey.append(ch) else currentValue.append(ch)
                isEscape = false
                i++
                continue
            }
            if (ch == '\\') {
                isEscape = true
                i++
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes) {
                if (ch == '[' || ch == '{') depth++
                if (ch == ']' || ch == '}') depth--

                if (ch == ':' && depth == 0 && inKey) {
                    inKey = false
                    i++
                    continue
                }
                if (ch == ',' && depth == 0) {
                    val k = currentKey.toString().trim()
                    val v = currentValue.toString().trim()
                    if (k.isNotEmpty()) map[k] = v
                    currentKey.clear()
                    currentValue.clear()
                    inKey = true
                    i++
                    continue
                }
            }
            if (inKey) currentKey.append(ch) else currentValue.append(ch)
            i++
        }
        val k = currentKey.toString().trim()
        val v = currentValue.toString().trim()
        if (k.isNotEmpty()) map[k] = v
        return map
    }

    companion object {
        private val idSequence = java.util.concurrent.atomic.AtomicLong(1000L)

        const val KEY_CASHIERS = "pos_cashiers_json"
        const val KEY_ACTIVE_CASHIER_ID = "pos_active_cashier_id"
        const val KEY_CATEGORIES = "pos_categories_json"
        const val KEY_PRODUCTS = "pos_products_json"
        const val KEY_INGREDIENTS = "pos_ingredients_json"
        const val KEY_TOPPINGS = "pos_toppings_json"
        const val KEY_MOVEMENTS = "pos_movements_json"
        const val KEY_SALES = "pos_sales_json"
        const val KEY_EXPENSES = "pos_expenses_json"
        const val KEY_PAYMENT_TRANSACTIONS = "pos_payment_transactions_json"
        const val KEY_RESERVATIONS = "pos_reservations_json"
        const val KEY_BANK_NOTIFICATIONS = "pos_bank_notifications_json"
        const val KEY_OUTBOX_MESSAGES = "pos_outbox_messages_json"
        const val KEY_PROCESSED_MSG_IDS = "pos_processed_msg_ids"
        const val KEY_LOCAL_STORE_ID = "pos_local_store_id"
        const val KEY_PEER_STORE_ID = "pos_peer_store_id"
        const val KEY_PEER_PHONE = "pos_peer_phone"

        const val ACTION_SMS_SENT = "com.ambiorix.pay.ACTION_SMS_SENT"
        const val ACTION_SMS_PART_SENT = "com.ambiorix.pay.ACTION_SMS_PART_SENT"
        const val EXTRA_MSG_ID = "extra_msg_id"
        const val EXTRA_ENTITY_ID = "extra_entity_id"

        @Volatile
        private var INSTANCE: PosRepository? = null

        fun getInstance(context: Context): PosRepository {
            return INSTANCE ?: synchronized(this) {
                val prefs = context.applicationContext.getSharedPreferences("ambiorix_pos_store", Context.MODE_PRIVATE)
                val repo = PosRepository(context.applicationContext, SharedPreferencesStorage(prefs))
                INSTANCE = repo
                repo
            }
        }

        fun createForTesting(storage: PosStorage = InMemoryPosStorage()): PosRepository {
            return PosRepository(null, storage)
        }

        fun defaultCashiers(): List<CashierProfile> = listOf(
            CashierProfile("cashier-1", "Laura Gómez", isActive = true),
            CashierProfile("cashier-2", "Camila Restrepo", isActive = true),
            CashierProfile("cashier-3", "Valentina Ortiz", isActive = true)
        )

        fun defaultCategories(): List<Category> = listOf(
            Category("productos", "Productos", 0, true, "🍕", true),
            Category("ingredientes", "Ingredientes", 1, true, "🧀", false),
            Category("combos", "Combos", 2, true, "🎁", false)
        )

        fun defaultIngredients(): List<Ingredient> = emptyList()

        fun defaultToppings(): List<ToppingOption> = emptyList()

        fun defaultProducts(): List<Product> = emptyList()
    }

    fun defaultPaymentTransactions(): List<PaymentTransaction> = emptyList()

    fun defaultExpenses(): List<ExpenseRecord> = emptyList()

    fun defaultSales(): List<SaleRecord> = emptyList()
}



package com.ambiorix.pay.pos.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.ambiorix.pay.R
import com.ambiorix.pay.ui.screens.HistoryScreen
import coil.compose.AsyncImage
import com.ambiorix.pay.data.AppSettings
import com.ambiorix.pay.data.ImageStore
import com.ambiorix.pay.data.PaymentRepository
import com.ambiorix.pay.pos.*
import com.ambiorix.pay.sound.SoundAnnouncer
import com.ambiorix.pay.ui.theme.*
import java.io.File
import java.text.NumberFormat
import java.util.Locale

fun formatCOP(amount: Long): String {
    val format = NumberFormat.getCurrencyInstance(Locale("es", "CO"))
    format.maximumFractionDigits = 0
    return format.format(amount)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosMainScreen(
    settings: AppSettings,
    posRepository: PosRepository,
    soundAnnouncer: SoundAnnouncer,
    onOpenSettingsClick: () -> Unit,
    onPaymentSuccess: (amount: Long, sender: String?, channel: String?) -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf("pos") } // pos, cash, stock, qr, settings
    var currentRole by remember { mutableStateOf(PosRole.CASHIER) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    // Repository Flows
    val products by posRepository.products.collectAsState()
    val categories by posRepository.categories.collectAsState()
    val activeCashier by posRepository.activeCashier.collectAsState()
    val cashiers by posRepository.cashiers.collectAsState()
    val inventoryMovements by posRepository.inventoryMovements.collectAsState()
    val ingredients by posRepository.ingredients.collectAsState()
    val cashShift by posRepository.cashShift.collectAsState()
    val toppingsList by posRepository.toppings.collectAsState()

    val paymentRepository = remember(context) { PaymentRepository.getInstance(context.applicationContext) }
    val recordedPayments by paymentRepository.paymentsFlow.collectAsState()
    val isHistoryLoading by paymentRepository.isLoadingFlow.collectAsState()
    val historyError by paymentRepository.errorFlow.collectAsState()

    // Catalog & Cart State
    var selectedCategoryId by remember { mutableStateOf("todos") }
    var searchQuery by remember { mutableStateOf("") }
    val cart = remember { mutableStateListOf<CartItem>() }
    var discountPercent by remember { mutableIntStateOf(0) }

    // Inventory Central Section Sub-Tab: productos, insumos, toppings, materiales
    var selectedInventorySection by remember { mutableStateOf("productos") }

    // Modals & Dialogs State
    var showCashierDialog by remember { mutableStateOf(false) }
    var showProductAdminDialog by remember { mutableStateOf(false) }
    var showIngredientAdminDialog by remember { mutableStateOf(false) }
    var showToppingAdminDialog by remember { mutableStateOf(false) }
    var showCategoryAdminDialog by remember { mutableStateOf(false) }
    var showStockAdminDialog by remember { mutableStateOf(false) }
    var showFinancialHistoryDialog by remember { mutableStateOf(false) }

    // Product Variant & Topping Selector
    var selectedProductForModal by remember { mutableStateOf<Product?>(null) }
    var modalVariantId by remember { mutableStateOf("") }
    var modalSelectedFlavor by remember { mutableStateOf("") }
    val modalSelectedToppings = remember { mutableStateListOf<String>() }

    // Checkout Modal State
    var showCheckoutModal by remember { mutableStateOf(false) }
    var checkoutMethod by remember { mutableStateOf("NEQUI") }
    var cashReceivedInput by remember { mutableStateOf("") }

    // Cash shift states
    var showExpenseDialog by remember { mutableStateOf(false) }
    var expenseConcept by remember { mutableStateOf("") }
    var expenseAmount by remember { mutableStateOf("") }

    var showAdjustBaseDialog by remember { mutableStateOf(false) }
    var adjustBaseAmountInput by remember { mutableStateOf("") }
    var adjustBaseReasonInput by remember { mutableStateOf("") }
    var adjustBaseError by remember { mutableStateOf<String?>(null) }

    var showBlindCloseDialog by remember { mutableStateOf(false) }
    var countedCashInput by remember { mutableStateOf("") }
    var blindCloseReport by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    // Calculations
    val cartSubtotal = cart.sumOf { it.totalPrice }
    val cartDiscount = (cartSubtotal * (discountPercent.toDouble() / 100.0)).toLong()
    val cartTotal = maxOf(0L, cartSubtotal - cartDiscount)

    val activeProducts = remember(products, selectedCategoryId, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        products.filter { p ->
            p.isActive &&
            (selectedCategoryId == "todos" || p.categoryId == selectedCategoryId) &&
            (q.isEmpty() || p.name.lowercase(Locale.getDefault()).contains(q) || p.description.lowercase(Locale.getDefault()).contains(q))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ambiorix_logo),
                            contentDescription = "Logo",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AMBIORIX PAY",
                            color = TextWhite,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = GoldPrimary.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "Pizzería Natus",
                                color = GoldPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    // Active Cashier Badge (Clickable to edit name / switch profile)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier
                            .clickable { showCashierDialog = true }
                            .padding(end = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = GoldPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = activeCashier.name,
                                color = TextWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Role Switcher Button
                    Button(
                        onClick = {
                            if (currentRole == PosRole.CASHIER) {
                                pinInput = ""
                                pinError = false
                                showPinDialog = true
                            } else {
                                currentRole = PosRole.CASHIER
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentRole == PosRole.OWNER) GoldPrimary.copy(alpha = 0.2f) else DarkSurfaceElevated
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (currentRole == PosRole.OWNER) Icons.Default.Shield else Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (currentRole == PosRole.OWNER) GoldPrimary else TextGray,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (currentRole == PosRole.OWNER) "Patrón" else "Cajera",
                            color = if (currentRole == PosRole.OWNER) GoldPrimary else TextWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Open Financial History Calendar Button (Sound test removed)
                    IconButton(
                        onClick = { showFinancialHistoryDialog = true }
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = "Historial Financiero", tint = GoldPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF070B16),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == "pos",
                    onClick = { currentTab = "pos" },
                    icon = { Icon(Icons.Default.ShoppingBag, contentDescription = "Venta") },
                    label = { Text("Venta", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        selectedTextColor = GoldPrimary,
                        indicatorColor = GoldPrimary,
                        unselectedIconColor = TextGray,
                        unselectedTextColor = TextGray
                    )
                )

                NavigationBarItem(
                    selected = currentTab == "cash",
                    onClick = { currentTab = "cash" },
                    icon = { Icon(Icons.Default.AttachMoney, contentDescription = "Caja") },
                    label = { Text("Caja", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        selectedTextColor = GoldPrimary,
                        indicatorColor = GoldPrimary,
                        unselectedIconColor = TextGray,
                        unselectedTextColor = TextGray
                    )
                )

                NavigationBarItem(
                    selected = currentTab == "stock",
                    onClick = { currentTab = "stock" },
                    icon = { Icon(Icons.Default.Inventory2, contentDescription = "Inventario") },
                    label = { Text("Inventario", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        selectedTextColor = GoldPrimary,
                        indicatorColor = GoldPrimary,
                        unselectedIconColor = TextGray,
                        unselectedTextColor = TextGray
                    )
                )

                NavigationBarItem(
                    selected = currentTab == "qr",
                    onClick = { currentTab = "qr" },
                    icon = { Icon(Icons.Default.QrCode, contentDescription = "QR") },
                    label = { Text("QR", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        selectedTextColor = GoldPrimary,
                        indicatorColor = GoldPrimary,
                        unselectedIconColor = TextGray,
                        unselectedTextColor = TextGray
                    )
                )

                NavigationBarItem(
                    selected = currentTab == "settings",
                    onClick = onOpenSettingsClick,
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                    label = { Text("Ajustes", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        selectedTextColor = GoldPrimary,
                        indicatorColor = GoldPrimary,
                        unselectedIconColor = TextGray,
                        unselectedTextColor = TextGray
                    )
                )
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentTab) {
                // ==========================================
                // 1. POS / VENTA SCREEN
                // ==========================================
                "pos" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        // Compact Search Input
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Buscar producto vendible...", fontSize = 12.sp, color = TextGray) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextGray, modifier = Modifier.size(16.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = TextGray, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GoldPrimary,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Compact Single-Row Horizontal Category Bar (Sellable Categories Only)
                        val sellableCategories = remember(categories) {
                            listOf(Category("todos", "Todos", 0, true, "🍽️")) +
                                categories.filter { it.isActive && it.id != "ingredientes" }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            sellableCategories.forEach { cat ->
                                val isSelected = selectedCategoryId == cat.id
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) GoldPrimary else DarkSurfaceElevated,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) GoldPrimary else DarkBorder
                                    ),
                                    modifier = Modifier.clickable { selectedCategoryId = cat.id }
                                ) {
                                    Text(
                                        text = "${cat.emoji} ${cat.name}",
                                        color = if (isSelected) DarkBackground else TextWhite,
                                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Product Grid (2 columns of visual POS cards)
                        if (activeProducts.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DarkSurfaceElevated,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(if (selectedCategoryId == "combos") "🎁" else "🍨", fontSize = 32.sp)
                                        Text(
                                            text = if (selectedCategoryId == "combos") "No hay combos registrados" else "No hay productos vendibles en esta categoría",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "Gestiona productos, recetas, toppings e insumos desde la sección central de Inventario.",
                                            color = TextGray,
                                            fontSize = 11.sp
                                        )
                                        Button(
                                            onClick = { currentTab = "stock" },
                                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Ir a Inventario", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                items(activeProducts, key = { it.id }) { product ->
                                    val isOutOfStock = !product.allowNegativeStock && product.recipe.isEmpty() && !product.isCombo && product.stockQuantity <= 0.0
                                    val isLowStock = !isOutOfStock && product.recipe.isEmpty() && !product.isCombo && product.stockQuantity <= product.minStockAlert
                                    val isCustomizable = product.variants.isNotEmpty() || product.allowedToppings
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = DarkSurfaceElevated,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isOutOfStock) DangerRed.copy(alpha = 0.5f) else DarkBorder
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isCustomizable) {
                                                    selectedProductForModal = product
                                                    modalVariantId = product.variants.firstOrNull()?.id ?: ""
                                                    modalSelectedFlavor = ""
                                                    modalSelectedToppings.clear()
                                                } else {
                                                    val existingIdx = cart.indexOfFirst {
                                                        it.productId == product.id && it.variantId.isEmpty() && it.toppings.isEmpty()
                                                    }
                                                    if (existingIdx >= 0) {
                                                        val cur = cart[existingIdx]
                                                        val newQty = cur.quantity + 1
                                                        cart[existingIdx] = cur.copy(
                                                            quantity = newQty,
                                                            totalPrice = cur.unitPrice * newQty
                                                        )
                                                    } else {
                                                        cart.add(
                                                            CartItem(
                                                                cartItemId = "ci-${System.currentTimeMillis()}",
                                                                productId = product.id,
                                                                productName = product.name,
                                                                variantId = "",
                                                                variantName = "Estándar",
                                                                basePrice = product.basePrice,
                                                                variantPriceDelta = 0L,
                                                                unitPrice = product.basePrice,
                                                                quantity = 1,
                                                                toppings = emptyList(),
                                                                totalPrice = product.basePrice,
                                                                estimatedCost = product.baseCost,
                                                                imagePath = product.imagePath
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            // Visual Image or Neutral Placeholder
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(78.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFF111827)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (product.imagePath != null && File(product.imagePath).exists()) {
                                                    AsyncImage(
                                                        model = File(product.imagePath),
                                                        contentDescription = product.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Text(product.emoji, fontSize = 32.sp)
                                                        Text(
                                                            text = if (isCustomizable) "Personalizable" else "Producto POS",
                                                            color = TextGray.copy(alpha = 0.7f),
                                                            fontSize = 8.sp
                                                        )
                                                    }
                                                }

                                                // Availability Badge
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = when {
                                                        isOutOfStock -> DangerRed
                                                        isLowStock -> GoldPrimary
                                                        else -> SuccessGreen.copy(alpha = 0.85f)
                                                    },
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(4.dp)
                                                ) {
                                                    Text(
                                                        text = when {
                                                            isOutOfStock -> "Agotado"
                                                            isLowStock -> "Quedan ${product.stockQuantity.toInt()}"
                                                            else -> "Disponible"
                                                        },
                                                        color = if (isLowStock) DarkBackground else TextWhite,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(5.dp))
                                            Text(
                                                text = product.name,
                                                color = TextWhite,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = formatCOP(product.basePrice),
                                                    color = GoldPrimary,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 12.sp
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = GoldPrimary.copy(alpha = 0.18f)
                                                ) {
                                                    Text(
                                                        text = if (isCustomizable) "Elegir" else "+ Agregar",
                                                        color = GoldPrimary,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Compact Floating Cart Bar
                        if (cart.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = GoldPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { showCheckoutModal = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = DarkBackground,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(cart.sumOf { it.quantity }.toString(), color = GoldPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Cobrar Orden", color = DarkBackground, fontWeight = FontWeight.Black, fontSize = 13.sp)
                                    }
                                    Text(formatCOP(cartTotal), color = DarkBackground, fontWeight = FontWeight.Black, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 2. CASH DRAWER & SHIFT
                // ==========================================
                "cash" -> {
                    val shiftExpenseMovements = remember(cashShift.expenses) {
                        cashShift.expenses.filter { it.type == "EXPENSE" || it.type == "OUT" }
                    }
                    val shiftBaseAdjustments = remember(cashShift.expenses) {
                        cashShift.expenses.filter { it.type == "BASE_ADJUSTMENT" }
                    }
                    val totalShiftExpenses = shiftExpenseMovements.sumOf { it.amount }
                    val expectedCashInDrawer = cashShift.initialCash + cashShift.cashIn - totalShiftExpenses

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = DarkSurfaceElevated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Gestión de Efectivo en Caja", color = TextGray, fontSize = 11.sp)
                                Text("Responsable: ${cashShift.cashierName}", color = TextWhite, fontWeight = FontWeight.Black, fontSize = 16.sp)

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Base de Caja", color = TextGray, fontSize = 10.sp)
                                        Text(formatCOP(cashShift.initialCash), color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                    Column {
                                        Text("Ventas Efectivo", color = TextGray, fontSize = 10.sp)
                                        Text("+${formatCOP(cashShift.cashIn)}", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                    Column {
                                        Text("Otros Movimientos", color = TextGray, fontSize = 10.sp)
                                        Text("-${formatCOP(totalShiftExpenses)}", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DarkBackground,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Efectivo esperado en caja:", color = TextGray, fontSize = 11.sp)
                                        Text(formatCOP(expectedCashInDrawer), color = GoldPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    adjustBaseAmountInput = cashShift.initialCash.toString()
                                    adjustBaseReasonInput = ""
                                    adjustBaseError = null
                                    showAdjustBaseDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ajustar Base de Caja", color = TextWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    expenseConcept = ""
                                    expenseAmount = ""
                                    showExpenseDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Registrar Salida", color = TextWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                countedCashInput = ""
                                blindCloseReport = null
                                showBlindCloseDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = TextWhite, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cerrar Caja", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        if (shiftBaseAdjustments.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = DarkSurfaceElevated,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("Historial de Ajustes de Base de Caja", color = GoldPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    shiftBaseAdjustments.forEach { adj ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(adj.concept, color = TextWhite, fontSize = 11.sp)
                                                Text(adj.time, color = TextGray, fontSize = 9.sp)
                                            }
                                            Text(formatCOP(adj.amount), color = GoldPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = DarkSurfaceElevated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Otros Movimientos de Efectivo (Salidas / Gastos)", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                if (shiftExpenseMovements.isEmpty()) {
                                    Text("Sin salidas de efectivo registradas", color = TextGray, fontSize = 11.sp)
                                } else {
                                    shiftExpenseMovements.forEach { exp ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("${exp.concept} (${exp.time})", color = TextWhite, fontSize = 12.sp)
                                            Text("-${formatCOP(exp.amount)}", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 3. CENTRALIZED INVENTARIO (PRODUCTOS, INSUMOS, TOPPINGS, MATERIALES)
                // ==========================================
                "stock" -> {
                    val operationalMaterials = remember(ingredients) {
                        ingredients.filter {
                            val cat = it.category.lowercase(Locale.getDefault())
                            cat.contains("material") || cat.contains("empaque") || cat.contains("operaci") || cat.contains("desechable") || cat.contains("envase")
                        }
                    }
                    val preparationInsumos = remember(ingredients, operationalMaterials) {
                        val matIds = operationalMaterials.map { it.id }.toSet()
                        ingredients.filter { !matIds.contains(it.id) }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Inventario Central", color = TextWhite, fontWeight = FontWeight.Black, fontSize = 15.sp)
                                Text("Productos vendibles, insumos, toppings y materiales", color = TextGray, fontSize = 10.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { showCategoryAdminDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Categorías", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { showStockAdminDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Kardex / Stock", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // 4 Clear Sub-sections of Inventory
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val sections = listOf(
                                Triple("productos", "🍨 Productos (${products.size})", GoldPrimary),
                                Triple("insumos", "🥛 Insumos (${preparationInsumos.size})", GoldPrimary),
                                Triple("toppings", "🍫 Toppings (${toppingsList.size})", GoldPrimary),
                                Triple("materiales", "🥡 Materiales (${operationalMaterials.size})", GoldPrimary)
                            )
                            sections.forEach { (secId, label, _) ->
                                val isSelected = selectedInventorySection == secId
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) GoldPrimary else DarkSurfaceElevated,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) GoldPrimary else DarkBorder),
                                    modifier = Modifier.clickable { selectedInventorySection = secId }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) DarkBackground else TextWhite,
                                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        when (selectedInventorySection) {
                            "productos" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Productos de Venta y Combos", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Button(
                                        onClick = { showProductAdminDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("+ Gestionar Productos", color = DarkBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (products.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = DarkSurfaceElevated,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "No hay productos vendibles registrados. Usa '+ Gestionar Productos' para crear helados, bebidas, pizzas o combos y asociar sus insumos.",
                                            color = TextGray,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else {
                                    products.forEach { prod ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = DarkSurfaceElevated,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showProductAdminDialog = true }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                    Text(prod.emoji, fontSize = 24.sp)
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Text(prod.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                        Text(
                                                            text = "Precio: ${formatCOP(prod.basePrice)} • Receta: ${prod.recipe.size} insumos" +
                                                                if (prod.allowedToppings) " • Con Toppings" else "",
                                                            color = TextGray,
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = "${prod.stockQuantity.toInt()} ${prod.unit}",
                                                        color = if (prod.stockQuantity <= prod.minStockAlert) DangerRed else GoldPrimary,
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        text = if (prod.stockQuantity <= 0.0) "Agotado" else if (prod.stockQuantity <= prod.minStockAlert) "Stock Bajo" else "Disponible",
                                                        color = if (prod.stockQuantity <= prod.minStockAlert) DangerRed else SuccessGreen,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "insumos" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Insumos de Preparación (Leche, Bases, Frutas, Salsas)", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Button(
                                        onClick = { showIngredientAdminDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("+ Gestionar Insumos", color = DarkBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (preparationInsumos.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = DarkSurfaceElevated,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "No hay insumos de preparación registrados. Registra leche, crema, bases de helado, frutas, chocolate o salsas.",
                                            color = TextGray,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else {
                                    preparationInsumos.forEach { ing ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = DarkSurfaceElevated,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showIngredientAdminDialog = true }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(ing.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    Text(
                                                        text = "${ing.category} • Unidad: ${ing.unit} • Min: ${ing.minStockAlert} ${ing.unit}",
                                                        color = TextGray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = "${ing.currentStock} ${ing.unit}",
                                                        color = if (ing.currentStock <= ing.minStockAlert) DangerRed else GoldPrimary,
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        text = if (ing.currentStock <= 0.0) "Agotado" else if (ing.currentStock <= ing.minStockAlert) "Stock Bajo" else "Disponible",
                                                        color = if (ing.currentStock <= ing.minStockAlert) DangerRed else SuccessGreen,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "toppings" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Toppings y Adicionales", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Button(
                                        onClick = { showToppingAdminDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("+ Gestionar Toppings", color = DarkBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (toppingsList.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = DarkSurfaceElevated,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "No hay toppings registrados. Agrega chispas de chocolate, gomitas, maní, leche condensada, galleta triturada o salsas adicionales.",
                                            color = TextGray,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else {
                                    toppingsList.forEach { top ->
                                        val linkedIng = ingredients.find { it.id == top.ingredientId }
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = DarkSurfaceElevated,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showToppingAdminDialog = true }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(top.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    Text(
                                                        text = if (linkedIng != null) "Descuenta ${top.gramsRequired} ${linkedIng.unit} de ${linkedIng.name} (Stock: ${linkedIng.currentStock})"
                                                        else "Sin descuento automático de insumo",
                                                        color = TextGray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Text(
                                                    text = "+${formatCOP(top.price)}",
                                                    color = GoldPrimary,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            "materiales" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Materiales de Operación (Vasos, Conos, Servilletas, Empaques)", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Button(
                                        onClick = { showIngredientAdminDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("+ Registrar Material", color = DarkBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (operationalMaterials.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = DarkSurfaceElevated,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "No hay materiales de operación registrados. Registra vasos, conos, cucharitas, servilletas, tapas o empaques con categoría 'Materiales de operación'.",
                                            color = TextGray,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else {
                                    operationalMaterials.forEach { mat ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = DarkSurfaceElevated,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showIngredientAdminDialog = true }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(mat.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    Text(
                                                        text = "${mat.category} • Unidad: ${mat.unit} • Alerta mín: ${mat.minStockAlert}",
                                                        color = TextGray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = "${mat.currentStock} ${mat.unit}",
                                                        color = if (mat.currentStock <= mat.minStockAlert) DangerRed else GoldPrimary,
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        text = if (mat.currentStock <= 0.0) "Agotado" else if (mat.currentStock <= mat.minStockAlert) "Stock Bajo" else "Disponible",
                                                        color = if (mat.currentStock <= mat.minStockAlert) DangerRed else SuccessGreen,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 4. QR KIOSCO
                // ==========================================
                "qr" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(settings.businessName, color = TextWhite, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text("ESCANEA PARA PAGAR CON NEQUI", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(settings.phoneText, color = TextGray, fontSize = 12.sp)

                        Spacer(modifier = Modifier.height(16.dp))

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color.Black,
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                            modifier = Modifier.size(240.dp)
                        ) {
                            if (settings.qr1Path != null && File(settings.qr1Path).exists()) {
                                AsyncImage(
                                    model = File(settings.qr1Path),
                                    contentDescription = "QR Comercio",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.QrCode, contentDescription = null, tint = GoldPrimary.copy(alpha = 0.5f), modifier = Modifier.size(90.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // DIALOG: EDIT CASHIER NAME & SELECT PROFILE
    // =========================================================================
    if (showCashierDialog) {
        var cashierNameInput by remember { mutableStateOf(activeCashier.name) }
        var cashierError by remember { mutableStateOf<String?>(null) }
        var isCreatingNew by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCashierDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        if (isCreatingNew) {
                            val res = posRepository.createCashier(cashierNameInput)
                            if (res.isSuccess) {
                                posRepository.selectCashier(res.getOrThrow().id)
                                showCashierDialog = false
                            } else {
                                cashierError = res.exceptionOrNull()?.message ?: "Error al crear cajera"
                            }
                        } else {
                            val res = posRepository.updateCashierName(activeCashier.id, cashierNameInput)
                            if (res.isSuccess) {
                                showCashierDialog = false
                            } else {
                                cashierError = res.exceptionOrNull()?.message ?: "Error al actualizar nombre"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("Guardar", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCashierDialog = false }) {
                    Text("Cancelar", color = TextGray)
                }
            },
            title = {
                Text(
                    text = if (isCreatingNew) "Agregar Nueva Cajera" else "Editar Nombre de Cajera",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "El nombre de la cajera se mostrará en los recibos, ventas y arqueos de turno.",
                        color = TextGray,
                        fontSize = 11.sp
                    )

                    OutlinedTextField(
                        value = cashierNameInput,
                        onValueChange = {
                            cashierNameInput = it
                            cashierError = null
                        },
                        label = { Text("Nombre de la Cajera") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = GoldPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (cashierError != null) {
                        Text(cashierError!!, color = DangerRed, fontSize = 11.sp)
                    }

                    if (!isCreatingNew) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Perfiles de Cajeras Guardados:", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        cashiers.forEach { profile ->
                            val isSel = profile.id == activeCashier.id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) GoldPrimary.copy(alpha = 0.2f) else DarkSurfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) GoldPrimary else DarkBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        posRepository.selectCashier(profile.id)
                                        cashierNameInput = profile.name
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(profile.name, color = TextWhite, fontSize = 12.sp)
                                    if (isSel) {
                                        Text("Activa", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        TextButton(
                            onClick = {
                                isCreatingNew = true
                                cashierNameInput = ""
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ Agregar Otro Perfil", color = GoldPrimary, fontSize = 11.sp)
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // =========================================================================
    // DIALOG: PRODUCT MANAGEMENT (CRUD)
    // =========================================================================
    if (showProductAdminDialog) {
        var editingProduct by remember { mutableStateOf<Product?>(null) }
        var isNewProduct by remember { mutableStateOf(false) }

        var prodName by remember { mutableStateOf("") }
        var prodPrice by remember { mutableStateOf("") }
        var prodCost by remember { mutableStateOf("") }
        var prodCatId by remember { mutableStateOf(categories.firstOrNull { it.id != "ingredientes" }?.id ?: "productos") }
        var prodDesc by remember { mutableStateOf("") }
        var prodUnit by remember { mutableStateOf("unidades") }
        var prodStock by remember { mutableStateOf("10") }
        var prodMinAlert by remember { mutableStateOf("5") }
        var prodImagePath by remember { mutableStateOf<String?>(null) }
        var prodEmoji by remember { mutableStateOf("🍕") }
        var prodActive by remember { mutableStateOf(true) }
        var prodIsCombo by remember { mutableStateOf(false) }
        var prodAllowNegative by remember { mutableStateOf(false) }
        var prodAllowedToppings by remember { mutableStateOf(false) }
        val prodVariants = remember { mutableStateListOf<ProductVariant>() }
        var newVariantName by remember { mutableStateOf("") }
        var newVariantPriceDelta by remember { mutableStateOf("0") }
        val prodRecipe = remember { mutableStateListOf<RecipeItem>() }
        val prodComboItems = remember { mutableStateListOf<ComboItem>() }
        var formError by remember { mutableStateOf<String?>(null) }

        // Ingredient picker helper state
        var selectedIngIdForRecipe by remember { mutableStateOf("") }
        var recipeQtyInput by remember { mutableStateOf("100") }
        var recipeUnitInput by remember { mutableStateOf("g") }

        // Combo item helper state
        var selectedChildProdId by remember { mutableStateOf("") }
        var comboQtyInput by remember { mutableStateOf("1") }

        val photoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                val saved = ImageStore.saveGalleryImage(context, it, "prod")
                if (saved != null) {
                    prodImagePath = saved
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                showProductAdminDialog = false
                editingProduct = null
                isNewProduct = false
            },
            confirmButton = {
                if (editingProduct != null || isNewProduct) {
                    Button(
                        onClick = {
                            val priceLong = prodPrice.toLongOrNull() ?: -1L
                            val costLong = prodCost.toLongOrNull() ?: 0L
                            val stockDbl = prodStock.toDoubleOrNull() ?: 0.0
                            val minAlertDbl = prodMinAlert.toDoubleOrNull() ?: 5.0

                            if (prodName.trim().isEmpty()) {
                                formError = "El nombre es obligatorio"
                                return@Button
                            }
                            if (priceLong < 0) {
                                formError = "El precio debe ser un número válido"
                                return@Button
                            }

                            if (isNewProduct) {
                                val res = posRepository.createProduct(
                                    name = prodName,
                                    basePrice = priceLong,
                                    categoryId = prodCatId,
                                    description = prodDesc,
                                    imagePath = prodImagePath,
                                    emoji = prodEmoji,
                                    stockQuantity = stockDbl,
                                    unit = prodUnit,
                                    baseCost = costLong,
                                    minStockAlert = minAlertDbl,
                                    isCombo = prodIsCombo,
                                    recipe = prodRecipe.toList(),
                                    comboItems = prodComboItems.toList(),
                                    allowNegativeStock = prodAllowNegative,
                                    variants = prodVariants.toList(),
                                    allowedToppings = prodAllowedToppings
                                )
                                if (res.isSuccess) {
                                    isNewProduct = false
                                    editingProduct = null
                                } else {
                                    formError = res.exceptionOrNull()?.message
                                }
                            } else {
                                val res = posRepository.updateProduct(
                                    id = editingProduct!!.id,
                                    name = prodName,
                                    basePrice = priceLong,
                                    categoryId = prodCatId,
                                    description = prodDesc,
                                    imagePath = prodImagePath,
                                    emoji = prodEmoji,
                                    unit = prodUnit,
                                    baseCost = costLong,
                                    minStockAlert = minAlertDbl,
                                    isActive = prodActive,
                                    isCombo = prodIsCombo,
                                    recipe = prodRecipe.toList(),
                                    comboItems = prodComboItems.toList(),
                                    allowNegativeStock = prodAllowNegative,
                                    variants = prodVariants.toList(),
                                    allowedToppings = prodAllowedToppings
                                )
                                if (res.isSuccess) {
                                    editingProduct = null
                                } else {
                                    formError = res.exceptionOrNull()?.message
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Guardar Producto", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { showProductAdminDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Listo", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (editingProduct != null || isNewProduct) {
                    TextButton(onClick = {
                        editingProduct = null
                        isNewProduct = false
                    }) {
                        Text("Volver", color = TextGray)
                    }
                }
            },
            title = {
                Text(
                    text = if (isNewProduct) "Crear Nuevo Producto" else if (editingProduct != null) "Editar Producto" else "Administrar Productos",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                if (editingProduct != null || isNewProduct) {
                    // Form View
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = prodName,
                            onValueChange = { prodName = it; formError = null },
                            label = { Text("Nombre del Producto") },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = GoldPrimary),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = prodPrice,
                                onValueChange = { prodPrice = it; formError = null },
                                label = { Text("Precio COP") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary, focusedBorderColor = GoldPrimary),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = prodCost,
                                onValueChange = { prodCost = it },
                                label = { Text("Costo Base COP") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = GoldPrimary),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Category Dropdown selector
                        Text("Categoría:", color = TextGray, fontSize = 11.sp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            categories.filter { it.id != "ingredientes" }.forEach { cat ->
                                val isSel = prodCatId == cat.id
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) GoldPrimary else DarkSurfaceElevated,
                                    modifier = Modifier.clickable {
                                        prodCatId = cat.id
                                        if (cat.id == "combos") prodIsCombo = true
                                    }
                                ) {
                                    Text(
                                        text = "${cat.emoji} ${cat.name}",
                                        color = if (isSel) DarkBackground else TextWhite,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // Photo Picker Section
                        Text("Fotografía del Producto:", color = TextGray, fontSize = 11.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black,
                                modifier = Modifier.size(50.dp)
                            ) {
                                if (prodImagePath != null && File(prodImagePath!!).exists()) {
                                    AsyncImage(
                                        model = File(prodImagePath!!),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(prodEmoji, fontSize = 24.sp)
                                    }
                                }
                            }

                            Button(
                                onClick = { photoPickerLauncher.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Elegir de Galería", color = TextWhite, fontSize = 11.sp)
                            }

                            if (prodImagePath != null) {
                                TextButton(onClick = { prodImagePath = null }) {
                                    Text("Quitar", color = DangerRed, fontSize = 11.sp)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = prodDesc,
                            onValueChange = { prodDesc = it },
                            label = { Text("Descripción (opcional)") },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = GoldPrimary),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = prodStock,
                                onValueChange = { prodStock = it },
                                label = { Text("Stock Actual") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = GoldPrimary),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = prodUnit,
                                onValueChange = { prodUnit = it },
                                label = { Text("Unidad (ej. unidades)") },
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = GoldPrimary),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Tipo de Producto: Estándar con receta vs Combo
                        Text("Tipo de Producto:", color = TextGray, fontSize = 11.sp)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (!prodIsCombo) GoldPrimary else DarkSurfaceElevated,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { prodIsCombo = false }
                            ) {
                                Text(
                                    "🍨 Individual / Receta",
                                    color = if (!prodIsCombo) DarkBackground else TextWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (prodIsCombo) GoldPrimary else DarkSurfaceElevated,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { prodIsCombo = true }
                            ) {
                                Text(
                                    "🎁 Combo Compuesto",
                                    color = if (prodIsCombo) DarkBackground else TextWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
                                )
                            }
                        }

                        if (prodIsCombo) {
                            // SECCIÓN: COMBO ITEMS
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = DarkBackground,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Componentes del Combo (${prodComboItems.size})", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                                    if (prodComboItems.isEmpty()) {
                                        Text("No has añadido productos a este combo.", color = TextGray, fontSize = 10.sp)
                                    } else {
                                        prodComboItems.forEachIndexed { idx, ci ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("• ${ci.quantity}x ${ci.productName}", color = TextWhite, fontSize = 11.sp)
                                                IconButton(
                                                    onClick = { prodComboItems.removeAt(idx) },
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = "Quitar", tint = DangerRed, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }

                                    // Selector de producto para añadir
                                    val candidateProducts = products.filter { p ->
                                        p.id != editingProduct?.id && !p.isCombo
                                    }
                                    if (candidateProducts.isNotEmpty()) {
                                        Text("Añadir producto:", color = TextGray, fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            var selProd by remember { mutableStateOf(candidateProducts.first().id) }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = DarkSurfaceElevated,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .horizontalScroll(rememberScrollState())
                                                        .padding(4.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    candidateProducts.forEach { cp ->
                                                        val isSel = selProd == cp.id
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = if (isSel) GoldPrimary else DarkBackground,
                                                            modifier = Modifier.clickable { selProd = cp.id }
                                                        ) {
                                                            Text(
                                                                cp.name,
                                                                color = if (isSel) DarkBackground else TextWhite,
                                                                fontSize = 9.sp,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    val cp = candidateProducts.find { it.id == selProd }
                                                    if (cp != null) {
                                                        prodComboItems.add(ComboItem(cp.id, cp.name, 1))
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("+ Añadir", color = DarkBackground, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // SECCIÓN: RECETA E INGREDIENTES
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = DarkBackground,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val recipeCost = posRepository.calculateRecipeCost(prodRecipe)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Receta e Insumos (${prodRecipe.size})", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("Costo: ${formatCOP(recipeCost)}", color = TextGray, fontSize = 10.sp)
                                    }

                                    if (prodRecipe.isEmpty()) {
                                        Text("Sin receta definida. Se descontará directamente del stock del producto.", color = TextGray, fontSize = 10.sp)
                                    } else {
                                        prodRecipe.forEachIndexed { idx, item ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("• ${item.ingredientName}: ${item.quantity} ${item.unit}", color = TextWhite, fontSize = 11.sp)
                                                IconButton(
                                                    onClick = { prodRecipe.removeAt(idx) },
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = "Quitar", tint = DangerRed, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }

                                    // Selector de ingrediente a incorporar
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Añadir Insumo a la Receta:", color = TextGray, fontSize = 10.sp)
                                        TextButton(
                                            onClick = { showIngredientAdminDialog = true },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("+ Gestionar Insumos", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (ingredients.isEmpty()) {
                                        Text("No hay ingredientes creados. Pulsa '+ Gestionar Insumos' para registrarlos.", color = TextGray, fontSize = 10.sp)
                                    } else {
                                        val firstIng = ingredients.first()
                                        if (selectedIngIdForRecipe.isEmpty() || ingredients.none { it.id == selectedIngIdForRecipe }) {
                                            selectedIngIdForRecipe = firstIng.id
                                            recipeUnitInput = firstIng.unit
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            ingredients.forEach { ing ->
                                                val isSel = selectedIngIdForRecipe == ing.id
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (isSel) GoldPrimary else DarkSurfaceElevated,
                                                    modifier = Modifier.clickable {
                                                        selectedIngIdForRecipe = ing.id
                                                        recipeUnitInput = ing.unit
                                                    }
                                                ) {
                                                    Text(
                                                        "${ing.name} (${ing.unit})",
                                                        color = if (isSel) DarkBackground else TextWhite,
                                                        fontSize = 9.sp,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = recipeQtyInput,
                                                onValueChange = { recipeQtyInput = it },
                                                label = { Text("Cantidad") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite),
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = recipeUnitInput,
                                                onValueChange = { recipeUnitInput = it },
                                                label = { Text("Unidad") },
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = {
                                                    val ing = ingredients.find { it.id == selectedIngIdForRecipe }
                                                    val q = recipeQtyInput.toDoubleOrNull() ?: 0.0
                                                    if (ing != null && q > 0) {
                                                        prodRecipe.add(RecipeItem(ing.id, ing.name, q, recipeUnitInput.trim()))
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text("+ Agregar", color = DarkBackground, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // SECCIÓN: PRESENTACIONES / BOLAS Y TOPPINGS
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkBackground,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Presentaciones / Bolas (${prodVariants.size})", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    TextButton(
                                        onClick = {
                                            prodVariants.clear()
                                            prodVariants.add(ProductVariant("bola-1", "1 Bola", 0L, 1.0))
                                            prodVariants.add(ProductVariant("bola-2", "2 Bolas", 3500L, 2.0))
                                            prodVariants.add(ProductVariant("bola-3", "3 Bolas", 6500L, 3.0))
                                            prodAllowedToppings = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("+ Cargar 1/2/3 Bolas", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                prodVariants.forEachIndexed { idx, v ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("• ${v.name} (+${formatCOP(v.priceDelta)})", color = TextWhite, fontSize = 11.sp)
                                        IconButton(
                                            onClick = { prodVariants.removeAt(idx) },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Quitar", tint = DangerRed, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = newVariantName,
                                        onValueChange = { newVariantName = it },
                                        label = { Text("Opción (ej. 2 Bolas)") },
                                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = newVariantPriceDelta,
                                        onValueChange = { newVariantPriceDelta = it },
                                        label = { Text("+Precio COP") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            val vName = newVariantName.trim()
                                            val delta = newVariantPriceDelta.toLongOrNull() ?: 0L
                                            if (vName.isNotEmpty()) {
                                                prodVariants.add(ProductVariant("var-${System.currentTimeMillis()}", vName, delta, 1.0))
                                                newVariantName = ""
                                                newVariantPriceDelta = "0"
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("+ Opción", color = DarkBackground, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Permitir elegir Toppings en Venta", color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("Muestra selector de tarjetas de toppings al vender", color = TextGray, fontSize = 9.sp)
                                    }
                                    Switch(
                                        checked = prodAllowedToppings,
                                        onCheckedChange = { prodAllowedToppings = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                                    )
                                }
                            }
                        }

                        // Permitir inventario negativo
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Permitir existencias negativas", color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Permite vender aun sin stock registrado previo", color = TextGray, fontSize = 9.sp)
                            }
                            Switch(
                                checked = prodAllowNegative,
                                onCheckedChange = { prodAllowNegative = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Producto Activo en Ventas", color = TextWhite, fontSize = 12.sp)
                            Switch(
                                checked = prodActive,
                                onCheckedChange = { prodActive = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                            )
                        }

                        if (formError != null) {
                            Text(formError!!, color = DangerRed, fontSize = 11.sp)
                        }
                    }
                } else {
                    // Products List View
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isNewProduct = true
                                prodName = ""
                                prodPrice = ""
                                prodCost = ""
                                prodCatId = categories.firstOrNull { it.id != "ingredientes" }?.id ?: "productos"
                                prodDesc = ""
                                prodStock = "10"
                                prodUnit = "unidades"
                                prodMinAlert = "5"
                                prodImagePath = null
                                prodEmoji = "🍨"
                                prodActive = true
                                prodIsCombo = false
                                prodAllowNegative = false
                                prodAllowedToppings = false
                                prodVariants.clear()
                                prodRecipe.clear()
                                prodComboItems.clear()
                                formError = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ Nuevo Producto", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(products, key = { it.id }) { p ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DarkBackground,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (p.imagePath != null && File(p.imagePath).exists()) {
                                                AsyncImage(
                                                    model = File(p.imagePath),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )
                                            } else {
                                                Text(p.emoji, fontSize = 22.sp)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(p.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    if (p.isCombo) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = GoldPrimary.copy(alpha = 0.2f)
                                                        ) {
                                                            Text("COMBO", color = GoldPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                        }
                                                    } else if (p.recipe.isNotEmpty()) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = SuccessGreen.copy(alpha = 0.2f)
                                                        ) {
                                                            Text("RECETA", color = SuccessGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                        }
                                                    }
                                                }
                                                Text("${formatCOP(p.basePrice)} • ${p.stockQuantity.toInt()} ${p.unit}", color = GoldPrimary, fontSize = 10.sp)
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    editingProduct = p
                                                    isNewProduct = false
                                                    prodName = p.name
                                                    prodPrice = p.basePrice.toString()
                                                    prodCost = p.baseCost.toString()
                                                    prodCatId = p.categoryId
                                                    prodDesc = p.description
                                                    prodStock = p.stockQuantity.toString()
                                                    prodUnit = p.unit
                                                    prodMinAlert = p.minStockAlert.toString()
                                                    prodImagePath = p.imagePath
                                                    prodEmoji = p.emoji
                                                    prodActive = p.isActive
                                                    prodIsCombo = p.isCombo
                                                    prodAllowNegative = p.allowNegativeStock
                                                    prodAllowedToppings = p.allowedToppings
                                                    prodVariants.clear()
                                                    prodVariants.addAll(p.variants)
                                                    prodRecipe.clear()
                                                    prodRecipe.addAll(p.recipe)
                                                    prodComboItems.clear()
                                                    prodComboItems.addAll(p.comboItems)
                                                    formError = null
                                                }
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Editar", tint = GoldPrimary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // =========================================================================
    // DIALOG: INGREDIENT MANAGEMENT (CRUD & STOCK MOVEMENTS)
    // =========================================================================
    if (showIngredientAdminDialog) {
        var editingIngredient by remember { mutableStateOf<Ingredient?>(null) }
        var isNewIngredient by remember { mutableStateOf(false) }
        var stockAdjustIngredient by remember { mutableStateOf<Ingredient?>(null) }

        var ingName by remember { mutableStateOf("") }
        var ingCategory by remember { mutableStateOf("Insumos") }
        var ingUnit by remember { mutableStateOf("g") }
        var ingInitialStock by remember { mutableStateOf("0") }
        var ingMinAlert by remember { mutableStateOf("100") }
        var ingUnitCost by remember { mutableStateOf("0") }
        var ingActive by remember { mutableStateOf(true) }
        var ingError by remember { mutableStateOf<String?>(null) }

        // Stock adjustment fields for an ingredient
        var ingMoveType by remember { mutableStateOf("PURCHASE") } // PURCHASE, WASTE_MERMA, MANUAL_ADJUSTMENT
        var ingMoveQty by remember { mutableStateOf("") }
        var ingMoveUnit by remember { mutableStateOf("g") }
        var ingMoveReason by remember { mutableStateOf("") }

        val availableUnits = listOf("g", "kg", "ml", "L", "unidades")

        AlertDialog(
            onDismissRequest = {
                showIngredientAdminDialog = false
                editingIngredient = null
                isNewIngredient = false
                stockAdjustIngredient = null
            },
            confirmButton = {
                when {
                    stockAdjustIngredient != null -> {
                        Button(
                            onClick = {
                                val target = stockAdjustIngredient ?: return@Button
                                val qty = ingMoveQty.toDoubleOrNull()
                                if (qty == null || qty < 0.0) {
                                    ingError = "Ingresa una cantidad válida"
                                    return@Button
                                }
                                val res = when (ingMoveType) {
                                    "PURCHASE" -> posRepository.recordIngredientPurchase(
                                        ingredientId = target.id,
                                        quantity = qty,
                                        unit = ingMoveUnit,
                                        supplierOrNote = ingMoveReason.ifBlank { "Compra de insumo" },
                                        userName = activeCashier.name
                                    )
                                    "WASTE_MERMA" -> posRepository.recordIngredientWaste(
                                        ingredientId = target.id,
                                        quantity = qty,
                                        unit = ingMoveUnit,
                                        reason = ingMoveReason.ifBlank { "Merma operativa" },
                                        userName = activeCashier.name
                                    )
                                    else -> posRepository.adjustIngredientStock(
                                        ingredientId = target.id,
                                        newStock = qty,
                                        reason = ingMoveReason.ifBlank { "Ajuste manual de inventario" },
                                        userName = activeCashier.name
                                    )
                                }
                                if (res.isSuccess) {
                                    stockAdjustIngredient = null
                                    ingError = null
                                } else {
                                    ingError = res.exceptionOrNull()?.message
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text("Aplicar Movimiento", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                    editingIngredient != null || isNewIngredient -> {
                        Button(
                            onClick = {
                                val minAlert = ingMinAlert.toDoubleOrNull() ?: 0.0
                                val cost = ingUnitCost.toLongOrNull() ?: 0L
                                if (ingName.trim().isEmpty()) {
                                    ingError = "El nombre del ingrediente es obligatorio"
                                    return@Button
                                }
                                if (isNewIngredient) {
                                    val initStock = ingInitialStock.toDoubleOrNull() ?: 0.0
                                    val res = posRepository.createIngredient(
                                        name = ingName,
                                        unit = ingUnit,
                                        initialStock = initStock,
                                        minStockAlert = minAlert,
                                        unitCostCOP = cost,
                                        category = ingCategory.ifBlank { "Insumos" }
                                    )
                                    if (res.isSuccess) {
                                        isNewIngredient = false
                                        editingIngredient = null
                                        ingError = null
                                    } else {
                                        ingError = res.exceptionOrNull()?.message
                                    }
                                } else {
                                    val res = posRepository.updateIngredient(
                                        id = editingIngredient!!.id,
                                        name = ingName,
                                        unit = ingUnit,
                                        minStockAlert = minAlert,
                                        unitCostCOP = cost,
                                        isActive = ingActive,
                                        category = ingCategory.ifBlank { "Insumos" }
                                    )
                                    if (res.isSuccess) {
                                        editingIngredient = null
                                        ingError = null
                                    } else {
                                        ingError = res.exceptionOrNull()?.message
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text("Guardar Ingrediente", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {
                        Button(
                            onClick = { showIngredientAdminDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text("Cerrar", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                if (editingIngredient != null || isNewIngredient || stockAdjustIngredient != null) {
                    TextButton(onClick = {
                        editingIngredient = null
                        isNewIngredient = false
                        stockAdjustIngredient = null
                        ingError = null
                    }) {
                        Text("Volver", color = TextGray)
                    }
                }
            },
            title = {
                Text(
                    text = when {
                        stockAdjustIngredient != null -> "Existencias: ${stockAdjustIngredient!!.name}"
                        isNewIngredient -> "Registrar Nuevo Ingrediente"
                        editingIngredient != null -> "Editar Ingrediente"
                        else -> "Administrar Ingredientes (${ingredients.size})"
                    },
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                when {
                    stockAdjustIngredient != null -> {
                        val target = stockAdjustIngredient!!
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Stock actual: ${target.currentStock} ${target.unit}",
                                color = GoldPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { ingMoveType = "PURCHASE" },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (ingMoveType == "PURCHASE") SuccessGreen else DarkBackground),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("+ Compra", fontSize = 10.sp, color = TextWhite)
                                }
                                Button(
                                    onClick = { ingMoveType = "WASTE_MERMA" },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (ingMoveType == "WASTE_MERMA") DangerRed else DarkBackground),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("- Merma", fontSize = 10.sp, color = TextWhite)
                                }
                                Button(
                                    onClick = {
                                        ingMoveType = "MANUAL_ADJUSTMENT"
                                        ingMoveUnit = target.unit
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (ingMoveType == "MANUAL_ADJUSTMENT") GoldPrimary else DarkBackground),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Fijar Stock", fontSize = 10.sp, color = if (ingMoveType == "MANUAL_ADJUSTMENT") DarkBackground else TextWhite)
                                }
                            }

                            OutlinedTextField(
                                value = ingMoveQty,
                                onValueChange = { ingMoveQty = it; ingError = null },
                                label = {
                                    Text(
                                        if (ingMoveType == "MANUAL_ADJUSTMENT") "Nuevo stock total (${target.unit})"
                                        else "Cantidad (${ingMoveUnit})"
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (ingMoveType != "MANUAL_ADJUSTMENT") {
                                Text("Unidad del movimiento:", color = TextGray, fontSize = 10.sp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    availableUnits.forEach { u ->
                                        val isSel = ingMoveUnit == u
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSel) GoldPrimary else DarkBackground,
                                            modifier = Modifier.clickable { ingMoveUnit = u }
                                        ) {
                                            Text(
                                                text = u,
                                                color = if (isSel) DarkBackground else TextWhite,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = ingMoveReason,
                                onValueChange = { ingMoveReason = it; ingError = null },
                                label = { Text("Motivo o Proveedor (obligatorio)") },
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (ingError != null) {
                                Text(ingError!!, color = DangerRed, fontSize = 11.sp)
                            }
                        }
                    }

                    editingIngredient != null || isNewIngredient -> {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = ingName,
                                onValueChange = { ingName = it; ingError = null },
                                label = { Text("Nombre del Ingrediente (ej. Queso Mozzarella)") },
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = GoldPrimary),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text("Clasificación del Elemento en Inventario:", color = TextGray, fontSize = 11.sp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("Insumos", "Lácteos y Bases", "Frutas y Salsas", "Materiales de operación").forEach { presetCat ->
                                    val isSel = ingCategory.equals(presetCat, ignoreCase = true)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSel) GoldPrimary else DarkBackground,
                                        modifier = Modifier.clickable { ingCategory = presetCat }
                                    ) {
                                        Text(
                                            text = presetCat,
                                            color = if (isSel) DarkBackground else TextWhite,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = ingCategory,
                                onValueChange = { ingCategory = it },
                                label = { Text("Grupo / Categoría (ej. Insumos, Materiales de operación)") },
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = GoldPrimary),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text("Unidad Base de Inventario:", color = TextGray, fontSize = 11.sp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                availableUnits.forEach { u ->
                                    val isSel = ingUnit == u
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSel) GoldPrimary else DarkBackground,
                                        modifier = Modifier.clickable { ingUnit = u }
                                    ) {
                                        Text(
                                            text = u,
                                            color = if (isSel) DarkBackground else TextWhite,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isNewIngredient) {
                                    OutlinedTextField(
                                        value = ingInitialStock,
                                        onValueChange = { ingInitialStock = it },
                                        label = { Text("Stock Inicial ($ingUnit)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                OutlinedTextField(
                                    value = ingMinAlert,
                                    onValueChange = { ingMinAlert = it },
                                    label = { Text("Alerta Mín. ($ingUnit)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = ingUnitCost,
                                onValueChange = { ingUnitCost = it },
                                label = { Text("Costo por $ingUnit (COP)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (!isNewIngredient) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Ingrediente Activo", color = TextWhite, fontSize = 12.sp)
                                    Switch(
                                        checked = ingActive,
                                        onCheckedChange = { ingActive = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
                                    )
                                }
                            }

                            if (ingError != null) {
                                Text(ingError!!, color = DangerRed, fontSize = 11.sp)
                            }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isNewIngredient = true
                                    editingIngredient = null
                                    stockAdjustIngredient = null
                                    ingName = ""
                                    ingCategory = "Insumos"
                                    ingUnit = "g"
                                    ingInitialStock = "0"
                                    ingMinAlert = "100"
                                    ingUnitCost = "0"
                                    ingActive = true
                                    ingError = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+ Nuevo Ingrediente", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }

                            if (ingredients.isEmpty()) {
                                Text(
                                    text = "No hay ingredientes registrados. Crea aquí los insumos para tus recetas de Pizzería Natus.",
                                    color = TextGray,
                                    fontSize = 11.sp
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(ingredients, key = { it.id }) { ing ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = DarkBackground,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(ing.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    Text(
                                                        text = "${ing.currentStock} ${ing.unit} • Costo/${ing.unit}: ${formatCOP(ing.unitCostCOP)}",
                                                        color = if (ing.currentStock <= ing.minStockAlert) DangerRed else GoldPrimary,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(
                                                        onClick = {
                                                            stockAdjustIngredient = ing
                                                            ingMoveType = "PURCHASE"
                                                            ingMoveQty = ""
                                                            ingMoveUnit = ing.unit
                                                            ingMoveReason = ""
                                                            ingError = null
                                                        }
                                                    ) {
                                                        Icon(Icons.Default.Inventory2, contentDescription = "Existencias", tint = SuccessGreen, modifier = Modifier.size(18.dp))
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            editingIngredient = ing
                                                            isNewIngredient = false
                                                            ingName = ing.name
                                                            ingCategory = ing.category
                                                            ingUnit = ing.unit
                                                            ingMinAlert = ing.minStockAlert.toString()
                                                            ingUnitCost = ing.unitCostCOP.toString()
                                                            ingActive = ing.isActive
                                                            ingError = null
                                                        }
                                                    ) {
                                                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = GoldPrimary, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // =========================================================================
    // DIALOG: CATEGORY MANAGEMENT (SAFE DELETE & REASSIGN)
    // =========================================================================
    if (showCategoryAdminDialog) {
        var newCatName by remember { mutableStateOf("") }
        var newCatEmoji by remember { mutableStateOf("🍕") }
        var catToDelete by remember { mutableStateOf<Category?>(null) }
        var reassignTargetId by remember { mutableStateOf("") }
        var catError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                showCategoryAdminDialog = false
                catToDelete = null
            },
            confirmButton = {
                if (catToDelete != null) {
                    Button(
                        onClick = {
                            val res = posRepository.deleteCategory(catToDelete!!.id, reassignTargetId)
                            if (res.isSuccess) {
                                catToDelete = null
                            } else {
                                catError = res.exceptionOrNull()?.message
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                    ) {
                        Text("Confirmar Reasignación y Eliminar", color = TextWhite, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { showCategoryAdminDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Cerrar", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (catToDelete != null) {
                    TextButton(onClick = { catToDelete = null }) {
                        Text("Cancelar", color = TextGray)
                    }
                }
            },
            title = {
                Text(
                    text = if (catToDelete != null) "Reasignar Productos de Categoría" else "Administrar Categorías",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (catToDelete != null) {
                    val count = posRepository.getProductCountForCategory(catToDelete!!.id)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "La categoría '${catToDelete!!.name}' tiene $count producto(s) asociado(s). Para no perderlos, selecciona la categoría de destino:",
                            color = TextWhite,
                            fontSize = 12.sp
                        )
                        categories.filter { it.id != catToDelete!!.id }.forEach { target ->
                            val isSel = reassignTargetId == target.id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) GoldPrimary.copy(alpha = 0.2f) else DarkBackground,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) GoldPrimary else DarkBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { reassignTargetId = target.id }
                            ) {
                                Text(
                                    text = "${target.emoji} ${target.name}",
                                    color = if (isSel) GoldPrimary else TextWhite,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        if (catError != null) {
                            Text(catError!!, color = DangerRed, fontSize = 11.sp)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Create Category
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = newCatName,
                                onValueChange = { newCatName = it; catError = null },
                                placeholder = { Text("Nueva Categoría") },
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val res = posRepository.createCategory(newCatName, newCatEmoji)
                                    if (res.isSuccess) {
                                        newCatName = ""
                                    } else {
                                        catError = res.exceptionOrNull()?.message
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Crear", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (catError != null) {
                            Text(catError!!, color = DangerRed, fontSize = 11.sp)
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(categories, key = { it.id }) { cat ->
                                val count = posRepository.getProductCountForCategory(cat.id)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DarkBackground,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("${cat.emoji} ${cat.name}", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("$count productos asociados", color = TextGray, fontSize = 10.sp)
                                        }

                                        if (categories.size > 1) {
                                            IconButton(
                                                onClick = {
                                                    catToDelete = cat
                                                    val other = categories.firstOrNull { it.id != cat.id }
                                                    reassignTargetId = other?.id ?: ""
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = DangerRed, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // =========================================================================
    // DIALOG: STOCK ADJUSTMENT & AUDIT LOGS
    // =========================================================================
    if (showStockAdminDialog) {
        var selectedProductForStock by remember { mutableStateOf<Product?>(products.firstOrNull()) }
        var adjustmentAmount by remember { mutableStateOf("") }
        var adjustmentReason by remember { mutableStateOf("") }
        var adjustmentType by remember { mutableStateOf("MANUAL_ADJUSTMENT") } // MANUAL_ADJUSTMENT, PURCHASE, WASTE_MERMA
        var stockError by remember { mutableStateOf<String?>(null) }
        var currentSubTab by remember { mutableStateOf("adjust") } // adjust, logs

        AlertDialog(
            onDismissRequest = { showStockAdminDialog = false },
            confirmButton = {
                if (currentSubTab == "adjust") {
                    Button(
                        onClick = {
                            val prod = selectedProductForStock
                            if (prod == null) return@Button
                            val qty = adjustmentAmount.toDoubleOrNull()
                            if (qty == null) {
                                stockError = "Cantidad inválida"
                                return@Button
                            }

                            when (adjustmentType) {
                                "PURCHASE" -> {
                                    val res = posRepository.recordPurchaseEntry(prod.id, qty, adjustmentReason, activeCashier.name)
                                    if (res.isSuccess) {
                                        adjustmentAmount = ""
                                        adjustmentReason = ""
                                        currentSubTab = "logs"
                                    } else stockError = res.exceptionOrNull()?.message
                                }
                                "WASTE_MERMA" -> {
                                    val res = posRepository.recordWaste(prod.id, qty, adjustmentReason, activeCashier.name)
                                    if (res.isSuccess) {
                                        adjustmentAmount = ""
                                        adjustmentReason = ""
                                        currentSubTab = "logs"
                                    } else stockError = res.exceptionOrNull()?.message
                                }
                                else -> {
                                    val res = posRepository.adjustStock(
                                        productId = prod.id,
                                        newStock = qty,
                                        reason = adjustmentReason,
                                        userName = activeCashier.name,
                                        type = "MANUAL_ADJUSTMENT"
                                    )
                                    if (res.isSuccess) {
                                        adjustmentAmount = ""
                                        adjustmentReason = ""
                                        currentSubTab = "logs"
                                    } else stockError = res.exceptionOrNull()?.message
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Registrar Ajuste", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { showStockAdminDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Cerrar", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showStockAdminDialog = false }) {
                    Text("Cancelar", color = TextGray)
                }
            },
            title = {
                Text("Inventario y Auditoría", color = TextWhite, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Sub Tabs: Adjust vs Logs
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { currentSubTab = "adjust" },
                            colors = ButtonDefaults.buttonColors(containerColor = if (currentSubTab == "adjust") GoldPrimary else DarkBackground),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Ajustar Stock", color = if (currentSubTab == "adjust") DarkBackground else TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { currentSubTab = "logs" },
                            colors = ButtonDefaults.buttonColors(containerColor = if (currentSubTab == "logs") GoldPrimary else DarkBackground),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Historial (${inventoryMovements.size})", color = if (currentSubTab == "logs") DarkBackground else TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (currentSubTab == "adjust") {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Selecciona el producto:", color = TextGray, fontSize = 11.sp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                products.forEach { p ->
                                    val isSel = selectedProductForStock?.id == p.id
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSel) GoldPrimary else DarkBackground,
                                        modifier = Modifier.clickable {
                                            selectedProductForStock = p
                                            adjustmentAmount = p.stockQuantity.toString()
                                        }
                                    ) {
                                        Text(
                                            text = "${p.emoji} ${p.name} (${p.stockQuantity.toInt()})",
                                            color = if (isSel) DarkBackground else TextWhite,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { adjustmentType = "MANUAL_ADJUSTMENT" },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (adjustmentType == "MANUAL_ADJUSTMENT") GoldPrimary else DarkBackground),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Fijar Total", fontSize = 9.sp, color = if (adjustmentType == "MANUAL_ADJUSTMENT") DarkBackground else TextWhite)
                                }
                                Button(
                                    onClick = { adjustmentType = "PURCHASE" },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (adjustmentType == "PURCHASE") SuccessGreen else DarkBackground),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("+ Entrada", fontSize = 9.sp, color = TextWhite)
                                }
                                Button(
                                    onClick = { adjustmentType = "WASTE_MERMA" },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (adjustmentType == "WASTE_MERMA") DangerRed else DarkBackground),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("- Merma", fontSize = 9.sp, color = TextWhite)
                                }
                            }

                            OutlinedTextField(
                                value = adjustmentAmount,
                                onValueChange = { adjustmentAmount = it; stockError = null },
                                label = { Text(if (adjustmentType == "MANUAL_ADJUSTMENT") "Nuevo Stock Resultante" else "Cantidad a Ingresar/Descontar") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = adjustmentReason,
                                onValueChange = { adjustmentReason = it; stockError = null },
                                label = { Text("Motivo Obligatorio (ej. Factura 123, Vencimiento)") },
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text("Usuario auditor: ${activeCashier.name}", color = TextGray, fontSize = 11.sp)

                            if (stockError != null) {
                                Text(stockError!!, color = DangerRed, fontSize = 11.sp)
                            }
                        }
                    } else {
                        // Movements Log View
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (inventoryMovements.isEmpty()) {
                                item {
                                    Text("Sin movimientos registrados", color = TextGray, fontSize = 11.sp)
                                }
                            } else {
                                items(inventoryMovements, key = { it.id }) { mov ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkBackground,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(mov.productName, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Text(
                                                    text = (if (mov.change > 0) "+" else "") + "${mov.change} (${mov.resultingStock})",
                                                    color = if (mov.change > 0) SuccessGreen else DangerRed,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Text("${mov.type} • ${mov.reason}", color = TextGray, fontSize = 10.sp)
                                            Text("${mov.dateFormatted} por ${mov.userName}", color = GoldPrimary, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // Modal: Toppings Administration (Centralized in Inventory)
    if (showToppingAdminDialog) {
        var editingTopping by remember { mutableStateOf<ToppingOption?>(null) }
        var isNewTopping by remember { mutableStateOf(false) }
        var topName by remember { mutableStateOf("") }
        var topPrice by remember { mutableStateOf("2000") }
        var topIngredientId by remember { mutableStateOf<String?>(null) }
        var topGramsRequired by remember { mutableStateOf("20") }
        var topError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                showToppingAdminDialog = false
                editingTopping = null
                isNewTopping = false
            },
            confirmButton = {
                if (editingTopping != null || isNewTopping) {
                    Button(
                        onClick = {
                            val price = topPrice.toLongOrNull() ?: -1L
                            val grams = topGramsRequired.toDoubleOrNull() ?: 0.0
                            val res = if (isNewTopping) {
                                posRepository.createTopping(
                                    name = topName,
                                    price = price,
                                    ingredientId = topIngredientId,
                                    gramsRequired = grams
                                )
                            } else {
                                posRepository.updateTopping(
                                    id = editingTopping!!.id,
                                    name = topName,
                                    price = price,
                                    ingredientId = topIngredientId,
                                    gramsRequired = grams
                                )
                            }
                            if (res.isSuccess) {
                                isNewTopping = false
                                editingTopping = null
                                topError = null
                            } else {
                                topError = res.exceptionOrNull()?.message
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Guardar Topping", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { showToppingAdminDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Cerrar", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (editingTopping != null || isNewTopping) {
                    TextButton(onClick = {
                        editingTopping = null
                        isNewTopping = false
                        topError = null
                    }) {
                        Text("Volver", color = TextGray)
                    }
                }
            },
            title = {
                Text(
                    text = when {
                        isNewTopping -> "Nuevo Topping / Adicional"
                        editingTopping != null -> "Editar Topping"
                        else -> "Toppings y Adicionales (${toppingsList.size})"
                    },
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                if (editingTopping != null || isNewTopping) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = topName,
                            onValueChange = { topName = it; topError = null },
                            label = { Text("Nombre (ej. Chispas de chocolate, Maní)") },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, focusedBorderColor = GoldPrimary),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = topPrice,
                            onValueChange = { topPrice = it; topError = null },
                            label = { Text("Precio Adicional (COP)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary, focusedBorderColor = GoldPrimary),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Vincular a Insumo para Descuento Automático (opcional):", color = TextGray, fontSize = 11.sp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (topIngredientId == null) GoldPrimary else DarkBackground,
                                modifier = Modifier.clickable { topIngredientId = null }
                            ) {
                                Text(
                                    text = "Sin vincular",
                                    color = if (topIngredientId == null) DarkBackground else TextWhite,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                            ingredients.forEach { ing ->
                                val isSel = topIngredientId == ing.id
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) GoldPrimary else DarkBackground,
                                    modifier = Modifier.clickable { topIngredientId = ing.id }
                                ) {
                                    Text(
                                        text = "${ing.name} (${ing.unit})",
                                        color = if (isSel) DarkBackground else TextWhite,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                        if (topIngredientId != null) {
                            OutlinedTextField(
                                value = topGramsRequired,
                                onValueChange = { topGramsRequired = it },
                                label = { Text("Cantidad a descontar por porción") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (topError != null) {
                            Text(topError!!, color = DangerRed, fontSize = 11.sp)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                topName = ""
                                topPrice = "2000"
                                topIngredientId = null
                                topGramsRequired = "20"
                                topError = null
                                isNewTopping = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("+ Crear Nuevo Topping", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        toppingsList.forEach { top ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = DarkBackground,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(top.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("+${formatCOP(top.price)}", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = {
                                            editingTopping = top
                                            topName = top.name
                                            topPrice = top.price.toString()
                                            topIngredientId = top.ingredientId
                                            topGramsRequired = top.gramsRequired.toString()
                                            topError = null
                                        }) {
                                            Text("Editar", color = GoldPrimary, fontSize = 11.sp)
                                        }
                                        TextButton(onClick = { posRepository.deleteTopping(top.id) }) {
                                            Text("Eliminar", color = DangerRed, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // Modal: Product Variant (Bolas / Presentación), Flavors & Toppings Visual Card Selector
    selectedProductForModal?.let { product ->
        val selectedVariant = product.variants.find { it.id == modalVariantId } ?: product.variants.firstOrNull()
        val selectedToppings = toppingsList.filter { modalSelectedToppings.contains(it.id) }
        val previewUnitPrice = product.basePrice + (selectedVariant?.priceDelta ?: 0L) + selectedToppings.sumOf { it.price }

        AlertDialog(
            onDismissRequest = { selectedProductForModal = null },
            confirmButton = {
                Button(
                    onClick = {
                        val variant = selectedVariant
                        val unitPrice = previewUnitPrice
                        val estimatedCost = product.baseCost + (selectedToppings.size * 500L)
                        val variantLabel = buildString {
                            append(variant?.name ?: "Estándar")
                            if (modalSelectedFlavor.isNotBlank()) {
                                append(" • ")
                                append(modalSelectedFlavor)
                            }
                        }

                        val cartItem = CartItem(
                            cartItemId = "ci-${System.currentTimeMillis()}",
                            productId = product.id,
                            productName = product.name,
                            variantId = variant?.id ?: "",
                            variantName = variantLabel,
                            basePrice = product.basePrice,
                            variantPriceDelta = variant?.priceDelta ?: 0L,
                            unitPrice = unitPrice,
                            quantity = 1,
                            toppings = selectedToppings,
                            totalPrice = unitPrice,
                            estimatedCost = estimatedCost,
                            imagePath = product.imagePath
                        )
                        cart.add(cartItem)
                        selectedProductForModal = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("Agregar (${formatCOP(previewUnitPrice)})", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedProductForModal = null }) {
                    Text("Cancelar", color = TextGray)
                }
            },
            title = {
                Text("${product.emoji} ${product.name}", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (product.variants.isNotEmpty()) {
                        Text("1. Elige Presentación / Cantidad de Bolas:", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            product.variants.chunked(2).forEach { rowVariants ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowVariants.forEach { v ->
                                        val isSel = (selectedVariant?.id ?: "") == v.id
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSel) GoldPrimary.copy(alpha = 0.2f) else DarkBackground,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) GoldPrimary else DarkBorder),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { modalVariantId = v.id }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text("🍨", fontSize = 20.sp)
                                                Text(v.name, color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                                Text(
                                                    text = if (v.priceDelta > 0) "+${formatCOP(v.priceDelta)}" else formatCOP(product.basePrice),
                                                    color = GoldPrimary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                        }
                                    }
                                    if (rowVariants.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    if (product.allowedToppings && toppingsList.isNotEmpty()) {
                        Text("2. Toppings y Adicionales (Tarjetas Seleccionables):", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            toppingsList.chunked(2).forEach { rowToppings ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowToppings.forEach { t ->
                                        val isSelected = modalSelectedToppings.contains(t.id)
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) GoldPrimary.copy(alpha = 0.22f) else DarkBackground,
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (isSelected) GoldPrimary else DarkBorder
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    if (isSelected) modalSelectedToppings.remove(t.id)
                                                    else modalSelectedToppings.add(t.id)
                                                }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(if (isSelected) "✅" else "🍫", fontSize = 16.sp)
                                                Text(
                                                    text = t.name,
                                                    color = TextWhite,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "+${formatCOP(t.price)}",
                                                    color = GoldPrimary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                        }
                                    }
                                    if (rowToppings.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    // Clear Order Item Summary Before Adding
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DarkBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Resumen de selección", color = TextGray, fontSize = 10.sp)
                            Text(
                                text = "${product.name} (${selectedVariant?.name ?: "Estándar"})",
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (selectedToppings.isNotEmpty()) {
                                Text(
                                    text = "Toppings: ${selectedToppings.joinToString(", ") { it.name }}",
                                    color = GoldPrimary,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = "Total ítem: ${formatCOP(previewUnitPrice)}",
                                color = SuccessGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // Modal: Checkout Payment
    if (showCheckoutModal) {
        var checkoutError by remember { mutableStateOf<String?>(null) }
        var activeReservationId by remember { mutableStateOf<String?>(null) }
        val allReservations by posRepository.reservations.collectAsState()
        val activeReservation = remember(allReservations, activeReservationId) {
            allReservations.find { it.id == activeReservationId }
        }
        val recoverableReservations = remember(allReservations) {
            posRepository.getRecoverableReservations().filter { it.isPendingHold }
        }

        // Automatically complete UI flow if bank notification matched and completed the active reservation
        LaunchedEffect(activeReservation?.state) {
            if (activeReservation != null && activeReservation.state == ReservationState.COMPLETED) {
                onPaymentSuccess(activeReservation.amount, "Pago Nequi Verificado", "Nequi")
                cart.clear()
                activeReservationId = null
                showCheckoutModal = false
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (activeReservation == null || activeReservation.state != ReservationState.ACTIVE) {
                    showCheckoutModal = false
                    checkoutError = null
                }
            },
            confirmButton = {
                if (activeReservation != null && (activeReservation.state == ReservationState.ACTIVE || activeReservation.state == ReservationState.PAYMENT_CANDIDATE)) {
                    // Explicit auditable manual confirmation by cashier (Section 17)
                    Button(
                        onClick = {
                            val res = posRepository.completeSale(
                                items = cart.toList(),
                                subtotal = cartSubtotal,
                                discount = cartDiscount,
                                total = cartTotal,
                                paymentMethod = "NEQUI",
                                cashierName = activeCashier.name,
                                senderName = "Verificación Manual (${activeCashier.name})",
                                channel = "Manual Auditado",
                                saleId = "sale-${activeReservation.id}"
                            )
                            if (res.isSuccess) {
                                onPaymentSuccess(cartTotal, "Verificación Manual", "Nequi")
                                cart.clear()
                                activeReservationId = null
                                showCheckoutModal = false
                            } else {
                                checkoutError = res.exceptionOrNull()?.message
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Confirmación Manual Verificada", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            checkoutError = null
                            if (checkoutMethod == "CASH") {
                                val res = if (activeReservationId != null) {
                                    posRepository.completeCashSaleFromReservation(activeReservationId!!, activeCashier.name)
                                } else {
                                    posRepository.completeSale(
                                        items = cart.toList(),
                                        subtotal = cartSubtotal,
                                        discount = cartDiscount,
                                        total = cartTotal,
                                        paymentMethod = "CASH",
                                        cashierName = activeCashier.name
                                    )
                                }
                                if (res.isSuccess) {
                                    onPaymentSuccess(cartTotal, "Venta en Efectivo", "Caja Pizzería Natus")
                                    cart.clear()
                                    activeReservationId = null
                                    showCheckoutModal = false
                                } else {
                                    checkoutError = res.exceptionOrNull()?.message ?: "Error al completar venta"
                                }
                            } else {
                                // Create Nequi reservation and enter waiting state (Sections 4 & 8: never mark sale paid on reservation creation!)
                                val resResult = posRepository.createNequiReservation(
                                    amount = cartTotal,
                                    items = cart.toList(),
                                    subtotal = cartSubtotal,
                                    discount = cartDiscount,
                                    cashierName = activeCashier.name
                                )
                                if (resResult.isSuccess) {
                                    activeReservationId = resResult.getOrThrow().id
                                } else {
                                    checkoutError = resResult.exceptionOrNull()?.message ?: "Error al crear reserva Nequi"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text(if (checkoutMethod == "NEQUI") "Iniciar Espera Nequi" else "Confirmar Cobro", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (activeReservation == null) {
                    TextButton(onClick = {
                        showCheckoutModal = false
                        checkoutError = null
                    }) {
                        Text("Cerrar", color = TextGray)
                    }
                }
            },
            title = {
                Text(
                    text = if (activeReservation != null) "Esperando confirmación de pago" else "Finalizar Cobro: ${formatCOP(cartTotal)}",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cajera a cargo: ${activeCashier.name}", color = GoldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    if (activeReservation != null && (activeReservation.state == ReservationState.ACTIVE || activeReservation.state == ReservationState.PAYMENT_CANDIDATE)) {
                        // Friendly visual waiting state for Cashier (purely visual animation, never confirms payment by timer)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkBackground,
                            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = GoldPrimary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(34.dp)
                                )
                                Text(
                                    text = "Esperando confirmación de pago",
                                    color = TextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = formatCOP(activeReservation.amount),
                                    color = GoldPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Estamos verificando el pago. Esta pantalla se actualizará cuando recibamos la notificación.",
                                    color = TextGray,
                                    fontSize = 11.sp
                                )

                                // Technical diagnostic block only shown in OWNER mode
                                if (currentRole == PosRole.OWNER) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkSurfaceElevated,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text("Diagnóstico Técnico (Modo Patrón)", color = GoldPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            Text("ID Reserva: ${activeReservation.id}", color = TextGray, fontSize = 9.sp)
                                            Text(
                                                text = "Sincronización: ${activeReservation.syncState.name}",
                                                color = if (activeReservation.syncState == SyncState.ACKNOWLEDGED) SuccessGreen else GoldPrimary,
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Section 9, 10, 11 Buttons: Dejar pendiente, Cancelar, Cambiar a efectivo
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    posRepository.leaveReservationPending(activeReservation.id)
                                    activeReservationId = null
                                    showCheckoutModal = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkBackground),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Dejar pendiente", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    posRepository.cancelNequiReservation(activeReservation.id, "USER_CANCEL")
                                    activeReservationId = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancelar", color = TextWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                posRepository.switchNequiReservationToCash(activeReservation.id)
                                checkoutMethod = "CASH"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cambiar a efectivo (conservando orden)", color = DarkBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { checkoutMethod = "NEQUI" },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (checkoutMethod == "NEQUI") GoldPrimary else DarkBackground
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Nequi", color = if (checkoutMethod == "NEQUI") DarkBackground else TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { checkoutMethod = "CASH" },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (checkoutMethod == "CASH") GoldPrimary else DarkBackground
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Efectivo", color = if (checkoutMethod == "CASH") DarkBackground else TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (checkoutMethod == "NEQUI") {
                            Text(
                                text = "Al confirmar, se creará una reserva Nequi coordinada por SMS y se abrirá la espera de notificación bancaria.",
                                color = TextGray,
                                fontSize = 11.sp
                            )
                            if (recoverableReservations.isNotEmpty()) {
                                Text("Operaciones Nequi pendientes recuperables:", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                recoverableReservations.take(2).forEach { pendingRes ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = DarkBackground,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val restored = posRepository.recoverReservationCart(pendingRes.id)
                                                if (restored.isNotEmpty()) {
                                                    cart.clear()
                                                    cart.addAll(restored)
                                                }
                                                activeReservationId = pendingRes.id
                                            }
                                    ) {
                                        Text(
                                            text = "Recuperar ${pendingRes.id} (${formatCOP(pendingRes.amount)})",
                                            color = GoldPrimary,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(6.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = cashReceivedInput,
                                onValueChange = { cashReceivedInput = it },
                                label = { Text("Efectivo Recibido (COP)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = GoldPrimary,
                                    unfocusedTextColor = GoldPrimary,
                                    focusedBorderColor = GoldPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (checkoutError != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DangerRed.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ $checkoutError",
                                color = DangerRed,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // PIN Verification Dialog for Owner Mode
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput == settings.pinCode) {
                            currentRole = PosRole.OWNER
                            showPinDialog = false
                        } else {
                            pinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("Ingresar", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancelar", color = TextGray)
                }
            },
            title = { Text("PIN del Patrón", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Ingresa el PIN de seguridad para acceder a la administración de productos, costos e informes:", color = TextGray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it; pinError = false },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GoldPrimary,
                            unfocusedTextColor = GoldPrimary,
                            focusedBorderColor = GoldPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Text("PIN incorrecto", color = DangerRed, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // Dialog for Expense Recording
    if (showExpenseDialog) {
        AlertDialog(
            onDismissRequest = { showExpenseDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = expenseAmount.toLongOrNull() ?: 0L
                        if (expenseConcept.isNotEmpty() && amt > 0) {
                            posRepository.addCashExpense(expenseConcept, amt)
                            showExpenseDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("Guardar", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExpenseDialog = false }) {
                    Text("Cancelar", color = TextGray)
                }
            },
            title = { Text("Registrar Gasto de Caja", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = expenseConcept,
                        onValueChange = { expenseConcept = it },
                        label = { Text("Concepto (ej. Hielo)") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                    )
                    OutlinedTextField(
                        value = expenseAmount,
                        onValueChange = { expenseAmount = it },
                        label = { Text("Monto en COP") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary)
                    )
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // Dialog for Adjusting Cash Base (Ajustar Base de Caja)
    if (showAdjustBaseDialog) {
        AlertDialog(
            onDismissRequest = { showAdjustBaseDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val newBase = adjustBaseAmountInput.toLongOrNull()
                        if (newBase == null || newBase < 0L) {
                            adjustBaseError = "Ingresa un monto de base válido"
                            return@Button
                        }
                        val res = posRepository.adjustCashBase(
                            newBaseAmount = newBase,
                            reason = adjustBaseReasonInput,
                            userName = activeCashier.name
                        )
                        if (res.isSuccess) {
                            showAdjustBaseDialog = false
                            adjustBaseError = null
                        } else {
                            adjustBaseError = res.exceptionOrNull()?.message
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("Guardar Base", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdjustBaseDialog = false }) {
                    Text("Cancelar", color = TextGray)
                }
            },
            title = { Text("Ajustar Base de Caja", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Base actual: ${formatCOP(cashShift.initialCash)} • Usuario: ${activeCashier.name}",
                        color = GoldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = adjustBaseAmountInput,
                        onValueChange = { adjustBaseAmountInput = it; adjustBaseError = null },
                        label = { Text("Nuevo monto de base (COP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary)
                    )
                    OutlinedTextField(
                        value = adjustBaseReasonInput,
                        onValueChange = { adjustBaseReasonInput = it; adjustBaseError = null },
                        label = { Text("Motivo del ajuste (obligatorio)") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                    )
                    if (adjustBaseError != null) {
                        Text(adjustBaseError!!, color = DangerRed, fontSize = 11.sp)
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    // Dialog for Closing Cash Drawer (Cerrar Caja with 6 required breakdown items)
    if (showBlindCloseDialog) {
        val otherCashMovements = cashShift.expenses
            .filter { it.type == "EXPENSE" || it.type == "OUT" }
            .sumOf { it.amount }
        val expectedBeforeClose = cashShift.initialCash + cashShift.cashIn - otherCashMovements

        AlertDialog(
            onDismissRequest = { showBlindCloseDialog = false },
            confirmButton = {
                if (blindCloseReport == null) {
                    Button(
                        onClick = {
                            val counted = countedCashInput.toLongOrNull() ?: 0L
                            val closed = posRepository.performBlindClose(counted)
                            blindCloseReport = Pair(closed.expectedCash ?: expectedBeforeClose, counted)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                    ) {
                        Text("Confirmar Cierre de Caja", color = TextWhite, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { showBlindCloseDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) {
                        Text("Finalizar", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (blindCloseReport == null) {
                    TextButton(onClick = { showBlindCloseDialog = false }) {
                        Text("Cancelar", color = TextGray)
                    }
                }
            },
            title = { Text("Cerrar Caja", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                val previewCounted = countedCashInput.toLongOrNull() ?: 0L
                val (expected, counted) = blindCloseReport ?: Pair(expectedBeforeClose, previewCounted)
                val diff = counted - expected

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DarkBackground,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("1. Base de caja:", color = TextGray, fontSize = 11.sp)
                                Text(formatCOP(cashShift.initialCash), color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("2. Ventas en efectivo:", color = TextGray, fontSize = 11.sp)
                                Text("+${formatCOP(cashShift.cashIn)}", color = SuccessGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("3. Otros movimientos de efectivo:", color = TextGray, fontSize = 11.sp)
                                Text("-${formatCOP(otherCashMovements)}", color = DangerRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("4. Efectivo esperado en caja:", color = GoldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(formatCOP(expected), color = GoldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }

                    if (blindCloseReport == null) {
                        OutlinedTextField(
                            value = countedCashInput,
                            onValueChange = { countedCashInput = it },
                            label = { Text("5. Efectivo contado en caja (COP)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = GoldPrimary, unfocusedTextColor = GoldPrimary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("5. Efectivo contado ingresado:", color = TextGray, fontSize = 12.sp)
                            Text(formatCOP(counted), color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkBackground,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("6. Diferencia resultante:", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = when {
                                    diff == 0L -> "$0 (Cuadre exacto)"
                                    diff > 0L -> "+${formatCOP(diff)} (Sobrante)"
                                    else -> "-${formatCOP(Math.abs(diff))} (Faltante)"
                                },
                                color = if (diff == 0L) SuccessGreen else if (diff > 0L) GoldPrimary else DangerRed,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            containerColor = DarkSurfaceElevated
        )
    }

    if (showFinancialHistoryDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showFinancialHistoryDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HistoryScreen(
                    payments = recordedPayments,
                    isLoading = isHistoryLoading,
                    errorMessage = historyError,
                    onRetryLoad = { paymentRepository.reloadFromStorage() },
                    onBackClick = { showFinancialHistoryDialog = false },
                    onClearHistory = { paymentRepository.clearHistory() }
                )
            }
        }
    }
}


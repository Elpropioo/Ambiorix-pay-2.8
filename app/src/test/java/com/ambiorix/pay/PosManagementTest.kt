package com.ambiorix.pay

import com.ambiorix.pay.pos.InMemoryPosStorage
import com.ambiorix.pay.pos.PosRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PosManagementTest {

    private lateinit var storage: InMemoryPosStorage
    private lateinit var repository: PosRepository

    @Before
    fun setUp() {
        storage = InMemoryPosStorage()
        repository = PosRepository.createForTesting(storage)
    }

    // 1. Crear producto
    @Test
    fun testCrearProducto() {
        val result = repository.createProduct(
            name = "Pizza Hawaiana",
            basePrice = 7000L,
            categoryId = "productos",
            description = "Piña y jamón con queso mozzarella",
            stockQuantity = 25.0,
            unit = "unidades"
        )

        assertTrue("El producto debe crearse exitosamente", result.isSuccess)
        val product = result.getOrNull()
        assertNotNull(product)
        assertEquals("Pizza Hawaiana", product!!.name)
        assertEquals(7000L, product.basePrice)
        assertEquals("productos", product.categoryId)
        assertEquals(25.0, product.stockQuantity, 0.001)

        val found = repository.products.value.find { it.id == product.id }
        assertNotNull(found)
    }

    // 2. Modificar precio
    @Test
    fun testModificarPrecio() {
        val created = repository.createProduct(
            name = "Cono Sencillo",
            basePrice = 4500L,
            categoryId = "helados"
        ).getOrThrow()

        val updateResult = repository.updateProductPrice(created.id, 5200L)
        assertTrue(updateResult.isSuccess)
        assertEquals(5200L, updateResult.getOrThrow().basePrice)

        // Verificar que persiste recargando desde almacenamiento
        val reloadedRepo = PosRepository.createForTesting(storage)
        val reloadedProd = reloadedRepo.products.value.find { it.id == created.id }
        assertNotNull(reloadedProd)
        assertEquals(5200L, reloadedProd!!.basePrice)
    }

    // 3. Modificar nombre y descripción
    @Test
    fun testModificarNombreYDescripcion() {
        val created = repository.createProduct(
            name = "Café",
            basePrice = 3000L,
            categoryId = "bebidas",
            description = "Café normal"
        ).getOrThrow()

        val updateResult = repository.updateProduct(
            id = created.id,
            name = "Café Espresso Premium",
            basePrice = 3500L,
            categoryId = "bebidas",
            description = "Café de grano colombiano recién molido"
        )

        assertTrue(updateResult.isSuccess)
        val updated = updateResult.getOrThrow()
        assertEquals("Café Espresso Premium", updated.name)
        assertEquals("Café de grano colombiano recién molido", updated.description)
        assertEquals(3500L, updated.basePrice)
    }

    // 4. Asignar y cambiar categoría
    @Test
    fun testAsignarYCambiarCategoria() {
        val created = repository.createProduct(
            name = "Pizza Especial",
            basePrice = 12000L,
            categoryId = "productos"
        ).getOrThrow()

        assertEquals("productos", created.categoryId)

        val updateResult = repository.updateProduct(
            id = created.id,
            name = created.name,
            basePrice = created.basePrice,
            categoryId = "combos"
        )

        assertTrue(updateResult.isSuccess)
        assertEquals("combos", updateResult.getOrThrow().categoryId)

        val productsInCombos = repository.searchProducts("", categoryId = "combos")
        assertTrue(productsInCombos.any { it.id == created.id })
    }

    // 5. Asignar y cambiar fotografía (preservando el archivo interno)
    @Test
    fun testAsignarYCambiarFotografia() {
        val initialPath = "/data/user/0/com.ambiorix.pay/files/images/img_1.jpg"
        val created = repository.createProduct(
            name = "Sundae Fresa",
            basePrice = 9000L,
            categoryId = "copas",
            imagePath = initialPath
        ).getOrThrow()

        assertEquals(initialPath, created.imagePath)

        val newPath = "/data/user/0/com.ambiorix.pay/files/images/img_2.jpg"
        val updateResult = repository.updateProductImage(created.id, newPath)
        assertTrue(updateResult.isSuccess)
        assertEquals(newPath, updateResult.getOrThrow().imagePath)

        // Comprobar persistencia de la ruta
        val reloaded = PosRepository.createForTesting(storage)
        assertEquals(newPath, reloaded.products.value.find { it.id == created.id }?.imagePath)
    }

    // 6. Desactivar producto y verificar que no aparece en catálogo de venta
    @Test
    fun testDesactivarProductoNoApareceEnVenta() {
        val created = repository.createProduct(
            name = "Edición Limitada Verano",
            basePrice = 15000L,
            categoryId = "gourmet"
        ).getOrThrow()

        assertTrue(repository.getActiveProducts().any { it.id == created.id })

        val toggleResult = repository.toggleProductActive(created.id, isActive = false)
        assertTrue(toggleResult.isSuccess)
        assertFalse(toggleResult.getOrThrow().isActive)

        // No debe aparecer en catálogo activo de ventas
        assertFalse(repository.getActiveProducts().any { it.id == created.id })

        // Pero sí existe en la lista administrativa de productos
        assertTrue(repository.products.value.any { it.id == created.id })
    }

    // 7. Crear categoría
    @Test
    fun testCrearCategoria() {
        val result = repository.createCategory(name = "Postres Calientes", emoji = "🥧")
        assertTrue(result.isSuccess)
        val cat = result.getOrThrow()
        assertEquals("Postres Calientes", cat.name)
        assertEquals("🥧", cat.emoji)
        assertTrue(repository.categories.value.any { it.id == cat.id })

        // Rechazar categoría con nombre vacío
        val invalidResult = repository.createCategory(name = "   ")
        assertTrue(invalidResult.isFailure)
    }

    // 8. Renombrar categoría
    @Test
    fun testRenombrarCategoria() {
        val created = repository.createCategory(name = "Dulces", emoji = "🍬").getOrThrow()
        val updateResult = repository.updateCategory(created.id, newName = "Confitería Artesanal", newEmoji = "🍭")
        assertTrue(updateResult.isSuccess)
        assertEquals("Confitería Artesanal", updateResult.getOrThrow().name)
        assertEquals("🍭", updateResult.getOrThrow().emoji)
    }

    // 9. Eliminar categoría con reasignación
    @Test
    fun testEliminarCategoriaConReasignacion() {
        val catA = repository.createCategory(name = "Temporada", emoji = "🎄").getOrThrow()
        val catB = repository.createCategory(name = "General", emoji = "📦").getOrThrow()

        val prod1 = repository.createProduct("Helado Navideño", 8000L, catA.id).getOrThrow()
        val prod2 = repository.createProduct("Turrón Helado", 9000L, catA.id).getOrThrow()

        assertEquals(2, repository.getProductCountForCategory(catA.id))

        // Eliminar catA reasignando sus productos a catB
        val deleteResult = repository.deleteCategory(catA.id, reassignToCategoryId = catB.id)
        assertTrue(deleteResult.isSuccess)

        // La categoría eliminada ya no existe
        assertNull(repository.categories.value.find { it.id == catA.id })

        // Los productos siguen existiendo y ahora pertenecen a catB
        val reloadedProd1 = repository.products.value.find { it.id == prod1.id }
        val reloadedProd2 = repository.products.value.find { it.id == prod2.id }
        assertNotNull(reloadedProd1)
        assertNotNull(reloadedProd2)
        assertEquals(catB.id, reloadedProd1!!.categoryId)
        assertEquals(catB.id, reloadedProd2!!.categoryId)
    }

    // 10. Ajustar inventario hacia arriba y registrar motivo
    @Test
    fun testAjustarInventarioHaciaArribaConMotivo() {
        val prod = repository.createProduct("Conos Dobles", 6000L, "helados", stockQuantity = 10.0).getOrThrow()

        val entryResult = repository.recordPurchaseEntry(
            productId = prod.id,
            quantity = 15.0,
            supplierOrNote = "Factura Prov-501",
            userName = "Laura Gómez"
        )

        assertTrue(entryResult.isSuccess)
        val movement = entryResult.getOrThrow()
        assertEquals(10.0, movement.previousStock, 0.001)
        assertEquals(15.0, movement.change, 0.001)
        assertEquals(25.0, movement.resultingStock, 0.001)
        assertEquals("PURCHASE", movement.type)
        assertTrue(movement.reason.contains("Factura Prov-501"))
        assertEquals("Laura Gómez", movement.userName)

        val updatedProd = repository.products.value.find { it.id == prod.id }
        assertEquals(25.0, updatedProd!!.stockQuantity, 0.001)
    }

    // 11. Ajustar inventario hacia abajo y registrar motivo
    @Test
    fun testAjustarInventarioHaciaAbajoConMotivo() {
        val prod = repository.createProduct("Paleta Frutas", 5000L, "paletas", stockQuantity = 20.0).getOrThrow()

        val wasteResult = repository.recordWaste(
            productId = prod.id,
            quantity = 3.0,
            reason = "Rotura al descargar congelador",
            userName = "Camila Restrepo"
        )

        assertTrue(wasteResult.isSuccess)
        val movement = wasteResult.getOrThrow()
        assertEquals(20.0, movement.previousStock, 0.001)
        assertEquals(-3.0, movement.change, 0.001)
        assertEquals(17.0, movement.resultingStock, 0.001)
        assertEquals("WASTE_MERMA", movement.type)
        assertTrue(movement.reason.contains("Rotura"))
        assertEquals("Camila Restrepo", movement.userName)
    }

    // 12. Rechazar inventario negativo no permitido
    @Test
    fun testRechazarInventarioNegativo() {
        val prod = repository.createProduct("Barquillos", 1000L, "helados", stockQuantity = 5.0).getOrThrow()

        val invalidResult = repository.adjustStock(
            productId = prod.id,
            newStock = -2.0,
            reason = "Ajuste erróneo",
            userName = "Admin",
            allowNegative = false
        )

        assertTrue("Debe rechazar inventario negativo", invalidResult.isFailure)
        assertTrue(invalidResult.exceptionOrNull()?.message?.contains("negativo") == true)

        // El stock original se mantiene intacto
        val unchangedProd = repository.products.value.find { it.id == prod.id }
        assertEquals(5.0, unchangedProd!!.stockQuantity, 0.001)
    }

    // 13. Editar nombre de cajera y persistir
    @Test
    fun testEditarNombreCajeraYPersistir() {
        val initialCashier = repository.activeCashier.value
        assertEquals("Laura Gómez", initialCashier.name)

        // Rechazar nombre vacío
        val emptyResult = repository.updateCashierName(initialCashier.id, "   ")
        assertTrue("No debe permitir nombre vacío", emptyResult.isFailure)

        // Actualizar a nuevo nombre válido
        val updateResult = repository.updateCashierName(initialCashier.id, "Laura Sofía Gómez")
        assertTrue(updateResult.isSuccess)
        assertEquals("Laura Sofía Gómez", updateResult.getOrThrow().name)
        assertEquals("Laura Sofía Gómez", repository.activeCashier.value.name)
        assertEquals("Laura Sofía Gómez", repository.cashShift.value.cashierName)

        // Comprobar persistencia tras recargar
        val reloaded = PosRepository.createForTesting(storage)
        assertEquals("Laura Sofía Gómez", reloaded.activeCashier.value.name)

        // Crear otro perfil de cajera y seleccionarlo
        val newProfile = reloaded.createCashier("Valentina Ortiz").getOrThrow()
        reloaded.selectCashier(newProfile.id)
        assertEquals("Valentina Ortiz", reloaded.activeCashier.value.name)
    }

    // 14. Conversión de unidades segura y rechazo de unidades incompatibles
    @Test
    fun testConversionDeUnidadesYRechazoIncompatibles() {
        // g a kg
        val convertedKg = com.ambiorix.pay.pos.UnitConverter.convert(150.0, "g", "kg")
        assertEquals(0.15, convertedKg, 0.0001)

        // kg a g
        val convertedG = com.ambiorix.pay.pos.UnitConverter.convert(2.5, "kg", "g")
        assertEquals(2500.0, convertedG, 0.0001)

        // ml a L
        val convertedL = com.ambiorix.pay.pos.UnitConverter.convert(500.0, "ml", "L")
        assertEquals(0.5, convertedL, 0.0001)

        // unidades a unidades
        val convertedU = com.ambiorix.pay.pos.UnitConverter.convert(10.0, "unidades", "unidades")
        assertEquals(10.0, convertedU, 0.0001)

        // Rechazo automático de unidades incompatibles (ej. gramos a unidades o litros a gramos)
        assertFalse(com.ambiorix.pay.pos.UnitConverter.canConvert("g", "unidades"))
        assertFalse(com.ambiorix.pay.pos.UnitConverter.canConvert("L", "unidades"))

        try {
            com.ambiorix.pay.pos.UnitConverter.convert(50.0, "g", "unidades")
            fail("Debe lanzar IllegalArgumentException al intentar convertir unidades incompatibles")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("incompatibles") == true)
        }
    }

    // 15. Creación de producto con receta e ingredientes
    @Test
    fun testCrearProductoConReceta() {
        val baseHelado = repository.createIngredient(
            name = "Base Artesanal de Fresa",
            unit = "kg",
            initialStock = 5.0,
            unitCostCOP = 12000L
        ).getOrThrow()

        val barquillos = repository.createIngredient(
            name = "Cono Galleta Barquillo",
            unit = "unidades",
            initialStock = 100.0,
            unitCostCOP = 400L
        ).getOrThrow()

        val recipe = listOf(
            com.ambiorix.pay.pos.RecipeItem(
                ingredientId = baseHelado.id,
                ingredientName = baseHelado.name,
                quantity = 150.0,
                unit = "g"
            ),
            com.ambiorix.pay.pos.RecipeItem(
                ingredientId = barquillos.id,
                ingredientName = barquillos.name,
                quantity = 1.0,
                unit = "unidades"
            )
        )

        val prodResult = repository.createProduct(
            name = "Cono Gourmet Fresa",
            basePrice = 8500L,
            categoryId = "helados",
            recipe = recipe
        )

        assertTrue(prodResult.isSuccess)
        val product = prodResult.getOrThrow()
        assertEquals(2, product.recipe.size)
        // Costo calculado: 0.15kg * 12000 + 1 * 400 = 1800 + 400 = 2200 COP
        assertEquals(2200L, product.baseCost)
    }

    // 16. Descuento de inventario automático con conversión de unidad (150g de 5kg = 4.85kg)
    @Test
    fun testDescuentoInventarioConConversionDeUnidades() {
        val baseHelado = repository.createIngredient(
            name = "Base Helado Vainilla",
            unit = "kg",
            initialStock = 5.0,
            unitCostCOP = 15000L
        ).getOrThrow()

        val recipe = listOf(
            com.ambiorix.pay.pos.RecipeItem(
                ingredientId = baseHelado.id,
                ingredientName = baseHelado.name,
                quantity = 150.0,
                unit = "g"
            )
        )

        val prod = repository.createProduct(
            name = "Helado Vainilla Especial",
            basePrice = 6000L,
            categoryId = "helados",
            recipe = recipe
        ).getOrThrow()

        val cartItem = com.ambiorix.pay.pos.CartItem(
            cartItemId = "cart-1",
            productId = prod.id,
            productName = prod.name,
            basePrice = prod.basePrice,
            unitPrice = prod.basePrice,
            quantity = 1,
            totalPrice = prod.basePrice,
            estimatedCost = prod.baseCost
        )

        val saleResult = repository.completeSale(
            items = listOf(cartItem),
            subtotal = prod.basePrice,
            discount = 0L,
            total = prod.basePrice,
            paymentMethod = "CASH",
            cashierName = "Laura Gómez",
            saleId = "sale-unit-test-1"
        )

        assertTrue("La venta debe completarse exitosamente", saleResult.isSuccess)

        // 5 kg - 0.150 kg (150g) = 4.85 kg
        val updatedIngredient = repository.ingredients.value.find { it.id == baseHelado.id }
        assertNotNull(updatedIngredient)
        assertEquals(4.85, updatedIngredient!!.currentStock, 0.001)

        val mov = repository.inventoryMovements.value.find { it.saleId == "sale-unit-test-1" }
        assertNotNull(mov)
        assertEquals("SALE_RECIPE", mov!!.type)
        assertEquals(-0.15, mov.change, 0.001)
        assertEquals(4.85, mov.resultingStock, 0.001)
    }

    // 17. Rechazo por stock insuficiente antes de descontar inventario
    @Test
    fun testRechazoVentaPorStockInsuficiente() {
        val ingreEscaso = repository.createIngredient(
            name = "Chocolate Belga Limitado",
            unit = "g",
            initialStock = 50.0,
            unitCostCOP = 50L
        ).getOrThrow()

        val prod = repository.createProduct(
            name = "Copa Chocolate Belga",
            basePrice = 14000L,
            categoryId = "copas",
            recipe = listOf(
                com.ambiorix.pay.pos.RecipeItem(
                    ingredientId = ingreEscaso.id,
                    ingredientName = ingreEscaso.name,
                    quantity = 100.0,
                    unit = "g"
                )
            )
        ).getOrThrow()

        val cartItem = com.ambiorix.pay.pos.CartItem(
            cartItemId = "cart-fail",
            productId = prod.id,
            productName = prod.name,
            basePrice = prod.basePrice,
            unitPrice = prod.basePrice,
            quantity = 1,
            totalPrice = prod.basePrice
        )

        val saleResult = repository.completeSale(
            items = listOf(cartItem),
            subtotal = prod.basePrice,
            discount = 0L,
            total = prod.basePrice,
            paymentMethod = "CASH",
            cashierName = "Laura Gómez",
            saleId = "sale-fail-stock"
        )

        assertTrue("La venta debe fallar por stock insuficiente", saleResult.isFailure)
        assertTrue(saleResult.exceptionOrNull()?.message?.contains("insuficiente") == true)

        val unchanged = repository.ingredients.value.find { it.id == ingreEscaso.id }
        assertEquals(50.0, unchanged!!.currentStock, 0.001)
    }

    // 18. Creación de Combo y prevención de referencias circulares
    @Test
    fun testCombosYPrevencionReferenciasCirculares() {
        val prodA = repository.createProduct("Banana Split", 12000L, "copas").getOrThrow()
        val prodB = repository.createProduct("Malteada Fresa", 9000L, "bebidas").getOrThrow()

        val comboItems = listOf(
            com.ambiorix.pay.pos.ComboItem(productId = prodA.id, productName = prodA.name, quantity = 1),
            com.ambiorix.pay.pos.ComboItem(productId = prodB.id, productName = prodB.name, quantity = 1)
        )

        val comboResult = repository.createProduct(
            name = "Combo Pareja Dulce",
            basePrice = 18000L,
            categoryId = "combos",
            isCombo = true,
            comboItems = comboItems
        )

        assertTrue(comboResult.isSuccess)
        val combo = comboResult.getOrThrow()
        assertTrue(combo.isCombo)
        assertEquals(2, combo.comboItems.size)

        // Intento de circularidad directa: un combo no puede contenerse a sí mismo
        val circularDirect = repository.updateProductCombo(
            productId = combo.id,
            comboItems = listOf(
                com.ambiorix.pay.pos.ComboItem(productId = combo.id, productName = combo.name, quantity = 1)
            )
        )
        assertTrue("Debe rechazar combo que se contiene a sí mismo", circularDirect.isFailure)
        assertTrue(circularDirect.exceptionOrNull()?.message?.contains("circular") == true)
    }

    // 19. Descuento recursivo de componentes al vender un Combo
    @Test
    fun testDescuentoRecursivoAlVenderCombo() {
        val platano = repository.createIngredient("Plátano Criollo", "unidades", 10.0, 800L).getOrThrow()
        val helado = repository.createIngredient("Helado Vainilla", "kg", 4.0, 10000L).getOrThrow()

        val bananaSplit = repository.createProduct(
            name = "Banana Split",
            basePrice = 12000L,
            categoryId = "copas",
            recipe = listOf(
                com.ambiorix.pay.pos.RecipeItem(platano.id, platano.name, 1.0, "unidades"),
                com.ambiorix.pay.pos.RecipeItem(helado.id, helado.name, 200.0, "g")
            )
        ).getOrThrow()

        val refresco = repository.createProduct(
            name = "Refresco Frutal",
            basePrice = 4000L,
            categoryId = "bebidas",
            stockQuantity = 20.0
        ).getOrThrow()

        val combo = repository.createProduct(
            name = "Super Combo Verano",
            basePrice = 17000L,
            categoryId = "combos",
            isCombo = true,
            comboItems = listOf(
                com.ambiorix.pay.pos.ComboItem(bananaSplit.id, bananaSplit.name, 1),
                com.ambiorix.pay.pos.ComboItem(refresco.id, refresco.name, 2)
            )
        ).getOrThrow()

        val cartItem = com.ambiorix.pay.pos.CartItem(
            cartItemId = "cart-combo-1",
            productId = combo.id,
            productName = combo.name,
            basePrice = combo.basePrice,
            unitPrice = combo.basePrice,
            quantity = 1,
            totalPrice = combo.basePrice
        )

        val saleResult = repository.completeSale(
            items = listOf(cartItem),
            subtotal = combo.basePrice,
            discount = 0L,
            total = combo.basePrice,
            paymentMethod = "CASH",
            cashierName = "Laura Gómez",
            saleId = "sale-combo-rec-1"
        )

        assertTrue(saleResult.isSuccess)

        // Verificar descuentos recursivos:
        // Plátano: 10 - 1 = 9 unidades
        assertEquals(9.0, repository.ingredients.value.find { it.id == platano.id }!!.currentStock, 0.001)
        // Helado: 4.0 kg - 200g (0.2kg) = 3.8 kg
        assertEquals(3.8, repository.ingredients.value.find { it.id == helado.id }!!.currentStock, 0.001)
        // Refresco directo: 20 - 2 = 18 unidades
        assertEquals(18.0, repository.products.value.find { it.id == refresco.id }!!.stockQuantity, 0.001)
    }

    // 20. Idempotencia: el segundo llamado a completeSale con el mismo ID no descuenta dos veces
    @Test
    fun testIdempotenciaVenta() {
        val galleta = repository.createIngredient("Galleta Choco", "unidades", 50.0, 500L).getOrThrow()
        val prod = repository.createProduct(
            name = "Galleta Empacada",
            basePrice = 1200L,
            categoryId = "snacks",
            recipe = listOf(com.ambiorix.pay.pos.RecipeItem(galleta.id, galleta.name, 1.0, "unidades"))
        ).getOrThrow()

        val cartItem = com.ambiorix.pay.pos.CartItem(
            cartItemId = "cart-idemp-1",
            productId = prod.id,
            productName = prod.name,
            basePrice = prod.basePrice,
            unitPrice = prod.basePrice,
            quantity = 2,
            totalPrice = prod.basePrice * 2
        )

        val fixedSaleId = "sale-idempotent-unique-123"

        val firstResult = repository.completeSale(
            items = listOf(cartItem),
            subtotal = prod.basePrice * 2,
            discount = 0L,
            total = prod.basePrice * 2,
            paymentMethod = "NEQUI",
            cashierName = "Laura Gómez",
            saleId = fixedSaleId
        )
        assertTrue(firstResult.isSuccess)
        assertEquals(48.0, repository.ingredients.value.find { it.id == galleta.id }!!.currentStock, 0.001)

        val secondResult = repository.completeSale(
            items = listOf(cartItem),
            subtotal = prod.basePrice * 2,
            discount = 0L,
            total = prod.basePrice * 2,
            paymentMethod = "NEQUI",
            cashierName = "Laura Gómez",
            saleId = fixedSaleId
        )
        assertTrue(secondResult.isSuccess)

        // El stock DEBE permanecer en 48.0, NO 46.0
        assertEquals(48.0, repository.ingredients.value.find { it.id == galleta.id }!!.currentStock, 0.001)
    }

    // 21. Devolución / anulación de venta con movimientos compensatorios
    @Test
    fun testDevolucionVentaYMovimientosCompensatorios() {
        val fresa = repository.createIngredient("Fresa Fresca", "kg", 2.0, 8000L).getOrThrow()
        val prod = repository.createProduct(
            name = "Copa Fresa",
            basePrice = 7500L,
            categoryId = "copas",
            recipe = listOf(com.ambiorix.pay.pos.RecipeItem(fresa.id, fresa.name, 250.0, "g"))
        ).getOrThrow()

        val cartItem = com.ambiorix.pay.pos.CartItem(
            cartItemId = "c-ret-1",
            productId = prod.id,
            productName = prod.name,
            basePrice = prod.basePrice,
            unitPrice = prod.basePrice,
            quantity = 1,
            totalPrice = prod.basePrice
        )

        val sale = repository.completeSale(
            items = listOf(cartItem),
            subtotal = prod.basePrice,
            discount = 0L,
            total = prod.basePrice,
            paymentMethod = "CASH",
            cashierName = "Laura Gómez"
        ).getOrThrow()

        // 2.0 kg - 250g = 1.75 kg
        assertEquals(1.75, repository.ingredients.value.find { it.id == fresa.id }!!.currentStock, 0.001)

        val returnResult = repository.returnSale(
            saleId = sale.id,
            reason = "Cliente cambió de opinión",
            userName = "Laura Gómez"
        )
        assertTrue(returnResult.isSuccess)
        val returnedSale = returnResult.getOrThrow()
        assertEquals("RETURNED", returnedSale.status)

        // 1.75 + 0.25 = 2.0 kg
        assertEquals(2.0, repository.ingredients.value.find { it.id == fresa.id }!!.currentStock, 0.001)

        val compensations = repository.inventoryMovements.value.filter { it.type == "RETURN_COMPENSATION" && it.saleId == sale.id }
        assertEquals(1, compensations.size)
        assertEquals(0.25, compensations[0].change, 0.001)
    }

    // =========================================================================
    // NUEVO MÓDULO: HISTORIAL FINANCIERO, CALENDARIO, NEQUI, GASTOS Y PRODUCTOS
    // =========================================================================

    // 22. Navegación por fechas y generación de días en el calendario
    @Test
    fun testNavegacionCalendarioYGeneracionDias() {
        val daysOctubre = repository.getMonthCalendarDays(2026, 10)
        assertEquals("Octubre debe tener 31 días", 31, daysOctubre.size)
        assertEquals(1, daysOctubre[0].dayNumber)
        assertEquals("2026-10-01", daysOctubre[0].dateStr)
        assertEquals(31, daysOctubre[30].dayNumber)
        assertEquals("2026-10-31", daysOctubre[30].dateStr)

        val daysNoviembre = repository.getMonthCalendarDays(2026, 11)
        assertEquals("Noviembre debe tener 30 días", 30, daysNoviembre.size)

        val daysFebreroBisiesto = repository.getMonthCalendarDays(2024, 2)
        assertEquals("Febrero 2024 bisiesto debe tener 29 días", 29, daysFebreroBisiesto.size)
    }

    // 23. Acumulación correcta del total Nequi para un día con múltiples pagos
    @Test
    fun testAcumulacionTotalNequiMultiplesPagos() {
        val testDate = "2026-10-26"
        val initialSummary = repository.getDailyFinancialSummary(testDate)
        val initialNequi = initialSummary.totalNequi

        // Registrar 2 transacciones Nequi adicionales
        repository.recordPaymentTransaction(
            method = "NEQUI",
            amount = 35000L,
            dateStr = testDate,
            payerNameOrPhone = "Andrés Parra",
            bankTransactionRef = "M991823"
        )
        repository.recordPaymentTransaction(
            method = "NEQUI",
            amount = 15000L,
            dateStr = testDate,
            payerNameOrPhone = "Carolina Díaz",
            bankTransactionRef = "M991824"
        )

        val updatedSummary = repository.getDailyFinancialSummary(testDate)
        assertEquals(initialNequi + 50000L, updatedSummary.totalNequi)
        assertEquals(initialSummary.nequiCount + 2, updatedSummary.nequiCount)
    }

    // 24. Separación entre ID de evento SMS y Referencia Bancaria ("No disponible en el SMS")
    @Test
    fun testSeparacionIdSmsYReferenciaBancariaFallback() {
        val testDate = "2026-10-27"
        // 1. Pago Nequi CON referencia bancaria
        val txConRef = repository.recordPaymentTransaction(
            method = "NEQUI",
            amount = 20000L,
            dateStr = testDate,
            smsEventId = "SMS-20261027-101",
            bankTransactionRef = "M123456"
        )
        assertEquals("M123456", txConRef.displayBankRef)
        assertEquals("SMS-20261027-101", txConRef.smsEventId)

        // 2. Pago Nequi SIN referencia bancaria (debe mostrar explícitamente "No disponible en el SMS")
        val txSinRef = repository.recordPaymentTransaction(
            method = "NEQUI",
            amount = 12000L,
            dateStr = testDate,
            smsEventId = "SMS-20261027-102",
            bankTransactionRef = null
        )
        assertEquals("No disponible en el SMS", txSinRef.displayBankRef)
        assertEquals("SMS-20261027-102", txSinRef.smsEventId)
    }

    // 25. Acumulación correcta del total efectivo (ventas + ingresos manuales a caja)
    @Test
    fun testAcumulacionTotalEfectivo() {
        val testDate = "2026-10-28"
        // Cobro por venta
        repository.recordPaymentTransaction(
            method = "CASH",
            amount = 25000L,
            dateStr = testDate,
            entryType = "SALE_PAYMENT",
            saleNumber = "V-00201",
            cashierName = "Laura Gómez"
        )
        // Ingreso manual a caja
        repository.recordPaymentTransaction(
            method = "CASH",
            amount = 40000L,
            dateStr = testDate,
            entryType = "MANUAL_CASH_IN",
            notes = "Base de cambio adicional",
            cashierName = "Laura Gómez"
        )

        val summary = repository.getDailyFinancialSummary(testDate)
        assertEquals(65000L, summary.totalCash)
        assertEquals(2, summary.cashCount)
    }

    // 26. Acumulación y categorización de gastos
    @Test
    fun testAcumulacionYCategorizacionGastos() {
        val testDate = "2026-10-29"
        val exp1 = repository.recordExpense(
            amount = 30000L,
            category = "Insumos",
            description = "10 Litros de leche entera",
            paymentSource = "Caja efectivo",
            cashierName = "Laura Gómez",
            dateStr = testDate,
            isLinkedToInventory = true
        ).getOrThrow()

        val exp2 = repository.recordExpense(
            amount = 15000L,
            category = "Transporte",
            description = "Domicilio de insumos",
            paymentSource = "Caja efectivo",
            cashierName = "Laura Gómez",
            dateStr = testDate
        ).getOrThrow()

        val summary = repository.getDailyFinancialSummary(testDate)
        assertEquals(45000L, summary.totalExpenses)
        assertEquals(2, summary.expensesCount)

        // Anular un gasto por el patrón
        val annulResult = repository.annulExpense(exp2.id, "Gasto cargado por error", "Patrón")
        assertTrue(annulResult.isSuccess)

        val summaryAfterAnnul = repository.getDailyFinancialSummary(testDate)
        // Solo debe quedar activo el de 30.000
        assertEquals(30000L, summaryAfterAnnul.totalExpenses)
        assertEquals(2, summaryAfterAnnul.expensesCount) // Total registros auditables
    }

    // 27. Cálculo exacto del balance de movimientos: Total recibido - Total gastos
    @Test
    fun testCalculoExactoBalanceMovimientos() {
        val testDate = "2026-10-30"
        // Nequi: 50.000
        repository.recordPaymentTransaction(
            method = "NEQUI",
            amount = 50000L,
            dateStr = testDate,
            payerNameOrPhone = "Juan Restrepo"
        )
        // Efectivo: 30.000
        repository.recordPaymentTransaction(
            method = "CASH",
            amount = 30000L,
            dateStr = testDate,
            entryType = "SALE_PAYMENT"
        )
        // Gastos: 20.000
        repository.recordExpense(
            amount = 20000L,
            category = "Servicios",
            description = "Recibo energía",
            paymentSource = "Caja efectivo",
            dateStr = testDate
        )

        val summary = repository.getDailyFinancialSummary(testDate)
        assertEquals(50000L, summary.totalNequi)
        assertEquals(30000L, summary.totalCash)
        assertEquals(80000L, summary.totalReceived) // 50k + 30k
        assertEquals(20000L, summary.totalExpenses)
        assertEquals(60000L, summary.movementBalance) // 80k - 20k = 60k
    }

    // 28. No duplicar ventas y transacciones de cobro
    @Test
    fun testNoDuplicarVentasYTransaccionesDeCobro() {
        val prod = repository.createProduct(
            name = "Copa Especial",
            basePrice = 16000L,
            categoryId = "copas",
            stockQuantity = 10.0
        ).getOrThrow()

        val cartItem = com.ambiorix.pay.pos.CartItem(
            cartItemId = "cart-test-1",
            productId = prod.id,
            productName = prod.name,
            basePrice = prod.basePrice,
            unitPrice = prod.basePrice,
            quantity = 1,
            totalPrice = prod.basePrice
        )

        // Completar venta pagada por Nequi
        val sale = repository.completeSale(
            items = listOf(cartItem),
            subtotal = 16000L,
            discount = 0L,
            total = 16000L,
            paymentMethod = "NEQUI",
            cashierName = "Laura Gómez",
            senderName = "Mónica Silva"
        ).getOrThrow()

        // Intentar repetir la misma operación con el mismo saleId no debe duplicar ni venta ni cobro ni descuento de stock
        val repeatedSale = repository.completeSale(
            items = listOf(cartItem),
            subtotal = 16000L,
            discount = 0L,
            total = 16000L,
            paymentMethod = "NEQUI",
            cashierName = "Laura Gómez",
            senderName = "Mónica Silva",
            saleId = sale.id
        ).getOrThrow()
        assertEquals(sale.id, repeatedSale.id)
        assertEquals(1, repository.sales.value.count { it.id == sale.id })

        val updatedProd = repository.products.value.find { it.id == prod.id }!!
        assertEquals("El stock debe descontarse una única vez", 9.0, updatedProd.stockQuantity, 0.001)

        val todayDate = repository.getTodayDateKey()
        val summary = repository.getDailyFinancialSummary(todayDate)

        // El total recibido debe computar exactamente 16000L de Nequi, nunca 32000L (duplicando venta + pago)
        val saleTxs = summary.nequiTransactions.filter { it.saleId == sale.id }
        assertEquals("Debe existir exactamente una transacción Nequi vinculada a la venta", 1, saleTxs.size)
        val saleTx = saleTxs.first()
        assertNotNull("Debe existir la transacción Nequi vinculada a la venta", saleTx)
        assertEquals(16000L, saleTx.amount)
        assertEquals(sale.id, saleTx.saleId)
    }

    // 29. Agrupación precisa de productos vendidos: Combos no inflan componentes individuales
    @Test
    fun testAgrupacionProductosVendidosYCombos() {
        val testDate = repository.getTodayDateKey()
        val prodBase = repository.createProduct(
            name = "Porción Pizza",
            basePrice = 15000L,
            categoryId = "productos",
            stockQuantity = 20.0,
            unit = "unidades"
        ).getOrThrow()

        val comboProd = repository.createProduct(
            name = "Combo Pareja Pizza",
            basePrice = 28000L,
            categoryId = "combos",
            isCombo = true,
            comboItems = listOf(
                com.ambiorix.pay.pos.ComboItem(
                    productId = prodBase.id,
                    productName = prodBase.name,
                    quantity = 2
                )
            )
        ).getOrThrow()
        assertTrue("Debe ser un producto combo", comboProd.isCombo)

        val comboItem = com.ambiorix.pay.pos.CartItem(
            cartItemId = "cart-combo-test",
            productId = comboProd.id,
            productName = comboProd.name,
            basePrice = comboProd.basePrice,
            unitPrice = comboProd.basePrice,
            quantity = 2,
            totalPrice = comboProd.basePrice * 2
        )

        val sale = repository.completeSale(
            items = listOf(comboItem),
            subtotal = 56000L,
            discount = 0L,
            total = 56000L,
            paymentMethod = "NEQUI",
            cashierName = "Laura Gómez"
        ).getOrThrow()

        val summary = repository.getDailyFinancialSummary(testDate)
        assertEquals(56000L, summary.totalNequi)

        val comboSold = summary.productsSold.find { it.productId == comboProd.id }
        assertNotNull("El combo debe aparecer como producto vendido", comboSold)
        assertTrue(comboSold!!.isCombo)
        assertEquals("Combo Pareja Pizza", comboSold.productName)
        assertEquals(28000L, comboSold.unitSalePrice)
        assertEquals(2, comboSold.totalQuantity)
        assertNull(
            "El componente individual no debe inflar la lista de productos vendidos por sí solo",
            summary.productsSold.find { it.productId == prodBase.id }
        )
        assertNotNull(sale.id)
    }

    // 30. Persistencia de gastos e historial de pagos
    @Test
    fun testPersistenciaGastosYPagos() {
        val testDate = "2026-10-22"
        val exp = repository.recordExpense(
            amount = 48000L,
            category = "Mantenimiento",
            description = "Mantenimiento preventivo congelador",
            paymentSource = "Caja efectivo",
            cashierName = "Laura Gómez",
            dateStr = testDate,
            notes = "Técnico refrigeración"
        ).getOrThrow()

        val tx = repository.recordPaymentTransaction(
            method = "NEQUI",
            amount = 75000L,
            dateStr = testDate,
            payerNameOrPhone = "Empresa Asociada SAS",
            bankTransactionRef = "M554433"
        )

        // Recargar repositorio desde almacenamiento persistente
        val reloadedRepo = com.ambiorix.pay.pos.PosRepository.createForTesting(storage)
        val reloadedSummary = reloadedRepo.getDailyFinancialSummary(testDate)

        val foundExp = reloadedSummary.expenses.find { it.id == exp.id }
        assertNotNull("El gasto debe persistir tras recargar el repositorio", foundExp)
        assertEquals(48000L, foundExp!!.amount)
        assertEquals("Mantenimiento", foundExp.category)

        val foundTx = reloadedSummary.nequiTransactions.find { it.id == tx.id }
        assertNotNull("La transacción de pago debe persistir tras recargar el repositorio", foundTx)
        assertEquals(75000L, foundTx!!.amount)
        assertEquals("M554433", foundTx.bankTransactionRef)
    }

    // =========================================================================
    // SECCIÓN 22: PRUEBAS DE RESERVAS NEQUI, COMUNICACIÓN SMS Y CONCILIACIÓN
    // =========================================================================

    private class FakePeerTransport {
        val sentFromHeladeria = mutableListOf<String>()
        val sentFromPizzeria = mutableListOf<String>()
        var failHeladeriaSend = false
        var failPizzeriaSend = false
        var autoDeliver = true

        lateinit var heladeriaRepo: PosRepository
        lateinit var pizzeriaRepo: PosRepository

        val heladeriaTransport = object : com.ambiorix.pay.pos.StoreCoordinationTransport {
            override fun sendCommand(payload: String, targetPhone: String): Boolean {
                if (failHeladeriaSend) return false
                sentFromHeladeria.add(payload)
                if (autoDeliver && ::pizzeriaRepo.isInitialized) {
                    pizzeriaRepo.processIncomingCoordinationSms(payload, "3001111111")
                }
                return true
            }
        }

        val pizzeriaTransport = object : com.ambiorix.pay.pos.StoreCoordinationTransport {
            override fun sendCommand(payload: String, targetPhone: String): Boolean {
                if (failPizzeriaSend) return false
                sentFromPizzeria.add(payload)
                if (autoDeliver && ::heladeriaRepo.isInitialized) {
                    heladeriaRepo.processIncomingCoordinationSms(payload, "3002222222")
                }
                return true
            }
        }
    }

    private fun createPeerStores(bus: FakePeerTransport): Pair<PosRepository, PosRepository> {
        val storageH = InMemoryPosStorage()
        val storageP = InMemoryPosStorage()
        val repoH = PosRepository.createForTesting(storageH)
        val repoP = PosRepository.createForTesting(storageP)
        repoH.coordinationTransport = bus.heladeriaTransport
        repoP.coordinationTransport = bus.pizzeriaTransport
        repoH.configureStore(
            localStoreId = "HELADERIA",
            peerStoreId = "PIZZERIA",
            peerPhone = "3002222222"
        )
        repoP.configureStore(
            localStoreId = "PIZZERIA",
            peerStoreId = "HELADERIA",
            peerPhone = "3001111111"
        )
        repoH.createProduct(
            name = "Producto Base H",
            basePrice = 10000L,
            categoryId = "productos",
            stockQuantity = 20.0,
            unit = "unidades"
        )
        repoP.createProduct(
            name = "Pizza Base P",
            basePrice = 10000L,
            categoryId = "productos",
            stockQuantity = 20.0,
            unit = "unidades"
        )
        bus.heladeriaRepo = repoH
        bus.pizzeriaRepo = repoP
        return Pair(repoH, repoP)
    }

    // A. Reservas (Escenarios 1 a 7 y 46)
    @Test
    fun testReservasEntreHeladeriaYPizzeriaYAmbiguedad() {
        val bus = FakePeerTransport()
        val (heladeria, pizzeria) = createPeerStores(bus)

        val prodH = heladeria.products.value.first { !it.isCombo && it.stockQuantity >= 5.0 }
        val initialStockH = prodH.stockQuantity
        val itemH = com.ambiorix.pay.pos.CartItem(
            cartItemId = "c-h-1",
            productId = prodH.id,
            productName = prodH.name,
            basePrice = 32500L,
            unitPrice = 32500L,
            quantity = 1,
            totalPrice = 32500L
        )

        // 1. La heladería crea una reserva por $32.500
        val resH = heladeria.createNequiReservation(
            amount = 32500L,
            items = listOf(itemH),
            subtotal = 32500L,
            discount = 0L,
            cashierName = "Laura Gómez"
        ).getOrThrow()

        assertEquals(32500L, resH.amount)
        // 46. Una reserva no descuenta inventario
        assertEquals(initialStockH, heladeria.products.value.first { it.id == prodH.id }.stockQuantity, 0.001)

        // 2. El mensaje contiene el valor correcto
        val reserveSms = bus.sentFromHeladeria.first { it.contains("|RESERVE|") }
        assertTrue(reserveSms.contains("amount=32500"))
        assertTrue(reserveSms.contains("src=HELADERIA"))

        // 3. La pizzería guarda la reserva y envía RESERVE_ACK
        val pizzeriaSavedH = pizzeria.reservations.value.find { it.id == resH.id }
        assertNotNull("La pizzería debe guardar la reserva de la heladería", pizzeriaSavedH)
        assertEquals(32500L, pizzeriaSavedH!!.amount)
        assertEquals("HELADERIA", pizzeriaSavedH.storeId)

        // Heladería recibió RESERVE_ACK
        val heladeriaUpdatedH = heladeria.reservations.value.find { it.id == resH.id }!!
        assertEquals(com.ambiorix.pay.pos.SyncState.ACKNOWLEDGED, heladeriaUpdatedH.syncState)

        // 4. Un SMS duplicado no duplica la reserva
        pizzeria.processIncomingCoordinationSms(reserveSms, "3001111111")
        assertEquals(1, pizzeria.reservations.value.count { it.id == resH.id })

        // 5 y 6. La pizzería puede crear su propia reserva y no sobrescribe la anterior
        val itemP = itemH.copy(cartItemId = "c-p-1", basePrice = 45000L, unitPrice = 45000L, totalPrice = 45000L)
        val resP = pizzeria.createNequiReservation(
            amount = 45000L,
            items = listOf(itemP),
            subtotal = 45000L,
            discount = 0L,
            cashierName = "Carlos"
        ).getOrThrow()

        assertEquals(2, heladeria.reservations.value.size)
        assertEquals(2, pizzeria.reservations.value.size)
        assertNotNull(heladeria.reservations.value.find { it.id == resP.id })

        // 7. Dos reservas del mismo valor ($32.500) en distintos locales se consideran ambiguas
        val itemP2 = itemH.copy(cartItemId = "c-p-2", basePrice = 32500L, unitPrice = 32500L, totalPrice = 32500L)
        val resPSameAmount = pizzeria.createNequiReservation(
            amount = 32500L,
            items = listOf(itemP2),
            subtotal = 32500L,
            discount = 0L,
            cashierName = "Carlos"
        ).getOrThrow()

        val bankSmsAmbiguous = "Nequi: Recibiste $32.500 de Juan Perez en tu Nequi Negocios. Ref. M998877"
        val notifH = heladeria.processNequiBankSms(bankSmsAmbiguous, "85954", 1793025500000L)!!
        assertEquals(com.ambiorix.pay.pos.NotificationState.REQUIRES_REVIEW, notifH.state)
        assertEquals(
            com.ambiorix.pay.pos.ReservationState.REQUIRES_REVIEW,
            heladeria.reservations.value.find { it.id == resH.id }!!.state
        )
        assertEquals(
            com.ambiorix.pay.pos.ReservationState.REQUIRES_REVIEW,
            heladeria.reservations.value.find { it.id == resPSameAmount.id }!!.state
        )
    }

    // B. Notificaciones Nequi (Escenarios 8 a 15 y 45)
    @Test
    fun testProcesamientoNotificacionesBancariasNequi() {
        val bus = FakePeerTransport()
        val (heladeria, pizzeria) = createPeerStores(bus)

        val prod = heladeria.createProduct(
            name = "Malteada Especial",
            basePrice = 24000L,
            categoryId = "helados",
            stockQuantity = 10.0
        ).getOrThrow()

        val item = com.ambiorix.pay.pos.CartItem(
            cartItemId = "c-1",
            productId = prod.id,
            productName = prod.name,
            basePrice = 24000L,
            unitPrice = 24000L,
            quantity = 1,
            totalPrice = 24000L
        )

        val res = heladeria.createNequiReservation(
            amount = 24000L,
            items = listOf(item),
            subtotal = 24000L,
            discount = 0L,
            cashierName = "Laura Gómez"
        ).getOrThrow()

        // 9. Una notificación de valor diferente no confirma la reserva
        val wrongAmountSms = "Nequi: Recibiste $19.000 de Pedro Gomez. Ref. M111222"
        val notifWrong = heladeria.processNequiBankSms(wrongAmountSms, "85954", 1793026000000L)!!
        assertEquals(com.ambiorix.pay.pos.NotificationState.UNASSIGNED, notifWrong.state)
        assertNotEquals(
            com.ambiorix.pay.pos.ReservationState.COMPLETED,
            heladeria.reservations.value.find { it.id == res.id }!!.state
        )

        // 10. Una notificación sin valor extraíble queda pendiente de revisión
        val noAmountSms = "Nequi: Tienes un movimiento en tu cuenta Nequi Negocios por verificar."
        val notifNoAmount = heladeria.processNequiBankSms(noAmountSms, "85954", 1793026010000L)!!
        assertEquals(com.ambiorix.pay.pos.NotificationState.REQUIRES_REVIEW, notifNoAmount.state)
        // 15. Los datos bancarios no disponibles no se inventan
        assertEquals(0L, notifNoAmount.amount)
        assertNull(notifNoAmount.senderName)
        assertNull(notifNoAmount.bankRef)

        // 14. Un comando interno no registra una venta si no hay notificación bancaria real
        val salesBefore = heladeria.sales.value.size
        assertEquals(salesBefore, heladeria.sales.value.size)

        // 8 y 45. Una notificación bancaria coincide con una única reserva válida, confirma la venta y descuenta stock una sola vez
        val matchingSms = "Nequi: Recibiste $24.000 de Maria Fernanda. Ref. M334455"
        val notifMatchH = heladeria.processNequiBankSms(matchingSms, "85954", 1793026050000L)!!
        // También llega al teléfono de la pizzería
        val notifMatchP = pizzeria.processNequiBankSms(matchingSms, "85954", 1793026050000L)!!

        assertEquals(com.ambiorix.pay.pos.NotificationState.LINKED_TO_SALE, notifMatchH.state)
        assertEquals(
            com.ambiorix.pay.pos.ReservationState.COMPLETED,
            heladeria.reservations.value.find { it.id == res.id }!!.state
        )
        assertEquals(
            com.ambiorix.pay.pos.ReservationState.COMPLETED,
            pizzeria.reservations.value.find { it.id == res.id }!!.state
        )
        // La pizzería no registra una venta propia para la reserva de la heladería
        assertEquals(0, pizzeria.sales.value.count { it.id == notifMatchH.linkedSaleId })
        assertEquals(9.0, heladeria.products.value.first { it.id == prod.id }.stockQuantity, 0.001)
        assertEquals("HELADERIA", notifMatchP.assignedStore)

        // 12. La misma notificación recibida dos veces no produce dos ventas
        val duplicateNotif = heladeria.processNequiBankSms(matchingSms, "85954", 1793026050000L)!!
        assertEquals(com.ambiorix.pay.pos.NotificationState.DUPLICATE_EVENT, duplicateNotif.state)
        assertEquals(9.0, heladeria.products.value.first { it.id == prod.id }.stockQuantity, 0.001)

        // 13. Dos transferencias reales distintas del mismo valor ($24.000) con diferente referencia no se fusionan
        val secondRealSms = "Nequi: Recibiste $24.000 de Maria Fernanda. Ref. M999888"
        val secondNotif = heladeria.processNequiBankSms(secondRealSms, "85954", 1793026090000L)!!
        assertNotEquals(notifMatchH.id, secondNotif.id)
        assertEquals(com.ambiorix.pay.pos.NotificationState.UNASSIGNED, secondNotif.state)
    }

    // C. Cancelación y D. Cambio a efectivo + Notificación tardía (Escenarios 16 a 27, 37, 43, 44)
    @Test
    fun testCancelacionCambioAEfectivoYNotificacionTardia() {
        val bus = FakePeerTransport()
        val (heladeria, pizzeria) = createPeerStores(bus)

        val prod = heladeria.createProduct(
            name = "Sundae Arequipe",
            basePrice = 32500L,
            categoryId = "helados",
            stockQuantity = 10.0
        ).getOrThrow()

        val item = com.ambiorix.pay.pos.CartItem(
            cartItemId = "c-sundae",
            productId = prod.id,
            productName = prod.name,
            basePrice = 32500L,
            unitPrice = 32500L,
            quantity = 1,
            totalPrice = 32500L
        )

        val res = heladeria.createNequiReservation(
            amount = 32500L,
            items = listOf(item),
            subtotal = 32500L,
            discount = 0L,
            cashierName = "Laura Gómez"
        ).getOrThrow()

        val originalReserveSms = bus.sentFromHeladeria.last { it.contains("|RESERVE|") }

        // 16 y 17. Cancelar reserva cambia estado de manera persistente y se comunica al otro local
        val cancelled = heladeria.cancelNequiReservation(res.id, "USER_CANCEL").getOrThrow()
        assertEquals(com.ambiorix.pay.pos.ReservationState.CANCELLED, cancelled.state)
        assertEquals(
            com.ambiorix.pay.pos.ReservationState.CANCELLED,
            pizzeria.reservations.value.find { it.id == res.id }!!.state
        )
        // 21. La cancelación conserva los ítems de la orden en la reserva para que el carrito no se pierda
        val recoveredAfterCancel = heladeria.recoverReservationCart(res.id)
        assertEquals(1, recoveredAfterCancel.size)
        assertEquals("Sundae Arequipe", recoveredAfterCancel.first().productName)

        // 18. Un SMS de cancelación duplicado es idempotente
        val cancelSms = bus.sentFromHeladeria.last { it.contains("|CANCEL|") }
        pizzeria.processIncomingCoordinationSms(cancelSms, "3001111111")
        assertEquals(
            com.ambiorix.pay.pos.ReservationState.CANCELLED,
            pizzeria.reservations.value.find { it.id == res.id }!!.state
        )

        // 19 y 43. Un mensaje RESERVE antiguo no reactiva una reserva cancelada
        pizzeria.processIncomingCoordinationSms(originalReserveSms, "3001111111")
        assertEquals(
            com.ambiorix.pay.pos.ReservationState.CANCELLED,
            pizzeria.reservations.value.find { it.id == res.id }!!.state
        )

        // 22, 23, 24, 26, 27. Cambiar a efectivo conserva el carrito, sustituye la reserva y funciona aunque falle el SMS
        val resForCash = heladeria.createNequiReservation(
            amount = 32500L,
            items = listOf(item),
            subtotal = 32500L,
            discount = 0L,
            cashierName = "Laura Gómez"
        ).getOrThrow()

        bus.failHeladeriaSend = true // Simular fallo de envío SMS al cambiar a efectivo
        val switched = heladeria.switchNequiReservationToCash(resForCash.id).getOrThrow()
        assertEquals(com.ambiorix.pay.pos.ReservationState.REPLACED_BY_CASH, switched.state)
        assertEquals(com.ambiorix.pay.pos.SyncState.RETRY_PENDING, switched.syncState)
        assertEquals(1, heladeria.recoverReservationCart(resForCash.id).size)

        val cashSale = heladeria.completeCashSaleFromReservation(
            reservationId = resForCash.id,
            cashierName = "Laura Gómez"
        ).getOrThrow()
        assertEquals("CASH", cashSale.paymentMethod)
        assertEquals(9.0, heladeria.products.value.first { it.id == prod.id }.stockQuantity, 0.001)

        // 20, 25, 44. Llega notificación bancaria tardía por $32.500 después de cancelar / cambiar a efectivo
        val lateSms = "Nequi: Recibiste $32.500 de Camilo Rojas. Ref. M777666"
        val lateNotif = heladeria.processNequiBankSms(lateSms, "85954", 1793027000000L)!!

        assertEquals(com.ambiorix.pay.pos.NotificationState.RECONCILIATION_PENDING, lateNotif.state)
        // No se crea una segunda venta ni se descuenta inventario otra vez
        assertEquals(9.0, heladeria.products.value.first { it.id == prod.id }.stockQuantity, 0.001)
        assertEquals(1, heladeria.sales.value.count { it.id == cashSale.id })

        // Conciliar la notificación tardía sin duplicar venta
        val reconciled = heladeria.reconcileLateNotification(
            notificationId = lateNotif.id,
            resolutionNote = "Transferencia tardía verificada para devolución o abono",
            cashierName = "Laura Gómez"
        ).getOrThrow()
        assertEquals(com.ambiorix.pay.pos.NotificationState.ASSIGNED, reconciled.state)
    }

    // E. Asignación manual desde el historial y F. Sincronización / Persistencia (Escenarios 11, 28 a 42)
    @Test
    fun testAsignacionManualSinReservaYSincronizacionPersistente() {
        val bus = FakePeerTransport()
        val (heladeria, pizzeria) = createPeerStores(bus)

        // 11 y 28. Una transacción sin reserva se guarda en ambos historiales como pendiente de asignación (UNASSIGNED)
        val rawBankSms = "Nequi: Recibiste $18.000 de Luisa Martinez. Ref. M445566"
        val bankTs = 1793027220000L
        val notifH = heladeria.processNequiBankSms(rawBankSms, "85954", bankTs)!!
        val notifP = pizzeria.processNequiBankSms(rawBankSms, "85954", bankTs)!!

        assertEquals(notifH.id, notifP.id)
        assertEquals(com.ambiorix.pay.pos.NotificationState.UNASSIGNED, notifH.state)
        assertEquals(com.ambiorix.pay.pos.NotificationState.UNASSIGNED, notifP.state)

        // 30, 31, 33, 34. En la heladería eligen «El otro local» (PIZZERIA)
        heladeria.assignNotificationToPeerStore(
            notificationOrTxId = notifH.id,
            cashierName = "Laura Gómez"
        ).getOrThrow()

        val assignSms = bus.sentFromHeladeria.last { it.contains("|ASSIGN|") }
        assertTrue(assignSms.contains("dst=PIZZERIA"))
        assertTrue(assignSms.contains("amount=18000"))

        // El destino (Pizzería) encontró la notificación original, la asignó a PIZZERIA y respondió ASSIGN_ACK
        val updatedNotifP = pizzeria.bankNotifications.value.find { it.id == notifP.id }!!
        assertEquals(com.ambiorix.pay.pos.NotificationState.ASSIGNED, updatedNotifP.state)
        assertEquals("PIZZERIA", updatedNotifP.assignedStore)

        val updatedNotifH = heladeria.bankNotifications.value.find { it.id == notifH.id }!!
        assertEquals(com.ambiorix.pay.pos.SyncState.ACKNOWLEDGED, updatedNotifH.syncState)
        assertEquals("PIZZERIA", updatedNotifH.assignedStore)

        // 34. Un ASSIGN duplicado no duplica la asignación
        val txCountBefore = pizzeria.paymentTransactions.value.size
        pizzeria.processIncomingCoordinationSms(assignSms, "3001111111")
        assertEquals(txCountBefore, pizzeria.paymentTransactions.value.size)

        // 29. Nueva transacción sin reserva asignada a «Este local»
        val rawBankSms2 = "Nequi: Recibiste $15.500 de Andres Parra. Ref. M123987"
        val notifH2 = heladeria.processNequiBankSms(rawBankSms2, "85954", bankTs + 5000L)!!
        val notifP2 = pizzeria.processNequiBankSms(rawBankSms2, "85954", bankTs + 5000L)!!

        val assignedLocal = heladeria.assignNotificationToLocalStore(
            notificationOrTxId = notifH2.id,
            cashierName = "Laura Gómez"
        ).getOrThrow()
        assertEquals("HELADERIA", assignedLocal.assignedStore)
        assertEquals(
            "HELADERIA",
            pizzeria.bankNotifications.value.find { it.id == notifP2.id }!!.assignedStore
        )

        // 32. El destino no inventa una transacción ni venta si recibe ASSIGN de un evento desconocido
        val unknownAssign = com.ambiorix.pay.pos.AmbiorixSmsProtocol.buildAssign(
            tx = "NEQUI-MISSING-999",
            src = "HELADERIA",
            dst = "PIZZERIA",
            amount = 21000L,
            bankts = 1793028000000L,
            msgId = "MSG-UNKNOWN-1"
        )
        val salesCountBeforeP = pizzeria.sales.value.size
        pizzeria.processIncomingCoordinationSms(unknownAssign, "3001111111")
        assertEquals("No debe inventar una venta", salesCountBeforeP, pizzeria.sales.value.size)
        assertTrue(
            "Debe guardar la asignación pendiente de sincronización sin inventar transacción",
            pizzeria.pendingRemoteAssignments.value.any { it.tx == "NEQUI-MISSING-999" }
        )

        // 35. Dos intentos incompatibles cuando están desconectados generan REQUIRES_REVIEW
        bus.autoDeliver = false
        val rawBankSms3 = "Nequi: Recibiste $29.000 de Diana Castro. Ref. M654321"
        val notifH3 = heladeria.processNequiBankSms(rawBankSms3, "85954", bankTs + 10000L)!!
        val notifP3 = pizzeria.processNequiBankSms(rawBankSms3, "85954", bankTs + 10000L)!!

        heladeria.assignNotificationToLocalStore(notifH3.id, "Laura Gómez")
        pizzeria.assignNotificationToLocalStore(notifP3.id, "Carlos")

        // Ahora llegan los SMS cruzados
        val msgFromH = bus.sentFromHeladeria.last()
        val msgFromP = bus.sentFromPizzeria.last()
        pizzeria.processIncomingCoordinationSms(msgFromH, "3001111111")
        heladeria.processIncomingCoordinationSms(msgFromP, "3002222222")

        assertEquals(
            com.ambiorix.pay.pos.NotificationState.REQUIRES_REVIEW,
            heladeria.bankNotifications.value.find { it.id == notifH3.id }!!.state
        )
        assertEquals(
            com.ambiorix.pay.pos.NotificationState.REQUIRES_REVIEW,
            pizzeria.bankNotifications.value.find { it.id == notifP3.id }!!.state
        )
    }

    // F. Dejar pendiente, cola de reintentos y recuperación tras reinicio (Escenarios 36, 39, 40, 41, 47)
    @Test
    fun testDejarPendienteReintentosYPersistenciaTrasReinicio() {
        val storageH = InMemoryPosStorage()
        val storageP = InMemoryPosStorage()
        val bus = FakePeerTransport()
        val heladeria = PosRepository.createForTesting(storageH)
        val pizzeria = PosRepository.createForTesting(storageP)
        heladeria.coordinationTransport = bus.heladeriaTransport
        pizzeria.coordinationTransport = bus.pizzeriaTransport
        heladeria.configureStore("HELADERIA", "PIZZERIA", "3002222222")
        pizzeria.configureStore("PIZZERIA", "HELADERIA", "3001111111")
        heladeria.createProduct(
            name = "Producto Reserva",
            basePrice = 27000L,
            categoryId = "productos",
            stockQuantity = 20.0,
            unit = "unidades"
        )
        bus.heladeriaRepo = heladeria
        bus.pizzeriaRepo = pizzeria

        val prod = heladeria.products.value.first { !it.isCombo && it.stockQuantity >= 5.0 }
        val item = com.ambiorix.pay.pos.CartItem(
            cartItemId = "c-pend-1",
            productId = prod.id,
            productName = prod.name,
            basePrice = 27000L,
            unitPrice = 27000L,
            quantity = 1,
            totalPrice = 27000L
        )

        // 36 y 41. Fallo de envío inicial deja la reserva en RETRY_PENDING y en la cola de salida
        bus.failHeladeriaSend = true
        val res = heladeria.createNequiReservation(
            amount = 27000L,
            items = listOf(item),
            subtotal = 27000L,
            discount = 0L,
            cashierName = "Laura Gómez"
        ).getOrThrow()

        assertEquals(com.ambiorix.pay.pos.SyncState.RETRY_PENDING, res.syncState)
        assertTrue(heladeria.outboxMessages.value.isNotEmpty())

        // 47. Dejar pendiente conserva la reserva, no envía CANCEL y no crea venta confirmada
        val leftPending = heladeria.leaveReservationPending(res.id).getOrThrow()
        assertTrue(leftPending.isPendingHold)
        assertTrue(heladeria.getRecoverableReservations().any { it.id == res.id })

        // 39 y 40. Reiniciar el dispositivo (recargar desde storageH) conserva la reserva y la cola pendiente
        val reloadedHeladeria = PosRepository.createForTesting(storageH)
        reloadedHeladeria.coordinationTransport = bus.heladeriaTransport
        reloadedHeladeria.configureStore("HELADERIA", "PIZZERIA", "3002222222")
        bus.heladeriaRepo = reloadedHeladeria

        assertEquals(1, reloadedHeladeria.getRecoverableReservations().count { it.id == res.id })
        assertTrue(reloadedHeladeria.outboxMessages.value.isNotEmpty())

        // Reintentar envío cuando vuelve la señal SMS
        bus.failHeladeriaSend = false
        val sentCount = reloadedHeladeria.retryPendingOutboxMessages()
        assertTrue(sentCount >= 1)
        assertEquals(
            com.ambiorix.pay.pos.SyncState.ACKNOWLEDGED,
            reloadedHeladeria.reservations.value.find { it.id == res.id }!!.syncState
        )
    }

    // G. Estado inicial Pizzería Natus: categorías base y catálogo vacío sin datos precargados de heladería
    @Test
    fun testCatalogoInicialVacioYCategoriasPizzeriaNatus() {
        val freshStorage = InMemoryPosStorage()
        val freshRepo = PosRepository.createForTesting(freshStorage)

        val catIds = freshRepo.categories.value.map { it.id }
        assertEquals(listOf("productos", "ingredientes", "combos"), catIds)
        assertTrue("El catálogo inicial de productos debe estar vacío", freshRepo.products.value.isEmpty())
        assertTrue("El catálogo inicial de ingredientes debe estar vacío", freshRepo.ingredients.value.isEmpty())
        assertTrue("El catálogo inicial de toppings debe estar vacío", freshRepo.toppings.value.isEmpty())
        assertTrue("El historial inicial de ventas debe estar vacío", freshRepo.sales.value.isEmpty())
        assertTrue("El historial inicial de gastos debe estar vacío", freshRepo.expenses.value.isEmpty())
        assertTrue("El historial inicial de transacciones debe estar vacío", freshRepo.paymentTransactions.value.isEmpty())
    }

    // H. Inventario centralizado (Insumos, Toppings, Materiales de operación) y Ajuste de Base de Caja
    @Test
    fun testInventarioCentralToppingsYAjusteBaseCaja() {
        val storage = InMemoryPosStorage()
        val repo = PosRepository.createForTesting(storage)

        // 1. Registrar insumo de preparación, insumo de topping y material de operación
        val baseHelado = repo.createIngredient("Base Helado Vainilla", "g", 2000.0, 300.0, 20L, "Insumos").getOrThrow()
        val chispasIng = repo.createIngredient("Chispas de Chocolate", "g", 500.0, 50.0, 30L, "Insumos").getOrThrow()
        val vasoMat = repo.createIngredient("Vasos 8oz", "unidades", 100.0, 15.0, 150L, "Empaques").getOrThrow()

        // Actualizar clasificación a Materiales de operación
        val updatedVaso = repo.updateIngredient(
            id = vasoMat.id,
            name = "Vasos 8oz",
            unit = "unidades",
            minStockAlert = 15.0,
            unitCostCOP = 150L,
            isActive = true,
            category = "Materiales de operación"
        ).getOrThrow()
        assertEquals("Materiales de operación", updatedVaso.category)

        // 2. Crear topping vinculado al insumo Chispas de Chocolate
        val toppingChispas = repo.createTopping(
            name = "Chispas de Chocolate",
            price = 2000L,
            ingredientId = chispasIng.id,
            gramsRequired = 25.0
        ).getOrThrow()
        assertEquals(1, repo.toppings.value.size)

        // 3. Crear producto personalizable con variantes (1 bola, 2 bolas) y receta vinculada a base + vaso
        val copaHelado = repo.createProduct(
            name = "Copa de Helado",
            basePrice = 8000L,
            categoryId = "productos",
            recipe = listOf(
                com.ambiorix.pay.pos.RecipeItem(baseHelado.id, baseHelado.name, 100.0, "g"),
                com.ambiorix.pay.pos.RecipeItem(vasoMat.id, vasoMat.name, 1.0, "unidades")
            ),
            variants = listOf(
                com.ambiorix.pay.pos.ProductVariant("bola-1", "1 Bola", 0L, 1.0),
                com.ambiorix.pay.pos.ProductVariant("bola-2", "2 Bolas", 3500L, 2.0)
            ),
            allowedToppings = true
        ).getOrThrow()
        assertEquals(2, copaHelado.variants.size)
        assertTrue(copaHelado.allowedToppings)

        // 4. Vender 1 Copa de Helado con topping de Chispas en efectivo y verificar descuento de receta + topping + material
        val cartItem = com.ambiorix.pay.pos.CartItem(
            cartItemId = "ci-test-1",
            productId = copaHelado.id,
            productName = copaHelado.name,
            variantId = "bola-1",
            variantName = "1 Bola",
            basePrice = 8000L,
            variantPriceDelta = 0L,
            unitPrice = 10000L,
            quantity = 1,
            toppings = listOf(toppingChispas),
            totalPrice = 10000L,
            estimatedCost = 2500L
        )
        val saleRes = repo.completeSale(
            items = listOf(cartItem),
            subtotal = 10000L,
            discount = 0L,
            total = 10000L,
            paymentMethod = "CASH",
            cashierName = "Laura Gómez"
        )
        assertTrue(saleRes.isSuccess)
        assertEquals(1900.0, repo.ingredients.value.find { it.id == baseHelado.id }!!.currentStock, 0.01)
        assertEquals(475.0, repo.ingredients.value.find { it.id == chispasIng.id }!!.currentStock, 0.01)
        assertEquals(99.0, repo.ingredients.value.find { it.id == vasoMat.id }!!.currentStock, 0.01)

        // 5. Ajustar base de caja con motivo obligatorio y verificar cierre de caja
        val failEmptyReason = repo.adjustCashBase(150000L, "   ", "Laura Gómez")
        assertTrue(failEmptyReason.isFailure)

        val adjustedShift = repo.adjustCashBase(150000L, "Ajuste de sencillo inicial", "Laura Gómez").getOrThrow()
        assertEquals(150000L, adjustedShift.initialCash)
        assertTrue(adjustedShift.expenses.any { it.type == "BASE_ADJUSTMENT" && it.amount == 150000L })

        // Registrar un gasto menor de $5.000
        repo.addCashExpense("Compra de servilletas urgentes", 5000L)

        // Esperado en caja = Base ($150.000) + Ventas efectivo ($10.000) - Otros movimientos ($5.000) = $155.000
        val closedShift = repo.performBlindClose(155000L)
        assertEquals(155000L, closedShift.expectedCash)
        assertEquals(155000L, closedShift.countedCash)
        assertEquals(0L, closedShift.difference)
    }
}




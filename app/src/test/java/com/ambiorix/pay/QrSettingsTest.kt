package com.ambiorix.pay

import com.ambiorix.pay.data.AppSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QrSettingsTest {

    @Test
    fun testDefaultSettingsHaveNoQRs() {
        val settings = AppSettings()
        assertNull(settings.qr1Path)
        assertNull(settings.qr2Path)
        assertFalse(settings.qr2Enabled)
    }

    @Test
    fun testImportAndReplaceQr1Independently() {
        var settings = AppSettings()

        // 1. Import QR 1
        val initialQr1 = "/data/user/0/com.ambiorix.pay/files/images/qr1_1001.png"
        settings = settings.copy(qr1Path = initialQr1)
        assertEquals(initialQr1, settings.qr1Path)
        assertNull(settings.qr2Path)

        // 2. Replace QR 1 with a JPG
        val updatedQr1 = "/data/user/0/com.ambiorix.pay/files/images/qr1_1002.jpg"
        settings = settings.copy(qr1Path = updatedQr1)
        assertEquals(updatedQr1, settings.qr1Path)
        assertNull(settings.qr2Path)

        // 3. Delete QR 1
        settings = settings.copy(qr1Path = null)
        assertNull(settings.qr1Path)
    }

    @Test
    fun testDualQrIndependentManagement() {
        var settings = AppSettings()

        // Configure QR 1
        val qr1Path = "/data/user/0/com.ambiorix.pay/files/images/qr1_1001.png"
        settings = settings.copy(qr1Path = qr1Path)

        // Enable and configure QR 2
        val qr2Path = "/data/user/0/com.ambiorix.pay/files/images/qr2_2001.jpeg"
        settings = settings.copy(qr2Enabled = true, qr2Path = qr2Path)

        assertEquals(qr1Path, settings.qr1Path)
        assertEquals(qr2Path, settings.qr2Path)
        assertTrue(settings.qr2Enabled)

        // Replace QR 2 independently without affecting QR 1
        val qr2Updated = "/data/user/0/com.ambiorix.pay/files/images/qr2_2002.png"
        settings = settings.copy(qr2Path = qr2Updated)

        assertEquals(qr1Path, settings.qr1Path) // QR 1 unchanged
        assertEquals(qr2Updated, settings.qr2Path)

        // Delete QR 2 without affecting QR 1
        settings = settings.copy(qr2Path = null)
        assertEquals(qr1Path, settings.qr1Path)
        assertNull(settings.qr2Path)
    }

    @Test
    fun testCompatibleExtensions() {
        val supportedExtensions = listOf("jpg", "jpeg", "png")
        for (ext in supportedExtensions) {
            val fileName = "qr1_123456.$ext"
            val file = File("/dummy/path", fileName)
            assertEquals(ext, file.extension.lowercase())
            assertTrue(listOf("jpg", "jpeg", "png").contains(file.extension.lowercase()))
        }
    }
}

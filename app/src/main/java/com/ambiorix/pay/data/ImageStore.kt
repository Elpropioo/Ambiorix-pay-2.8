package com.ambiorix.pay.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

object ImageStore {

    /**
     * Copia una imagen seleccionada por el usuario desde la galería al almacenamiento privado interno de la app.
     * De esta forma la app retiene el acceso permanente a la imagen sin requerir permisos de lectura externa
     * ni depender de la vigencia del URI del ContentProvider tras reiniciar el dispositivo.
     *
     * Compatible con Android 10 (API 29) y versiones posteriores.
     */
    fun saveImageToInternalStorage(context: Context, sourceUri: Uri, fileName: String): String? {
        return try {
            val dir = File(context.filesDir, "images")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val destinationFile = File(dir, fileName)

            val mimeType = context.contentResolver.getType(sourceUri)
            // Permitir JPG, JPEG, PNG y formatos de imagen estándar
            if (mimeType != null && !mimeType.startsWith("image/")) {
                return null
            }

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            // Si el archivo quedó vacío por algún fallo en la lectura, eliminarlo y retornar null
            if (destinationFile.length() == 0L) {
                destinationFile.delete()
                return null
            }

            destinationFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Guarda una imagen QR o Logo con un sufijo temporal único para evitar problemas de caché de Coil
     * y devolver la ruta absoluta en almacenamiento interno privado.
     */
    fun saveGalleryImage(context: Context, sourceUri: Uri, prefix: String): String? {
        val extension = getExtensionFromUri(context, sourceUri) ?: "png"
        val fileName = "${prefix}_${System.currentTimeMillis()}.$extension"
        return saveImageToInternalStorage(context, sourceUri, fileName)
    }

    private fun getExtensionFromUri(context: Context, uri: Uri): String? {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType != null) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                if (!ext.isNullOrBlank()) ext else "png"
            } else {
                val path = uri.path
                if (path != null && path.contains(".")) {
                    path.substringAfterLast('.').lowercase()
                } else {
                    "png"
                }
            }
        } catch (e: Exception) {
            "png"
        }
    }

    fun deleteFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val file = File(path)
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            false
        }
    }
}

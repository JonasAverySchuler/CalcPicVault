package com.appnovastudios.calculatorsafe.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.appnovastudios.calculatorsafe.data.FileDetail
import com.example.calculatorsafe.data.Album
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec

object EncryptionUtils {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS7Padding"

    fun getAlbumsDir(context: Context): File {
        return File(context.filesDir, "Albums")
    }

    fun encryptImage(bitmap: Bitmap?): ByteArray? {
        val secretKey = KeystoreUtils.getOrCreateGlobalKey()

        // Early return if the bitmap is null
        if (bitmap == null) {
            Log.e("Encryption", "Bitmap is null")
            return null
        }

        // Compress the bitmap into a byte array
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()

        // Check if the byte array is valid and non-empty
        if (byteArray.isEmpty()) {
            Log.e("Encryption", "Compressed byte array is empty")
            return null
        }

        try {
            // Initialize the cipher for AES in CBC mode with PKCS7 padding
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            // Generate the IV from the cipher
            val iv = cipher.iv

            // Encrypt the byte array
            val encryptedData = cipher.doFinal(byteArray)

            // Return the IV + encrypted data
            return iv + encryptedData

        } catch (e: Exception) {
            Log.e("Encryption", "Error during encryption: ${e.message}")
            return null
        }
    }

    fun encryptVideo(file: File): ByteArray? {
        val secretKey = KeystoreUtils.getOrCreateGlobalKey()

        // Early return if the file doesn't exist or is empty
        if (!file.exists() || file.length() == 0L) {
            Log.e("Encryption", "File does not exist or is empty: ${file.absolutePath}")
            return null
        }

        try {
            // Initialize the cipher for AES in CBC mode with PKCS7 padding
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            // Generate the IV from the cipher
            val iv = cipher.iv

            // Read the file into a byte array
            val fileBytes = file.readBytes()

            // Encrypt the file bytes
            val encryptedData = cipher.doFinal(fileBytes)

            // Return the IV + encrypted data
            return iv + encryptedData

        } catch (e: Exception) {
            Log.e("Encryption", "Error during video encryption: ${e.message}")
            return null
        }
    }

    suspend fun decryptVideo(encryptedBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val secretKey = KeystoreUtils.getOrCreateGlobalKey()

        try {
            // Initialize the cipher for AES in CBC mode with PKCS7 padding
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")

            // Extract the IV from the encryptedBytes
            val ivSize = cipher.blockSize // Typically 16 bytes for AES
            if (encryptedBytes.size <= ivSize) {
                Log.e("Decryption", "Invalid encrypted data. Too short to contain IV and data.")
                return@withContext null
            }
            val iv = encryptedBytes.copyOfRange(0, ivSize)
            val encryptedData = encryptedBytes.copyOfRange(ivSize, encryptedBytes.size)

            // Initialize the cipher in DECRYPT_MODE with the IV
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            // Decrypt the data
            return@withContext cipher.doFinal(encryptedData)

        } catch (e: Exception) {
            Log.e("Decryption", "Error during video decryption: ${e.message}")
            null
        }
    }

    fun saveEncryptedFileToStorage(context: Context, encryptedImage: ByteArray, targetAlbum: Album?, encryptedFileName: String): String {
        val albumsDir = getAlbumsDir(context)
        if (!albumsDir.exists()) {
            albumsDir.mkdirs() // Create the albums directory if it doesn't exist
        }

        val albumDir = File(albumsDir, targetAlbum?.name ?: "default")
        if (!albumDir.exists()) {
            albumDir.mkdirs() // Create the album directory if it doesn't exist
        }

        val file = File(albumDir, encryptedFileName)

        FileOutputStream(file).use {
            it.write(encryptedImage)
        }

        return file.absolutePath
    }

    suspend fun decryptImage(file: File, downscale: Boolean = true): Bitmap? = withContext(Dispatchers.IO) {
        val secretKey = KeystoreUtils.getOrCreateGlobalKey()
        val iv = ByteArray(16) // 16 bytes for the IV

        // Temporary file for decrypted data
        val decryptedFile = File.createTempFile("decrypted_", ".tmp", file.parentFile).apply {
            deleteOnExit() // Ensures the file is deleted when the JVM exits
        }

        try {
            // Read the IV
            file.inputStream().use { inputStream ->
                val bytesRead = inputStream.read(iv)
                if (bytesRead != 16) {
                    throw IllegalArgumentException("Unable to read IV, bytes read: $bytesRead")
                }
            }

            // Set up the cipher for decryption
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            // Use a CipherInputStream to decrypt in chunks and write to a file
            FileOutputStream(decryptedFile).use { outputStream ->
                val buffer = ByteArray(16 * 1024) // 16 KB buffer
                file.inputStream().use { inputStream ->
                    inputStream.skip(16) // Skip the IV
                    val cipherInputStream = CipherInputStream(inputStream, cipher)

                    var bytesRead: Int
                    while (cipherInputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }

            // Once the file is decrypted, load it as a Bitmap
            return@withContext loadBitmapFromFile(decryptedFile, downscale)

        } catch (e: Exception) {
            Log.e("Decryption", "Error during decryption: ${e.message}")
            null
        } finally {
            if (decryptedFile.exists()) {
                decryptedFile.delete()
            }
        }
    }

    private fun loadBitmapFromFile(file: File, downscale: Boolean): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        // Decode bounds first to get the image size
        BitmapFactory.decodeFile(file.absolutePath, options)

        // Optionally downscale the image to avoid OOM issues
        if (downscale) {
            options.inSampleSize = calculateInSampleSize(options, maxWidth = 200, maxHeight = 200)
        }
        options.inJustDecodeBounds = false

        // Decode and return the actual Bitmap
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, maxWidth: Int, maxHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 4

        if (height > maxHeight || width > maxWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // Calculate the largest inSampleSize value that is a power of 2
            // and keeps both height and width larger than the requested max dimensions.
            while (halfHeight / inSampleSize >= maxHeight && halfWidth / inSampleSize >= maxWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    fun getBitmapFromUri(contentResolver: ContentResolver, uri: Uri): Bitmap? {
        try {
            // Open an InputStream to the content URI
            val inputStream = contentResolver.openInputStream(uri)
            inputStream?.use {
                // Decode the bitmap from the input stream
                return BitmapFactory.decodeStream(it)
            }
        } catch (e: FileNotFoundException) {
            Log.e("ImageDecoder", "File not found for URI: $uri", e)
        } catch (e: IOException) {
            Log.e("ImageDecoder", "I/O error while reading URI: $uri", e)
        } catch (e: Exception) {
            Log.e("ImageDecoder", "Error loading image from URI: $uri", e)
        }
        return null
    }

    private fun saveBitmapToFile(bitmap: Bitmap?, newFileName: String, context: Context): File? {
        return try {
            val tempFile = File(context.cacheDir, newFileName)
            val outputStream = FileOutputStream(tempFile)
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream) // Save as JPEG with 100% quality
            outputStream.flush()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun restorePhotoToDevice(file: File, context: Context): Boolean {
        val decryptedBitmap = decryptImage(file, downscale = false)
        val restoredFile = saveBitmapToFile(decryptedBitmap, "restored_img_${System.currentTimeMillis()}.jpg", context) ?: return false

        try {
            // Get the metadata for the album directory
            val albumDir = File(file.parent!!) //Parent is the album directory so we are certain it exists so it is safe to assert this here
            val metadataFile = File(albumDir, "metadata.json")

            // Read and update metadata
            val gson = Gson()
            val metadata = if (metadataFile.exists()) {
                gson.fromJson(metadataFile.readText(), FileUtils.Metadata::class.java)
            } else {
                FileUtils.Metadata(albumName = albumDir.name, files = emptyList<FileDetail>().toMutableList())
            }

            // Find the file in the metadata and retrieve the original name
            val fileDetail = metadata.files.find { it.encryptedFileName == file.name }
            val originalFileName = fileDetail?.originalFileName ?: "${System.currentTimeMillis()}.jpg"

            // Step 1: Name the restored file with the original file name
            val restoredFileName = "$originalFileName.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, restoredFileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                resolver.openOutputStream(it).use { outputStream ->
                    restoredFile.inputStream().copyTo(outputStream!!)
                }

                // Step 2: Delete the encrypted file
                file.delete()

                // Step 3: Remove the file from metadata and save
                val updatedFiles = metadata.files.filterNot { it.encryptedFileName == file.name }
                metadata.files = updatedFiles.toMutableList()

                // Save the updated metadata
                metadataFile.writeText(gson.toJson(metadata))

                return true // Photo restored successfully
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false // Restoration failed
    }

}
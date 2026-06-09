package com.shotgun.smsbot.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloadManager {

    private const val TAG = "ModelDownloadManager"
    const val MODEL_FILENAME = "gemma-2b-it-cpu-int4.bin"

    const val MODEL_DOWNLOAD_URL =
        "https://github.com/Nugahh/fly-shotgun/releases/download/0.9.0/gemma-2b-it-cpu-int4.bin"

    // Taille attendue ~1.1 Go — tout fichier < 100 Mo est considéré corrompu
    private const val MIN_VALID_SIZE = 100_000_000L

    fun isModelReady(context: Context): Boolean {
        val file = getModelFile(context)
        return file.exists() && file.length() > MIN_VALID_SIZE
    }

    fun getModelPath(context: Context): String = getModelFile(context).absolutePath

    fun getModelFile(context: Context): File {
        val dir = context.getExternalFilesDir("models") ?: context.filesDir
        return File(dir, MODEL_FILENAME)
    }

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Télécharge le modèle si absent. [onProgress] reçoit 0..100.
     * Lance une exception si le téléchargement échoue.
     */
    suspend fun downloadIfNeeded(
        context: Context,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isModelReady(context)) {
            onProgress(100)
            return@withContext
        }

        val file = getModelFile(context)
        file.parentFile?.mkdirs()
        val tmpFile = File(file.parent, "${MODEL_FILENAME}.tmp")

        val conn = URL(MODEL_DOWNLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000
        conn.setRequestProperty("User-Agent", "TransaviaShotgun/1.0")

        try {
            conn.connect()
            val total = conn.contentLengthLong

            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded * 100 / total).toInt())
                    }
                }
            }

            // Atomic rename once download is complete
            tmpFile.renameTo(file)
            Log.i(TAG, "Modèle téléchargé : ${file.absolutePath} (${file.length()} octets)")
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e(TAG, "Échec téléchargement modèle", e)
            throw e
        } finally {
            conn.disconnect()
        }
    }
}

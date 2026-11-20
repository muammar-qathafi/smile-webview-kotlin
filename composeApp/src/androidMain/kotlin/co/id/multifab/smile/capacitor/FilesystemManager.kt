/**
 * Status: COMPAT
 * Flow: Tulis/baca file cache untuk plugin Capacitor Filesystem yang dipanggil web.
 * Takeout: kandidat jika web sudah tidak memakai Capacitor Filesystem.
 */
package co.id.multifab.smile.capacitor

import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import co.id.multifab.smile.SmileApplication
import org.json.JSONObject
import java.io.File

object FilesystemManager {

    private const val AUTHORITY = "co.id.multifab.fileprovider"

    fun resolveDirectory(directory: String?): File {
        val ctx = SmileApplication.instance
        return when (directory?.uppercase()) {
            "CACHE", "CACHE_DIRECTORY" -> ctx.cacheDir
            else -> ctx.cacheDir
        }
    }

    fun writeFile(options: JSONObject): JSONObject {
        val path = options.optString("path")
        val directory = options.optString("directory", "CACHE")
        val data = options.optString("data")
        val recursive = options.optBoolean("recursive", false)

        val base = resolveDirectory(directory)
        val file = File(base, path)
        if (recursive) file.parentFile?.mkdirs() else file.parentFile?.mkdirs()
        file.writeBytes(Base64.decode(data, Base64.DEFAULT))

        return JSONObject().apply {
            put("uri", toContentUri(file).toString())
        }
    }

    fun readFile(options: JSONObject): JSONObject {
        val path = options.optString("path")
        val directory = options.optString("directory", "CACHE")
        val file = File(resolveDirectory(directory), path)
        if (!file.exists()) throw IllegalArgumentException("File not found")
        return JSONObject().apply {
            put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }
    }

    fun stat(options: JSONObject): JSONObject {
        val path = options.optString("path")
        val directory = options.optString("directory", "CACHE")
        val file = File(resolveDirectory(directory), path)
        if (!file.exists()) throw IllegalArgumentException("File not found")
        return JSONObject().apply {
            put("uri", toContentUri(file).toString())
            put("size", file.length())
            put("mtime", file.lastModified())
            put("type", if (file.isDirectory) "directory" else "file")
        }
    }

    fun mkdir(options: JSONObject): JSONObject {
        val path = options.optString("path")
        val directory = options.optString("directory", "CACHE")
        val recursive = options.optBoolean("recursive", false)
        val dir = File(resolveDirectory(directory), path)
        if (recursive) dir.mkdirs() else dir.mkdir()
        return JSONObject().apply { put("uri", toContentUri(dir).toString()) }
    }

    private fun toContentUri(file: File): Uri =
        FileProvider.getUriForFile(SmileApplication.instance, AUTHORITY, file)
}

/**
 * Status: AKTIF
 * Flow: Antrian lokal titik lokasi (JSONL) FIFO. Selalu akumulasi; hapus hanya setelah batch sukses terkirim.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Penyimpanan lokal sebelum kirim batch.
 * FIFO: peek/remove dari depan. Titik baru selalu ditampung; tidak ada trim/tolak.
 */
object NativeLocationStore {

    private const val FILE = "smile_location_log.jsonl"

    private val lock = Any()
    private val cachedCount = AtomicInteger(-1)

    fun appendPoint(point: JSONObject) {
        synchronized(lock) {
            val ctx = co.id.multifab.smile.SmileApplication.instance
            val file = File(ctx.filesDir, FILE)
            val count = ensureCount(file)
            if (!point.has("tracked_at")) {
                point.put("tracked_at", System.currentTimeMillis())
            }
            file.appendText(point.toString() + "\n")
            cachedCount.set(count + 1)
        }
    }

    fun readRecent(limit: Int = 50): JSONArray {
        synchronized(lock) {
            val ctx = co.id.multifab.smile.SmileApplication.instance
            val file = File(ctx.filesDir, FILE)
            if (!file.exists()) return JSONArray()
            val lines = file.readLines().filter { it.isNotBlank() }.takeLast(limit)
            return JSONArray().apply { lines.forEach { runCatching { put(JSONObject(it)) } } }
        }
    }

    private fun ensureCount(file: File): Int {
        val cached = cachedCount.get()
        if (cached >= 0) return cached
        if (!file.exists()) {
            cachedCount.set(0)
            return 0
        }
        val n = file.readLines().count { it.isNotBlank() }
        cachedCount.set(n)
        return n
    }

    fun pendingCount(): Int {
        synchronized(lock) {
            val ctx = co.id.multifab.smile.SmileApplication.instance
            return ensureCount(File(ctx.filesDir, FILE))
        }
    }

    /** Baca titik tertua tanpa menghapus. */
    fun peekPending(limit: Int = 40): List<JSONObject> {
        synchronized(lock) {
            val ctx = co.id.multifab.smile.SmileApplication.instance
            val file = File(ctx.filesDir, FILE)
            if (!file.exists()) return emptyList()
            return file.readLines()
                .filter { it.isNotBlank() }
                .take(limit.coerceAtLeast(1))
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        }
    }

    /** Hapus [count] titik tertua setelah POST batch sukses. */
    fun removeFirst(count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            val ctx = co.id.multifab.smile.SmileApplication.instance
            val file = File(ctx.filesDir, FILE)
            if (!file.exists()) {
                cachedCount.set(0)
                return
            }
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                file.delete()
                cachedCount.set(0)
                return
            }
            val rest = lines.drop(count.coerceAtMost(lines.size))
            if (rest.isEmpty()) {
                file.delete()
                cachedCount.set(0)
            } else {
                file.writeText(rest.joinToString("\n") + "\n")
                cachedCount.set(rest.size)
            }
        }
    }
}

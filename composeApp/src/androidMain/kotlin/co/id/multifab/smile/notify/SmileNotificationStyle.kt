/**
 * Status: AKTIF
 * Flow: Memilih notifikasi absensi/pengingat yang wajib pakai logo standar, bukan gambar lampiran.
 * Takeout: jangan.
 */
package co.id.multifab.smile.notify

/** Satu gaya logo (tes 15 detik) untuk pengingat absensi & push terkait. */
object SmileNotificationStyle {

    fun useStandardLogoOnly(type: String, data: Map<String, String> = emptyMap()): Boolean {
        if (data["smileReminder"] == "1" || data["smile_reminder"] == "1") return true
        val t = type.trim().lowercase()
        if (t.isEmpty()) return false
        if (t.contains("reminder")) return true
        return t in STANDARD_LOGO_TYPES
    }

    private val STANDARD_LOGO_TYPES = setOf(
        "attendance_leave_reminder",
        "leave_reminder",
        "attendance_check_in",
        "attendance_check_out",
        "check_in",
        "check_out",
    )
}

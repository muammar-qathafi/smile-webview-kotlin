/**
 * Status: LEGACY
 * Flow: Dulu cek izin READ_MEDIA_* untuk upload. Play policy: diganti system picker.
 *       API tetap ada agar bridge/diagnosis lama tidak putus; selalu “cukup” tanpa izin broad.
 * Takeout: boleh setelah web tidak memanggil path media permission.
 */
package co.id.multifab.smile.permission

object NativeMediaPermission {

    fun requiredPermissions(): Array<String> = emptyArray()

    /** Selalu true — upload lewat system picker, tanpa READ_MEDIA_*. */
    fun isGranted(): Boolean = true

    fun isFullyGranted(): Boolean = true

    fun isPartialOnly(): Boolean = false
}

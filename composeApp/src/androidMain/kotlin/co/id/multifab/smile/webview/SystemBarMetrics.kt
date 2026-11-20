/**
 * Status: PENDUKUNG
 * Flow: Tinggi status bar untuk padding WebView.
 * Takeout: jangan.
 */
package co.id.multifab.smile.webview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources

object SystemBarMetrics {

    fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    fun statusBarPx(resources: Resources): Int = systemDimenPx(resources, "status_bar_height")

    fun navigationBarPx(resources: Resources): Int = systemDimenPx(resources, "navigation_bar_height")

    private fun systemDimenPx(resources: Resources, name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}

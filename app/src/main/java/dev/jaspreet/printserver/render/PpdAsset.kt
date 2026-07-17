package dev.jaspreet.printserver.render

import android.content.Context
import java.io.File

object PpdAsset {
    private const val ASSET = "ppd/hp_deskjet_2300_series.ppd"

    /** Extracts the bundled PPD to filesDir (idempotent) and returns its path. */
    fun extract(context: Context): File {
        val target = File(context.filesDir, "hp_deskjet_2300_series.ppd")
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(ASSET).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }
}

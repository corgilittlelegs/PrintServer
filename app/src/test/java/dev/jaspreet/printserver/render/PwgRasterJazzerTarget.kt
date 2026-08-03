package dev.jaspreet.printserver.render

import java.io.ByteArrayInputStream
import java.io.IOException

/** Standalone Jazzer-compatible entry point; intentionally has no JUnit dependency. */
object PwgRasterJazzerTarget {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        try {
            PwgRasterValidator().validate(ByteArrayInputStream(data))
        } catch (_: IOException) {
            // Invalid input is expected. Any unchecked exception is a fuzz finding.
        }
    }
}

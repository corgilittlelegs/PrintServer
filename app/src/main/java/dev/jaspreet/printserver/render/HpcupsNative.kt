package dev.jaspreet.printserver.render

object HpcupsNative {
    init { System.loadLibrary("hpcupsjni") }
    /** [options] is a CUPS-style options string, e.g. "ColorModel=RGB OutputMode=Normal".
     *  Returns 0 on success. Not thread-safe — serialize calls (JobQueue does). */
    external fun encode(
        rgb: ByteArray, width: Int, height: Int, dpi: Int,
        ppdPath: String, outPath: String, options: String,
    ): Int

    /** Encodes a client-supplied PWG/CUPS raster file to printer-ready PCL3-GUI.
     *  [options] is a CUPS-style options string, e.g. "ColorModel=KGray OutputMode=FastDraft". */
    external fun encodeRaster(inputPath: String, ppdPath: String, outPath: String, options: String): Int
}

package dev.jaspreet.printserver.jobs

/** Maps to the bundled PPD's OutputMode: DRAFT→FastDraft(300dpi), NORMAL→Normal(600dpi),
 *  HIGH→Best(600dpi). The PPD's fourth mode, Photo(1200dpi), has no standard IPP
 *  print-quality value to map from and is intentionally unreachable. */
enum class PrintQuality { DRAFT, NORMAL, HIGH }

/** Maps to the bundled PPD's ColorModel: COLOR→RGB, MONOCHROME→KGray. */
enum class ColorMode { COLOR, MONOCHROME }

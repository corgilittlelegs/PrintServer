package dev.jaspreet.printserver.scan

/** Maps to LEDM's ColorSpace field: COLOR->Color (Color8), GRAYSCALE->Gray (Gray8).
 *  LEDM's ce_element table also defines a K1 (1-bit black-and-white) mode, but
 *  bb_ledm.c's own job-creation code hardcodes BitDepth=8 on every branch of its
 *  ternary regardless of mode -- true 1-bit output isn't reachable through this
 *  protocol path, so it's intentionally not modeled here. */
enum class ScanColorMode { COLOR, GRAYSCALE }

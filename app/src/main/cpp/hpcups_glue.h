#ifndef HPCUPS_GLUE_H
#define HPCUPS_GLUE_H
/* Set by hpcupsjni.cpp before invoking hpcups_main. Defaults preserve
 * original CUPS-filter behavior (stdin/stdout). */
extern int g_hpcups_input_fd;
extern int g_hpcups_output_fd;
#endif

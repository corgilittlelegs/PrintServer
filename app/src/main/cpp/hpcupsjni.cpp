#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "cupsraster/cups/raster.h"
#include "hpcups_glue.h"

int g_hpcups_input_fd = 0;
int g_hpcups_output_fd = 1;

extern int hpcups_main(int argc, char *argv[]);

#define LOG_TAG "hpcupsjni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct RasterFeed {
    int fd;                 // write end of the pipe
    const unsigned char *rgb;
    unsigned width, height, dpi;
};

/* Writer thread: emits one CUPS-Raster v2 page (sRGB, 8bpc chunky) into the pipe. */
static void *feed_raster(void *arg) {
    RasterFeed *f = (RasterFeed *)arg;
    cups_raster_t *ras = cupsRasterOpen(f->fd, CUPS_RASTER_WRITE);
    if (ras) {
        cups_page_header2_t h;
        memset(&h, 0, sizeof(h));
        strcpy(h.MediaClass, "");
        h.HWResolution[0] = f->dpi;
        h.HWResolution[1] = f->dpi;
        h.cupsWidth = f->width;
        h.cupsHeight = f->height;
        h.cupsBitsPerColor = 8;
        h.cupsBitsPerPixel = 24;
        h.cupsBytesPerLine = f->width * 3;
        h.cupsColorOrder = CUPS_ORDER_CHUNKED;
        h.cupsColorSpace = CUPS_CSPACE_SRGB;
        h.cupsNumColors = 3;
        h.PageSize[0] = (unsigned)(f->width * 72 / f->dpi);
        h.PageSize[1] = (unsigned)(f->height * 72 / f->dpi);
        h.NumCopies = 1;
        if (cupsRasterWriteHeader2(ras, &h)) {
            for (unsigned y = 0; y < f->height; y++) {
                if (cupsRasterWritePixels(ras,
                        (unsigned char *)f->rgb + (size_t)y * h.cupsBytesPerLine,
                        h.cupsBytesPerLine) == 0) {
                    LOGE("raster write failed at row %u", y);
                    break;
                }
            }
        }
        cupsRasterClose(ras);
    }
    close(f->fd);
    return NULL;
}

/*
 * Encodes one RGB page to PCL3-GUI via hpcups.
 * Returns 0 on success, nonzero hpcups exit code / -1 on setup failure.
 * NOT thread-safe (globals + hpcups statics): callers must serialize.
 */
extern "C" JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_HpcupsNative_encode(
    JNIEnv *env, jobject thiz,
    jbyteArray jrgb, jint width, jint height, jint dpi,
    jstring jppdPath, jstring joutPath) {

    const char *ppd = env->GetStringUTFChars(jppdPath, NULL);
    const char *outPath = env->GetStringUTFChars(joutPath, NULL);
    jbyte *rgb = env->GetByteArrayElements(jrgb, NULL);
    int result = -1;

    int pipefd[2];
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (outFd >= 0 && pipe(pipefd) == 0) {
        setenv("PPD", ppd, 1);
        g_hpcups_input_fd = pipefd[0];
        g_hpcups_output_fd = outFd;

        RasterFeed feed = { pipefd[1], (const unsigned char *)rgb,
                            (unsigned)width, (unsigned)height, (unsigned)dpi };
        pthread_t writer;
        pthread_create(&writer, NULL, feed_raster, &feed);

        char *argv[] = { (char *)"hpcups", (char *)"1", (char *)"android",
                         (char *)"printserver", (char *)"1", (char *)"", NULL };
        result = hpcups_main(6, argv);

        pthread_join(writer, NULL);
        close(pipefd[0]);
    }
    if (outFd >= 0) close(outFd);

    env->ReleaseByteArrayElements(jrgb, rgb, JNI_ABORT);
    env->ReleaseStringUTFChars(jppdPath, ppd);
    env->ReleaseStringUTFChars(joutPath, outPath);
    return result;
}

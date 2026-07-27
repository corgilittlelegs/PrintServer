#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
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

/*
 * Stable negative setup-failure codes returned by encode()/encodeRaster().
 * hpcups itself only ever returns 0 (success) or a small positive exit
 * code, so these negative values can never collide with a real hpcups
 * result. Kotlin callers (Task 10) map these to user-facing messages —
 * keep the numbering stable once assigned; do not renumber existing
 * constants, only add new ones.
 */
#define HPCUPS_ERR_GENERIC        (-1)  // fallback / unexpected setup failure
#define HPCUPS_ERR_STRING_ALLOC   (-2)  // GetStringUTFChars returned NULL (OOM)
#define HPCUPS_ERR_ARRAY_ALLOC    (-3)  // GetByteArrayElements returned NULL (OOM)
#define HPCUPS_ERR_OPEN_OUTPUT    (-4)  // open() of the output file failed
#define HPCUPS_ERR_OPEN_INPUT     (-5)  // open() of the input file failed
#define HPCUPS_ERR_PIPE           (-6)  // pipe() failed
#define HPCUPS_ERR_THREAD_CREATE  (-7)  // pthread_create() failed

struct RasterFeed {
    int fd;                 // write end of the pipe
    const unsigned char *rgb;
    unsigned width, height, dpi;
};

static int run_hpcups(int inputFd, int outputFd, const char *ppd, const char *options) {
    setenv("PPD", ppd, 1);
    g_hpcups_input_fd = inputFd;
    g_hpcups_output_fd = outputFd;

    char *argv[] = { (char *)"hpcups", (char *)"1", (char *)"android",
                     (char *)"printserver", (char *)"1", (char *)options, NULL };
    return hpcups_main(6, argv);
}

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
 * Returns 0 on success, a positive hpcups exit code on hpcups failure, or
 * one of the HPCUPS_ERR_* negative constants above on setup failure.
 * NOT thread-safe (globals + hpcups statics): callers must serialize.
 */
extern "C" JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_HpcupsNative_encode(
    JNIEnv *env, jobject thiz,
    jbyteArray jrgb, jint width, jint height, jint dpi,
    jstring jppdPath, jstring joutPath, jstring joptions) {

    const char *ppd = env->GetStringUTFChars(jppdPath, NULL);
    const char *outPath = env->GetStringUTFChars(joutPath, NULL);
    const char *options = env->GetStringUTFChars(joptions, NULL);
    jbyte *rgb = NULL;
    int result = HPCUPS_ERR_GENERIC;

    if (ppd == NULL || outPath == NULL || options == NULL) {
        LOGE("GetStringUTFChars returned NULL (OOM) while acquiring encode() args");
        if (ppd != NULL) env->ReleaseStringUTFChars(jppdPath, ppd);
        if (outPath != NULL) env->ReleaseStringUTFChars(joutPath, outPath);
        if (options != NULL) env->ReleaseStringUTFChars(joptions, options);
        return HPCUPS_ERR_STRING_ALLOC;
    }

    rgb = env->GetByteArrayElements(jrgb, NULL);
    if (rgb == NULL) {
        LOGE("GetByteArrayElements returned NULL (OOM) for encode() rgb buffer");
        env->ReleaseStringUTFChars(jppdPath, ppd);
        env->ReleaseStringUTFChars(joutPath, outPath);
        env->ReleaseStringUTFChars(joptions, options);
        return HPCUPS_ERR_ARRAY_ALLOC;
    }

    int pipefd[2];
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (outFd < 0) {
        LOGE("open output file failed: path=%s errno=%d (%s)", outPath, errno, strerror(errno));
        result = HPCUPS_ERR_OPEN_OUTPUT;
    } else if (pipe(pipefd) != 0) {
        LOGE("pipe() failed: errno=%d (%s)", errno, strerror(errno));
        result = HPCUPS_ERR_PIPE;
    } else {
        RasterFeed feed = { pipefd[1], (const unsigned char *)rgb,
                            (unsigned)width, (unsigned)height, (unsigned)dpi };
        pthread_t writer;
        int rc = pthread_create(&writer, NULL, feed_raster, &feed);
        if (rc != 0) {
            LOGE("pthread_create() failed: rc=%d (%s)", rc, strerror(rc));
            close(pipefd[0]);
            close(pipefd[1]);
            result = HPCUPS_ERR_THREAD_CREATE;
        } else {
            result = run_hpcups(pipefd[0], outFd, ppd, options);
            pthread_join(writer, NULL);
            close(pipefd[0]);
            // pipefd[1] is closed by feed_raster() itself once it's done writing.
        }
    }
    if (outFd >= 0) close(outFd);

    env->ReleaseByteArrayElements(jrgb, rgb, JNI_ABORT);
    env->ReleaseStringUTFChars(jppdPath, ppd);
    env->ReleaseStringUTFChars(joutPath, outPath);
    env->ReleaseStringUTFChars(joptions, options);
    return result;
}

/*
 * Encodes a client-supplied PWG/CUPS/Apple raster file to PCL3-GUI via hpcups.
 * hpcups reads through CUPS' raster APIs, so CUPS_RASTER_READ handles the
 * supported raster stream variants and page count internally.
 * Returns 0 on success, a positive hpcups exit code on hpcups failure, or
 * one of the HPCUPS_ERR_* negative constants above on setup failure.
 */
extern "C" JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_HpcupsNative_encodeRaster(
    JNIEnv *env, jobject thiz,
    jstring jinputPath, jstring jppdPath, jstring joutPath, jstring joptions) {

    const char *inputPath = env->GetStringUTFChars(jinputPath, NULL);
    const char *ppd = env->GetStringUTFChars(jppdPath, NULL);
    const char *outPath = env->GetStringUTFChars(joutPath, NULL);
    const char *options = env->GetStringUTFChars(joptions, NULL);

    if (inputPath == NULL || ppd == NULL || outPath == NULL || options == NULL) {
        LOGE("GetStringUTFChars returned NULL (OOM) while acquiring encodeRaster() args");
        if (inputPath != NULL) env->ReleaseStringUTFChars(jinputPath, inputPath);
        if (ppd != NULL) env->ReleaseStringUTFChars(jppdPath, ppd);
        if (outPath != NULL) env->ReleaseStringUTFChars(joutPath, outPath);
        if (options != NULL) env->ReleaseStringUTFChars(joptions, options);
        return HPCUPS_ERR_STRING_ALLOC;
    }

    int result = HPCUPS_ERR_GENERIC;
    int inputFd = open(inputPath, O_RDONLY);
    if (inputFd < 0) {
        LOGE("open input raster failed: path=%s errno=%d (%s)", inputPath, errno, strerror(errno));
        result = HPCUPS_ERR_OPEN_INPUT;
    } else {
        int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (outFd < 0) {
            LOGE("open output file failed: path=%s errno=%d (%s)", outPath, errno, strerror(errno));
            result = HPCUPS_ERR_OPEN_OUTPUT;
        } else {
            result = run_hpcups(inputFd, outFd, ppd, options);
            close(outFd);
        }
        close(inputFd);
    }

    env->ReleaseStringUTFChars(jinputPath, inputPath);
    env->ReleaseStringUTFChars(jppdPath, ppd);
    env->ReleaseStringUTFChars(joutPath, outPath);
    env->ReleaseStringUTFChars(joptions, options);
    return result;
}

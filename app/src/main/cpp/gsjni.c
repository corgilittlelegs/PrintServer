#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "include/iapi.h"
#include "include/ierrors.h"

#define LOG_TAG "gsjni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Ghostscript's own diagnostic output (why a job failed, missing device,
 * bad PDF, etc.) normally goes to real stdout/stderr, which is invisible on
 * Android — nothing reads the process's stdio. Route it into logcat instead
 * so a gs_error_Fatal (-100) or similar isn't a silent, unexplained failure.
 */
static int gs_stdout_cb(void *caller_handle, const char *str, int len) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%.*s", len, str);
    return len;
}
static int gs_stderr_cb(void *caller_handle, const char *str, int len) {
    LOGE("%.*s", len, str);
    return len;
}

/*
 * Runs one Ghostscript invocation with the given argv.
 * Returns the gsapi error code (0 or gs_error_Quit on success).
 * Ghostscript is not reentrant: callers must serialize (JobQueue does).
 */
JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_GhostscriptNative_run(JNIEnv *env, jobject thiz, jobjectArray jargs) {
    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = calloc(argc, sizeof(char *));
    if (!argv) { LOGE("calloc failed for argc=%d", argc); return -100; }
    for (int i = 0; i < argc; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, js, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, js, c);
        (*env)->DeleteLocalRef(env, js);
    }

    void *instance = NULL;
    int code = gsapi_new_instance(&instance, NULL);
    if (code == 0) {
        gsapi_set_stdio(instance, NULL, gs_stdout_cb, gs_stderr_cb);
        code = gsapi_init_with_args(instance, argc, argv);
        if (code < 0 && code != gs_error_Quit) {
            LOGE("gsapi_init_with_args failed with code %d", code);
        }
        gsapi_exit(instance);
        gsapi_delete_instance(instance);
    } else {
        LOGE("gsapi_new_instance failed with code %d", code);
    }

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    if (code == gs_error_Quit) code = 0;
    return code;
}

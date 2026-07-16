#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "include/iapi.h"
#include "include/ierrors.h"

/*
 * Runs one Ghostscript invocation with the given argv.
 * Returns the gsapi error code (0 or gs_error_Quit on success).
 * Ghostscript is not reentrant: callers must serialize (JobQueue does).
 */
JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_GhostscriptNative_run(JNIEnv *env, jobject thiz, jobjectArray jargs) {
    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = calloc(argc, sizeof(char *));
    if (!argv) return -100;
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
        code = gsapi_init_with_args(instance, argc, argv);
        gsapi_exit(instance);
        gsapi_delete_instance(instance);
    }

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    if (code == gs_error_Quit) code = 0;
    return code;
}

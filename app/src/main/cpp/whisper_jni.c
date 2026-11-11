#include <jni.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static volatile int g_cancel = 0;

struct prog_ctx { JNIEnv *env; jclass clazz; jmethodID mid; };

static void progress_cb(struct whisper_context *ctx, struct whisper_state *state, int progress, void *user_data) {
    (void) ctx; (void) state;
    struct prog_ctx *p = (struct prog_ctx *) user_data;
    if (p && p->mid) (*p->env)->CallStaticVoidMethod(p->env, p->clazz, p->mid, (jint) progress);
}

static bool abort_cb(void *user_data) {
    (void) user_data;
    return g_cancel != 0;
}

JNIEXPORT jlong JNICALL
Java_com_whispertflite_cpp_WhisperCpp_initContext(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_whispertflite_cpp_WhisperCpp_freeContext(JNIEnv *env, jclass clazz, jlong ctx) {
    (void) env; (void) clazz;
    whisper_free((struct whisper_context *) ctx);
}

JNIEXPORT void JNICALL
Java_com_whispertflite_cpp_WhisperCpp_requestCancel(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    g_cancel = 1;
}

JNIEXPORT jint JNICALL
Java_com_whispertflite_cpp_WhisperCpp_transcribe(JNIEnv *env, jclass clazz, jlong ctxPtr, jint threads,
        jfloatArray audio, jstring lang, jboolean translate, jstring prompt) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ctxPtr;
    jfloat *data = (*env)->GetFloatArrayElements(env, audio, NULL);
    jsize len = (*env)->GetArrayLength(env, audio);
    const char *l = (*env)->GetStringUTFChars(env, lang, NULL);
    const char *p = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translate ? true : false;
    params.language = l;
    params.n_threads = threads;
    params.offset_ms = 0;
    params.no_context = false;
    params.single_segment = false;
    params.initial_prompt = p;
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = NULL;

    struct prog_ctx pc;
    pc.env = env;
    pc.clazz = clazz;
    pc.mid = (*env)->GetStaticMethodID(env, clazz, "onProgress", "(I)V");
    params.progress_callback = progress_cb;
    params.progress_callback_user_data = &pc;
    g_cancel = 0;
    whisper_reset_timings(ctx);
    LOGI("whisper_full start: %d samples, lang=%s", (int) len, l);
    int rc = whisper_full(ctx, params, data, len);
    LOGI("whisper_full done: rc=%d", rc);

    if (p) (*env)->ReleaseStringUTFChars(env, prompt, p);
    (*env)->ReleaseStringUTFChars(env, lang, l);
    (*env)->ReleaseFloatArrayElements(env, audio, data, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_whispertflite_cpp_WhisperCpp_segmentCount(JNIEnv *env, jclass clazz, jlong ctx) {
    (void) env; (void) clazz;
    return whisper_full_n_segments((struct whisper_context *) ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_whispertflite_cpp_WhisperCpp_segmentText(JNIEnv *env, jclass clazz, jlong ctx, jint i) {
    (void) clazz;
    const char *t = whisper_full_get_segment_text((struct whisper_context *) ctx, i);
    if (!t) t = "";
    jsize n = (jsize) strlen(t);
    jbyteArray arr = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, arr, 0, n, (const jbyte *) t);
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_whispertflite_cpp_WhisperCpp_segmentT0(JNIEnv *env, jclass clazz, jlong ctx, jint i) {
    (void) env; (void) clazz;
    return whisper_full_get_segment_t0((struct whisper_context *) ctx, i);
}

JNIEXPORT jlong JNICALL
Java_com_whispertflite_cpp_WhisperCpp_segmentT1(JNIEnv *env, jclass clazz, jlong ctx, jint i) {
    (void) env; (void) clazz;
    return whisper_full_get_segment_t1((struct whisper_context *) ctx, i);
}

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOG_TAG "androidspf_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef long long (*SpfStartFn)(int, char *, char *, char *);
typedef void (*SpfStopFn)(long long);
typedef char *(*SpfPollLogFn)(void);
typedef char *(*SpfParseSniFn)(char *, int);
typedef char *(*SpfVersionFn)(void);

static SpfStartFn fn_start = NULL;
static SpfStopFn fn_stop = NULL;
static SpfPollLogFn fn_poll = NULL;
static SpfParseSniFn fn_parse = NULL;
static SpfVersionFn fn_version = NULL;

static void *resolve_symbol(const char *name) {
    void *sym = dlsym(RTLD_DEFAULT, name);
    if (sym != NULL) return sym;
    void *handle = dlopen("libsnispf.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        LOGE("dlopen libsnispf.so failed: %s", dlerror());
        return NULL;
    }
    sym = dlsym(handle, name);
    if (sym == NULL) {
        LOGE("dlsym %s failed: %s", name, dlerror());
    }
    return sym;
}

static int ensure_loaded(void) {
    if (fn_start != NULL) return 1;
    fn_start = (SpfStartFn) resolve_symbol("SpfStart");
    fn_stop = (SpfStopFn) resolve_symbol("SpfStop");
    fn_poll = (SpfPollLogFn) resolve_symbol("SpfPollLog");
    fn_parse = (SpfParseSniFn) resolve_symbol("SpfParseSni");
    fn_version = (SpfVersionFn) resolve_symbol("SpfVersion");
    if (fn_start == NULL || fn_stop == NULL || fn_version == NULL) {
        LOGE("required native symbols missing");
        return 0;
    }
    return 1;
}

static char *jstring_to_utf(JNIEnv *env, jstring str) {
    if (str == NULL) return NULL;
    return (char *) (*env)->GetStringUTFChars(env, str, NULL);
}

static void release_utf(JNIEnv *env, jstring str, char *utf) {
    if (str != NULL && utf != NULL) {
        (*env)->ReleaseStringUTFChars(env, str, utf);
    }
}

JNIEXPORT jlong JNICALL
Java_com_rstagit_androidspf_core_protocol_GoNativeBridge_spfStart(
        JNIEnv *env, jclass clazz, jint listenPort,
        jstring remoteEndpoint, jstring fakeSni, jstring method) {
    if (!ensure_loaded()) return 0;
    char *remote = jstring_to_utf(env, remoteEndpoint);
    char *sni = jstring_to_utf(env, fakeSni);
    char *meth = jstring_to_utf(env, method);
    long long id = fn_start(listenPort, remote, sni, meth);
    release_utf(env, remoteEndpoint, remote);
    release_utf(env, fakeSni, sni);
    release_utf(env, method, meth);
    return (jlong) id;
}

JNIEXPORT void JNICALL
Java_com_rstagit_androidspf_core_protocol_GoNativeBridge_spfStop(
        JNIEnv *env, jclass clazz, jlong sessionId) {
    if (!ensure_loaded()) return;
    fn_stop((long long) sessionId);
}

JNIEXPORT jstring JNICALL
Java_com_rstagit_androidspf_core_protocol_GoNativeBridge_spfPollLog(
        JNIEnv *env, jclass clazz) {
    if (!ensure_loaded() || fn_poll == NULL) return NULL;
    char *msg = fn_poll();
    if (msg == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, msg);
    free(msg);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_rstagit_androidspf_core_protocol_GoNativeBridge_spfParseSni(
        JNIEnv *env, jclass clazz, jbyteArray data, jint length) {
    if (!ensure_loaded() || fn_parse == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    if (data == NULL || length <= 0) {
        return (*env)->NewStringUTF(env, "");
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    char *sni = fn_parse((char *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    jstring result = (*env)->NewStringUTF(env, sni != NULL ? sni : "");
    if (sni != NULL) free(sni);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_rstagit_androidspf_core_protocol_GoNativeBridge_spfVersion(
        JNIEnv *env, jclass clazz) {
    if (!ensure_loaded()) return (*env)->NewStringUTF(env, "unknown");
    char *ver = fn_version();
    jstring result = (*env)->NewStringUTF(env, ver != NULL ? ver : "unknown");
    if (ver != NULL) free(ver);
    return result;
}

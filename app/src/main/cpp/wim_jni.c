#include <jni.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <wimlib.h>

static pthread_once_t initialization = PTHREAD_ONCE_INIT;
static int initialization_error;

static void initialize_wim(void)
{
    initialization_error = wimlib_global_init(WIMLIB_INIT_FLAG_ASSUME_UTF8);
}

static void throw_error(JNIEnv *env, const char *message)
{
    if (!(*env)->ExceptionCheck(env)) {
        jclass type = (*env)->FindClass(env, "java/io/IOException");
        if (type) {
            (*env)->ThrowNew(env, type, message);
            (*env)->DeleteLocalRef(env, type);
        }
    }
}

JNIEXPORT jobjectArray JNICALL Java_pl_rufusportable_WimMetadata_inspect(
        JNIEnv *env, jclass type, jstring filename)
{
    (void)type;
    pthread_once(&initialization, initialize_wim);
    if (initialization_error) {
        throw_error(env, wimlib_get_error_string(initialization_error));
        return NULL;
    }
    if (!filename) {
        throw_error(env, "Missing WIM source");
        return NULL;
    }
    const char *path = (*env)->GetStringUTFChars(env, filename, NULL);
    if (!path)
        return NULL;
    WIMStruct *wim = NULL;
    int result = wimlib_open_wim(path, 0, &wim);
    (*env)->ReleaseStringUTFChars(env, filename, path);
    if (result) {
        throw_error(env, wimlib_get_error_string(result));
        return NULL;
    }
    struct wimlib_wim_info info;
    result = wimlib_get_wim_info(wim, &info);
    if (result || info.image_count > INT_MAX / 5) {
        throw_error(env, result ? wimlib_get_error_string(result) : "Too many WIM images");
        wimlib_free(wim);
        return NULL;
    }
    jclass byte_array = (*env)->FindClass(env, "[B");
    jobjectArray values = byte_array ?
            (*env)->NewObjectArray(env, info.image_count * 5, byte_array, NULL) : NULL;
    if (byte_array)
        (*env)->DeleteLocalRef(env, byte_array);
    if (values) {
        for (uint32_t index = 1; index <= info.image_count; index++) {
            const char *properties[] = {
                wimlib_get_image_name(wim, index),
                wimlib_get_image_description(wim, index),
                wimlib_get_image_property(wim, index, "WINDOWS/ARCH"),
                wimlib_get_image_property(wim, index, "WINDOWS/EDITIONID"),
                wimlib_get_image_property(wim, index, "WINDOWS/VERSION/BUILD")
            };
            for (int field = 0; field < 5; field++) {
                const char *text = properties[field] ? properties[field] : "";
                size_t length = strlen(text);
                if (length > INT_MAX) {
                    throw_error(env, "WIM property is too large");
                    break;
                }
                jbyteArray bytes = (*env)->NewByteArray(env, length);
                if (!bytes)
                    break;
                (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)text);
                if (!(*env)->ExceptionCheck(env))
                    (*env)->SetObjectArrayElement(env, values, (index - 1) * 5 + field, bytes);
                (*env)->DeleteLocalRef(env, bytes);
                if ((*env)->ExceptionCheck(env))
                    break;
            }
            if ((*env)->ExceptionCheck(env))
                break;
        }
    }
    wimlib_free(wim);
    return values;
}

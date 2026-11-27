#ifndef RUFUS_JNI_DISK_H
#define RUFUS_JNI_DISK_H

#include <jni.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include "ntfs_bridge.h"

typedef struct {
    JavaVM *vm;
    jobject device;
    jmethodID read, write, sync;
    rufus_disk disk;
    rufus_ntfs *volume;
} java_volume;

static JNIEnv *environment(java_volume *volume)
{
    JNIEnv *env = NULL;
    if ((*volume->vm)->GetEnv(volume->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        return NULL;
    return env;
}

static void io_error(JNIEnv *env)
{
    if (!(*env)->ExceptionCheck(env)) {
        jclass type = (*env)->FindClass(env, "java/io/IOException");
        if (type) {
            (*env)->ThrowNew(env, type, strerror(errno ? errno : EIO));
            (*env)->DeleteLocalRef(env, type);
        }
    }
}

static int read_device(void *context, int64_t offset, void *data, size_t length)
{
    java_volume *volume = context;
    JNIEnv *env = environment(volume);
    if (!env || (*env)->ExceptionCheck(env))
        return -1;
    jbyteArray buffer = (*env)->NewByteArray(env, (jsize)length);
    if (!buffer)
        return -1;
    (*env)->CallVoidMethod(env, volume->device, volume->read, (jlong)offset, buffer);
    if (!(*env)->ExceptionCheck(env))
        (*env)->GetByteArrayRegion(env, buffer, 0, (jsize)length, data);
    (*env)->DeleteLocalRef(env, buffer);
    return (*env)->ExceptionCheck(env) ? -1 : 0;
}

static int write_device(void *context, int64_t offset, const void *data, size_t length)
{
    java_volume *volume = context;
    JNIEnv *env = environment(volume);
    if (!env || (*env)->ExceptionCheck(env))
        return -1;
    jbyteArray buffer = (*env)->NewByteArray(env, (jsize)length);
    if (!buffer)
        return -1;
    (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)length, data);
    if (!(*env)->ExceptionCheck(env))
        (*env)->CallVoidMethod(env, volume->device, volume->write, (jlong)offset, buffer);
    (*env)->DeleteLocalRef(env, buffer);
    return (*env)->ExceptionCheck(env) ? -1 : 0;
}

static int sync_device(void *context)
{
    java_volume *volume = context;
    JNIEnv *env = environment(volume);
    if (!env || (*env)->ExceptionCheck(env))
        return -1;
    (*env)->CallVoidMethod(env, volume->device, volume->sync);
    return (*env)->ExceptionCheck(env) ? -1 : 0;
}

static void release_volume(JNIEnv *env, java_volume *volume)
{
    if (volume->device)
        (*env)->DeleteGlobalRef(env, volume->device);
    free(volume);
}

static java_volume *connect_device(JNIEnv *env, jobject device, jlong size, jint sector_size)
{
    if (!device) {
        errno = EINVAL;
        io_error(env);
        return NULL;
    }
    java_volume *volume = calloc(1, sizeof(*volume));
    if (!volume) {
        io_error(env);
        return NULL;
    }
    (*env)->GetJavaVM(env, &volume->vm);
    volume->device = (*env)->NewGlobalRef(env, device);
    if (!volume->device) {
        release_volume(env, volume);
        return NULL;
    }
    jclass device_type = (*env)->GetObjectClass(env, device);
    if (device_type && volume->device) {
        volume->read = (*env)->GetMethodID(env, device_type, "read", "(J[B)V");
        if (!(*env)->ExceptionCheck(env))
            volume->write = (*env)->GetMethodID(env, device_type, "write", "(J[B)V");
        if (!(*env)->ExceptionCheck(env))
            volume->sync = (*env)->GetMethodID(env, device_type, "sync", "()V");
    }
    if (device_type)
        (*env)->DeleteLocalRef(env, device_type);
    if ((*env)->ExceptionCheck(env) || !volume->device) {
        release_volume(env, volume);
        return NULL;
    }
    volume->disk = (rufus_disk) {
        .context = volume, .length = size, .sector_size = sector_size,
        .read = read_device, .write = write_device, .sync = sync_device
    };
    return volume;
}

#endif

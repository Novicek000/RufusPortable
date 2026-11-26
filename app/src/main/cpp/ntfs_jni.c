#include <jni.h>
#include <errno.h>
#include <stdint.h>
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

JNIEXPORT jlong JNICALL Java_pl_rufusportable_NtfsVolume_mount(
        JNIEnv *env, jclass type, jobject device, jlong size, jint sector_size)
{
    (void)type;
    java_volume *volume = connect_device(env, device, size, sector_size);
    if (!volume)
        return 0;
    volume->volume = rufus_ntfs_mount(&volume->disk);
    if (!volume->volume) {
        io_error(env);
        release_volume(env, volume);
        return 0;
    }
    return (jlong)(intptr_t)volume;
}

JNIEXPORT void JNICALL Java_pl_rufusportable_NtfsVolume_format(
        JNIEnv *env, jclass type, jobject device, jlong size, jint sector_size,
        jbyteArray label, jint cluster_size, jlong partition_start, jboolean quick)
{
    (void)type;
    if (!label) {
        errno = EINVAL;
        io_error(env);
        return;
    }
    jsize length = (*env)->GetArrayLength(env, label);
    if (length > 128) {
        errno = EINVAL;
        io_error(env);
        return;
    }
    char text[129];
    (*env)->GetByteArrayRegion(env, label, 0, length, (jbyte *)text);
    if ((*env)->ExceptionCheck(env))
        return;
    if (memchr(text, 0, length)) {
        errno = EINVAL;
        io_error(env);
        return;
    }
    text[length] = 0;
    java_volume *volume = connect_device(env, device, size, sector_size);
    if (!volume)
        return;
    int result = rufus_ntfs_format(&volume->disk, text, cluster_size, partition_start, quick);
    if (result != 0)
        io_error(env);
    release_volume(env, volume);
}

JNIEXPORT void JNICALL Java_pl_rufusportable_NtfsVolume_mkdir(
        JNIEnv *env, jclass type, jlong handle, jstring path)
{
    (void)type;
    java_volume *volume = (java_volume *)(intptr_t)handle;
    if (!volume || !path) {
        errno = EINVAL;
        io_error(env);
        return;
    }
    const jchar *name = (*env)->GetStringChars(env, path, NULL);
    if (!name)
        return;
    int result = rufus_ntfs_mkdir(volume->volume, name, (*env)->GetStringLength(env, path));
    (*env)->ReleaseStringChars(env, path, name);
    if (result != 0)
        io_error(env);
}

JNIEXPORT jlong JNICALL Java_pl_rufusportable_NtfsVolume_create(
        JNIEnv *env, jclass type, jlong handle, jstring path)
{
    (void)type;
    java_volume *volume = (java_volume *)(intptr_t)handle;
    if (!volume || !path) {
        errno = EINVAL;
        io_error(env);
        return 0;
    }
    const jchar *name = (*env)->GetStringChars(env, path, NULL);
    if (!name)
        return 0;
    rufus_ntfs_file *file = rufus_ntfs_create(volume->volume, name, (*env)->GetStringLength(env, path));
    (*env)->ReleaseStringChars(env, path, name);
    if (!file)
        io_error(env);
    return (jlong)(intptr_t)file;
}

JNIEXPORT jlong JNICALL Java_pl_rufusportable_NtfsVolume_openRead(
        JNIEnv *env, jclass type, jlong handle, jstring path)
{
    (void)type;
    java_volume *volume = (java_volume *)(intptr_t)handle;
    if (!volume || !path) {
        errno = EINVAL;
        io_error(env);
        return 0;
    }
    const jchar *name = (*env)->GetStringChars(env, path, NULL);
    if (!name)
        return 0;
    rufus_ntfs_file *file = rufus_ntfs_open(volume->volume, name, (*env)->GetStringLength(env, path));
    (*env)->ReleaseStringChars(env, path, name);
    if (!file)
        io_error(env);
    return (jlong)(intptr_t)file;
}

JNIEXPORT jint JNICALL Java_pl_rufusportable_NtfsVolume_read(
        JNIEnv *env, jclass type, jlong handle, jlong offset, jbyteArray data, jint start, jint length)
{
    (void)type;
    if (!handle || !data || start < 0 || length < 0 || length > 131072 ||
            start > (*env)->GetArrayLength(env, data) - length) {
        errno = EINVAL;
        io_error(env);
        return -1;
    }
    unsigned char *buffer = malloc(length ? (size_t)length : 1);
    if (!buffer) {
        io_error(env);
        return -1;
    }
    int64_t result = rufus_ntfs_read((rufus_ntfs_file *)(intptr_t)handle, offset, buffer, length);
    if (result < 0) {
        io_error(env);
    } else if (!(*env)->ExceptionCheck(env)) {
        (*env)->SetByteArrayRegion(env, data, start, result, (jbyte *)buffer);
    }
    free(buffer);
    return result;
}

JNIEXPORT void JNICALL Java_pl_rufusportable_NtfsVolume_write(
        JNIEnv *env, jclass type, jlong handle, jlong offset, jbyteArray data, jint start, jint length)
{
    (void)type;
    if (!handle || !data || start < 0 || length < 0 || length > 131072 ||
            start > (*env)->GetArrayLength(env, data) - length) {
        errno = EINVAL;
        io_error(env);
        return;
    }
    unsigned char *buffer = malloc(length ? (size_t)length : 1);
    if (!buffer) {
        io_error(env);
        return;
    }
    (*env)->GetByteArrayRegion(env, data, start, length, (jbyte *)buffer);
    if (!(*env)->ExceptionCheck(env) &&
            rufus_ntfs_write((rufus_ntfs_file *)(intptr_t)handle, offset, buffer, length) != 0)
        io_error(env);
    free(buffer);
}

JNIEXPORT void JNICALL Java_pl_rufusportable_NtfsVolume_closeFile(
        JNIEnv *env, jclass type, jlong handle)
{
    (void)type;
    if (rufus_ntfs_close_file((rufus_ntfs_file *)(intptr_t)handle) != 0)
        io_error(env);
}

JNIEXPORT void JNICALL Java_pl_rufusportable_NtfsVolume_unmount(
        JNIEnv *env, jclass type, jlong handle)
{
    (void)type;
    java_volume *volume = (java_volume *)(intptr_t)handle;
    if (!volume) {
        errno = EINVAL;
        io_error(env);
        return;
    }
    int result = rufus_ntfs_unmount(volume->volume);
    if (result != 0)
        io_error(env);
    release_volume(env, volume);
}

#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "ntfs_bridge.h"

#include "jni_disk.h"

typedef struct {uint16_t *name;size_t length;int64_t size;int directory,reparse;} list_item;
typedef struct {list_item *items;size_t count,capacity;} list_result;
static int collect_entry(void *context,const uint16_t *name,size_t length,int64_t size,int directory,int reparse)
{
    list_result *list=context;
    if(list->count==list->capacity) {
        size_t capacity=list->capacity?list->capacity*2:32;
        if(capacity>1048576){errno=EOVERFLOW;return -1;}
        list_item *items=realloc(list->items,capacity*sizeof(*items));if(!items)return -1;
        list->items=items;list->capacity=capacity;
    }
    uint16_t *copy=malloc(length*sizeof(*copy));if(!copy)return -1;memcpy(copy,name,length*sizeof(*copy));
    list->items[list->count++]=(list_item){copy,length,size,directory,reparse};return 0;
}
JNIEXPORT jobjectArray JNICALL Java_pl_rufusportable_NtfsVolume_listEntries(JNIEnv *env,jclass type,jlong handle,jstring path)
{
    (void)type;java_volume *volume=(java_volume *)(intptr_t)handle;
    if(!volume || !path){errno=EINVAL;io_error(env);return NULL;}
    const jchar *name=(*env)->GetStringChars(env,path,NULL);if(!name)return NULL;
    list_result list={0};jobjectArray result=NULL;
    int status=rufus_ntfs_list(volume->volume,name,(*env)->GetStringLength(env,path),&list,collect_entry),saved=errno;
    (*env)->ReleaseStringChars(env,path,name);
    if(status){errno=saved;io_error(env);goto done;}
    jclass entry=(*env)->FindClass(env,"pl/rufusportable/NtfsVolume$Entry");if(!entry)goto done;
    jmethodID constructor=(*env)->GetMethodID(env,entry,"<init>","(Ljava/lang/String;JZZ)V");
    if(constructor)result=(*env)->NewObjectArray(env,(jsize)list.count,entry,NULL);
    if(result)for(size_t i=0;i<list.count;i++) {
        list_item *item=&list.items[i];jstring text=(*env)->NewString(env,item->name,(jsize)item->length);if(!text)break;
        jobject value=(*env)->NewObject(env,entry,constructor,text,(jlong)item->size,(jboolean)item->directory,(jboolean)item->reparse);
        (*env)->DeleteLocalRef(env,text);
        if(value){(*env)->SetObjectArrayElement(env,result,(jsize)i,value);(*env)->DeleteLocalRef(env,value);}
        if((*env)->ExceptionCheck(env))break;
    }
    (*env)->DeleteLocalRef(env,entry);
done:
    for(size_t i=0;i<list.count;i++)free(list.items[i].name);
    free(list.items);return result;
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

JNIEXPORT jlong JNICALL Java_pl_rufusportable_NtfsVolume_openUpdate(
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
    rufus_ntfs_file *file = rufus_ntfs_open_update(volume->volume, name, (*env)->GetStringLength(env, path));
    (*env)->ReleaseStringChars(env, path, name);
    if (!file)
        io_error(env);
    return (jlong)(intptr_t)file;
}

JNIEXPORT jlong JNICALL Java_pl_rufusportable_NtfsVolume_fileSize(
        JNIEnv *env, jclass type, jlong handle)
{
    (void)type;
    int64_t length = rufus_ntfs_file_size((rufus_ntfs_file *)(intptr_t)handle);
    if (length < 0) io_error(env);
    return length;
}

JNIEXPORT void JNICALL Java_pl_rufusportable_NtfsVolume_truncate(
        JNIEnv *env, jclass type, jlong handle, jlong length)
{
    (void)type;
    if (rufus_ntfs_truncate((rufus_ntfs_file *)(intptr_t)handle, length) != 0) io_error(env);
}

JNIEXPORT void JNICALL Java_pl_rufusportable_NtfsVolume_syncFile(
        JNIEnv *env, jclass type, jlong handle)
{
    (void)type;
    if (rufus_ntfs_sync_file((rufus_ntfs_file *)(intptr_t)handle) != 0) io_error(env);
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

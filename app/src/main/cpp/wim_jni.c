#include "config.h"
#include <jni.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <wimlib.h>
#include "wimlib/wim.h"
#include "unattend.h"

#define MAX_METADATA_FIELDS 32
_Static_assert(sizeof(off_t)==8,"WIM JNI requires 64-bit file offsets");

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
        JNIEnv *env, jclass type, jstring filename, jobjectArray keys)
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
    jobjectArray values=NULL;
    jsize fields=keys?(*env)->GetArrayLength(env,keys):0;
    jstring key_objects[MAX_METADATA_FIELDS]={0};
    const char *properties[MAX_METADATA_FIELDS]={0};
    if(fields<1 || fields>MAX_METADATA_FIELDS) {
        throw_error(env,"Invalid WIM metadata properties");
        goto metadata_done;
    }
    for(jsize field=0;field<fields;field++) {
        key_objects[field]=(*env)->GetObjectArrayElement(env,keys,field);
        if(!key_objects[field]) {throw_error(env,"Missing WIM property key");goto metadata_done;}
        properties[field]=(*env)->GetStringUTFChars(env,key_objects[field],NULL);
        if(!properties[field])goto metadata_done;
    }
    struct wimlib_wim_info info;
    result = wimlib_get_wim_info(wim, &info);
    if (result || info.image_count > (uint32_t)(INT_MAX / fields)) {
        throw_error(env, result ? wimlib_get_error_string(result) : "Too many WIM images");
        goto metadata_done;
    }
    jclass byte_array = (*env)->FindClass(env, "[B");
    values = byte_array ? (*env)->NewObjectArray(env, info.image_count * fields, byte_array, NULL) : NULL;
    if (byte_array) (*env)->DeleteLocalRef(env, byte_array);
    if (values) {
        for (uint32_t index = 1; index <= info.image_count; index++) {
            for (jsize field = 0; field < fields; field++) {
                const char *text=wimlib_get_image_property(wim,index,properties[field]);
                if(!text)text="";
                size_t length = strlen(text);
                if (length > INT_MAX) {throw_error(env, "WIM property is too large");break;}
                jbyteArray bytes = (*env)->NewByteArray(env, length);
                if (!bytes) break;
                (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)text);
                if (!(*env)->ExceptionCheck(env))
                    (*env)->SetObjectArrayElement(env, values, (index - 1) * fields + field, bytes);
                (*env)->DeleteLocalRef(env, bytes);
                if ((*env)->ExceptionCheck(env)) break;
            }
            if ((*env)->ExceptionCheck(env)) break;
        }
    }
metadata_done:
    for(jsize field=0;field<MAX_METADATA_FIELDS;field++) {
        if(properties[field])(*env)->ReleaseStringUTFChars(env,key_objects[field],properties[field]);
        if(key_objects[field])(*env)->DeleteLocalRef(env,key_objects[field]);
    }
    wimlib_free(wim);
    return values;
}

static char *utf8_path(JNIEnv *env, jbyteArray array)
{
    if (!array) {throw_error(env,"Missing WIM path");return NULL;}
    jsize length=(*env)->GetArrayLength(env,array);
    char *path=malloc((size_t)length+1);
    if (!path) {throw_error(env,"Cannot allocate WIM path");return NULL;}
    (*env)->GetByteArrayRegion(env,array,0,length,(jbyte *)path);
    if ((*env)->ExceptionCheck(env)) {free(path);return NULL;}
    if (memchr(path,0,length)) {free(path);throw_error(env,"NUL in WIM path");return NULL;}
    path[length]=0;
    return path;
}

JNIEXPORT jlong JNICALL Java_pl_rufusportable_WimMetadata_update(
        JNIEnv *env,jclass type,jstring filename,jint image,jbyteArray source,jbyteArray target)
{
    (void)type;
    pthread_once(&initialization,initialize_wim);
    if(initialization_error) {throw_error(env,wimlib_get_error_string(initialization_error));return -1;}
    if(!filename || image<1) {throw_error(env,"Invalid WIM update");return -1;}
    char *source_path=utf8_path(env,source);
    if(!source_path) return -1;
    char *target_path=utf8_path(env,target);
    if(!target_path) {free(source_path);return -1;}
    const char *path=(*env)->GetStringUTFChars(env,filename,NULL);
    WIMStruct *wim=NULL;
    int result=0;
    jlong final_size=-1;
    if(path) {
        result=wimlib_open_wim(path,WIMLIB_OPEN_FLAG_WRITE_ACCESS,&wim);
        (*env)->ReleaseStringUTFChars(env,filename,path);
        if(!result) {
            struct wimlib_update_command command={0};
            command.op=WIMLIB_UPDATE_OP_ADD;
            command.add.fs_source_path=source_path;
            command.add.wim_target_path=target_path;
            result=wimlib_update_image(wim,image,&command,1,0);
            if(!result) result=wimlib_overwrite(wim,WIMLIB_WRITE_FLAG_FSYNC|WIMLIB_WRITE_FLAG_UNSAFE_COMPACT,1);
            if(!result) final_size=wim->out_fd.offset;
        }
    }
    wimlib_free(wim);
    free(source_path);free(target_path);
    if(result) throw_error(env,wimlib_get_error_string(result));
    return final_size;
}

JNIEXPORT void JNICALL Java_pl_rufusportable_WimMetadata_extract(
        JNIEnv *env,jclass type,jstring filename,jint image,jbyteArray target,jbyteArray directory)
{
    (void)type;
    pthread_once(&initialization,initialize_wim);
    if(initialization_error) {throw_error(env,wimlib_get_error_string(initialization_error));return;}
    if(!filename || image<1) {throw_error(env,"Invalid WIM extraction");return;}
    char *target_path=utf8_path(env,target);
    if(!target_path) return;
    char *destination=utf8_path(env,directory);
    if(!destination) {free(target_path);return;}
    const char *path=(*env)->GetStringUTFChars(env,filename,NULL);
    WIMStruct *wim=NULL;
    int result=0;
    if(path) {
        result=wimlib_open_wim(path,0,&wim);
        (*env)->ReleaseStringUTFChars(env,filename,path);
        if(!result) {
            const char *paths[]={target_path};
            result=wimlib_extract_paths(wim,image,destination,paths,1,
                    WIMLIB_EXTRACT_FLAG_NO_PRESERVE_DIR_STRUCTURE|WIMLIB_EXTRACT_FLAG_NO_ACLS);
        }
    }
    wimlib_free(wim);
    free(target_path);free(destination);
    if(result) throw_error(env,wimlib_get_error_string(result));
}

JNIEXPORT jbyteArray JNICALL Java_pl_rufusportable_WindowsUnattend_generate(
        JNIEnv *env,jclass type,jint architecture,jint flags,jint edition,jobjectArray values)
{
    (void)type;
    if(!values || (*env)->GetArrayLength(env,values)!=8 || architecture<1 || architecture>4 ||
            edition<1 || flags==0 || (flags&~0x11fff)) {
        throw_error(env,"Invalid Windows answer-file parameters");return NULL;
    }
    char *fields[8]={0};
    jbyteArray result=NULL;
    for(int i=0;i<8;i++) {
        jbyteArray value=(*env)->GetObjectArrayElement(env,values,i);
        fields[i]=utf8_path(env,value);
        if(value)(*env)->DeleteLocalRef(env,value);
        if(!fields[i])goto out;
    }
    rufus_unattend_options options={edition,fields[0],fields[1],fields[2],fields[3],fields[4],fields[5],fields[6],fields[7]};
    size_t length=0;
    char *xml=rufus_unattend_xml(architecture,flags,&options,&length);
    if(!xml) {throw_error(env,"Cannot generate Windows answer file");goto out;}
    if(length>INT_MAX) {throw_error(env,"Windows answer file too large");free(xml);goto out;}
    result=(*env)->NewByteArray(env,(jsize)length);
    if(result)(*env)->SetByteArrayRegion(env,result,0,(jsize)length,(jbyte *)xml);
    free(xml);
out:
    for(int i=0;i<8;i++)free(fields[i]);
    return result;
}

JNIEXPORT void JNICALL Java_pl_rufusportable_WimMetadata_verifyWim(
        JNIEnv *env,jclass type,jstring filename)
{
    (void)type;
    pthread_once(&initialization,initialize_wim);
    if(initialization_error) {throw_error(env,wimlib_get_error_string(initialization_error));return;}
    if(!filename) {throw_error(env,"Missing WIM source");return;}
    const char *path=(*env)->GetStringUTFChars(env,filename,NULL);
    if(!path)return;
    WIMStruct *wim=NULL;
    int result=wimlib_open_wim(path,0,&wim);
    (*env)->ReleaseStringUTFChars(env,filename,path);
    if(!result)result=wimlib_verify_wim(wim,0);
    wimlib_free(wim);
    if(result)throw_error(env,wimlib_get_error_string(result));
}

JNIEXPORT void JNICALL Java_pl_rufusportable_WimMetadata_requireEditable(
        JNIEnv *env,jclass type,jstring filename)
{
    (void)type;
    pthread_once(&initialization,initialize_wim);
    if(initialization_error) {throw_error(env,wimlib_get_error_string(initialization_error));return;}
    if(!filename) {throw_error(env,"Missing WIM source");return;}
    const char *path=(*env)->GetStringUTFChars(env,filename,NULL);
    if(!path)return;
    WIMStruct *wim=NULL;
    int result=wimlib_open_wim(path,0,&wim);
    (*env)->ReleaseStringUTFChars(env,filename,path);
    if(!result) {
        struct wimlib_wim_info info;
        result=wimlib_get_wim_info(wim,&info);
        if(!result && (info.pipable || info.total_parts!=1 || info.is_marked_readonly || info.write_in_progress))
            throw_error(env,"Ten boot.wim nie pozwala na bezpośrednią personalizację. Wybierz standardową instalację bez zmian WIM.");
    }
    wimlib_free(wim);
    if(result)throw_error(env,wimlib_get_error_string(result));
}

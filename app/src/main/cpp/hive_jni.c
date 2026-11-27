#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include "hivex.h"
_Static_assert(sizeof(off_t)==8,"Hive JNI requires 64-bit file offsets");
static void error(JNIEnv *env)
{
    if(!(*env)->ExceptionCheck(env)) {
        jclass type=(*env)->FindClass(env,"java/io/IOException");
        if(type){(*env)->ThrowNew(env,type,strerror(errno?errno:EIO));(*env)->DeleteLocalRef(env,type);}
    }
}
static char *text(JNIEnv *env,jbyteArray array)
{
    if(!array){errno=EINVAL;error(env);return NULL;}
    jsize length=(*env)->GetArrayLength(env,array);char *value=malloc((size_t)length+1);
    if(!value){error(env);return NULL;}
    (*env)->GetByteArrayRegion(env,array,0,length,(jbyte *)value);
    if((*env)->ExceptionCheck(env)){free(value);return NULL;}
    if(memchr(value,0,length)){free(value);errno=EINVAL;error(env);return NULL;}
    value[length]=0;return value;
}
static hive_node_h node(JNIEnv *env,hive_h *hive,char *path,int create)
{
    hive_node_h current=hivex_root(hive);char *state=NULL;
    if(!current){error(env);return 0;}
    for(char *part=strtok_r(path,"\\",&state);part;part=strtok_r(NULL,"\\",&state)) {
        errno=0;hive_node_h child=hivex_node_get_child(hive,current,part);
        if(!child && errno){error(env);return 0;}
        if(!child && create) {
            /* hivex 1.3.24 hashes new lh names as bytes. Rufus configuration paths
             * are ASCII; retain existing Unicode nodes but do not create bad hashes. */
            for(const unsigned char *c=(const unsigned char *)part;*c;c++)if(*c>=128){errno=ENOTSUP;error(env);return 0;}
            child=hivex_node_add_child(hive,current,part);if(!child)error(env);
        }
        if(!child)return 0;
        current=child;
    }
    return current;
}
static jbyteArray bytes(JNIEnv *env,const void *data,size_t length)
{
    if(length>INT32_MAX){errno=EOVERFLOW;error(env);return NULL;}
    jbyteArray value=(*env)->NewByteArray(env,(jsize)length);
    if(value && length)(*env)->SetByteArrayRegion(env,value,0,(jsize)length,data);
    return value;
}
JNIEXPORT jlong JNICALL Java_pl_rufusportable_RegistryHive_open(JNIEnv *env,jclass type,jbyteArray filename,jboolean writable)
{
    (void)type;char *path=text(env,filename);if(!path)return 0;
    hive_h *hive=hivex_open(path,writable?HIVEX_OPEN_WRITE:0);free(path);
    if(!hive)error(env);
    return (jlong)(intptr_t)hive;
}
JNIEXPORT jobjectArray JNICALL Java_pl_rufusportable_RegistryHive_read(JNIEnv *env,jclass type,jlong handle,jbyteArray path,jbyteArray name)
{
    (void)type;hive_h *hive=(hive_h *)(intptr_t)handle;char *key=text(env,path),*value_name=NULL;char *data=NULL;jobjectArray result=NULL;
    if(!key)goto done;
    value_name=text(env,name);if(!value_name)goto done;
    hive_node_h key_node=node(env,hive,key,0);if(!key_node)goto done;
    errno=0;hive_value_h value=hivex_node_get_value(hive,key_node,value_name);
    if(!value){if(errno)error(env);goto done;}
    hive_type value_type;size_t length;
    data=hivex_value_value(hive,value,&value_type,&length);if(!data){error(env);goto done;}
    unsigned char encoded_type[4];for(int i=0;i<4;i++)encoded_type[i]=((uint32_t)value_type>>(8*i))&255;
    jclass array_type=(*env)->FindClass(env,"[B");
    if(array_type){result=(*env)->NewObjectArray(env,2,array_type,NULL);(*env)->DeleteLocalRef(env,array_type);}
    if(result) {
        jbyteArray field=bytes(env,encoded_type,4);
        if(field){(*env)->SetObjectArrayElement(env,result,0,field);(*env)->DeleteLocalRef(env,field);}
        if(!(*env)->ExceptionCheck(env)) {
            field=bytes(env,data,length);
            if(field){(*env)->SetObjectArrayElement(env,result,1,field);(*env)->DeleteLocalRef(env,field);}
        }
    }
done:free(key);free(value_name);free(data);return result;
}
JNIEXPORT jobjectArray JNICALL Java_pl_rufusportable_RegistryHive_list(JNIEnv *env,jclass type,jlong handle,jbyteArray path)
{
    (void)type;hive_h *hive=(hive_h *)(intptr_t)handle;char *key=text(env,path);hive_node_h *children=NULL;jobjectArray result=NULL;
    if(!key)return NULL;
    hive_node_h key_node=node(env,hive,key,0);
    if((*env)->ExceptionCheck(env))goto done;
    size_t count=0;
    if(key_node){children=hivex_node_children(hive,key_node);if(!children){error(env);goto done;}while(children[count])count++;}
    if(count>INT32_MAX){errno=EOVERFLOW;error(env);goto done;}
    jclass array_type=(*env)->FindClass(env,"[B");
    if(array_type){result=(*env)->NewObjectArray(env,(jsize)count,array_type,NULL);(*env)->DeleteLocalRef(env,array_type);}
    if(result)for(size_t i=0;i<count;i++) {
        char *name=hivex_node_name(hive,children[i]);if(!name){error(env);break;}
        errno=0;size_t length=hivex_node_name_len(hive,children[i]);
        if(errno){free(name);error(env);break;}
        jbyteArray value=bytes(env,name,length);free(name);
        if(value){(*env)->SetObjectArrayElement(env,result,(jsize)i,value);(*env)->DeleteLocalRef(env,value);}
        if((*env)->ExceptionCheck(env))break;
    }
done:free(children);free(key);return result;
}
JNIEXPORT void JNICALL Java_pl_rufusportable_RegistryHive_write(JNIEnv *env,jclass type,jlong handle,jbyteArray path,jbyteArray name,jint value_type,jbyteArray data)
{
    (void)type;hive_h *hive=(hive_h *)(intptr_t)handle;char *key=text(env,path),*value_name=NULL,*value_data=NULL;
    if(!key)goto done;
    value_name=text(env,name);if(!value_name)goto done;
    if(!data || value_type<0 || value_type>11){errno=EINVAL;error(env);goto done;}
    jsize length=(*env)->GetArrayLength(env,data);value_data=malloc((size_t)length+1);if(!value_data){error(env);goto done;}
    (*env)->GetByteArrayRegion(env,data,0,length,(jbyte *)value_data);if((*env)->ExceptionCheck(env))goto done;
    hive_node_h key_node=node(env,hive,key,1);if(!key_node)goto done;
    hive_set_value value={value_name,(hive_type)value_type,(size_t)length,value_data};
    if(hivex_node_set_value(hive,key_node,&value,0))error(env);
done:free(key);free(value_name);free(value_data);
}
JNIEXPORT jboolean JNICALL Java_pl_rufusportable_RegistryHive_remove(JNIEnv *env,jclass type,jlong handle,jbyteArray path)
{
    (void)type;hive_h *hive=(hive_h *)(intptr_t)handle;char *key=text(env,path);if(!key)return JNI_FALSE;
    hive_node_h key_node=node(env,hive,key,0);free(key);if(!key_node)return JNI_FALSE;
    if(hivex_node_delete_child(hive,key_node)){error(env);return JNI_FALSE;}return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_pl_rufusportable_RegistryHive_save(JNIEnv *env,jclass type,jlong handle,jbyteArray filename)
{
    (void)type;hive_h *hive=(hive_h *)(intptr_t)handle;char *path=text(env,filename);if(!path)return;
    if(hivex_commit(hive,path,0)){error(env);free(path);return;}
    int fd=open(path,O_RDONLY|O_CLOEXEC);free(path);if(fd<0){error(env);return;}
    int status=fsync(fd),saved=errno;if(close(fd) && !status){status=-1;saved=errno;}
    if(status){errno=saved;error(env);}
}
JNIEXPORT void JNICALL Java_pl_rufusportable_RegistryHive_release(JNIEnv *env,jclass type,jlong handle)
{
    (void)type;if(hivex_close((hive_h *)(intptr_t)handle))error(env);
}

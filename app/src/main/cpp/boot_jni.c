#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "syslinux_bridge.h"

static void error(JNIEnv *env)
{
    if(!(*env)->ExceptionCheck(env)) {
        jclass type=(*env)->FindClass(env,"java/io/IOException");
        if(type){(*env)->ThrowNew(env,type,strerror(errno?errno:EIO));(*env)->DeleteLocalRef(env,type);}
    }
}
static unsigned char *copy(JNIEnv *env,jbyteArray array,jsize minimum,jsize maximum)
{
    if(!array){errno=EINVAL;error(env);return NULL;}
    jsize length=(*env)->GetArrayLength(env,array);
    if(length<minimum || length>maximum){errno=EINVAL;error(env);return NULL;}
    unsigned char *result=malloc((size_t)length+1);if(!result){error(env);return NULL;}
    (*env)->GetByteArrayRegion(env,array,0,length,(jbyte *)result);
    if((*env)->ExceptionCheck(env)){free(result);return NULL;}
    result[length]=0;return result;
}
JNIEXPORT jobjectArray JNICALL Java_pl_rufusportable_SyslinuxPatch_prepare(
        JNIEnv *env,jclass type,jbyteArray image,jbyteArray sector,jbyteArray boot,jlongArray map,jbyteArray directory,jboolean stupid,jboolean raid)
{
    (void)type;unsigned char *data=NULL,*bss=NULL,*vbr=NULL,*name=NULL;uint64_t *sectors=NULL;
    jobjectArray result=NULL;_Alignas(8) unsigned char adv[1024];
    data=copy(env,image,24,1048576);if(!data)goto done;
    bss=copy(env,sector,512,512);if(!bss)goto done;
    vbr=copy(env,boot,512,512);if(!vbr)goto done;
    name=copy(env,directory,0,4095);if(!name)goto done;
    jsize image_length=(*env)->GetArrayLength(env,image),name_length=(*env)->GetArrayLength(env,directory);
    if(memchr(name,0,name_length) || !map){errno=EINVAL;error(env);goto done;}
    jsize count=(*env)->GetArrayLength(env,map);
    if(count<1 || count>2050){errno=EINVAL;error(env);goto done;}
    sectors=malloc((size_t)count*sizeof(*sectors));if(!sectors){error(env);goto done;}
    (*env)->GetLongArrayRegion(env,map,0,count,(jlong *)sectors);if((*env)->ExceptionCheck(env))goto done;
    if(rufus_syslinux_patch(data,image_length,bss,512,vbr,512,sectors,count,(char *)name,stupid,raid,adv,sizeof adv)) {error(env);goto done;}
    jclass array=(*env)->FindClass(env,"[B");if(!array)goto done;
    result=(*env)->NewObjectArray(env,2,array,NULL);(*env)->DeleteLocalRef(env,array);
    if(result) {
        jbyteArray file=(*env)->NewByteArray(env,image_length+1024);
        if(!file)goto done;
        (*env)->SetByteArrayRegion(env,file,0,image_length,(jbyte *)data);
        (*env)->SetByteArrayRegion(env,file,image_length,1024,(jbyte *)adv);
        (*env)->SetObjectArrayElement(env,result,0,file);(*env)->DeleteLocalRef(env,file);
        if((*env)->ExceptionCheck(env))goto done;
        file=(*env)->NewByteArray(env,512);if(!file)goto done;
        (*env)->SetByteArrayRegion(env,file,0,512,(jbyte *)vbr);
        (*env)->SetObjectArrayElement(env,result,1,file);(*env)->DeleteLocalRef(env,file);
    }
done:free(data);free(bss);free(vbr);free(name);free(sectors);return result;
}

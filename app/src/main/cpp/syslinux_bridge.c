/* Android adapter for the unchanged Rufus Syslinux libinstaller (GPL-2.0-or-later).
 * Bounds checks precede the upstream patcher, whose input is normally a trusted executable resource. */
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "syslinux_bridge.h"
#include "syslinux.h"
#include "syslxint.h"
#include "syslxfs.h"

uint8_t *syslinux_ldlinux_buf[2];
uint32_t syslinux_ldlinux_len[2];
uint32_t SECTOR_SHIFT=9,SECTOR_SIZE=512;
static pthread_mutex_t patch_mutex=PTHREAD_MUTEX_INITIALIZER;
void uprintf(const char *format,...){(void)format;}

static int range(size_t start,size_t count,size_t length){return start<=length && count<=length-start;}
static int extents(const uint64_t *sectors,size_t count)
{
    uint32_t address=0x8000,base=address;uint64_t first=0;unsigned length=0;int result=0;
    for(size_t i=0;i<count;i++) {
        uint32_t bytes=(length+1)*512;
        if(length && sectors[i]==first+length && bytes<65536 && ((address^(base+bytes-1))&0xffff0000)==0)length++;
        else {if(length)result++;base=address;first=sectors[i];length=1;}
        address+=512;
    }
    return result+(length!=0);
}
int rufus_syslinux_patch(uint8_t *image,size_t image_length,uint8_t *bss,size_t bss_length,
        uint8_t *vbr,size_t vbr_length,const uint64_t *sectors,size_t count,
        const char *directory,int stupid,int raid,uint8_t *adv,size_t adv_length)
{
    if(!image || (uintptr_t)image%_Alignof(struct patch_area) || image_length<sizeof(struct patch_area) || image_length>1048576 || !bss || bss_length!=512 ||
            !vbr || vbr_length!=512 || !sectors || !adv || adv_length!=2*ADV_SIZE ||
            (stupid!=0 && stupid!=1) || (raid!=0 && raid!=1))goto invalid;
    if(get_32((const uint32_t *)(bss+504))!=LDLINUX_MAGIC || get_16((const uint16_t *)(bss+510))!=0xaa55 ||
            (get_16((const uint16_t *)(bss+508))&0xfe00)!=BS_MAGIC_VER)goto invalid;
    size_t required=(image_length+511)/512+2;
    if(count!=required)goto invalid;
    for(size_t i=0;i<count;i++) {
        if(!sectors[i] || sectors[i]>INT64_MAX/512)goto invalid;
        for(size_t j=0;j<i;j++)if(sectors[j]==sectors[i])goto invalid;
    }
    struct patch_area *patch=NULL;
    for(size_t offset=0;offset+sizeof(*patch)<=image_length;offset+=4)
        if(get_32((const uint32_t *)(image+offset))==LDLINUX_MAGIC){patch=(struct patch_area *)(image+offset);break;}
    if(!patch)goto invalid;
    size_t offset=get_16(&patch->epaoffset);
    if(offset%_Alignof(struct ext_patch_area) || !range(offset,sizeof(struct ext_patch_area),image_length))goto invalid;
    struct ext_patch_area *epa=(struct ext_patch_area *)(image+offset);
    if(!range(get_16(&epa->advptroffset),16,image_length) ||
            !range(get_16(&epa->secptroffset),(size_t)get_16(&epa->secptrcnt)*sizeof(struct syslinux_extent),image_length) ||
            !range(get_16(&epa->diroffset),get_16(&epa->dirlen),image_length) ||
            !range(get_16(&epa->subvoloffset),get_16(&epa->subvollen),image_length) ||
            !range(get_16(&epa->sect1ptr0),4,512) || !range(get_16(&epa->sect1ptr1),4,512) ||
            !range(get_16(&epa->raidpatch),2,512) ||
            extents(sectors+1,required-3)>get_16(&epa->secptrcnt))goto invalid;
    if(directory) {
        size_t length=strnlen(directory,4096);
        if(length==4096 || length+1>get_16(&epa->dirlen))goto invalid;
    }
    int filesystem=NONE;
    int locked=pthread_mutex_lock(&patch_mutex);if(locked){errno=locked;return -1;}
    SECTOR_SHIFT=9;SECTOR_SIZE=512;
    if(syslinux_check_bootsect(vbr,&filesystem) || (filesystem!=VFAT && filesystem!=NTFS)){pthread_mutex_unlock(&patch_mutex);goto invalid;}
    syslinux_ldlinux_buf[0]=image;syslinux_ldlinux_len[0]=(uint32_t)image_length;
    syslinux_ldlinux_buf[1]=bss;syslinux_ldlinux_len[1]=512;
    syslinux_reset_adv(adv);
    int result=syslinux_patch(sectors,(int)count,stupid,raid,directory,NULL);
    if(result>=0)syslinux_make_bootsect(vbr,filesystem);
    syslinux_ldlinux_buf[0]=NULL;syslinux_ldlinux_buf[1]=NULL;syslinux_ldlinux_len[0]=0;syslinux_ldlinux_len[1]=0;
    pthread_mutex_unlock(&patch_mutex);
    if(result<0)goto invalid;
    return 0;
invalid:errno=EINVAL;return -1;
}

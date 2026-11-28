#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "syslinux_bridge.h"
#include "syslinux.h"
#include "syslxint.h"
#include "syslxfs.h"

static uint8_t *read_file(const char *directory,const char *name,size_t *length)
{
    char path[4096];assert(snprintf(path,sizeof path,"%s/%s",directory,name)>0);FILE *file=fopen(path,"rb");assert(file);
    assert(!fseek(file,0,SEEK_END));*length=(size_t)ftell(file);rewind(file);uint8_t *data=malloc(*length);assert(data);
    assert(fread(data,1,*length,file)==*length);assert(!fclose(file));return data;
}
static void word(uint8_t *b,size_t off,uint16_t value){b[off]=value;b[off+1]=value>>8;}
static void dword(uint8_t *b,size_t off,uint32_t value){word(b,off,value);word(b,off+2,value>>16);}
static void boot(uint8_t *b,int fat32)
{
    memset(b,0,512);b[0]=0xeb;b[1]=fat32?0x58:0x3c;b[2]=0x90;memcpy(b+3,"MSWIN4.1",8);
    word(b,11,512);b[13]=fat32?1:8;word(b,14,fat32?32:1);b[16]=2;word(b,17,fat32?0:512);b[21]=0xf8;
    word(b,22,fat32?0:32);word(b,24,63);word(b,26,255);dword(b,28,2048);dword(b,32,fat32?262144:65536);
    b[fat32?66:38]=0x29;
    if(fat32){dword(b,36,2033);dword(b,44,2);word(b,48,1);word(b,50,6);memcpy(b+82,"FAT32   ",8);}
    else memcpy(b+54,"FAT16   ",8);
    word(b,510,0xaa55);
}
static void checksum(const uint8_t *image,size_t length)
{
    uint32_t sum=0;for(size_t i=0;i<length/4;i++)sum+=get_32((const uint32_t *)(image+4*i));assert(sum==LDLINUX_MAGIC);
}
static void original(uint8_t *image,size_t length,uint8_t *bss,uint8_t *vbr,const uint64_t *map,size_t count,const char *dir,int stupid,int raid,uint8_t *adv)
{
    syslinux_ldlinux_buf[0]=image;syslinux_ldlinux_buf[1]=bss;syslinux_ldlinux_len[0]=(uint32_t)length;syslinux_ldlinux_len[1]=512;
    SECTOR_SIZE=512;SECTOR_SHIFT=9;int fs=0;assert(!syslinux_check_bootsect(vbr,&fs));syslinux_reset_adv(adv);
    assert(syslinux_patch(map,(int)count,stupid,raid,dir,NULL)>=0);syslinux_make_bootsect(vbr,fs);
    syslinux_ldlinux_buf[0]=NULL;syslinux_ldlinux_buf[1]=NULL;syslinux_ldlinux_len[0]=0;syslinux_ldlinux_len[1]=0;
}
static void compare(const uint8_t *source,size_t length,const uint8_t *sector,int fat32,int fragmented,int stupid,int raid)
{
    size_t count=(length+511)/512+2;uint64_t *map=calloc(count,sizeof(*map));assert(map);
    for(size_t i=0;i<count;i++)map[i]=fragmented?2000+i/3*10+i%3:2000+i;
    uint8_t *image=malloc(length),*reference=malloc(length),bss[512],ref_bss[512],vbr[512],ref_vbr[512],before[512];
    _Alignas(8) uint8_t adv[1024],ref_adv[1024];assert(image && reference);
    memcpy(image,source,length);memcpy(reference,source,length);memcpy(bss,sector,512);memcpy(ref_bss,sector,512);boot(vbr,fat32);memcpy(ref_vbr,vbr,512);memcpy(before,vbr,512);
    const char *dir=fragmented?"/boot/syslinux":"";
    original(reference,length,ref_bss,ref_vbr,map,count,dir,stupid,raid,ref_adv);
    assert(!rufus_syslinux_patch(image,length,bss,512,vbr,512,map,count,dir,stupid,raid,adv,1024));
    assert(!memcmp(image,reference,length) && !memcmp(bss,ref_bss,512) && !memcmp(vbr,ref_vbr,512) && !memcmp(adv,ref_adv,1024));
    assert(!memcmp(before+11,vbr+11,79));assert(!syslinux_validate_adv(adv));assert(!memcmp(adv,adv+512,512));checksum(image,length);
    free(image);free(reference);free(map);
}
static uint32_t random_state=40;
static uint32_t random_value(void){random_state^=random_state<<13;random_state^=random_state>>17;random_state^=random_state<<5;return random_state;}
static void malformed(const uint8_t *source,size_t length,const uint8_t *sector)
{
    size_t count=(length+511)/512+2;uint64_t *map=calloc(count,sizeof(*map));uint8_t *image=malloc(length);assert(map && image);
    uint8_t bss[512],vbr[512];_Alignas(8) uint8_t adv[1024];for(size_t i=0;i<count;i++)map[i]=2000+i;
    memcpy(image,source,length);memcpy(bss,sector,512);boot(vbr,1);
    assert(rufus_syslinux_patch(image,1,bss,512,vbr,512,map,count,"",0,0,adv,1024)<0);
    assert(rufus_syslinux_patch(image,length,bss,511,vbr,512,map,count,"",0,0,adv,1024)<0);
    assert(rufus_syslinux_patch(image,length,bss,512,vbr,511,map,count,"",0,0,adv,1024)<0);
    assert(rufus_syslinux_patch(image,length,bss,512,vbr,512,map,count-1,"",0,0,adv,1024)<0);
    map[1]=map[0];assert(rufus_syslinux_patch(image,length,bss,512,vbr,512,map,count,"",0,0,adv,1024)<0);map[1]++;
    char name[300];memset(name,'a',sizeof name);name[299]=0;
    assert(rufus_syslinux_patch(image,length,bss,512,vbr,512,map,count,name,0,0,adv,1024)<0);
    for(int i=0;i<5000;i++) {
        memcpy(image,source,length);memcpy(bss,sector,512);boot(vbr,1);
        for(int j=0;j<4;j++)image[random_value()%length]=(uint8_t)random_value();
        if(i%4==0){word(image,46,(uint16_t)random_value());}
        if(i%4==1){size_t epa=get_16((const uint16_t *)(source+46));word(image,epa+2*(random_value()%10),(uint16_t)random_value());}
        int result=rufus_syslinux_patch(image,length,bss,512,vbr,512,map,count,"",0,0,adv,1024);
        if(!result)checksum(image,length);
    }
    free(image);free(map);
}
typedef struct {const uint8_t *source,*bss;size_t length;int index;} thread_context;
static void *worker(void *opaque)
{
    thread_context *ctx=opaque;size_t count=(ctx->length+511)/512+2;uint64_t *map=calloc(count,sizeof(*map));uint8_t *image=malloc(ctx->length);assert(map && image);
    for(size_t i=0;i<count;i++)map[i]=4000+ctx->index*10000+i;
    for(int round=0;round<40;round++) {
        uint8_t bss[512],vbr[512];_Alignas(8) uint8_t adv[1024];memcpy(image,ctx->source,ctx->length);memcpy(bss,ctx->bss,512);boot(vbr,round%2);
        assert(!rufus_syslinux_patch(image,ctx->length,bss,512,vbr,512,map,count,"",round%2,0,adv,1024));checksum(image,ctx->length);
    }
    free(map);free(image);return NULL;
}
int main(int argc,char **argv)
{
    assert(argc==2);
    for(int version=4;version<=6;version+=2) {
        char name[64];size_t length,bss_length;snprintf(name,sizeof name,"ldlinux_v%d.sys",version);uint8_t *image=read_file(argv[1],name,&length);
        snprintf(name,sizeof name,"ldlinux_v%d.bss",version);uint8_t *bss=read_file(argv[1],name,&bss_length);assert(bss_length==512);
        for(int fat=0;fat<2;fat++)for(int fragmentation=0;fragmentation<2;fragmentation++)for(int flags=0;flags<4;flags++)compare(image,length,bss,fat,fragmentation,flags&1,flags>>1);
        malformed(image,length,bss);
        pthread_t threads[4];thread_context contexts[4];for(int i=0;i<4;i++){contexts[i]=(thread_context){image,bss,length,i};assert(!pthread_create(&threads[i],NULL,worker,&contexts[i]));}
        for(int i=0;i<4;i++)assert(!pthread_join(threads[i],NULL));
        free(image);free(bss);
    }
    puts("Syslinux 4/6 patch bytes matched the original for FAT16/32, sector fragmentation, stupid/RAID modes and directories; malformed inputs and concurrent calls passed");
    return 0;
}

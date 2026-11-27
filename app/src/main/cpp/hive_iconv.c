/* Stateless UTF-8, UTF-16LE and Latin-1 conversions used by hivex on API 26+. */
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "hive_compat/iconv.h"
enum encoding {UTF8=1,UTF16LE,LATIN1};
typedef struct {enum encoding from,to;} converter;
static enum encoding encoding(const char *name) {
    if(!name)return 0;
    if(!strcmp(name,"UTF-8"))return UTF8;
    if(!strcmp(name,"UTF-16LE"))return UTF16LE;
    if(!strcmp(name,"LATIN1") || !strcmp(name,"ISO-8859-1"))return LATIN1;
    return 0;
}
iconv_t iconv_open(const char *to,const char *from)
{
    enum encoding destination=encoding(to),source=encoding(from);
    if(!destination || !source){errno=EINVAL;return (iconv_t)-1;}
    converter *value=malloc(sizeof(*value));
    if(!value)return (iconv_t)-1;
    *value=(converter){source,destination};return value;
}
int iconv_close(iconv_t value)
{
    if(!value || value==(iconv_t)-1){errno=EBADF;return -1;}
    free(value);return 0;
}
static int decode(enum encoding encoding,const unsigned char *input,size_t length,uint32_t *codepoint)
{
    if(encoding==LATIN1){*codepoint=input[0];return 1;}
    if(encoding==UTF16LE) {
        if(length<2){errno=EINVAL;return -1;}
        uint32_t first=input[0]|((uint32_t)input[1]<<8);
        if(first>=0xdc00 && first<=0xdfff){errno=EILSEQ;return -1;}
        if(first<0xd800 || first>0xdbff){*codepoint=first;return 2;}
        if(length<4){errno=EINVAL;return -1;}
        uint32_t second=input[2]|((uint32_t)input[3]<<8);
        if(second<0xdc00 || second>0xdfff){errno=EILSEQ;return -1;}
        *codepoint=0x10000+((first-0xd800)<<10)+(second-0xdc00);return 4;
    }
    unsigned char first=input[0];int count;
    if(first<0x80){*codepoint=first;return 1;}
    if(first>=0xc2 && first<=0xdf){count=2;*codepoint=first&31;}
    else if(first>=0xe0 && first<=0xef){count=3;*codepoint=first&15;}
    else if(first>=0xf0 && first<=0xf4){count=4;*codepoint=first&7;}
    else {errno=EILSEQ;return -1;}
    for(int i=1;i<count;i++) {
        if((size_t)i>=length){errno=EINVAL;return -1;}
        if((input[i]&0xc0)!=0x80){errno=EILSEQ;return -1;}
        *codepoint=(*codepoint<<6)|(input[i]&63);
    }
    if((count==2 && *codepoint<0x80) || (count==3 && *codepoint<0x800) || (count==4 && *codepoint<0x10000) ||
            (*codepoint>=0xd800 && *codepoint<=0xdfff) || *codepoint>0x10ffff){errno=EILSEQ;return -1;}
    return count;
}
static int encode(enum encoding encoding,uint32_t point,unsigned char bytes[4])
{
    if(encoding==LATIN1){if(point>255){errno=EILSEQ;return -1;}bytes[0]=(unsigned char)point;return 1;}
    if(encoding==UTF16LE) {
        if(point<0x10000){bytes[0]=point&255;bytes[1]=point>>8;return 2;}
        point-=0x10000;uint32_t high=0xd800+(point>>10),low=0xdc00+(point&1023);
        bytes[0]=high&255;bytes[1]=high>>8;bytes[2]=low&255;bytes[3]=low>>8;return 4;
    }
    if(point<0x80){bytes[0]=point;return 1;}
    if(point<0x800){bytes[0]=0xc0|(point>>6);bytes[1]=0x80|(point&63);return 2;}
    if(point<0x10000){bytes[0]=0xe0|(point>>12);bytes[1]=0x80|((point>>6)&63);bytes[2]=0x80|(point&63);return 3;}
    bytes[0]=0xf0|(point>>18);bytes[1]=0x80|((point>>12)&63);bytes[2]=0x80|((point>>6)&63);bytes[3]=0x80|(point&63);return 4;
}
size_t iconv(iconv_t value,char **input,size_t *input_left,char **output,size_t *output_left)
{
    if(!value || value==(iconv_t)-1){errno=EBADF;return (size_t)-1;}
    if(!input || !*input)return 0;
    if(!input_left || !output || !*output || !output_left){errno=EINVAL;return (size_t)-1;}
    converter *conversion=value;
    while(*input_left) {
        uint32_t point;unsigned char bytes[4];
        int consumed=decode(conversion->from,(const unsigned char *)*input,*input_left,&point);
        if(consumed<0)return (size_t)-1;
        int produced=encode(conversion->to,point,bytes);
        if(produced<0)return (size_t)-1;
        if((size_t)produced>*output_left){errno=E2BIG;return (size_t)-1;}
        memcpy(*output,bytes,produced);*input+=consumed;*input_left-=consumed;*output+=produced;*output_left-=produced;
    }
    return 0;
}

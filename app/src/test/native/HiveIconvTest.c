#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <iconv.h>
/* Reference uses libc; the portable adapter has distinct symbols. */
extern void *rufus_hive_iconv_open(const char *,const char *);
extern int rufus_hive_iconv_close(void *);
extern size_t rufus_hive_iconv(void *,char **,size_t *,char **,size_t *);
static void compare(const char *to,const char *from,const char *data,size_t size)
{
    iconv_t reference=iconv_open(to,from);void *portable=rufus_hive_iconv_open(to,from);
    assert(reference!=(iconv_t)-1 && portable!=(void *)-1);
    size_t capacity=size*4+16;char *a=malloc(capacity),*b=malloc(capacity);assert(a && b);
    char *ia=(char *)data,*ib=(char *)data,*oa=a,*ob=b;size_t na=size,nb=size,ra=capacity,rb=capacity;
    assert(iconv(reference,&ia,&na,&oa,&ra)==0);assert(rufus_hive_iconv(portable,&ib,&nb,&ob,&rb)==0);
    assert(na==0 && nb==0 && ra==rb && memcmp(a,b,capacity-ra)==0);
    assert(iconv_close(reference)==0);assert(rufus_hive_iconv_close(portable)==0);free(a);free(b);
}
static void invalid(const char *from,const unsigned char *data,size_t size,int expected)
{
    void *converter=rufus_hive_iconv_open("UTF-8",from);assert(converter!=(void *)-1);
    char output[32],*in=(char *)data,*out=output;size_t left=size,room=sizeof(output);errno=0;
    assert(rufus_hive_iconv(converter,&in,&left,&out,&room)==(size_t)-1);assert(errno==expected);
    assert(rufus_hive_iconv_close(converter)==0);
}
int main(void)
{
    size_t capacity=0x110000*4,used=0;char *unicode=malloc(capacity);assert(unicode);
    for(uint32_t cp=0;cp<=0x10ffff;cp++) {
        if(cp>=0xd800 && cp<=0xdfff)continue;
        if(cp<0x10000){unicode[used++]=cp&255;unicode[used++]=cp>>8;}
        else {uint32_t v=cp-0x10000,a=0xd800+(v>>10),b=0xdc00+(v&1023);
            unicode[used++]=a&255;unicode[used++]=a>>8;unicode[used++]=b&255;unicode[used++]=b>>8;}
    }
    compare("UTF-8","UTF-16LE",unicode,used);
    iconv_t conversion=iconv_open("UTF-8","UTF-16LE");char *utf8=malloc(used*2),*in=unicode,*out=utf8;size_t left=used,room=used*2;
    assert(utf8 && conversion!=(iconv_t)-1 && iconv(conversion,&in,&left,&out,&room)==0);assert(iconv_close(conversion)==0);
    compare("UTF-16LE","UTF-8",utf8,(size_t)(out-utf8));free(utf8);free(unicode);
    char latin[256];for(int i=0;i<256;i++)latin[i]=(char)i;
    compare("UTF-8","LATIN1",latin,sizeof(latin));
    void *converter=rufus_hive_iconv_open("UTF-8","LATIN1");char small[512],*source=latin,*dest=small;left=256;room=512;
    assert(rufus_hive_iconv(converter,&source,&left,&dest,&room)==0);compare("LATIN1","UTF-8",small,(size_t)(dest-small));
    assert(rufus_hive_iconv_close(converter)==0);
    invalid("UTF-8",(const unsigned char *)"\xc0\x80",2,EILSEQ);
    invalid("UTF-8",(const unsigned char *)"\xed\xa0\x80",3,EILSEQ);
    invalid("UTF-8",(const unsigned char *)"\xf4\x90\x80\x80",4,EILSEQ);
    invalid("UTF-8",(const unsigned char *)"\xe2\x82",2,EINVAL);
    invalid("UTF-16LE",(const unsigned char *)"\x00\xd8",2,EINVAL);
    invalid("UTF-16LE",(const unsigned char *)"\x00\xdc",2,EILSEQ);
    converter=rufus_hive_iconv_open("UTF-8","LATIN1");source=latin+233;dest=small;left=1;room=1;errno=0;
    assert(rufus_hive_iconv(converter,&source,&left,&dest,&room)==(size_t)-1 && errno==E2BIG && left==1 && room==1);
    assert(rufus_hive_iconv(converter,NULL,NULL,NULL,NULL)==0);assert(rufus_hive_iconv_close(converter)==0);
    assert(rufus_hive_iconv_open("invalid","UTF-8")== (void *)-1 && errno==EINVAL);
    puts("Hive encoding adapter matched libc iconv for every Unicode scalar, Latin-1, invalid input and short output");
    return 0;
}

/* Differential test against the unmodified generator from the pinned Rufus
 * wue.c (Pete Batard, GPL-3.0+). Windows host inputs are supplied by fixtures. */
#define _GNU_SOURCE 1
#include "unattend.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <strings.h>
#include <ctype.h>
#include <wchar.h>
#include <unistd.h>
#define ARCH_X86_32 1
#define ARCH_ARM_64 4
#define MAX_PATH 4096
#define MAX_USERNAME_LENGTH 128
#define USERNAME_INVALID_CHARS "/\\[]:;|=,+*?<>%@&\""
#define APPLICATION_NAME "RUFUS"
#define TRUE 1
#define TIME_ZONE_ID_INVALID -1
#define ARRAYSIZE(a) ((int)(sizeof(a)/sizeof((a)[0])))
#define stricmp strcasecmp
#define uprintf(...) ((void)0)
#define safe_strdup strdup
#define static_sprintf(array,...) snprintf(array,sizeof(array),__VA_ARGS__)
#define REGKEY_HKCU 1
#define REGKEY_HKLM 2
static const char *bypass_name[]={"BypassTPMCheck","BypassSecureBootCheck","BypassRAMCheck"};
static const char *temp_dir;
static int unattend_xml_flags,unattend_edition_index;
static char unattend_username[MAX_USERNAME_LENGTH];
static uint32_t removable_section[2];
typedef struct {wchar_t TimeZoneKeyName[128];} DYNAMIC_TIME_ZONE_INFORMATION;
static int GetDynamicTimeZoneInformation(DYNAMIC_TIME_ZONE_INFORMATION *info) {
    wcscpy(info->TimeZoneKeyName,L"Central European Standard Time");return 0;
}
static char *wchar_to_utf8(const wchar_t *text) {
    char *utf8=calloc(wcslen(text)*4+1,1);assert(utf8);
    assert(wcstombs(utf8,text,wcslen(text)*4)!=(size_t)-1);return utf8;
}
static int GetTempFileNameU(const char *directory,const char *name,int unused,char *path) {
    (void)name;(void)unused;
    snprintf(path,MAX_PATH,"%s/reference-XXXXXX",directory);
    int fd=mkstemp(path);assert(fd>=0);close(fd);return 1;
}
static const char *GetUILanguage(void){return "pl-PL";}
static int GetSystemDefaultLCID(void){return 1;}
static int GetUserDefaultLCID(void){return 2;}
static int GetUserDefaultUILanguage(void){return 3;}
static const char *ToLocaleName(int id){return id==1?"pl-PL":id==2?"de-DE":"en-US";}
static const char *ReadRegistryKeyStr(int key,const char *path) {
    (void)path;return key==REGKEY_HKCU?"00000415":"en-US";
}
typedef struct {char **String;int Index,capacity;} StrArray;
static void StrArrayCreate(StrArray *a,int capacity) {
    a->Index=0;a->capacity=capacity;a->String=calloc(capacity,sizeof(char *));assert(a->String);
}
static void StrArrayAdd(StrArray *a,const char *text,int duplicate) {
    (void)duplicate;
    if(a->Index==a->capacity){a->capacity*=2;a->String=realloc(a->String,a->capacity*sizeof(char *));assert(a->String);}
    a->String[a->Index]=strdup(text);assert(a->String[a->Index]);a->Index++;
}
static void StrArrayClear(StrArray *a){for(int i=0;i<a->Index;i++)free(a->String[i]);a->Index=0;}
static void StrArrayDestroy(StrArray *a){StrArrayClear(a);free(a->String);}
static void trim(char *text) {
    char *first=text;while(*first && isspace((unsigned char)*first))first++;
    memmove(text,first,strlen(first)+1);
    size_t end=strlen(text);while(end && isspace((unsigned char)text[end-1]))text[--end]=0;
}
static void filter_chars(char *text,const char *chars,char replacement){for(;*text;text++)if(strchr(chars,*text))*text=replacement;}
#include "unattend_reference.inc"

int main(int argc,char **argv)
{
    assert(argc==2);temp_dir=argv[1];
    const char *names[]={"  Anna  ","żółć/Jan&Dev","Administrator"};
    unsigned comparisons=0;
    for(int arch=1;arch<=4;arch++) {
        for(int mask=0;mask<=UNATTEND_FULL_MASK;mask++) {
            int flags=mask|((mask&1)?UNATTEND_WINDOWS_TO_GO:0);
            const char *username=names[mask%3];
            unattend_edition_index=1+(mask%7);
            snprintf(unattend_username,sizeof(unattend_username),"%s",username);
            char *reference=CreateUnattendXml(arch,flags);
            rufus_unattend_options options={unattend_edition_index,username,"Central European Standard Time",
                "00000415","pl-PL","de-DE","en-US","en-US","pl-PL"};
            size_t actual_length=0;
            char *actual=rufus_unattend_xml(arch,flags,&options,&actual_length);
            if(!reference){assert(!actual);comparisons++;continue;}
            FILE *file=fopen(reference,"rb");assert(file);
            assert(fseek(file,0,SEEK_END)==0);long size=ftell(file);assert(size>=0);
            rewind(file);char *expected=malloc((size_t)size+1);assert(expected);
            assert(fread(expected,1,size,file)==(size_t)size);fclose(file);unlink(reference);
            if(!actual || actual_length!=(size_t)size || memcmp(actual,expected,actual_length)) {
                fprintf(stderr,"Rufus XML mismatch: architecture %d flags 0x%x\n",arch,flags);
                free(actual);free(expected);return 1;
            }
            free(actual);free(expected);comparisons++;
        }
    }
    printf("Rufus reference XML matched for %u architecture/flag combinations\n",comparisons);
    return 0;
}

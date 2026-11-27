/* Independent read-back using wimlib's registry parser, not the writer library. */
#include "config.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wimlib.h>
#include "wimlib/registry.h"
int main(int argc,char **argv)
{
    assert(argc==2);FILE *file=fopen(argv[1],"rb");assert(file);
    assert(fseek(file,0,SEEK_END)==0);long size=ftell(file);assert(size>0);rewind(file);
    void *data=malloc((size_t)size);assert(data && fread(data,1,(size_t)size,file)==(size_t)size);assert(fclose(file)==0);
    assert(wimlib_global_init(WIMLIB_INIT_FLAG_ASSUME_UTF8)==0);
    assert(hive_validate(data,(size_t)size)==HIVE_OK);
    s64 value=0;
    assert(hive_get_number(data,"ControlSet001\\Services\\partmgr\\Parameters","SanPolicy",&value)==HIVE_OK && value==4);
    assert(hive_get_number(data,"Options","Change",&value)==HIVE_OK && value==2);
    assert(hive_get_number(data,"Options","Preserved",&value)==HIVE_OK && value==123);
    char *text=NULL;
    assert(hive_get_string(data,"Options","Nazwa 😀",&text)==HIVE_OK);assert(strcmp(text,"żółć 日本語 😀")==0);free(text);
    char **children=NULL;assert(hive_list_subkeys(data,"Many",&children)==HIVE_OK);
    size_t count=0;while(children[count])count++;assert(count==1000);hive_free_subkeys_list(children);
    assert(hive_get_number(data,"Many\\Key999","Value",&value)==HIVE_OK && value==999);
    assert(hive_get_number(data,"Remove\\Subkey","Value",&value)==HIVE_KEY_NOT_FOUND);
    wimlib_global_cleanup();free(data);
    puts("Independent wimlib registry parser verified updated hive, DWORDs, Unicode, subkeys and deletion");return 0;
}

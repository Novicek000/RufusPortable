/* Add original ADS data and upstream's own security test descriptor to our WIM fixture. */
#include "config.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <wimlib.h>
#include "wimlib/wim.h"
#include "wimlib/metadata.h"
#include "wimlib/dentry.h"
#include "wimlib/inode.h"
#include "wimlib/encoding.h"
#include "wimlib/security.h"

int main(int argc,char **argv)
{
    assert(argc==3);
    assert(sizeof(off_t)==8);
    assert(wimlib_global_init(WIMLIB_INIT_FLAG_ASSUME_UTF8)==0);
    WIMStruct *wim=NULL;
    assert(wimlib_open_wim(argv[1],WIMLIB_OPEN_FLAG_WRITE_ACCESS,&wim)==0);
    assert(select_wim_image(wim,1)==0);
    struct wim_dentry *file=get_dentry(wim,"/rich/data.bin",WIMLIB_CASE_SENSITIVE);
    assert(file);
    const utf16lechar *name=NULL;
    assert(tstr_get_utf16le("RufusTest",&name)==0);
    const char data[]="Original alternate data stream.\n";
    assert(inode_add_stream_with_data(file->d_inode,STREAM_TYPE_DATA,name,data,sizeof(data)-1,wim->blob_table));
    tstr_put_utf16le(name);
    FILE *input=fopen(argv[2],"rb");assert(input);
    char descriptor[4096];size_t length=fread(descriptor,1,sizeof(descriptor),input);
    assert(length>0 && feof(input));assert(fclose(input)==0);
    struct wim_sd_set descriptors;
    assert(init_sd_set(&descriptors,wim_get_current_security_data(wim))==0);
    file->d_inode->i_security_id=sd_set_add_sd(&descriptors,descriptor,length);
    assert(file->d_inode->i_security_id>=0);
    destroy_sd_set(&descriptors);
    file->d_inode->i_attributes=FILE_ATTRIBUTE_READONLY|FILE_ATTRIBUTE_HIDDEN|FILE_ATTRIBUTE_SYSTEM|FILE_ATTRIBUTE_ARCHIVE;
    file->d_inode->i_creation_time=132537600001234567ULL;
    file->d_inode->i_last_access_time=132537600011234567ULL;
    file->d_inode->i_last_write_time=132537600021234567ULL;
    mark_image_dirty(wim_get_current_image_metadata(wim));
    assert(wimlib_overwrite(wim,WIMLIB_WRITE_FLAG_UNSAFE_COMPACT|WIMLIB_WRITE_FLAG_FSYNC,1)==0);
    wimlib_free(wim);wimlib_global_cleanup();
    puts("Prepared named stream, security descriptor, attributes and timestamps in WIM");
    return 0;
}

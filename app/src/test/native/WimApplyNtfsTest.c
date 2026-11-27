#include "config.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include "ntfs_bridge.h"
#include "types.h"
#include "volume.h"
#include "dir.h"
#include "inode.h"
#include "security.h"
#include "reparse.h"
#include "endians.h"
static int read_disk(void *context,int64_t offset,void *data,size_t size) {return pread(*(int *)context,data,size,offset)==(ssize_t)size?0:-1;}
static int write_disk(void *context,int64_t offset,const void *data,size_t size) {return pwrite(*(int *)context,data,size,offset)==(ssize_t)size?0:-1;}
static int sync_disk(void *context) {return fsync(*(int *)context);}
int main(int argc,char **argv)
{
    assert(argc==3);
    int fd=open(argv[1],O_RDWR);assert(fd>=0);
    struct stat info;assert(fstat(fd,&info)==0);
    rufus_disk disk={&fd,info.st_size,512,read_disk,write_disk,sync_disk};
    rufus_ntfs *bridge=rufus_ntfs_mount(&disk);assert(bridge);
    ntfs_volume *volume=rufus_ntfs_native_volume(bridge);assert(ntfs_open_secure(volume)==0);
    ntfs_inode *file=ntfs_pathname_to_inode(volume,NULL,"/rich/data.bin");assert(file);
    ntfs_inode *alias=ntfs_pathname_to_inode(volume,NULL,"/rich/alias.bin");assert(alias);
    assert(file->mft_no==alias->mft_no);
    assert(le64_to_cpu(file->creation_time)==132537600001234567ULL);
    assert(le64_to_cpu(file->last_access_time)==132537600011234567ULL);
    assert(le64_to_cpu(file->last_data_change_time)==132537600021234567ULL);
    assert(file->flags==(FILE_ATTR_READONLY|FILE_ATTR_HIDDEN|FILE_ATTR_SYSTEM|FILE_ATTR_ARCHIVE));
    FILE *input=fopen(argv[2],"rb");assert(input);
    char expected[4096],actual[4096];size_t size=fread(expected,1,sizeof(expected),input);assert(size>0 && feof(input));assert(fclose(input)==0);
    struct SECURITY_CONTEXT context={0};context.vol=volume;
    int length=ntfs_get_ntfs_acl(&context,file,actual,sizeof(actual));assert(length==(int)size);assert(memcmp(expected,actual,size)==0);
    assert(ntfs_inode_close(alias)==0);assert(ntfs_inode_close(file)==0);
    ntfs_inode *link=ntfs_pathname_to_inode(volume,NULL,"/unicode-link");assert(link);
    char reparse[4096];length=ntfs_get_ntfs_reparse_data(link,reparse,sizeof(reparse));assert(length>8);
    REPARSE_POINT *point=(REPARSE_POINT *)reparse;assert(point->reparse_tag==IO_REPARSE_TAG_SYMLINK);
    assert(ntfs_inode_close(link)==0);
    ntfs_index_ctx_put(volume->secure_xsii);ntfs_index_ctx_put(volume->secure_xsdh);
    assert(ntfs_inode_close(volume->secure_ni)==0);volume->secure_ni=NULL;
    assert(rufus_ntfs_unmount(bridge)==0);assert(close(fd)==0);
    puts("NTFS hard links, ACL bytes, attributes, 100ns timestamps and reparse tag preserved");
    return 0;
}

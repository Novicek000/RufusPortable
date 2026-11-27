#ifndef RUFUS_WIM_PROXY_H
#define RUFUS_WIM_PROXY_H
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* AppFuse permits only one open per inode. Duplicate the descriptor and keep
 * its path rather than resolving it to an inode that cannot be reopened. */
static inline int android_proxy_fd(const char *filename)
{
    static const char prefix[]="/proc/self/fd/";
    if(strncmp(filename,prefix,sizeof(prefix)-1)!=0) return -1;
    const char *digits=filename+sizeof(prefix)-1;
    if(*digits<'0' || *digits>'9') return -1;
    char *end;
    errno=0;
    long number=strtol(digits,&end,10);
    return errno || *end || number<0 || number>INT_MAX ? -1 : (int)number;
}
static inline int android_proxy_writable(int fd)
{
    int flags=fcntl(fd,F_GETFL);
    return flags>=0 && (flags&O_ACCMODE)==O_RDWR;
}
static inline int android_proxy_duplicate(int fd,int writable)
{
    if(writable && !android_proxy_writable(fd)) {errno=EACCES;return -1;}
    int duplicate=fcntl(fd,F_DUPFD_CLOEXEC,0);
    if(duplicate>=0 && lseek(duplicate,0,SEEK_SET)<0) {
        int saved=errno;close(duplicate);errno=saved;return -1;
    }
    return duplicate;
}
#endif

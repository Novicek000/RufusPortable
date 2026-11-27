#ifndef RUFUS_HIVE_FULL_READ_H
#define RUFUS_HIVE_FULL_READ_H
#include <unistd.h>
#include <errno.h>
static inline size_t full_read(int fd,void *buffer,size_t length)
{
    size_t done=0;
    while(done<length) {
        size_t count=length-done;if(count>131072)count=131072;
        ssize_t result=read(fd,(char *)buffer+done,count);
        if(result<0 && errno==EINTR)continue;
        if(result<=0)break;
        done+=(size_t)result;
    }
    return done;
}
#endif

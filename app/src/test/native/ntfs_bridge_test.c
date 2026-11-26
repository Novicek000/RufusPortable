#define _FILE_OFFSET_BITS 64
#include "ntfs_bridge.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct {
    int fd;
    int writes;
    int syncs;
    int failed_read;
    int failed_write;
} test_disk;

static int read_disk(void *context, int64_t offset, void *data, size_t length)
{
    test_disk *disk = context;
    if (disk->failed_read)
        return -1;
    return pread(disk->fd, data, length, offset) == (ssize_t)length ? 0 : -1;
}
static int write_disk(void *context, int64_t offset, const void *data, size_t length)
{
    test_disk *disk = context;
    if (disk->failed_write)
        return -1;
    disk->writes++;
    return pwrite(disk->fd, data, length, offset) == (ssize_t)length ? 0 : -1;
}
static int sync_disk(void *context)
{
    test_disk *disk = context;
    disk->syncs++;
    return fsync(disk->fd);
}
int main(int argc, char **argv)
{
    assert(argc == 2);
    test_disk disk = {.fd = open(argv[1], O_RDWR)};
    assert(disk.fd >= 0);
    struct stat info;
    assert(fstat(disk.fd, &info) == 0);
    rufus_disk device = {
        .context = &disk, .length = info.st_size, .sector_size = 512,
        .read = read_disk, .write = write_disk, .sync = sync_disk
    };
    int64_t original_size = device.length;
    device.length = 16 * 1024 * 1024;
    assert(rufus_ntfs_format(&device, "FULL_TEST", 4096, 2048, 0) == 0);
    device.length = original_size;
    disk.failed_write = 1;
    assert(rufus_ntfs_format(&device, "FAILED", 4096, 2048, 1) == -1);
    disk.failed_write = 0;
    assert(rufus_ntfs_format(&device, "RUFUS_TEST", 4096, 2048, 1) == 0);
    unsigned char boot[512];
    assert(pread(disk.fd, boot, sizeof(boot), 0) == sizeof(boot));
    assert(memcmp(boot + 3, "NTFS    ", 8) == 0);
    assert(boot[28] == 0 && boot[29] == 8 && boot[30] == 0 && boot[31] == 0);
    rufus_ntfs *volume = rufus_ntfs_mount(&device);
    assert(volume);
    uint16_t directory[] = {'s','o','u','r','c','e','s'};
    assert(rufus_ntfs_mkdir(volume, directory, 7) == 0);
    uint16_t nested[] = {'s','o','u','r','c','e','s','/','p','l','-',0x17c,0xf3,0x142,0x107};
    assert(rufus_ntfs_mkdir(volume, nested, 15) == 0);
    uint16_t path[] = {'s','o','u','r','c','e','s','/','t','e','s','t','.','b','i','n'};
    rufus_ntfs_file *file = rufus_ntfs_create(volume, path, 16);
    assert(file);
    /* A live file prevents unmount and keeps all handles valid. */
    assert(rufus_ntfs_unmount(volume) == -1 && errno == EBUSY);
    unsigned char bytes[131072];
    for (size_t i = 0; i < sizeof(bytes); i++)
        bytes[i] = (unsigned char)(i * 37 + 11);
    for (int i = 0; i < 5; i++)
        assert(rufus_ntfs_write(file, (int64_t)i * sizeof(bytes), bytes, sizeof(bytes)) == 0);
    assert(rufus_ntfs_close_file(file) == 0);
    uint16_t traversal[] = {'.','.','/','b','a','d'};
    assert(rufus_ntfs_create(volume, traversal, 6) == NULL && errno == EINVAL);
    assert(rufus_ntfs_unmount(volume) == 0);
    assert(disk.writes > 0 && disk.syncs > 0);
    /* Mount failure must leave the adapter reusable without dangling handles. */
    disk.failed_read = 1;
    assert(rufus_ntfs_mount(&device) == NULL);
    disk.failed_read = 0;
    volume = rufus_ntfs_mount(&device);
    assert(volume);
    assert(rufus_ntfs_unmount(volume) == 0);
    close(disk.fd);
    puts("NTFS native quick/full formatting, retry, write, Unicode and lifetime tests passed");
    return 0;
}

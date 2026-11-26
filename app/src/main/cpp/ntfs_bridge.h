#ifndef RUFUS_NTFS_BRIDGE_H
#define RUFUS_NTFS_BRIDGE_H

#include <stdint.h>
#include <stddef.h>

typedef struct {
    void *context;
    int64_t length;
    int sector_size;
    int (*read)(void *context, int64_t offset, void *data, size_t length);
    int (*write)(void *context, int64_t offset, const void *data, size_t length);
    int (*sync)(void *context);
} rufus_disk;

typedef struct rufus_ntfs rufus_ntfs;
typedef struct rufus_ntfs_file rufus_ntfs_file;

/* The disk and its context must remain valid until unmount has returned. */
rufus_ntfs *rufus_ntfs_mount(rufus_disk *disk);
int rufus_ntfs_mkdir(rufus_ntfs *volume, const uint16_t *path, size_t length);
rufus_ntfs_file *rufus_ntfs_create(rufus_ntfs *volume, const uint16_t *path, size_t length);
int rufus_ntfs_write(rufus_ntfs_file *file, int64_t offset, const void *data, size_t length);
int rufus_ntfs_close_file(rufus_ntfs_file *file);
int rufus_ntfs_unmount(rufus_ntfs *volume);
#endif

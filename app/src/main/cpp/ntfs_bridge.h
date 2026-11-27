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
/* Serializes the formatter's upstream process-global state. Quick and full formats supported. */
int rufus_ntfs_format(rufus_disk *disk, const char *label, int cluster_size,
        int64_t partition_start_sector, int quick);
int rufus_ntfs_bind_formatter(rufus_disk *disk);
rufus_ntfs *rufus_ntfs_mount(rufus_disk *disk);
/* Borrowed native volume, valid only until unmount. */
void *rufus_ntfs_native_volume(rufus_ntfs *volume);
int rufus_ntfs_mkdir(rufus_ntfs *volume, const uint16_t *path, size_t length);
rufus_ntfs_file *rufus_ntfs_create(rufus_ntfs *volume, const uint16_t *path, size_t length);
rufus_ntfs_file *rufus_ntfs_open(rufus_ntfs *volume, const uint16_t *path, size_t length);
rufus_ntfs_file *rufus_ntfs_open_update(rufus_ntfs *volume, const uint16_t *path, size_t length);
int64_t rufus_ntfs_file_size(rufus_ntfs_file *file);
int rufus_ntfs_truncate(rufus_ntfs_file *file, int64_t length);
int rufus_ntfs_sync_file(rufus_ntfs_file *file);
int64_t rufus_ntfs_read(rufus_ntfs_file *file, int64_t offset, void *data, size_t length);
int rufus_ntfs_write(rufus_ntfs_file *file, int64_t offset, const void *data, size_t length);
int rufus_ntfs_close_file(rufus_ntfs_file *file);
int rufus_ntfs_unmount(rufus_ntfs *volume);
#endif

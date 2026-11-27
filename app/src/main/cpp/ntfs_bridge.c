#include "config.h"
#include "ntfs_bridge.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <linux/fs.h>
#include <unistd.h>

#include "types.h"
#include "device.h"
#include "volume.h"
#include "inode.h"
#include "attrib.h"
#include "dir.h"
#include "unistr.h"
#include "endians.h"

struct rufus_ntfs {
    rufus_disk *disk;
    struct ntfs_device *device;
    ntfs_volume *volume;
    size_t open_files;
};

struct rufus_ntfs_file {
    rufus_ntfs *owner;
    ntfs_inode *inode;
    ntfs_attr *attribute;
    int writable;
};

typedef struct {
    rufus_disk *disk;
    int64_t position;
    int owned;
} bridge_device;

static _Thread_local rufus_disk *formatter_disk;
static _Thread_local int formatter_failed;

int rufus_ntfs_bind_formatter(rufus_disk *disk)
{
    int previous_failure = formatter_failed;
    formatter_disk = disk;
    formatter_failed = 0;
    return previous_failure;
}

static int device_open(struct ntfs_device *device, int flags)
{
    if (NDevOpen(device)) {
        errno = EBUSY;
        return -1;
    }
    if (!device->d_private) {
        if (!formatter_disk) {
            errno = ENODEV;
            return -1;
        }
        bridge_device *bridge = calloc(1, sizeof(*bridge));
        if (!bridge)
            return -1;
        bridge->disk = formatter_disk;
        bridge->owned = 1;
        device->d_private = bridge;
    }
    NDevSetOpen(device);
    if ((flags & O_ACCMODE) == O_RDONLY)
        NDevSetReadOnly(device);
    return 0;
}

static int device_sync(struct ntfs_device *device)
{
    bridge_device *bridge = device->d_private;
    if (bridge->disk->sync(bridge->disk->context) != 0) {
        if (formatter_disk == bridge->disk)
            formatter_failed = 1;
        errno = EIO;
        return -1;
    }
    NDevClearDirty(device);
    return 0;
}

static int device_close(struct ntfs_device *device)
{
    int result = device_sync(device);
    NDevClearOpen(device);
    bridge_device *bridge = device->d_private;
    if (bridge->owned) {
        free(bridge);
        device->d_private = NULL;
    }
    return result;
}

static s64 device_seek(struct ntfs_device *device, s64 offset, int whence)
{
    bridge_device *bridge = device->d_private;
    int64_t origin;
    switch (whence) {
    case SEEK_SET: origin = 0; break;
    case SEEK_CUR: origin = bridge->position; break;
    case SEEK_END: origin = bridge->disk->length; break;
    default: errno = EINVAL; return -1;
    }
    if (offset < -origin || offset > bridge->disk->length - origin) {
        errno = EINVAL;
        return -1;
    }
    bridge->position = origin + offset;
    return bridge->position;
}

static s64 transfer(struct ntfs_device *device, void *data, s64 count, s64 offset, int writing)
{
    bridge_device *bridge = device->d_private;
    rufus_disk *disk = bridge->disk;
    if (!NDevOpen(device) || count < 0 || offset < 0 || offset > disk->length ||
            count > disk->length - offset || (writing && NDevReadOnly(device))) {
        errno = writing && NDevReadOnly(device) ? EROFS : EINVAL;
        return -1;
    }
    s64 done = 0;
    while (done < count) {
        size_t chunk = count - done > 131072 ? 131072 : (size_t)(count - done);
        void *buffer = (unsigned char *)data + done;
        int result = writing ? disk->write(disk->context, offset + done, buffer, chunk) :
                               disk->read(disk->context, offset + done, buffer, chunk);
        if (result != 0) {
            if (formatter_disk == disk)
                formatter_failed = 1;
            errno = EIO;
            return -1;
        }
        if (writing)
            NDevSetDirty(device);
        done += chunk;
    }
    return done;
}

static s64 device_pread(struct ntfs_device *device, void *data, s64 count, s64 offset)
{
    return transfer(device, data, count, offset, 0);
}

static s64 device_pwrite(struct ntfs_device *device, const void *data, s64 count, s64 offset)
{
    return transfer(device, (void *)data, count, offset, 1);
}

static s64 device_read(struct ntfs_device *device, void *data, s64 count)
{
    bridge_device *bridge = device->d_private;
    s64 result = device_pread(device, data, count, bridge->position);
    if (result > 0)
        bridge->position += result;
    return result;
}

static s64 device_write(struct ntfs_device *device, const void *data, s64 count)
{
    bridge_device *bridge = device->d_private;
    s64 result = device_pwrite(device, data, count, bridge->position);
    if (result > 0)
        bridge->position += result;
    return result;
}

static int device_stat(struct ntfs_device *device, struct stat *result)
{
    bridge_device *bridge = device->d_private;
    memset(result, 0, sizeof(*result));
    result->st_mode = S_IFBLK | 0600;
    result->st_size = bridge->disk->length;
    result->st_blksize = bridge->disk->sector_size;
    result->st_blocks = bridge->disk->length / 512;
    return 0;
}

static int device_ioctl(struct ntfs_device *device, unsigned long request, void *value)
{
    bridge_device *bridge = device->d_private;
    switch (request) {
    case BLKGETSIZE64: *(uint64_t *)value = bridge->disk->length; return 0;
    case BLKGETSIZE: *(unsigned long *)value = bridge->disk->length / 512; return 0;
    case BLKSSZGET: *(int *)value = bridge->disk->sector_size; return 0;
    case BLKROGET: *(int *)value = NDevReadOnly(device) ? 1 : 0; return 0;
    case BLKBSZSET: return 0;
    default: errno = ENOTTY; return -1;
    }
}

static struct ntfs_device_operations operations = {
    .open = device_open, .close = device_close, .seek = device_seek,
    .read = device_read, .write = device_write, .pread = device_pread,
    .pwrite = device_pwrite, .sync = device_sync, .stat = device_stat,
    .ioctl = device_ioctl
};

struct ntfs_device_operations rufus_format_ops = {
    .open = device_open, .close = device_close, .seek = device_seek,
    .read = device_read, .write = device_write, .pread = device_pread,
    .pwrite = device_pwrite, .sync = device_sync, .stat = device_stat,
    .ioctl = device_ioctl
};

rufus_ntfs *rufus_ntfs_mount(rufus_disk *disk)
{
    if (!disk || !disk->read || !disk->write || !disk->sync || disk->length <= 0 ||
            disk->sector_size < 512 || disk->sector_size > 4096 ||
            (disk->sector_size & (disk->sector_size - 1))) {
        errno = EINVAL;
        return NULL;
    }
    rufus_ntfs *result = calloc(1, sizeof(*result));
    bridge_device *bridge = calloc(1, sizeof(*bridge));
    if (!result || !bridge) {
        free(result);
        free(bridge);
        return NULL;
    }
    bridge->disk = disk;
    result->disk = disk;
    result->device = ntfs_device_alloc("android-usb-partition", 0, &operations, bridge);
    if (!result->device) {
        free(bridge);
        free(result);
        return NULL;
    }
    result->volume = ntfs_device_mount(result->device, 0);
    if (!result->volume) {
        int saved = errno;
        if (NDevOpen(result->device))
            device_close(result->device);
        ntfs_device_free(result->device);
        free(bridge);
        free(result);
        errno = saved;
        return NULL;
    }
    return result;
}

static ntfs_inode *resolve_entry(rufus_ntfs *volume, const uint16_t *path, size_t length, mode_t create_mode)
{
    if (!volume || !path || !length || length > 32767 || path[0] == '/') {
        errno = EINVAL;
        return NULL;
    }
    ntfs_inode *parent = ntfs_inode_open(volume->volume, FILE_root);
    if (!parent)
        return NULL;
    size_t start = 0;
    for (size_t pos = 0; pos <= length; pos++) {
        if (pos < length && path[pos] != '/') {
            if (!path[pos] || path[pos] == '\\') {
                errno = EINVAL;
                break;
            }
            continue;
        }
        size_t count = pos - start;
        if (!count || count > 255 || (count == 1 && path[start] == '.') ||
                (count == 2 && path[start] == '.' && path[start + 1] == '.')) {
            errno = EINVAL;
            break;
        }
        ntfschar name[255];
        for (size_t i = 0; i < count; i++)
            name[i] = cpu_to_le16(path[start + i]);
        if (pos == length) {
            ntfs_inode *created;
            if (create_mode) {
                created = ntfs_create(parent, const_cpu_to_le32(0), name, count, create_mode);
            } else {
                u64 reference = ntfs_inode_lookup_by_name(parent, name, count);
                created = reference == (u64)-1 ? NULL : ntfs_inode_open(volume->volume, reference);
            }
            int saved = errno;
            int closed = ntfs_inode_close(parent);
            if (created && closed != 0) {
                ntfs_inode_close(created);
                return NULL;
            }
            errno = saved;
            return created;
        }
        u64 reference = ntfs_inode_lookup_by_name(parent, name, count);
        if (reference == (u64)-1)
            break;
        ntfs_inode *next = ntfs_inode_open(volume->volume, reference);
        int saved = errno;
        int closed = ntfs_inode_close(parent);
        parent = next;
        if (!parent || closed != 0) {
            if (parent)
                ntfs_inode_close(parent);
            errno = saved;
            return NULL;
        }
        if (!(parent->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
            errno = ENOTDIR;
            break;
        }
        start = pos + 1;
    }
    int saved = errno;
    ntfs_inode_close(parent);
    errno = saved;
    return NULL;
}

int rufus_ntfs_mkdir(rufus_ntfs *volume, const uint16_t *path, size_t length)
{
    ntfs_inode *inode = resolve_entry(volume, path, length, S_IFDIR);
    return inode ? ntfs_inode_close(inode) : -1;
}

static rufus_ntfs_file *open_file(rufus_ntfs *volume, const uint16_t *path, size_t length, int creating)
{
    rufus_ntfs_file *file = calloc(1, sizeof(*file));
    if (!file)
        return NULL;
    file->owner = volume;
    file->writable = creating != 0;
    file->inode = resolve_entry(volume, path, length, creating == 1 ? S_IFREG : 0);
    if (file->inode)
        file->attribute = ntfs_attr_open(file->inode, AT_DATA, AT_UNNAMED, 0);
    if (!file->attribute) {
        int saved = errno;
        if (file->inode)
            ntfs_inode_close(file->inode);
        free(file);
        errno = saved;
        return NULL;
    }
    volume->open_files++;
    return file;
}

rufus_ntfs_file *rufus_ntfs_create(rufus_ntfs *volume, const uint16_t *path, size_t length)
{
    return open_file(volume, path, length, 1);
}

rufus_ntfs_file *rufus_ntfs_open(rufus_ntfs *volume, const uint16_t *path, size_t length)
{
    return open_file(volume, path, length, 0);
}

rufus_ntfs_file *rufus_ntfs_open_update(rufus_ntfs *volume, const uint16_t *path, size_t length)
{
    return open_file(volume, path, length, 2);
}

int64_t rufus_ntfs_file_size(rufus_ntfs_file *file)
{
    if (!file) { errno = EINVAL; return -1; }
    return file->attribute->data_size;
}

int rufus_ntfs_truncate(rufus_ntfs_file *file, int64_t length)
{
    if (!file || length < 0) { errno = EINVAL; return -1; }
    if (!file->writable) { errno = EROFS; return -1; }
    return ntfs_attr_truncate(file->attribute, length);
}

int rufus_ntfs_sync_file(rufus_ntfs_file *file)
{
    if (!file) { errno = EINVAL; return -1; }
    if (ntfs_inode_sync(file->inode) != 0) return -1;
    return file->owner->disk->sync(file->owner->disk->context);
}

int64_t rufus_ntfs_read(rufus_ntfs_file *file, int64_t offset, void *data, size_t length)
{
    if (!file || offset < 0 || length > (uint64_t)(INT64_MAX - offset) || (length && !data)) {
        errno = EINVAL;
        return -1;
    }
    return ntfs_attr_pread(file->attribute, offset, length, data);
}

int rufus_ntfs_write(rufus_ntfs_file *file, int64_t offset, const void *data, size_t length)
{
    if (!file || offset < 0 || length > (uint64_t)(INT64_MAX - offset) || (length && !data)) {
        errno = EINVAL;
        return -1;
    }
    if (!file->writable) {
        errno = EROFS;
        return -1;
    }
    s64 written = ntfs_attr_pwrite(file->attribute, offset, length, data);
    if (written >= 0 && written != (s64)length) errno = EIO;
    return written == (s64)length ? 0 : -1;
}

int rufus_ntfs_close_file(rufus_ntfs_file *file)
{
    if (!file) {
        errno = EINVAL;
        return -1;
    }
    ntfs_attr_close(file->attribute);
    int result = ntfs_inode_close(file->inode);
    file->owner->open_files--;
    free(file);
    return result;
}

int rufus_ntfs_unmount(rufus_ntfs *volume)
{
    if (!volume || volume->open_files) {
        errno = EBUSY;
        return -1;
    }
    void *bridge = volume->device->d_private;
    int result = ntfs_umount(volume->volume, FALSE);
    free(bridge);
    free(volume);
    return result;
}

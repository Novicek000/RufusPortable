#include "config.h"
#include "ntfs_bridge.h"
#include "device.h"
#include "utils.h"
#include <pthread.h>

/*
 * Compile the pinned formatter into this translation unit so its internal state
 * can be reset between calls. The CLI entry point is discarded by section GC.
 * The configured library has no Unix device backend; all I/O uses our callbacks.
 */
#undef HAVE_CONFIG_H
#undef NO_NTFS_DEVICE_DEFAULT_IO_OPS
#define ntfs_device_default_io_ops rufus_format_ops
extern struct ntfs_device_operations rufus_format_ops;
#define main rufus_mkntfs_unused_cli_main
#include "mkntfs.c"
#undef main

static pthread_mutex_t formatter_mutex = PTHREAD_MUTEX_INITIALIZER;

static void reset_formatter(void)
{
    g_mft_bitmap_byte_size = 0;
    g_lcn_bitmap_byte_size = 0;
    g_dynamic_buf_size = 0;
    g_mft_size = 0;
    g_mft_lcn = 0;
    g_mftmirr_lcn = 0;
    g_logfile_lcn = 0;
    g_logfile_size = 0;
    g_mft_zone_end = 0;
    g_num_bad_blocks = 0;
    /* Upstream cleanup frees this list without resetting its head. */
    g_allocation = NULL;
}

int rufus_ntfs_format(rufus_disk *disk, const char *label, int cluster_size,
        int64_t partition_start_sector, int quick)
{
    if (!disk || !disk->read || !disk->write || !disk->sync || !label ||
            disk->sector_size < 512 || disk->sector_size > 4096 ||
            (disk->sector_size & (disk->sector_size - 1)) ||
            disk->length < 16 * 1024 * 1024 || disk->length % disk->sector_size ||
            cluster_size < disk->sector_size || cluster_size > 2 * 1024 * 1024 ||
            (cluster_size & (cluster_size - 1)) || partition_start_sector < 0 ||
            partition_start_sector > UINT32_MAX) {
        errno = EINVAL;
        return -1;
    }
    ntfschar *unicode = NULL;
    int characters = ntfs_mbstoucs(label, &unicode);
    free(unicode);
    if (characters < 0 || characters > 32) {
        errno = EINVAL;
        return -1;
    }
    int lock_error = pthread_mutex_lock(&formatter_mutex);
    if (lock_error) {
        errno = lock_error;
        return -1;
    }
    reset_formatter();
    mkntfs_init_options(&opts);
    opts.dev_name = "android-usb-partition";
    opts.label = (char *)label;
    opts.quick_format = quick ? TRUE : FALSE;
    opts.force = TRUE;
    opts.sector_size = disk->sector_size;
    opts.num_sectors = disk->length / disk->sector_size;
    opts.cluster_size = cluster_size;
    opts.part_start_sect = partition_start_sector;
    opts.heads = 255;
    opts.sectors_per_track = 63;
    rufus_ntfs_bind_formatter(disk);
    errno = 0;
    int result = mkntfs_redirect(&opts);
    int saved_error = errno;
    int callback_failed = rufus_ntfs_bind_formatter(NULL);
    free(g_upcaseinfo);
    g_upcaseinfo = NULL;
    reset_formatter();
    pthread_mutex_unlock(&formatter_mutex);
    if (result || callback_failed) {
        errno = saved_error ? saved_error : EIO;
        return -1;
    }
    return 0;
}

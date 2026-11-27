#ifndef RUFUS_WIM_NTFS_H
#define RUFUS_WIM_NTFS_H
#include "ntfs_bridge.h"
#define RUFUS_WIM_NTFS_TARGET "rufus-callback-ntfs"
/* The caller owns the disk and remains on the same thread through extraction. */
int rufus_wim_bind_target(rufus_disk *disk);
rufus_ntfs *rufus_wim_mount_target(void);
#endif

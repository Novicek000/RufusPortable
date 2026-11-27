#include <errno.h>
#include "wim_ntfs.h"
static _Thread_local rufus_disk *target_disk;
int rufus_wim_bind_target(rufus_disk *disk)
{
    if(disk && target_disk) {errno=EBUSY;return -1;}
    target_disk=disk;
    return 0;
}
rufus_ntfs *rufus_wim_mount_target(void)
{
    if(!target_disk) {errno=ENODEV;return NULL;}
    return rufus_ntfs_mount(target_disk);
}

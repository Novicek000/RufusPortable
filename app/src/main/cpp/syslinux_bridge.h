#ifndef RUFUS_SYSLINUX_BRIDGE_H
#define RUFUS_SYSLINUX_BRIDGE_H
#include <stdint.h>
#include <stddef.h>

/* Copies are owned by the caller. Sector map addresses are relative to the volume. */
int rufus_syslinux_patch(uint8_t *image,size_t image_length,uint8_t *bss,size_t bss_length,
        uint8_t *vbr,size_t vbr_length,const uint64_t *sectors,size_t count,
        const char *directory,int stupid,int raid,uint8_t *adv,size_t adv_length);
#endif

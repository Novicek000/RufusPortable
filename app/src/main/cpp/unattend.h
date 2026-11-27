#ifndef RUFUS_UNATTEND_H
#define RUFUS_UNATTEND_H
#include <stddef.h>
/* Flag values and masks from the reference Rufus rufus.h (GPL-3.0+). */
#define UNATTEND_SECUREBOOT_TPM_MINRAM      0x00001
#define UNATTEND_NO_ONLINE_ACCOUNT          0x00004
#define UNATTEND_NO_DATA_COLLECTION         0x00008
#define UNATTEND_OFFLINE_INTERNAL_DRIVES    0x00010
#define UNATTEND_DUPLICATE_LOCALE           0x00020
#define UNATTEND_SET_USER                   0x00040
#define UNATTEND_DISABLE_BITLOCKER          0x00080
#define UNATTEND_FORCE_S_MODE               0x00100
#define UNATTEND_USE_MS2023_BOOTLOADERS     0x00200
#define UNATTEND_APPLY_SKUSIPOLICY          0x00400
#define UNATTEND_SILENT_INSTALL             0x00800
#define UNATTEND_QOL_ENHANCEMENTS           0x01000
#define UNATTEND_FULL_MASK                  0x01FFF
#define UNATTEND_DEFAULT_MASK               0x016FF		// Mask of values that are persisted
#define UNATTEND_WINDOWS_TO_GO              0x10000		// Special flag for Windows To Go

#define UNATTEND_WINPE_SETUP_MASK           (UNATTEND_SECUREBOOT_TPM_MINRAM | UNATTEND_SILENT_INSTALL)
#define UNATTEND_SPECIALIZE_DEPLOYMENT_MASK (UNATTEND_NO_ONLINE_ACCOUNT | UNATTEND_QOL_ENHANCEMENTS)
#define UNATTEND_OOBE_SHELL_SETUP_MASK      (UNATTEND_NO_DATA_COLLECTION | UNATTEND_SET_USER | UNATTEND_DUPLICATE_LOCALE | UNATTEND_SILENT_INSTALL)
#define UNATTEND_OOBE_INTERNATIONAL_MASK    (UNATTEND_DUPLICATE_LOCALE)
#define UNATTEND_OOBE_MASK                  (UNATTEND_OOBE_SHELL_SETUP_MASK | UNATTEND_OOBE_INTERNATIONAL_MASK | UNATTEND_DISABLE_BITLOCKER | \
                                             UNATTEND_USE_MS2023_BOOTLOADERS | UNATTEND_APPLY_SKUSIPOLICY | UNATTEND_QOL_ENHANCEMENTS)
#define UNATTEND_OFFLINE_SERVICING_MASK     (UNATTEND_OFFLINE_INTERNAL_DRIVES | UNATTEND_FORCE_S_MODE)
#define UNATTEND_DEFAULT_SELECTION_MASK     (UNATTEND_SECUREBOOT_TPM_MINRAM | UNATTEND_NO_ONLINE_ACCOUNT | UNATTEND_OFFLINE_INTERNAL_DRIVES)
typedef struct {
    int edition_index;
    const char *username,*timezone,*input_locale,*system_locale,*user_locale;
    const char *ui_language,*fallback_language,*setup_language;
} rufus_unattend_options;
char *rufus_unattend_xml(int arch,int flags,const rufus_unattend_options *options,size_t *length);
#endif

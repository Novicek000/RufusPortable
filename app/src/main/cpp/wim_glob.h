#ifndef RUFUS_WIM_GLOB_H
#define RUFUS_WIM_GLOB_H

#include <glob.h>

/* wimlib's Windows backend also supports only basename patterns and these flags. */
int rufus_wim_glob(const char *pattern, int flags,
        int (*error_callback)(const char *, int), glob_t *result);
void rufus_wim_globfree(glob_t *result);

#if defined(__ANDROID__) && __ANDROID_API__ < 28
#define glob rufus_wim_glob
#define globfree rufus_wim_globfree
#endif

#endif

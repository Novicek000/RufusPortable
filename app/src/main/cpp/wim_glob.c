#define _GNU_SOURCE 1
#include "wim_glob.h"
#include <dirent.h>
#include <errno.h>
#include <fnmatch.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

void rufus_wim_globfree(glob_t *result)
{
    if (!result)
        return;
    for (size_t i = 0; i < result->gl_pathc; i++)
        free(result->gl_pathv[i]);
    free(result->gl_pathv);
    result->gl_pathv = NULL;
    result->gl_pathc = 0;
}

static int compare_paths(const void *first, const void *second)
{
    return strcmp(*(const char *const *)first, *(const char *const *)second);
}

int rufus_wim_glob(const char *pattern, int flags,
        int (*error_callback)(const char *, int), glob_t *result)
{
    if (!pattern || !result || error_callback || !(flags & GLOB_ERR) ||
            (flags & ~(GLOB_ERR | GLOB_NOSORT))) {
        errno = EINVAL;
        return GLOB_ABORTED;
    }
    memset(result, 0, sizeof(*result));
    const char *separator = strrchr(pattern, '/');
    size_t prefix = separator ? (size_t)(separator - pattern + 1) : 0;
    char *directory = prefix ? strndup(pattern, prefix) : strdup(".");
    if (!directory)
        return GLOB_NOSPACE;
    DIR *stream = opendir(directory);
    free(directory);
    if (!stream)
        return GLOB_ABORTED;
    int status = 0;
    size_t allocated = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(stream);
        if (!entry) {
            if (errno)
                status = GLOB_ABORTED;
            break;
        }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..") ||
                fnmatch(pattern + prefix, entry->d_name, FNM_CASEFOLD) != 0)
            continue;
        size_t size = strlen(entry->d_name);
        if (size > SIZE_MAX - prefix - 1) {
            errno = ENOMEM;
            status = GLOB_NOSPACE;
            break;
        }
        if (result->gl_pathc + 1 >= allocated) {
            if (allocated > SIZE_MAX / 2 / sizeof(char *)) {
                errno = ENOMEM;
                status = GLOB_NOSPACE;
                break;
            }
            size_t next = allocated ? allocated * 2 : 8;
            char **paths = realloc(result->gl_pathv, next * sizeof(*paths));
            if (!paths) {
                status = GLOB_NOSPACE;
                break;
            }
            result->gl_pathv = paths;
            allocated = next;
        }
        char *path = malloc(prefix + size + 1);
        if (!path) {
            status = GLOB_NOSPACE;
            break;
        }
        memcpy(path, pattern, prefix);
        memcpy(path + prefix, entry->d_name, size + 1);
        result->gl_pathv[result->gl_pathc++] = path;
        result->gl_pathv[result->gl_pathc] = NULL;
    }
    int saved_error = errno;
    closedir(stream);
    if (status) {
        rufus_wim_globfree(result);
        errno = saved_error;
        return status;
    }
    if (!result->gl_pathc)
        return GLOB_NOMATCH;
    if (!(flags & GLOB_NOSORT))
        qsort(result->gl_pathv, result->gl_pathc, sizeof(char *), compare_paths);
    return 0;
}

#include "wim_glob.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv)
{
    assert(argc == 2);
    const char *names[] = {"install.swm", "INSTALL2.SWM", ".install3.swm"};
    char path[4096];
    for (size_t i = 0; i < 3; i++) {
        snprintf(path, sizeof(path), "%s/%s", argv[1], names[i]);
        FILE *file = fopen(path, "wb");
        assert(file);
        fclose(file);
    }
    snprintf(path, sizeof(path), "%s/*install*.swm", argv[1]);
    glob_t result;
    assert(rufus_wim_glob(path, GLOB_ERR | GLOB_NOSORT, NULL, &result) == 0);
    assert(result.gl_pathc == 3);
    for (size_t i = 0; i < result.gl_pathc; i++)
        assert(remove(result.gl_pathv[i]) == 0);
    rufus_wim_globfree(&result);
    assert(result.gl_pathc == 0 && result.gl_pathv == NULL);
    assert(rufus_wim_glob(path, GLOB_ERR | GLOB_NOSORT, NULL, &result) == GLOB_NOMATCH);
    snprintf(path, sizeof(path), "%s/missing-parent/*.swm", argv[1]);
    assert(rufus_wim_glob(path, GLOB_ERR | GLOB_NOSORT, NULL, &result) == GLOB_ABORTED);
    assert(errno == ENOENT);
    errno = 0;
    assert(rufus_wim_glob(path, GLOB_APPEND, NULL, &result) == GLOB_ABORTED);
    assert(errno == EINVAL);
    puts("WIM resource glob compatibility tests passed");
    return 0;
}

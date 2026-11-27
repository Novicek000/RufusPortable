#ifndef RUFUS_HIVE_ICONV_H
#define RUFUS_HIVE_ICONV_H
#include <stddef.h>
typedef void *iconv_t;
#define iconv_open rufus_hive_iconv_open
#define iconv_close rufus_hive_iconv_close
#define iconv rufus_hive_iconv
iconv_t iconv_open(const char *to,const char *from);
int iconv_close(iconv_t converter);
size_t iconv(iconv_t converter,char **input,size_t *input_left,char **output,size_t *output_left);
#endif

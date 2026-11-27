#ifndef RUFUS_HIVE_C_CTYPE_H
#define RUFUS_HIVE_C_CTYPE_H
static inline int c_isprint(int value) {return value>=32 && value<127;}
static inline int c_toupper(int value) {return value>='a' && value<='z'?value-'a'+'A':value;}
#endif

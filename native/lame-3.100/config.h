#ifndef LAME_ANDROID_CONFIG_H
#define LAME_ANDROID_CONFIG_H

#include <stdint.h>

#define STDC_HEADERS 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STRCHR 1
#define HAVE_MEMCPY 1
#define SIZEOF_DOUBLE 8
#define SIZEOF_FLOAT 4
#define SIZEOF_INT 4
#define SIZEOF_LONG 4
#define SIZEOF_SHORT 2
#define SIZEOF_UNSIGNED_INT 4
#define SIZEOF_UNSIGNED_LONG 4
#define SIZEOF_UNSIGNED_SHORT 2
#define USE_FAST_LOG 1
#define LAME_LIBRARY_BUILD 1

typedef double ieee754_float64_t;
typedef float ieee754_float32_t;
typedef long double ieee854_float80_t;

#endif

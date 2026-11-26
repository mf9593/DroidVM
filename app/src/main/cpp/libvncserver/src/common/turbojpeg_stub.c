/*
 * Stub TurboJPEG implementation for Android NDK builds.
 * libjpeg-turbo is not available in the NDK, so this provides
 * stub implementations that return errors. JPEG compression in
 * tight VNC encoding will not work, but all other VNC functionality
 * will function normally.
 *
 * The real turbojpeg.c requires jpeglib.h from libjpeg-turbo.
 */

#include <stdio.h>
#include <string.h>
#include "./turbojpeg.h"

static char error_str[256];

tjhandle tjInitDecompress(void) {
    snprintf(error_str, sizeof(error_str), "TurboJPEG: libjpeg-turbo not available");
    return NULL;
}

tjhandle tjInitCompress(void) {
    snprintf(error_str, sizeof(error_str), "TurboJPEG: libjpeg-turbo not available");
    return NULL;
}

int tjDecompress(tjhandle handle,
                 unsigned char *jpegBuf, unsigned long jpegSize, unsigned char *dstBuf,
                 int width, int pitch, int height, int pixelSize, int flags) {
    snprintf(error_str, sizeof(error_str), "TurboJPEG: decompression not available");
    return -1;
}

int tjDecompressHeader(tjhandle handle,
                       unsigned char *jpegBuf, unsigned long jpegSize, int *width, int *height) {
    snprintf(error_str, sizeof(error_str), "TurboJPEG: decompression not available");
    return -1;
}

int tjCompress2(tjhandle handle, unsigned char *srcBuf,
                int width, int pitch, int height, int pixelFormat,
                unsigned char **jpegBuf, unsigned long *jpegSize,
                int jpegSubsamp, int jpegQual, int flags) {
    snprintf(error_str, sizeof(error_str), "TurboJPEG: compression not available");
    return -1;
}

int tjCompress(tjhandle handle, unsigned char *srcBuf,
               int width, int pitch, int height, int pixelSize,
               unsigned char *dstBuf, unsigned long *compressedSize,
               int jpegSubsamp, int jpegQual, int flags) {
    snprintf(error_str, sizeof(error_str), "TurboJPEG: compression not available");
    return -1;
}

unsigned long TJBUFSIZE(int width, int height) {
    return (unsigned long)width * height * 3;
}

int tjDestroy(tjhandle handle) {
    return 0;
}

char* tjGetErrorStr(void) {
    return error_str;
}

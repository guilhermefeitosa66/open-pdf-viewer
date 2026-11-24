// Thin JNI layer over PDFium. Only the calls the app uses.
// Not thread-safe: the Kotlin side serializes every call on one thread.

#include <jni.h>
#include <android/bitmap.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cmath>
#include <cstring>
#include <string>
#include <unordered_set>
#include <vector>

#include "fpdf_doc.h"
#include "fpdf_text.h"
#include "fpdfview.h"

namespace {

struct Document {
    int fd = -1;
    FPDF_FILEACCESS access{};
    FPDF_DOCUMENT doc = nullptr;
};

int readBlock(void* param, unsigned long position, unsigned char* buf, unsigned long size) {
    auto* d = static_cast<Document*>(param);
    unsigned long done = 0;
    while (done < size) {
        ssize_t r = pread(d->fd, buf + done, size - done, static_cast<off_t>(position + done));
        if (r <= 0) return 0;
        done += static_cast<unsigned long>(r);
    }
    return 1;
}

bool initialized = false;

// Error of the last failed openDocument. Handles are returned as opaque jlongs: on arm64 heap
// pointers carry a tag in the top byte, so a valid handle can be negative and never doubles as
// an error code.
unsigned long lastOpenError = 0;

// Device space used to convert between page points and normalized [0,1] coordinates.
// 100 device units per point keeps the integer rounding of FPDF_PageToDevice negligible.
constexpr double kUnitsPerPoint = 100.0;

void deviceSize(FPDF_PAGE page, int* w, int* h) {
    *w = static_cast<int>(std::lround(FPDF_GetPageWidthF(page) * kUnitsPerPoint));
    *h = static_cast<int>(std::lround(FPDF_GetPageHeightF(page) * kUnitsPerPoint));
    if (*w < 1) *w = 1;
    if (*h < 1) *h = 1;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_init(JNIEnv*, jobject) {
    if (initialized) return;
    FPDF_LIBRARY_CONFIG config{};
    config.version = 2;
    FPDF_InitLibraryWithConfig(&config);
    initialized = true;
}

// Returns a Document handle, or 0 on failure (see lastOpenError).
JNIEXPORT jlong JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_openDocument(
        JNIEnv* env, jobject, jint fd, jstring password) {
    lastOpenError = 0;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        lastOpenError = FPDF_ERR_FILE;
        return 0;
    }

    auto* d = new Document();
    d->fd = dup(fd);
    if (d->fd < 0) {
        delete d;
        lastOpenError = FPDF_ERR_FILE;
        return 0;
    }
    d->access.m_FileLen = static_cast<unsigned long>(st.st_size);
    d->access.m_GetBlock = readBlock;
    d->access.m_Param = d;

    const char* pw = password ? env->GetStringUTFChars(password, nullptr) : nullptr;
    d->doc = FPDF_LoadCustomDocument(&d->access, pw);
    if (pw) env->ReleaseStringUTFChars(password, pw);

    if (!d->doc) {
        unsigned long err = FPDF_GetLastError();
        close(d->fd);
        delete d;
        lastOpenError = err == 0 ? FPDF_ERR_UNKNOWN : err;
        return 0;
    }
    return reinterpret_cast<jlong>(d);
}

JNIEXPORT jint JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_lastOpenError(JNIEnv*, jobject) {
    return static_cast<jint>(lastOpenError);
}

JNIEXPORT void JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_closeDocument(
        JNIEnv*, jobject, jlong handle) {
    auto* d = reinterpret_cast<Document*>(handle);
    if (!d) return;
    if (d->doc) FPDF_CloseDocument(d->doc);
    if (d->fd >= 0) close(d->fd);
    delete d;
}

JNIEXPORT jint JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_pageCount(
        JNIEnv*, jobject, jlong handle) {
    return FPDF_GetPageCount(reinterpret_cast<Document*>(handle)->doc);
}

// [w0, h0, w1, h1, ...] in points, page rotation applied. No page is loaded.
JNIEXPORT jfloatArray JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_pageSizes(
        JNIEnv* env, jobject, jlong handle) {
    auto* d = reinterpret_cast<Document*>(handle);
    int count = FPDF_GetPageCount(d->doc);
    std::vector<jfloat> out(static_cast<size_t>(count) * 2);
    for (int i = 0; i < count; i++) {
        FS_SIZEF size{};
        if (!FPDF_GetPageSizeByIndexF(d->doc, i, &size)) {
            size.width = 612;
            size.height = 792;
        }
        out[i * 2] = size.width;
        out[i * 2 + 1] = size.height;
    }
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

JNIEXPORT jlong JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_loadPage(
        JNIEnv*, jobject, jlong handle, jint index) {
    return reinterpret_cast<jlong>(FPDF_LoadPage(reinterpret_cast<Document*>(handle)->doc, index));
}

JNIEXPORT void JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_closePage(
        JNIEnv*, jobject, jlong page) {
    if (page) FPDF_ClosePage(reinterpret_cast<FPDF_PAGE>(page));
}

// Renders the page scaled to sizeX x sizeY, shifted by (startX, startY), into an ARGB_8888 bitmap.
// Negative starts render a region of a page larger than the bitmap (zoom tiles).
JNIEXPORT jboolean JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_renderPage(
        JNIEnv* env, jobject, jlong page, jobject bitmap,
        jint startX, jint startY, jint sizeX, jint sizeY) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;

    FPDF_BITMAP fb = FPDFBitmap_CreateEx(static_cast<int>(info.width), static_cast<int>(info.height),
                                         FPDFBitmap_BGRA, pixels, static_cast<int>(info.stride));
    if (fb) {
        FPDFBitmap_FillRect(fb, 0, 0, static_cast<int>(info.width), static_cast<int>(info.height),
                            0xFFFFFFFF);
        FPDF_RenderPageBitmap(fb, reinterpret_cast<FPDF_PAGE>(page), startX, startY, sizeX, sizeY, 0,
                              FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER);
        FPDFBitmap_Destroy(fb);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return fb ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_loadText(
        JNIEnv*, jobject, jlong page) {
    return reinterpret_cast<jlong>(FPDFText_LoadPage(reinterpret_cast<FPDF_PAGE>(page)));
}

JNIEXPORT void JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_closeText(
        JNIEnv*, jobject, jlong text) {
    if (text) FPDFText_ClosePage(reinterpret_cast<FPDF_TEXTPAGE>(text));
}

// The whole text of the page. Index i of the string is character i of the page.
JNIEXPORT jstring JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_pageText(
        JNIEnv* env, jobject, jlong text) {
    auto tp = reinterpret_cast<FPDF_TEXTPAGE>(text);
    int count = FPDFText_CountChars(tp);
    if (count <= 0) return env->NewStringUTF("");
    std::vector<unsigned short> buf(static_cast<size_t>(count) + 1);
    int written = FPDFText_GetText(tp, 0, count, buf.data());
    int len = written > 0 ? written - 1 : 0;  // written includes the terminating NUL
    if (len > count) len = count;
    // Keep a 1:1 mapping between string index and character index.
    for (int i = len; i < count; i++) buf[i] = ' ';
    return env->NewString(reinterpret_cast<const jchar*>(buf.data()), count);
}

// Rectangles covering characters [start, start + count), as normalized [l, t, r, b, ...]
// relative to the displayed page (0..1, origin top-left).
JNIEXPORT jfloatArray JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_textRects(
        JNIEnv* env, jobject, jlong pageHandle, jlong text, jint start, jint count) {
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    auto tp = reinterpret_cast<FPDF_TEXTPAGE>(text);
    int w, h;
    deviceSize(page, &w, &h);
    int n = FPDFText_CountRects(tp, start, count);
    std::vector<jfloat> out;
    out.reserve(static_cast<size_t>(n > 0 ? n : 0) * 4);
    for (int i = 0; i < n; i++) {
        double l, t, r, b;
        if (!FPDFText_GetRect(tp, i, &l, &t, &r, &b)) continue;
        int x1, y1, x2, y2;
        FPDF_PageToDevice(page, 0, 0, w, h, 0, l, t, &x1, &y1);
        FPDF_PageToDevice(page, 0, 0, w, h, 0, r, b, &x2, &y2);
        out.push_back(static_cast<jfloat>(std::fmin(x1, x2)) / w);
        out.push_back(static_cast<jfloat>(std::fmin(y1, y2)) / h);
        out.push_back(static_cast<jfloat>(std::fmax(x1, x2)) / w);
        out.push_back(static_cast<jfloat>(std::fmax(y1, y2)) / h);
    }
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (!out.empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

// Character index at a normalized point, or -1. Tolerance is in points.
JNIEXPORT jint JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_charIndexAt(
        JNIEnv*, jobject, jlong pageHandle, jlong text, jfloat nx, jfloat ny, jfloat tolerance) {
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    int w, h;
    deviceSize(page, &w, &h);
    double px, py;
    if (!FPDF_DeviceToPage(page, 0, 0, w, h, 0, static_cast<int>(nx * w), static_cast<int>(ny * h),
                           &px, &py)) {
        return -1;
    }
    return FPDFText_GetCharIndexAtPos(reinterpret_cast<FPDF_TEXTPAGE>(text), px, py, tolerance,
                                      tolerance);
}

}  // extern "C"

namespace {

jstring toJString(JNIEnv* env, const std::vector<unsigned short>& utf16) {
    size_t len = 0;
    while (len < utf16.size() && utf16[len] != 0) len++;
    return env->NewString(reinterpret_cast<const jchar*>(utf16.data()), static_cast<jsize>(len));
}

struct OutlineEntry {
    std::vector<unsigned short> title;
    int level;
    int page;
    float y;  // PDF y of the destination, NAN when unknown
};

void destInfo(FPDF_DOCUMENT doc, FPDF_DEST dest, int* page, float* y) {
    *page = -1;
    *y = NAN;
    if (!dest) return;
    *page = FPDFDest_GetDestPageIndex(doc, dest);
    FPDF_BOOL hasX = 0, hasY = 0, hasZoom = 0;
    FS_FLOAT x = 0, yy = 0, zoom = 0;
    if (FPDFDest_GetLocationInPage(dest, &hasX, &hasY, &hasZoom, &x, &yy, &zoom) && hasY) *y = yy;
}

void walkOutline(FPDF_DOCUMENT doc, FPDF_BOOKMARK bm, int level, std::vector<OutlineEntry>& out,
                 std::unordered_set<FPDF_BOOKMARK>& seen) {
    // Malformed files can loop: stop on repeats, very deep trees and huge outlines.
    while (bm && level < 32 && out.size() < 20000 && seen.insert(bm).second) {
        OutlineEntry e;
        unsigned long bytes = FPDFBookmark_GetTitle(bm, nullptr, 0);
        e.title.assign(bytes / 2 + 1, 0);
        if (bytes > 0) FPDFBookmark_GetTitle(bm, e.title.data(), bytes);
        e.level = level;
        FPDF_DEST dest = FPDFBookmark_GetDest(doc, bm);
        if (!dest) {
            FPDF_ACTION action = FPDFBookmark_GetAction(bm);
            if (action && FPDFAction_GetType(action) == PDFACTION_GOTO) dest = FPDFAction_GetDest(doc, action);
        }
        destInfo(doc, dest, &e.page, &e.y);
        out.push_back(std::move(e));
        walkOutline(doc, FPDFBookmark_GetFirstChild(doc, bm), level + 1, out, seen);
        bm = FPDFBookmark_GetNextSibling(doc, bm);
    }
}

}  // namespace

extern "C" {

// Flattened outline in reading order: Object[]{String[] titles, int[] levels, int[] pages, float[] ys}.
JNIEXPORT jobjectArray JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_outline(JNIEnv* env, jobject, jlong handle) {
    auto* d = reinterpret_cast<Document*>(handle);
    std::vector<OutlineEntry> entries;
    std::unordered_set<FPDF_BOOKMARK> seen;
    walkOutline(d->doc, FPDFBookmark_GetFirstChild(d->doc, nullptr), 0, entries, seen);
    auto n = static_cast<jsize>(entries.size());
    jobjectArray titles = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    std::vector<jint> levels(n), pages(n);
    std::vector<jfloat> ys(n);
    for (jsize i = 0; i < n; i++) {
        jstring t = toJString(env, entries[i].title);
        env->SetObjectArrayElement(titles, i, t);
        env->DeleteLocalRef(t);
        levels[i] = entries[i].level;
        pages[i] = entries[i].page;
        ys[i] = entries[i].y;
    }
    jintArray jl = env->NewIntArray(n);
    jintArray jp = env->NewIntArray(n);
    jfloatArray jy = env->NewFloatArray(n);
    if (n > 0) {
        env->SetIntArrayRegion(jl, 0, n, levels.data());
        env->SetIntArrayRegion(jp, 0, n, pages.data());
        env->SetFloatArrayRegion(jy, 0, n, ys.data());
    }
    jobjectArray result = env->NewObjectArray(4, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(result, 0, titles);
    env->SetObjectArrayElement(result, 1, jl);
    env->SetObjectArrayElement(result, 2, jp);
    env->SetObjectArrayElement(result, 3, jy);
    return result;
}

// Link under a normalized point: "p:<page>:<pdf y or NaN>" for internal targets, "u:<uri>" for
// web links, or null.
JNIEXPORT jstring JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_linkAt(
        JNIEnv* env, jobject, jlong handle, jlong pageHandle, jfloat nx, jfloat ny) {
    auto* d = reinterpret_cast<Document*>(handle);
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    int w, h;
    deviceSize(page, &w, &h);
    double px, py;
    if (!FPDF_DeviceToPage(page, 0, 0, w, h, 0, static_cast<int>(nx * w), static_cast<int>(ny * h), &px, &py)) {
        return nullptr;
    }
    FPDF_LINK link = FPDFLink_GetLinkAtPoint(page, px, py);
    if (!link) return nullptr;
    FPDF_DEST dest = FPDFLink_GetDest(d->doc, link);
    FPDF_ACTION action = dest ? nullptr : FPDFLink_GetAction(link);
    if (action) {
        unsigned long type = FPDFAction_GetType(action);
        if (type == PDFACTION_GOTO) {
            dest = FPDFAction_GetDest(d->doc, action);
        } else if (type == PDFACTION_URI) {
            unsigned long len = FPDFAction_GetURIPath(d->doc, action, nullptr, 0);
            if (len == 0 || len > 8192) return nullptr;
            std::string uri(len, '\0');
            FPDFAction_GetURIPath(d->doc, action, uri.data(), len);
            uri.resize(strnlen(uri.c_str(), len));
            // URIs are 7-bit in PDF; escape anything else so NewStringUTF never sees bad UTF-8.
            std::string safe = "u:";
            static const char* hex = "0123456789ABCDEF";
            for (unsigned char c : uri) {
                if (c >= 0x21 && c < 0x7F) {
                    safe += static_cast<char>(c);
                } else {
                    safe += '%';
                    safe += hex[c >> 4];
                    safe += hex[c & 15];
                }
            }
            return env->NewStringUTF(safe.c_str());
        }
    }
    if (!dest) return nullptr;
    int target;
    float y;
    destInfo(d->doc, dest, &target, &y);
    if (target < 0) return nullptr;
    std::string out = "p:" + std::to_string(target) + ":" + (std::isnan(y) ? std::string("NaN") : std::to_string(y));
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"

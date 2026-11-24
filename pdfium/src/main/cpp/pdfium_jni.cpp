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

#include "fpdf_annot.h"
#include "fpdf_attachment.h"
#include "fpdf_doc.h"
#include "fpdf_edit.h"
#include "fpdf_formfill.h"
#include "fpdf_save.h"
#include "fpdf_signature.h"
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

extern "C" {

// Object[]{String[8] meta (Title, Author, Subject, Keywords, Creator, Producer, CreationDate,
// ModDate), int[4] {file version (e.g. 17), security handler revision (-1 = not encrypted),
// form type, signature count}}.
JNIEXPORT jobjectArray JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_documentInfo(JNIEnv* env, jobject, jlong handle) {
    auto* d = reinterpret_cast<Document*>(handle);
    static const char* tags[] = {"Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate"};
    jobjectArray meta = env->NewObjectArray(8, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < 8; i++) {
        unsigned long bytes = FPDF_GetMetaText(d->doc, tags[i], nullptr, 0);
        std::vector<unsigned short> buf(bytes / 2 + 1, 0);
        if (bytes > 0) FPDF_GetMetaText(d->doc, tags[i], buf.data(), bytes);
        jstring s = toJString(env, buf);
        env->SetObjectArrayElement(meta, i, s);
        env->DeleteLocalRef(s);
    }
    int version = 0;
    if (!FPDF_GetFileVersion(d->doc, &version)) version = 0;
    jint nums[4] = {version, FPDF_GetSecurityHandlerRevision(d->doc), FPDF_GetFormType(d->doc),
                    FPDF_GetSignatureCount(d->doc)};
    jintArray jn = env->NewIntArray(4);
    env->SetIntArrayRegion(jn, 0, 4, nums);
    jobjectArray result = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(result, 0, meta);
    env->SetObjectArrayElement(result, 1, jn);
    return result;
}

}  // extern "C"

namespace {

struct FdWriter : FPDF_FILEWRITE {
    int fd;
    bool failed;
};

int writeBlock(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
    auto* w = static_cast<FdWriter*>(self);
    const auto* p = static_cast<const unsigned char*>(data);
    unsigned long done = 0;
    while (done < size) {
        ssize_t n = write(w->fd, p + done, size - done);
        if (n <= 0) {
            w->failed = true;
            return 0;
        }
        done += static_cast<unsigned long>(n);
    }
    return 1;
}

}  // namespace

extern "C" {

// Writes the whole document to fd (a full rewrite, not incremental). removeSecurity drops the
// encryption of a password-protected document opened with its password.
JNIEXPORT jboolean JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_saveCopy(
        JNIEnv*, jobject, jlong handle, jint fd, jboolean removeSecurity) {
    auto* d = reinterpret_cast<Document*>(handle);
    FdWriter w{};
    w.version = 1;
    w.WriteBlock = writeBlock;
    w.fd = fd;
    w.failed = false;
    FPDF_DWORD flags = FPDF_NO_INCREMENTAL | (removeSecurity ? FPDF_REMOVE_SECURITY : 0);
    bool ok = FPDF_SaveAsCopy(d->doc, &w, flags);
    return (ok && !w.failed) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

extern "C" {

// Names of the files embedded in the document (the EmbeddedFiles name tree).
JNIEXPORT jobjectArray JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_attachmentNames(JNIEnv* env, jobject, jlong handle) {
    auto* d = reinterpret_cast<Document*>(handle);
    int n = FPDFDoc_GetAttachmentCount(d->doc);
    if (n < 0) n = 0;
    jobjectArray out = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < n; i++) {
        FPDF_ATTACHMENT a = FPDFDoc_GetAttachment(d->doc, i);
        std::vector<unsigned short> buf(1, 0);
        if (a) {
            unsigned long bytes = FPDFAttachment_GetName(a, nullptr, 0);
            buf.assign(bytes / 2 + 1, 0);
            if (bytes > 0) FPDFAttachment_GetName(a, buf.data(), bytes);
        }
        jstring s = toJString(env, buf);
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

// Contents of attachment i, or null when it cannot be read (or is larger than 512 MB).
JNIEXPORT jbyteArray JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_attachmentData(JNIEnv* env, jobject, jlong handle, jint index) {
    auto* d = reinterpret_cast<Document*>(handle);
    FPDF_ATTACHMENT a = FPDFDoc_GetAttachment(d->doc, index);
    if (!a) return nullptr;
    unsigned long len = 0;
    if (!FPDFAttachment_GetFile(a, nullptr, 0, &len)) return nullptr;
    if (len > (512ul << 20)) return nullptr;
    std::vector<unsigned char> buf(len);
    unsigned long got = 0;
    if (len > 0 && !FPDFAttachment_GetFile(a, buf.data(), len, &got)) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (arr && len > 0) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(buf.data()));
    return arr;
}

}  // extern "C"

namespace {

std::vector<unsigned short> toUtf16(JNIEnv* env, jstring s) {
    std::vector<unsigned short> out;
    if (!s) return {0};
    const jchar* chars = env->GetStringChars(s, nullptr);
    jsize len = env->GetStringLength(s);
    out.assign(chars, chars + len);
    out.push_back(0);
    env->ReleaseStringChars(s, chars);
    return out;
}

void setAuthor(JNIEnv* env, FPDF_ANNOTATION annot, jstring author) {
    if (!author || env->GetStringLength(author) == 0) return;
    auto w = toUtf16(env, author);
    FPDFAnnot_SetStringValue(annot, "T", reinterpret_cast<FPDF_WIDESTRING>(w.data()));
}

}  // namespace

extern "C" {

// Adds a text markup annotation (highlight, underline, squiggly, strikeout) over characters
// [start, start + count) of the page, one quad per text rectangle. Returns its index or -1.
JNIEXPORT jint JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_addTextMarkup(
        JNIEnv* env, jobject, jlong pageHandle, jlong textHandle, jint start, jint count, jint subtype,
        jint argb, jstring author) {
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    auto tp = reinterpret_cast<FPDF_TEXTPAGE>(textHandle);
    int n = FPDFText_CountRects(tp, start, count);
    if (n <= 0) return -1;
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, subtype);
    if (!annot) return -1;
    double minL = 1e9, minB = 1e9, maxR = -1e9, maxT = -1e9;
    for (int i = 0; i < n; i++) {
        double l, t, r, b;
        if (!FPDFText_GetRect(tp, i, &l, &t, &r, &b)) continue;
        FS_QUADPOINTSF q{};
        q.x1 = static_cast<float>(l); q.y1 = static_cast<float>(t);
        q.x2 = static_cast<float>(r); q.y2 = static_cast<float>(t);
        q.x3 = static_cast<float>(l); q.y3 = static_cast<float>(b);
        q.x4 = static_cast<float>(r); q.y4 = static_cast<float>(b);
        FPDFAnnot_AppendAttachmentPoints(annot, &q);
        minL = std::fmin(minL, l); maxR = std::fmax(maxR, r);
        minB = std::fmin(minB, b); maxT = std::fmax(maxT, t);
    }
    FS_RECTF rect{static_cast<float>(minL), static_cast<float>(maxT), static_cast<float>(maxR), static_cast<float>(minB)};
    FPDFAnnot_SetRect(annot, &rect);
    unsigned int a = (static_cast<unsigned int>(argb) >> 24) & 0xFF;
    unsigned int r = (static_cast<unsigned int>(argb) >> 16) & 0xFF;
    unsigned int g = (static_cast<unsigned int>(argb) >> 8) & 0xFF;
    unsigned int b = static_cast<unsigned int>(argb) & 0xFF;
    FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, r, g, b, a);
    FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
    setAuthor(env, annot, author);
    FPDFPage_CloseAnnot(annot);
    return FPDFPage_GetAnnotCount(page) - 1;
}

}  // extern "C"

namespace {

// Normalized page point (0..1, top-left) to PDF page coordinates.
bool toPagePoint(FPDF_PAGE page, float nx, float ny, double* px, double* py) {
    int w, h;
    deviceSize(page, &w, &h);
    return FPDF_DeviceToPage(page, 0, 0, w, h, 0, static_cast<int>(nx * w), static_cast<int>(ny * h), px, py);
}

}  // namespace

extern "C" {

// Adds one ink stroke as an Ink annotation. xy = normalized [x0, y0, x1, y1, ...]; width in
// points. Returns the annotation index or -1.
JNIEXPORT jint JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_addInk(
        JNIEnv* env, jobject, jlong pageHandle, jfloatArray xy, jint argb, jfloat width, jstring author) {
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    jsize n = env->GetArrayLength(xy) / 2;
    if (n < 1) return -1;
    std::vector<jfloat> raw(static_cast<size_t>(n) * 2);
    env->GetFloatArrayRegion(xy, 0, n * 2, raw.data());
    std::vector<FS_POINTF> pts;
    pts.reserve(static_cast<size_t>(n));
    double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
    for (jsize i = 0; i < n; i++) {
        double px, py;
        if (!toPagePoint(page, raw[i * 2], raw[i * 2 + 1], &px, &py)) continue;
        pts.push_back(FS_POINTF{static_cast<float>(px), static_cast<float>(py)});
        minX = std::fmin(minX, px); maxX = std::fmax(maxX, px);
        minY = std::fmin(minY, py); maxY = std::fmax(maxY, py);
    }
    if (pts.empty()) return -1;
    if (pts.size() == 1) pts.push_back(FS_POINTF{pts[0].x + 0.1f, pts[0].y + 0.1f});  // a dot
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_INK);
    if (!annot) return -1;
    FPDFAnnot_AddInkStroke(annot, pts.data(), pts.size());
    float pad = width;
    FS_RECTF rect{static_cast<float>(minX - pad), static_cast<float>(maxY + pad), static_cast<float>(maxX + pad), static_cast<float>(minY - pad)};
    FPDFAnnot_SetRect(annot, &rect);
    unsigned int a = (static_cast<unsigned int>(argb) >> 24) & 0xFF;
    unsigned int r = (static_cast<unsigned int>(argb) >> 16) & 0xFF;
    unsigned int g = (static_cast<unsigned int>(argb) >> 8) & 0xFF;
    unsigned int b = static_cast<unsigned int>(argb) & 0xFF;
    FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, r, g, b, a);
    FPDFAnnot_SetBorder(annot, 0, 0, width);
    FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
    setAuthor(env, annot, author);
    FPDFPage_CloseAnnot(annot);
    return FPDFPage_GetAnnotCount(page) - 1;
}

// Index of the ink annotation passing within tolerance (points) of a normalized point, or -1.
// The topmost (last drawn) wins.
JNIEXPORT jint JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_inkAt(
        JNIEnv*, jobject, jlong pageHandle, jfloat nx, jfloat ny, jfloat tolerance) {
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    double px, py;
    if (!toPagePoint(page, nx, ny, &px, &py)) return -1;
    int count = FPDFPage_GetAnnotCount(page);
    for (int i = count - 1; i >= 0; i--) {
        FPDF_ANNOTATION annot = FPDFPage_GetAnnot(page, i);
        if (!annot) continue;
        bool hit = false;
        if (FPDFAnnot_GetSubtype(annot) == FPDF_ANNOT_INK) {
            unsigned long paths = FPDFAnnot_GetInkListCount(annot);
            for (unsigned long k = 0; k < paths && !hit; k++) {
                unsigned long len = FPDFAnnot_GetInkListPath(annot, k, nullptr, 0);
                std::vector<FS_POINTF> pts(len);
                if (len > 0) FPDFAnnot_GetInkListPath(annot, k, pts.data(), len);
                for (unsigned long j = 0; j < len && !hit; j++) {
                    // Distance to the segment j..j+1 (or the point when it is the last one).
                    const FS_POINTF& a = pts[j];
                    const FS_POINTF& b = pts[j + 1 < len ? j + 1 : j];
                    double dx = b.x - a.x, dy = b.y - a.y;
                    double l2 = dx * dx + dy * dy;
                    double t = l2 > 0 ? ((px - a.x) * dx + (py - a.y) * dy) / l2 : 0;
                    t = t < 0 ? 0 : (t > 1 ? 1 : t);
                    double cx = a.x + t * dx - px, cy = a.y + t * dy - py;
                    if (cx * cx + cy * cy <= tolerance * tolerance) hit = true;
                }
            }
        }
        FPDFPage_CloseAnnot(annot);
        if (hit) return i;
    }
    return -1;
}

JNIEXPORT jboolean JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_removeAnnot(
        JNIEnv*, jobject, jlong pageHandle, jint index) {
    return FPDFPage_RemoveAnnot(reinterpret_cast<FPDF_PAGE>(pageHandle), index) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

extern "C" {

// Adds a sticky note (Text annotation) with an icon at a normalized point. Returns its index.
JNIEXPORT jint JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_addNote(
        JNIEnv* env, jobject, jlong pageHandle, jfloat nx, jfloat ny, jstring contents, jint argb, jstring author) {
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    double px, py;
    if (!toPagePoint(page, nx, ny, &px, &py)) return -1;
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_TEXT);
    if (!annot) return -1;
    FS_RECTF rect{static_cast<float>(px), static_cast<float>(py), static_cast<float>(px + 20), static_cast<float>(py - 20)};
    FPDFAnnot_SetRect(annot, &rect);
    unsigned int uargb = static_cast<unsigned int>(argb);
    FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, (uargb >> 16) & 0xFF, (uargb >> 8) & 0xFF, uargb & 0xFF, (uargb >> 24) & 0xFF);
    auto w = toUtf16(env, contents);
    FPDFAnnot_SetStringValue(annot, "Contents", reinterpret_cast<FPDF_WIDESTRING>(w.data()));
    FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
    setAuthor(env, annot, author);
    FPDFPage_CloseAnnot(annot);
    return FPDFPage_GetAnnotCount(page) - 1;
}

// Adds text that shows exactly as typed (one line per '\n') with a standard font, as a Stamp
// annotation holding text objects (PDFium cannot draw FreeText appearances). The text is also
// stored in /Contents. (nx, ny) is the top-left corner. Returns the annotation index or -1.
JNIEXPORT jint JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_addTextStamp(
        JNIEnv* env, jobject, jlong handle, jlong pageHandle, jfloat nx, jfloat ny, jstring text,
        jstring fontName, jfloat size, jint argb, jstring author) {
    auto* d = reinterpret_cast<Document*>(handle);
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    double px, py;
    if (!toPagePoint(page, nx, ny, &px, &py)) return -1;
    const char* fname = env->GetStringUTFChars(fontName, nullptr);
    FPDF_FONT font = FPDFText_LoadStandardFont(d->doc, fname);
    env->ReleaseStringUTFChars(fontName, fname);
    if (!font) return -1;
    auto all = toUtf16(env, text);
    unsigned int uargb = static_cast<unsigned int>(argb);
    // Build the text objects first, so the annotation rectangle is known before they are added.
    std::vector<FPDF_PAGEOBJECT> objs;
    float left = 1e9f, bottom = 1e9f, right = -1e9f, top = -1e9f;
    size_t lineStart = 0;
    int line = 0;
    for (size_t i = 0; i < all.size(); i++) {
        if (all[i] != '\n' && all[i] != 0) continue;
        std::vector<unsigned short> one(all.begin() + static_cast<long>(lineStart), all.begin() + static_cast<long>(i));
        one.push_back(0);
        lineStart = i + 1;
        if (one.size() > 1) {
            FPDF_PAGEOBJECT obj = FPDFPageObj_CreateTextObj(d->doc, font, size);
            if (obj) {
                FPDFText_SetText(obj, reinterpret_cast<FPDF_WIDESTRING>(one.data()));
                FPDFPageObj_SetFillColor(obj, (uargb >> 16) & 0xFF, (uargb >> 8) & 0xFF, uargb & 0xFF, (uargb >> 24) & 0xFF);
                FPDFPageObj_Transform(obj, 1, 0, 0, 1, px, py - size * (1.0 + 1.25 * line));
                float l, b, r, t;
                if (FPDFPageObj_GetBounds(obj, &l, &b, &r, &t)) {
                    left = std::fmin(left, l); bottom = std::fmin(bottom, b);
                    right = std::fmax(right, r); top = std::fmax(top, t);
                }
                objs.push_back(obj);
            }
        }
        line++;
        if (all[i] == 0) break;
    }
    if (objs.empty() || right < left) {
        for (auto o : objs) FPDFPageObj_Destroy(o);
        return -1;
    }
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_STAMP);
    if (!annot) {
        for (auto o : objs) FPDFPageObj_Destroy(o);
        return -1;
    }
    FS_RECTF rect{left - 2, top + 2, right + 2, bottom - 2};
    FPDFAnnot_SetRect(annot, &rect);
    for (auto o : objs) FPDFAnnot_AppendObject(annot, o);
    FPDFAnnot_SetStringValue(annot, "Contents", reinterpret_cast<FPDF_WIDESTRING>(all.data()));
    FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
    setAuthor(env, annot, author);
    FPDFPage_CloseAnnot(annot);
    return FPDFPage_GetAnnotCount(page) - 1;
}

// The annotation under a normalized point: Object[]{int[]{index, subtype}, String contents}, or
// null. Ignores links and form widgets. Topmost first.
JNIEXPORT jobjectArray JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_annotAt(
        JNIEnv* env, jobject, jlong pageHandle, jfloat nx, jfloat ny) {
    auto page = reinterpret_cast<FPDF_PAGE>(pageHandle);
    double px, py;
    if (!toPagePoint(page, nx, ny, &px, &py)) return nullptr;
    int count = FPDFPage_GetAnnotCount(page);
    for (int i = count - 1; i >= 0; i--) {
        FPDF_ANNOTATION annot = FPDFPage_GetAnnot(page, i);
        if (!annot) continue;
        int subtype = FPDFAnnot_GetSubtype(annot);
        FS_RECTF r{};
        bool inside = subtype != FPDF_ANNOT_LINK && subtype != 20 /* widget */ && subtype != 16 /* popup */ &&
                      FPDFAnnot_GetRect(annot, &r) &&
                      px >= std::fmin(r.left, r.right) && px <= std::fmax(r.left, r.right) &&
                      py >= std::fmin(r.bottom, r.top) && py <= std::fmax(r.bottom, r.top);
        if (!inside) {
            FPDFPage_CloseAnnot(annot);
            continue;
        }
        unsigned long bytes = FPDFAnnot_GetStringValue(annot, "Contents", nullptr, 0);
        std::vector<unsigned short> buf(bytes / 2 + 1, 0);
        if (bytes > 0) FPDFAnnot_GetStringValue(annot, "Contents", reinterpret_cast<FPDF_WCHAR*>(buf.data()), bytes);
        FPDFPage_CloseAnnot(annot);
        jintArray ids = env->NewIntArray(2);
        jint v[2] = {i, subtype};
        env->SetIntArrayRegion(ids, 0, 2, v);
        jobjectArray out = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
        env->SetObjectArrayElement(out, 0, ids);
        jstring str = toJString(env, buf);
        env->SetObjectArrayElement(out, 1, str);
        return out;
    }
    return nullptr;
}

// Replaces /Contents of annotation [index].
JNIEXPORT jboolean JNICALL
Java_io_github_guilhermefeitosa66_openpdfviewer_pdfium_PdfiumNative_setAnnotContents(
        JNIEnv* env, jobject, jlong pageHandle, jint index, jstring contents) {
    FPDF_ANNOTATION annot = FPDFPage_GetAnnot(reinterpret_cast<FPDF_PAGE>(pageHandle), index);
    if (!annot) return JNI_FALSE;
    auto w = toUtf16(env, contents);
    bool ok = FPDFAnnot_SetStringValue(annot, "Contents", reinterpret_cast<FPDF_WIDESTRING>(w.data()));
    FPDFPage_CloseAnnot(annot);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

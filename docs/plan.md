# Plan for v0.1

Status: **approved and implemented in v0.1** (2026-11-24). Deviations are noted inline. Scope is in [roadmap.md](roadmap.md#v01--viewing-the-first-release).

## Rendering engine

| | Platform `PdfRenderer` | `androidx.pdf` | **PDFium (own thin JNI binding)** |
|---|---|---|---|
| License | AOSP (Apache-2.0) | Apache-2.0 | BSD-3-Clause / Apache-2.0 |
| Render pages | API 21+ | yes | yes, any API level |
| Text, search, selection | Only API 35+, or API 31–34 with SDK extension 13 (`PdfRendererPreV`). Nothing below | yes | yes (`fpdf_text`: search, char boxes, bounded text) |
| Maturity | stable | **1.0.0-beta01** (Aug 2026); Compose `PdfViewer` still `@ExperimentalPdfApi`; read/render backported to API 28 in alpha18 | the engine of Chrome and of Android's own renderer |
| Control over the UI | full | the viewer is Google's widget: our thumbnails, search bar and look fight it | full |
| Editing later | none (some form APIs on 35+) | ink annotations and forms (`EditablePdfViewerFragment`); no page operations | annotations (`fpdf_annot`), forms (`fpdf_formfill`), merge/split/reorder (`fpdf_ppo`), delete/rotate/insert (`fpdf_edit`), save (`fpdf_save`) |
| Cost | zero bytes | small | native `.so` per ABI (to be measured; split APKs / AAB keep it per device) |

**Choice: PDFium.** It is the only option that behaves the same on every supported Android
version (search and selection are the core of v0.1, and the platform gives them only from API 35),
it leaves the UI entirely ours, and the same binding covers most of the editing roadmap (annotations,
forms, page operations, save) without a second engine. `androidx.pdf` is a good library but is
still beta, owns its UI and stops short of page operations. MuPDF is excluded (AGPL).

How: a `:pdfium` Gradle module with a small C++/JNI layer (only the calls we use) over the prebuilt
Android libraries from `bblanchon/pdfium-binaries`. Before writing our own layer, check whether
`io.legere:pdfiumandroid` (Kotlin binding, API 21+) has a compatible license and exposes the text
API; if it does, use it and save the JNI work. PDFium is not thread-safe: every call goes through
one dedicated thread.

### Manipulation library for later stages

- **PDFium itself** for annotations, forms, merge, split, reorder, delete, rotate, insert, visual
  signature and saving (incremental or full).
- **Compression**: re-encode images through PDFium's image-object API; if we also need object
  streams and garbage collection, add **qpdf** (Apache-2.0, native).
- **PdfBox-Android** (Apache-2.0, port of PDFBox 2.0.27, slow release pace) only if something is
  missing from both, typically encryption and PAdES digital signatures (with BouncyCastle, MIT).
  Not a v0.1 dependency.

## minSdk 26 (Android 8.0)

- With PDFium the engine no longer sets the floor (it works from API 21). The floor comes from the
  rest of the stack: current Jetpack Compose and AndroidX require API 23.
- 26 keeps `java.time` and adaptive icons without desugaring or extra resources, and still covers
  practically every active device. Going lower buys almost no users and costs testing.
- For comparison: with the platform renderer, full v0.1 would need API 35 (or 31 + extension 13);
  with `androidx.pdf`, 28.

`targetSdk` / `compileSdk`: the latest stable API level when the project is created.

## Architecture

- Single activity, Jetpack Compose, Material 3 with the libre-apps palette (terracotta / ochre,
  [design canvas](https://claude.ai/artifact/LtJmgvCEM39KXXSCqA774L)).
- Modules: `:app` (UI, intents, navigation, document session, render scheduler, bitmap cache,
  recent files) and `:pdfium` (binding). *A separate `:core` was planned; it stays inside `:app`
  until something else needs it.* No DI framework, no Room: a few classes wired by
  hand keep cold start small.
- Rendering: each page is drawn first as a low-resolution bitmap for scrolling, then as
  high-resolution tiles for the visible area when zoomed. LRU cache sized from the device memory
  class. Work is cancelled as soon as a page scrolls away.
- Search runs page by page in the background and streams results; the first match shows before the
  whole document is scanned.
- Recent files: a small JSON file in app storage (URI, display name, page count, last page, time)
  plus a thumbnail cache in `cacheDir`. `takePersistableUriPermission` on picker results; a file
  received through "Open with" or Share without a persistable grant still goes to the list, and
  reopening it later tells the user when the grant has expired.
- Manifest: no storage permission; `INTERNET` only for the update check (0.52, can be turned off). Intent filters for `application/pdf` on `VIEW`
  and `SEND`.

## Performance budget

Targets for a mid-range device. *v0.1 measures with `am start -W` on the `benchmark` build and the
`open to first page` log line; Macrobenchmark and a Baseline Profile are still to do.*

v0.1 on an x86_64 emulator (API 35, software GPU): cold start 500–650 ms, first page 146 ms on the
first open and about 25 ms when reopening.

- Cold start to the recent files list: **< 500 ms**.
- Open a file to the first page visible: **< 300 ms** for a typical text PDF.
- Scrolling: no dropped frames on text documents at fit width.

## Dependencies (all to be listed in the README with their license)

| Dependency | License |
|---|---|
| Kotlin, kotlinx.coroutines | Apache-2.0 |
| Jetpack Compose, AndroidX (core, activity, lifecycle), Material 3 | Apache-2.0 |
| PDFium | BSD-3-Clause / Apache-2.0 |
| pdfium-binaries packaging (`chromium/8179`, own JNI layer) | MIT |
| AndroidX Benchmark / Baseline Profile (tests and build only) | Apache-2.0 |

## Project

- License: Apache-2.0. Repository `guilhermefeitosa66/open-pdf-viewer`, public.
- Code, README, commits and issues in English; app strings in English and Portuguese (Spanish next).
- `applicationId`: `io.github.guilhermefeitosa66.openpdfviewer` (fixed after the first release;
  see the decisions below).
- Releases on GitHub; the libre-apps site gets a page with a "coming soon to Google Play" badge.

## Decisions taken

1. App name **Open PDF Viewer** ("OpenPDF" is a well-known Java library). Can still change before
   the first store release.
2. Engine PDFium with our own JNI layer over `bblanchon/pdfium-binaries`; minSdk 26.
3. Design direction approved (canvas linked above).

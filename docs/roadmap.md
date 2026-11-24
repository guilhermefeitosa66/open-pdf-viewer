# Roadmap

Feature survey of **Adobe Acrobat Reader (Android)** and **iLovePDF** (Android app and web),
filtered for an offline, account-free, ad-free app, and split into stages.

Survey date: 2026-11-24. Everything listed here runs on the device. Nothing needs a network.

## What we drop, and why

| Feature (where seen) | Why it is out |
|---|---|
| Cloud storage, sync, "Recent" across devices, sharing links, review/collaboration (Acrobat, iLovePDF) | Needs an account and a server |
| AI Assistant, summaries, chat with the PDF, translate (Acrobat, iLovePDF) | Online AI |
| Liquid Mode / reflow (Acrobat) | Runs on Adobe's servers |
| Premium paywall for edit, export, compress, combine, organize (Acrobat) | We do these locally and for free, in later stages |
| Office conversions: PDF ↔ Word / Excel / PowerPoint, HTML → PDF (iLovePDF) | Needs a layout engine of its own. Belongs to the sibling `open-documents-viewer` apps, not here |
| Compare two PDFs (iLovePDF, premium) | Low value for the effort. Revisit after 1.0 |
| Ads, telemetry, sign-in | libre-apps rule |

## Stages

Each stage ships as a release. Measuring cold start and time to first page is part of every stage
(see [plan.md](plan.md#performance-budget)), and a regression is a bug.

### v0.1 · Viewing (the first release)

1. Open a PDF from the system picker (SAF, `ACTION_OPEN_DOCUMENT`), from "Open with" (`ACTION_VIEW`)
   and from Share (`ACTION_SEND`). No storage permission.
2. Recent files list, with last page read, removable entries. Reopening uses the persisted URI
   grant; when the grant is gone the app says so and offers the picker.
3. Continuous vertical scrolling, page indicator, fast-scroll handle, "go to page".
4. Zoom: pinch, double tap (fit width ⇄ 2×), fit page / fit width.
5. Search in text: highlights, current match, next/previous, match count, case toggle.
6. Select text by long press, adjust with handles, copy / share. Select all on page.
7. Page thumbnails in a bottom sheet, tap to jump.
8. Password-protected PDFs: ask for the password and open (decrypt in memory only).
9. Light and dark UI following the system (the page itself stays as authored), plus the shared
   libre-apps themes (Pastel, Dracula, Kitty, Neon) with optional background art, chosen in Settings.
10. English and Portuguese, then Spanish (same as the other libre-apps).

### v0.2 · Reading comfort

- Document outline (table of contents) and internal links; external links open the browser after a
  confirmation.
- Night reading mode for the page (inverted / sepia), keep screen on, lock orientation.
- Remember zoom and position per document.
- Horizontal / single-page mode (page by page swipe), two-page spread on tablets and landscape.
- Document info (title, author, page size, producer, PDF version).
- Print through the Android print framework.
- Bookmarks of your own (stored in the app, the file is not touched).

### v0.3 · Annotations (the file is changed only when you save)

- Highlight, underline, strikethrough on selected text.
- Free-hand ink, sticky note, text box, simple shapes.
- List of annotations with jump-to; delete.
- Save in place (when the grant allows) or "Save as copy".

### v0.4 · Organize pages

- Delete, rotate, reorder, duplicate pages (thumbnail grid in edit mode).
- Extract pages into a new PDF; split by ranges or every N pages.
- Merge several PDFs.
- Insert blank page; insert pages from another PDF.

### v0.5 · Forms and signatures

- Fill AcroForm fields (text, checkbox, radio, choice), flatten on export.
- Visual signature: draw or import an image once, place and resize it on the page (stored locally).
- Stamps (date, "Approved", custom text).

### v0.6 · Convert and optimize

- Compress: re-encode images at chosen quality, drop unused objects.
- Images → PDF (from gallery or the camera app's photos), PDF → images (PNG/JPEG per page).
- Page numbers, text or image watermark, crop margins.
- Password protect / remove password (AES-256), with honest wording about what it protects.

### Later, if there is demand

- Scan to PDF with perspective correction (camera intent, no camera permission of our own).
- Offline OCR to make scans searchable (Tesseract, Apache-2.0).
- Redaction that really removes the content (not only a black box).
- Digital signatures with a certificate (PAdES), signature validation.
- PDF/A export, repair damaged files.
- Read aloud with the system TTS.

## Where each item came from

| Item | Acrobat Reader Android | iLovePDF |
|---|---|---|
| Continuous / single page, zoom, search, select, copy | yes | viewer in the app |
| Thumbnails, go to page, recent files | yes | partly |
| Outline, bookmarks, night mode, keep screen on | yes | no |
| Highlight, ink, notes, text | yes (free) | yes (app) |
| Fill & sign | yes (free) | sign (app, web) |
| Organize, rotate, delete, reorder, extract | premium | yes |
| Merge, split | premium | yes |
| Compress | premium | yes |
| Images ↔ PDF | export premium | yes |
| Page numbers, watermark, crop | edit premium | yes |
| Protect / unlock | premium | yes |
| OCR, scan | Adobe Scan app | yes |
| Redact, PDF/A, repair | desktop / premium | yes (web) |

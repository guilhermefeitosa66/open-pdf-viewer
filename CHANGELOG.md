# Changelog

All notable changes to Open PDF Viewer. Versions follow SemVer; the Android versionCode goes up by one each release.

## 0.7.0 (2026-11-24)

- Page layout dialog in the viewer menu: continuous scroll (default) or page by page, with horizontal swipes that snap to whole pages.
- Two pages side by side in landscape and on tablets (page-by-page mode), with the first page alone as a cover by default.
- Optional right-to-left reading order for slots and spreads.

## 0.6.0 (2026-11-24)

- The exact reading position is remembered per document: page, offset inside it, zoom and horizontal position, restored when the file opens again.
- Fix: restoring the position no longer counts as scrolling, so full screen does not hide the bars right after opening.

## 0.5.0 (2026-11-24)

- Reading options in the viewer menu: keep screen on, lock rotation, and full screen while reading (the app and system bars hide once the page scrolls; a tap brings them back). Page padding ignores system bar visibility, so pages do not jump.

## 0.4.0 (2026-11-24)

- Page color for reading: normal, night or sepia, from the viewer menu. Applied as a color filter when drawing, so switching is instant and nothing is re-rendered; remembered globally, like the theme.

## 0.3.0 (2026-11-24)

- Contents: the document outline as an expandable tree in a new tab of the pages sheet; the branch of the current page starts open.
- Links: internal links jump inside the document and a "Back to page N" chip (or the system back) returns to where you were; web links ask before opening the browser (the app itself still has no internet permission).

## 0.2.0 (2026-11-24)

- Share a PDF from the viewer or from any recent file: the system share sheet gets a copy under the file's own name (FileProvider).
- Save a copy… to any folder the user picks (system Save screen), streamed with a cancellable progress dialog.
- Files that arrive through Open with/Share get a private copy in the background, so they can still be reopened, shared and saved after the temporary permission expires; copies of files no longer in the list are cleaned at the next start.
- Opening failures are logged without the file path.

## 0.1.0 (2026-11-24)

- First version: continuous scrolling, zoom, search, text selection, page thumbnails, recent files, password-protected PDFs, libre-apps themes, en/pt/es.

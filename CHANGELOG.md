# Changelog

All notable changes to Open PDF Viewer. Versions follow SemVer; the Android versionCode goes up by one each release.

## 0.15.0 (2026-11-24)

- Search options: whole word and ignore accents (on by default, so "exportacao" finds "exportação"), next to match case.
- All results: a list of every match with a short excerpt around it, grouped by page; tap one to jump there.

## 0.14.1 (2026-11-24)

- Faster home screen start: plain text tabs instead of a Material TabRow, and favorites and folders load after the first frame.

## 0.14.0 (2026-11-24)

- Library on the home screen: Recent, Favorites and Folders tabs.
- Favorites: star any file (from Recent or a folder); starred files stay even after leaving the recent list.
- Folders: add folders through the system folder picker (persisted grant, no storage permission) and see the PDFs inside them and their subfolders, with search by name and sorting by name, date or size.

## 0.13.0 (2026-11-24)

- Attachments: files embedded in the PDF are listed in an Attachments tab of the pages sheet, and each can be saved anywhere (system Save screen) or shared.
- Save as now works for any file type (used by attachments and, later, exports).

## 0.12.0 (2026-11-24)

- Read aloud from the viewer menu with the system text-to-speech engine (offline when the voice is installed): starts at the current page, highlights the sentence being read and follows it, with play/pause, speed (0.75× to 2×) and stop.

## 0.11.0 (2026-11-24)

- New app icon: a white page on red. Works as a themed (monochrome) icon too.

## 0.10.0 (2026-11-24)

- Print from the viewer menu through the Android print framework (page ranges, copies and paper come from the system dialog). Password-protected PDFs are handed to the print service without their encryption, written on the fly by PDFium.

## 0.9.0 (2026-11-24)

- Document info from the viewer menu: title, author, subject, keywords, creator and producer, creation and modification dates, PDF version, page count, page size (named when it is A3/A4/A5/Letter/Legal/Tabloid), file size, and whether the PDF has a form, digital signatures or encryption.

## 0.8.0 (2026-11-24)

- Bookmarks: mark the current page from the viewer menu with an editable title (the contents entry of the page is suggested), see a ribbon on marked pages, and jump, rename or remove them from a Bookmarks tab in the pages sheet. Stored by the app; the PDF is not changed.

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

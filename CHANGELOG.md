# Changelog

All notable changes to Open PDF Viewer. Versions follow SemVer; the Android versionCode goes up by one each release.

## 0.2.0 (2026-11-24)

- Share a PDF from the viewer or from any recent file: the system share sheet gets a copy under the file's own name (FileProvider).
- Save a copy… to any folder the user picks (system Save screen), streamed with a cancellable progress dialog.
- Files that arrive through Open with/Share get a private copy in the background, so they can still be reopened, shared and saved after the temporary permission expires; copies of files no longer in the list are cleaned at the next start.
- Opening failures are logged without the file path.

## 0.1.0 (2026-11-24)

- First version: continuous scrolling, zoom, search, text selection, page thumbnails, recent files, password-protected PDFs, libre-apps themes, en/pt/es.

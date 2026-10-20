# Changelog

## v1.2.0 (2026-10-20)

- Edit a book before sending it: tap Edit details on its page to fix the title and authors, set a series and its number, and add tags. KOReader then sorts and groups the book properly, and shows the tags as keywords.
- Your changes are written into the file when the book is sent: in the OPF of EPUB books, in FB2, MOBI, AZW3 and in the information of PDF files. The copy on your phone isn't changed, and Restore details from the file brings back the original details.
- Change a book's cover with an image from your phone, for EPUB, MOBI, AZW3 and FB2 books.
- Crop the cover before using it: 2:3, 5:8 (Kindle), 3:4 (Kobo), square, the image's own shape or free, with rotation and the final size in pixels. In EPUB books the cover page is fitted to the new image, so it isn't stretched.
- KOReader keeps the cover and details of books it has already received. When you send an edited book again, KoSend gives it a new file name, so KOReader reads the new cover and details, and deletes the old copy from the e-reader. A short note on the edit page explains what to do if KOReader still shows the old cover.
- KoSend now reads the list of books on the e-reader each time it connects, so it only deletes old copies that are really there and never overwrites a different book with the same name.
- Series, series number and tags are now sent to KOReader's calibre metadata for every book.
- New app logo.

## v1.1.0 (2026-10-19)

- Book covers in the queue, read from EPUB, MOBI/AZW3 and PDF files. Books already in the queue get theirs on the next start.
- Tap a book to see its details: cover, authors, series, publisher, publication date, language, ISBN, subjects, description and file information.
- Drag books to change the order they are sent in. The list now shows the queue in sending order, top first.
- Swipe a book to the left to reveal Remove: tap it, or keep swiping, to take the book out of the queue, with Undo.
- A failed transfer can be retried right from the list; the book's copy is kept.
- Settings: Check for updates looks for a newer release on GitHub and opens it.
- Title, author and other details are now read from MOBI and AZW3 files too.

## v1.0.1 (2026-10-19)

- Settings show how much space the book copies take, and explain that KoSend keeps a copy until the book is removed.
- New option: delete a book's copy right after it is sent.
- Copies left behind by an interrupted import are cleaned up at startup.

## v1.0.0 (2026-10-17)

First release.

- Sends EPUB, PDF, MOBI and other formats KOReader accepts, over Wi-Fi, using KOReader's built-in calibre wireless client.
- Queue that survives app restarts; books added while the Kindle is connected are sent right away.
- Share books to KoSend from any app.
- Title and author read from EPUB files.
- Server runs only while the app is open and switched on.
- English and Italian, light and dark theme.

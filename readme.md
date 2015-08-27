# tagdrop - Tag Dead Drop

Is is a work in progress. The concept is embedding small amount of media physically on paper as 2d QR barcodes. This is unlike current usage of QR codes, where it is normally used to just store plaintext or urls or contacts.

Instead it would be nice if you can store a simple audio soundclip, or a small javascript game, etc...

1. First objective is to decode datauri sent in via intent, and display render via android web browser. e.g. from zxing QR barcode scanner app, or reading it from an NFC tag.

2. Secondary objective, is that for larger files, you want to spread it over multiple QR codes.
So will need a way to read all these tags an then join it together.

# Status

## V1.0

First objective is completed. Secondary objective is not completed, as there is no easy way to split a file across multiple QR codes in an easy to use manner.

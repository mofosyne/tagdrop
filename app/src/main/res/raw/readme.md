# TagDrop

TagDrop stores small files — text, HTML pages, images, audio, SVGs — inside QR codes that work completely offline. Scan a code and view its content right away, with no internet connection needed.

> Works fully offline: scanning, your collection, and this help page itself never need a network connection.

## Scanning

Tap the camera button to open the scanner and point it at a TagDrop QR code. Scanned items appear on the **Collections** and **History** tabs right away.

- **Single codes** open straight away — one QR code holds one piece of content.
- **Paper manifests** show a directory of files on a printed sheet — scan the manifest first, then any of the file codes on that same sheet, in any order.
- **Larger content** that doesn't fit in one QR code is split into several sector codes plus a manifest — scan them in any order and TagDrop reassembles and checks them automatically.
- A printed **parity recovery code** can sometimes recover one missing sector, if the creator added one.
- **Encrypted content** shows a lock badge until you scan its matching key code, or type its passphrase when prompted.

## Collections, History & Map

- **Collections** groups everything you've found — single pages, papers, and ad-hoc trails — into cards you can revisit anytime, offline.
- A home badge marks a collection's homepage file, when it has one, so you know which file to open first.
- **History** is a log of every code you've scanned, newest first.
- **Map** shows your located finds as pins, and hints toward related papers you haven't found yet nearby.
- Use the search box on **Collections** or **History** to filter by title, filename, or hint, or tap a tag chip to filter by hashtag.

## Viewing what you scanned

Open any scanned item and tap **View as** to change how its content is shown — Rendered for normal display, Text for the raw text, Hex for a raw byte dump, CBOR for the decoded TagDrop structure, or JSON for pretty-printed JSON.

- From a scanned item's screen you can also open it in another app, share it, or save a copy to this device.
- Small images the creator marked as pixel art render crisp instead of blurry when shown larger than their original size.

## Creating your own

Use **Create Cache** to turn typed or pasted text, HTML, SVG, or JSON into a QR code you can share or print right from your phone.

Use **Create Paper** to build a multi-file paper — a manifest QR plus one QR per file — and print the whole sheet, or save it as a PDF, straight from the app.

- Either screen lets you set a title and an optional passphrase, so the content stays encrypted until someone scans its key code.
- For larger or more elaborate layouts, including domain names and parity recovery codes, use the web generator in the TagDrop repository on a computer.

## Sharing & NFC

Already-scanned content can be re-shared as a fresh QR code, or written straight onto a blank NFC tag from its detail screen.

- Writing to a tag can include a plain record alongside TagDrop's own, so a phone without TagDrop installed can still read something when it taps the tag.

## Keys & Encryption

- Locked content shows a lock badge until it's unlocked with its key code or passphrase.
- Unlocking offers to remember its key on this device, so anything else encrypted with the same key unlocks automatically afterwards.
- Manage or delete saved keys from **Retained Keys** in the menu.

## Backup & Restore

- **Backup data** in the menu saves every scan, paper, and retained key on this device into one zip file you can store safely or move to a new phone.
- **Restore data** loads a backup back in — this replaces everything currently on the device, so it's best used on a fresh install or to recover after losing a phone.

---

## Extra

The full technical wire-format spec is also available in-app — see **Wire Format Spec** in the menu, next to this page.

Source: [github.com/mofosyne/tagdrop](https://github.com/mofosyne/tagdrop)

Licence: GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007

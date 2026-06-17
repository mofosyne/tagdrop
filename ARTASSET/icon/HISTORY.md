# App icon history

## 2026 — ink-drop + QR finder marks

`2026-inkdrop/tagdrop2026-source.svg` is the original Inkscape prototype
(hand-drawn, navy ink-drop/blob shape with three QR-style finder marks in
varying sizes — same top-left/top-right/bottom-left convention real QR codes
use, so the icon reads as "this app deals with QR codes" at a glance). It
also doubles as a printable marker: stamping a small version of this shape
next to QR codes on a printed TagDrop paper makes the paper recognizable as
a TagDrop artifact even before scanning, geocache-log-book style.

That prototype bakes its own rounded-square card shape into the artwork
(`#f3ede1` fill, full canvas). Android's adaptive icon system (API 26+)
already applies its own mask (circle, squircle, rounded square, or teardrop
depending on launcher/OEM) on top of whatever the app provides, so a second,
independent rounding baked into the source art doesn't compose with it — it
just leaves visible gaps of background color in the corners once the OS
mask is applied on top, since the two roundings don't line up. See
`app/src/main/res/drawable/ic_launcher_foreground.xml` for the fix: the
prototype's blob + three finder-mark paths, re-centered and scaled to fit
inside Android's 66dp "safe zone" (a circle of that diameter centered in the
108dp foreground canvas — content inside it survives *any* mask shape), with
a flat full-bleed `@color/ic_launcher_background` (`#F3EDE1`) behind it
instead of a second baked-in rounded card.

Derived files in `2026-inkdrop/`, all generated from the same path data as
the prototype (re-centered/rescaled, see git history of this file's
directory for the generating script if the transform ever needs
reproducing):

- `ic_launcher_foreground.svg` / `ic_launcher_background` (the `@color`
  above) — source for `app/src/main/res/drawable/ic_launcher_foreground.xml`
  and the `mipmap-anydpi-v26/ic_launcher*.xml` adaptive icon, used on
  Android 8+.
- `ic_launcher_legacy_square.svg` / `ic_launcher_legacy_round.svg` — a
  bolder, larger rendition (no safe-zone constraint, since nothing masks
  these) used to regenerate the flat `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,
  xxxhdpi}/ic_launcher.png` and `ic_launcher_round.png` fallbacks for
  pre-Oreo launchers, plus the Play Store icon
  (`ARTASSET/icon/android/playstore-icon.png`) and
  `fastlane/metadata/android/en-US/images/icon.png`.
- `featureGraphic.svg` — the Play Store feature graphic (1024×500), reusing
  the legacy icon mark beside a "TagDrop" wordmark and the store listing's
  tagline. Rendered to
  `fastlane/metadata/android/en-US/images/featureGraphic.png` with
  `rsvg-convert -w 1024 -h 500`. The `phoneScreenshots/` in that same
  directory are in-app UI captures, unrelated to the icon — not regenerated
  here.

Palette: background `#F3EDE1` (cream/parchment), blob `#2A3941` (deep slate
navy), finder marks `#D9652B` (terracotta/burnt orange).

## 2014 — placeholder programmer art

`history/2014-placeholder/` holds the original launcher icon: three solid
black squares (a literal, unstyled QR finder-pattern reference) plus the
text "TAG DROP", made in Inkscape 0.91. It shipped as flat PNGs only (no
adaptive icon support — that Android feature didn't exist yet in 2014) and
came with a full multi-platform export set (`ios/`, `watchkit/`,
`android/drawable-*`) from what was evidently a "generate all the icon
sizes" service, left over from when the project targeted more than Android.
Kept here for reference rather than deleted.

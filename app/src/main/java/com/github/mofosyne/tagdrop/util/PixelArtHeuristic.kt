package com.github.mofosyne.tagdrop.util

/**
 * Native pixel dimension at/below which a raster image is treated as pixel art and rendered
 * without smoothing, regardless of any author-declared [com.github.mofosyne.tagdrop.data.db.FoundCache.pixelArt]
 * flag (SPEC §7, "Pixel art"). Small images upscaled with bilinear smoothing look blurry rather
 * than crisp, which is rarely what's intended at this size.
 */
const val PIXEL_ART_HEURISTIC_MAX_PX = 64

/** True if a raster image this small is being upscaled is presumed to be pixel art. Doesn't apply to vector formats (e.g. SVG), which have no native pixel grid. */
fun looksLikePixelArt(width: Int, height: Int): Boolean =
    width in 1..PIXEL_ART_HEURISTIC_MAX_PX && height in 1..PIXEL_ART_HEURISTIC_MAX_PX

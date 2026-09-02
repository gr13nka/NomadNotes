package com.nomadnotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A picture placed on a page: where it sits, and which stored file it shows.
 *
 * The model holds no pixels. [assetRef] names a file the notebook keeps beside its pages, and
 * resolving that name to a decoded bitmap is the app's job — so the note model stays pure, a page
 * costs the same to load whether or not it has pictures, and an image whose file has gone missing is
 * a rendering problem rather than a page that will not open.
 *
 * Like a [Stroke], a page image is immutable: moving or resizing one produces a new instance.
 *
 * @property id stable identity, unchanged across moves, resizes and re-serialization.
 * @property assetRef names the stored file within its notebook, as written by the app that imported
 *   it. Opaque to the model — an unrecognised or missing ref must degrade to drawing nothing.
 * @property rect where the image is drawn, in page pixels (see [Page] for the coordinate space).
 *   The image is scaled to fill it, so the caller decides whether to keep the source's aspect ratio.
 * @property extra forward-compatibility escape hatch, as on [Stroke].
 */
@Serializable
data class PageImage(
    val id: ImageId,
    val assetRef: String,
    val rect: PageRect,
    val extra: Map<String, JsonElement> = emptyMap(),
)

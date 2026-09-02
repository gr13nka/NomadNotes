package com.nomadnotes.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Reading an image file into a bitmap no larger than it needs to be.
 *
 * Shared by the two things that put stored pictures on a page — the stationery template behind the
 * ink, and images placed on it. Both face the same problem: a photo off a phone camera is many times
 * the panel's resolution, and decoding one at full size costs tens of megabytes on a device that is
 * already holding a full-screen bitmap per layer.
 */

/**
 * Decodes [file] downsampled to roughly [reqWidth] × [reqHeight] and turned upright, or null when the
 * file is absent or is not a decodable image.
 *
 * Downsampling happens during the decode, so the full-resolution pixels are never allocated. The
 * result is a power-of-two step *at least* the requested size, not an exact fit — callers scale it to
 * the rectangle they are drawing into, which they must do anyway.
 */
fun decodeSampled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
    if (!file.isFile || reqWidth <= 0 || reqHeight <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
    }
    val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
    return uprighted(decoded, file)
}

/**
 * The largest power-of-two downsample that still leaves the image at least [reqWidth] × [reqHeight].
 * Kept separate from the decode so the arithmetic can be reasoned about (and tested) on its own.
 */
internal fun sampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
    if (reqWidth <= 0 || reqHeight <= 0) return 1
    var sample = 1
    while (srcWidth / (sample * 2) >= reqWidth && srcHeight / (sample * 2) >= reqHeight) {
        sample *= 2
    }
    return sample
}

/**
 * Applies the rotation [file]'s EXIF orientation asks for, returning [decoded] itself when there is
 * nothing to do.
 *
 * Phone cameras store the sensor's pixels unrotated and record which way up the phone was held, so a
 * portrait photo decodes on its side unless this is honoured. A file with no EXIF block, or one that
 * cannot be read, is taken as already upright — a wrong guess would be worse than none.
 */
private fun uprighted(decoded: Bitmap, file: File): Bitmap {
    val orientation = try {
        ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (e: IOException) {
        Log.w(TAG, "Could not read EXIF orientation, assuming upright: ${file.name}", e)
        ExifInterface.ORIENTATION_NORMAL
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return decoded
    }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated != decoded) decoded.recycle()
    return rotated
}

private const val TAG = "BitmapDecode"

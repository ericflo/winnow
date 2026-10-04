package com.ericflo.winnow.data

/**
 * The geometry behind the composer's crop: boxes are fractions (0..1) of the photo as it's
 * shown, upright. Pure Kotlin, so it's unit-tested without Android.
 */
object PhotoCrop {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        companion object {
            val FULL = Box(0f, 0f, 1f, 1f)
        }
    }

    /** What a drag takes hold of: the whole box, a corner or an edge. */
    enum class Handle(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
        MOVE,
        TOP_LEFT(left = true, top = true), TOP_RIGHT(right = true, top = true),
        BOTTOM_LEFT(left = true, bottom = true), BOTTOM_RIGHT(right = true, bottom = true),
        LEFT(left = true), TOP(top = true), RIGHT(right = true), BOTTOM(bottom = true);

        val isCorner: Boolean get() = (left || right) && (top || bottom)
    }

    // EXIF orientations (ExifInterface's values), so this needs no Android.
    private const val FLIP_HORIZONTAL = 2
    private const val ROTATE_180 = 3
    private const val FLIP_VERTICAL = 4
    private const val TRANSPOSE = 5
    private const val ROTATE_90 = 6
    private const val TRANSVERSE = 7
    private const val ROTATE_270 = 8

    /** Whether a photo stored with [orientation] is shown on its side: its width and height swap. */
    fun swapsSides(orientation: Int): Boolean = orientation in TRANSPOSE..ROTATE_270

    /**
     * [box], drawn on the upright photo, in the stored image's own coordinates: what to cut
     * from the file before turning it upright, for a photo whose EXIF says [orientation].
     */
    fun toStored(box: Box, orientation: Int): Box {
        // Where an upright point (u, v) is in the stored image.
        fun stored(u: Float, v: Float): Pair<Float, Float> = when (orientation) {
            FLIP_HORIZONTAL -> (1 - u) to v
            ROTATE_180 -> (1 - u) to (1 - v)
            FLIP_VERTICAL -> u to (1 - v)
            TRANSPOSE -> v to u
            ROTATE_90 -> v to (1 - u)
            TRANSVERSE -> (1 - v) to (1 - u)
            ROTATE_270 -> (1 - v) to u
            else -> u to v
        }
        val (x1, y1) = stored(box.left, box.top)
        val (x2, y2) = stored(box.right, box.bottom)
        return Box(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /**
     * [box] after [handle] is dragged by ([dx], [dy]), kept on the photo and at least [min] of
     * it each way. With [aspect] (width over height, in these fractions), corners keep the
     * shape and edges don't move.
     */
    fun drag(start: Box, handle: Handle, dx: Float, dy: Float, min: Float, aspect: Float? = null): Box {
        // On the photo and the right way round, whatever rounding did: every range below is then non-empty.
        val box = start.onPhoto()
        if (handle == Handle.MOVE) {
            val x = dx.coerceIn(-box.left, 1 - box.right)
            val y = dy.coerceIn(-box.top, 1 - box.bottom)
            return Box(box.left + x, box.top + y, box.right + x, box.bottom + y)
        }
        if (aspect != null) {
            if (!handle.isCorner) return box
            // The opposite corner stays; the size follows whichever way the finger moved more.
            val anchorX = if (handle.left) box.right else box.left
            val anchorY = if (handle.top) box.bottom else box.top
            val roomX = if (handle.left) anchorX else 1 - anchorX
            val roomY = if (handle.top) anchorY else 1 - anchorY
            // In width terms, how much each way of the drag would change the size.
            val byX = if (handle.left) -dx else dx
            val byY = (if (handle.top) -dy else dy) * aspect
            val minW = maxOf(min, min * aspect)
            var w = (box.width + if (kotlin.math.abs(byX) >= kotlin.math.abs(byY)) byX else byY).coerceAtLeast(minW)
            w = minOf(w, roomX, roomY * aspect)
            val h = w / aspect
            val left = if (handle.left) anchorX - w else anchorX
            val top = if (handle.top) anchorY - h else anchorY
            return Box(left, top, left + w, top + h)
        }
        var (left, top, right, bottom) = box
        // A box already under the minimum (made square, say) can grow but not shrink.
        if (handle.left) left = (left + dx).coerceIn(0f, maxOf(left, right - min))
        if (handle.right) right = (right + dx).coerceIn(minOf(right, left + min), 1f)
        if (handle.top) top = (top + dy).coerceIn(0f, maxOf(top, bottom - min))
        if (handle.bottom) bottom = (bottom + dy).coerceIn(minOf(bottom, top + min), 1f)
        return Box(left, top, right, bottom)
    }

    /** [this] clamped onto the photo, its edges in order. */
    private fun Box.onPhoto(): Box {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        return Box(l, t, right.coerceIn(l, 1f), bottom.coerceIn(t, 1f))
    }

    /** The biggest box of [aspect] (width over height, in these fractions) centered on [box]'s center, on the photo. */
    fun fit(start: Box, aspect: Float): Box {
        val box = start.onPhoto()
        val cx = (box.left + box.right) / 2
        val cy = (box.top + box.bottom) / 2
        // As big as the current box allows its shorter side, then pulled in to stay on the photo.
        var w = minOf(box.width, box.height * aspect)
        w = minOf(w, 2 * cx, 2 * (1 - cx), 2 * cy * aspect, 2 * (1 - cy) * aspect)
        if (w <= 0f) w = minOf(1f, aspect)
        val h = w / aspect
        return Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** Which handle a touch at ([x], [y]) takes, within [reachX]/[reachY] of a corner or edge; null if it's off the box. */
    fun handleAt(box: Box, x: Float, y: Float, reachX: Float, reachY: Float): Handle? {
        val nearLeft = kotlin.math.abs(x - box.left) <= reachX
        val nearRight = kotlin.math.abs(x - box.right) <= reachX
        val nearTop = kotlin.math.abs(y - box.top) <= reachY
        val nearBottom = kotlin.math.abs(y - box.bottom) <= reachY
        val withinX = x in (box.left - reachX)..(box.right + reachX)
        val withinY = y in (box.top - reachY)..(box.bottom + reachY)
        return when {
            nearLeft && nearTop -> Handle.TOP_LEFT
            nearRight && nearTop -> Handle.TOP_RIGHT
            nearLeft && nearBottom -> Handle.BOTTOM_LEFT
            nearRight && nearBottom -> Handle.BOTTOM_RIGHT
            nearLeft && withinY -> Handle.LEFT
            nearRight && withinY -> Handle.RIGHT
            nearTop && withinX -> Handle.TOP
            nearBottom && withinX -> Handle.BOTTOM
            x in box.left..box.right && y in box.top..box.bottom -> Handle.MOVE
            else -> null
        }
    }
}

package com.ericflo.winnow

import com.ericflo.winnow.data.PhotoCrop
import com.ericflo.winnow.data.PhotoCrop.Box
import com.ericflo.winnow.data.PhotoCrop.Handle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCropTest {
    private fun assertBox(expected: Box, actual: Box) {
        val near = listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .all { (e, a) -> kotlin.math.abs(e - a) < 1e-4f }
        assertTrue("expected $expected, was $actual", near)
    }

    // The top quarter of the upright photo, and where each EXIF orientation stores it.
    private val topStrip = Box(0f, 0f, 1f, 0.25f)

    @Test
    fun `an upright box maps back to the stored image for every orientation`() {
        assertBox(topStrip, PhotoCrop.toStored(topStrip, 1))
        assertBox(topStrip, PhotoCrop.toStored(topStrip, 2)) // mirrored left to right: the top stays the top
        assertBox(Box(0f, 0.75f, 1f, 1f), PhotoCrop.toStored(topStrip, 3)) // upside down
        assertBox(Box(0f, 0.75f, 1f, 1f), PhotoCrop.toStored(topStrip, 4)) // mirrored top to bottom
        assertBox(Box(0f, 0f, 0.25f, 1f), PhotoCrop.toStored(topStrip, 5)) // transposed: the stored left is the top
        assertBox(Box(0f, 0f, 0.25f, 1f), PhotoCrop.toStored(topStrip, 6)) // turned a quarter clockwise to show
        assertBox(Box(0.75f, 0f, 1f, 1f), PhotoCrop.toStored(topStrip, 7))
        assertBox(Box(0.75f, 0f, 1f, 1f), PhotoCrop.toStored(topStrip, 8)) // a quarter counterclockwise
        // A corner, to tell mirrored from not.
        val corner = Box(0f, 0f, 0.5f, 0.25f)
        assertBox(Box(0.5f, 0f, 1f, 0.25f), PhotoCrop.toStored(corner, 2))
        assertBox(Box(0f, 0.5f, 0.25f, 1f), PhotoCrop.toStored(corner, 6))
        assertBox(Box(0f, 0f, 0.25f, 0.5f), PhotoCrop.toStored(corner, 5))
        assertTrue(PhotoCrop.swapsSides(6))
        assertFalse(PhotoCrop.swapsSides(3))
    }

    @Test
    fun `dragging a corner or edge resizes, kept on the photo and at least the minimum`() {
        val box = Box(0.2f, 0.2f, 0.8f, 0.8f)
        assertBox(Box(0.1f, 0.3f, 0.8f, 0.8f), PhotoCrop.drag(box, Handle.TOP_LEFT, -0.1f, 0.1f, min = 0.1f))
        assertBox(Box(0.2f, 0.2f, 1f, 0.8f), PhotoCrop.drag(box, Handle.RIGHT, 0.5f, 0.3f, min = 0.1f))
        assertBox(Box(0.2f, 0.2f, 0.8f, 0.3f), PhotoCrop.drag(box, Handle.BOTTOM, 0f, -0.9f, min = 0.1f))
    }

    @Test
    fun `moving keeps the whole box on the photo`() {
        val box = Box(0.2f, 0.2f, 0.6f, 0.6f)
        assertBox(Box(0.6f, 0f, 1f, 0.4f), PhotoCrop.drag(box, Handle.MOVE, 0.9f, -0.5f, min = 0.1f))
    }

    @Test
    fun `a square crop keeps its shape`() {
        // A 4:3 landscape photo: a square is 0.75 of its width for every 1.0 of its height.
        val aspect = 3f / 4f
        val square = PhotoCrop.fit(Box.FULL, aspect)
        assertBox(Box(0.125f, 0f, 0.875f, 1f), square)
        val dragged = PhotoCrop.drag(square, Handle.BOTTOM_RIGHT, -0.2f, -0.05f, min = 0.1f, aspect = aspect)
        assertEquals(aspect, dragged.width / dragged.height, 1e-4f)
        assertEquals(square.left, dragged.left, 1e-6f)
        assertEquals(square.top, dragged.top, 1e-6f)
        // Edges don't move a shape that's held.
        assertBox(square, PhotoCrop.drag(square, Handle.LEFT, 0.1f, 0f, min = 0.1f, aspect = aspect))
        // Growing past the photo stops at its edge, still square.
        val grown = PhotoCrop.drag(dragged, Handle.BOTTOM_RIGHT, 1f, 1f, min = 0.1f, aspect = aspect)
        assertEquals(aspect, grown.width / grown.height, 1e-4f)
        assertTrue(grown.right <= 1f + 1e-6f && grown.bottom <= 1f + 1e-6f)
    }

    @Test
    fun `fitting a shape stays centered and on the photo`() {
        val nearEdge = Box(0.7f, 0.1f, 1f, 0.9f)
        val fitted = PhotoCrop.fit(nearEdge, 1f)
        assertEquals(1f, fitted.width / fitted.height, 1e-4f)
        assertTrue(fitted.left >= 0f && fitted.right <= 1f && fitted.top >= 0f && fitted.bottom <= 1f)
        assertEquals(0.85f, (fitted.left + fitted.right) / 2, 1e-4f)
    }

    @Test
    fun `touches find the handle nearest them`() {
        val box = Box(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(Handle.TOP_LEFT, PhotoCrop.handleAt(box, 0.21f, 0.19f, 0.05f, 0.05f))
        assertEquals(Handle.BOTTOM_RIGHT, PhotoCrop.handleAt(box, 0.83f, 0.78f, 0.05f, 0.05f))
        assertEquals(Handle.RIGHT, PhotoCrop.handleAt(box, 0.8f, 0.5f, 0.05f, 0.05f))
        assertEquals(Handle.TOP, PhotoCrop.handleAt(box, 0.5f, 0.17f, 0.05f, 0.05f))
        assertEquals(Handle.MOVE, PhotoCrop.handleAt(box, 0.5f, 0.5f, 0.05f, 0.05f))
        assertNull(PhotoCrop.handleAt(box, 0.05f, 0.5f, 0.05f, 0.05f))
    }
}

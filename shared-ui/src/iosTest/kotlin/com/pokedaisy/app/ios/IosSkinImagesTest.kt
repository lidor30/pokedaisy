package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.data.writeBytes
import okio.FileSystem
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IosSkinImagesTest {
    /** A synthetic vector page, transparent except for its lower-left red square. No bundled artwork. */
    private fun pdf(): ByteArray {
        val stream = "1 0 0 rg 0 0 50 50 re f\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>",
            "<< /Length ${stream.length} >>\nstream\n${stream}endstream")
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, obj -> out.length.also { out.append("${i + 1} 0 obj\n$obj\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 5\n0000000000 65535 f \n")
        offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test fun pngThumbnailBoundsTheDecodedImageAndRetainsTransparency() {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "skin-${Random.nextLong().toULong()}.png"
        file.writeBytes(kotlin.io.encoding.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAABQAAAAKCAYAAAC0VX7mAAAAGUlEQVR4nGP4z8DwnxjMQCwYNXDUwOFoIADSeMc5D5IKoAAAAABJRU5ErkJggg=="))
        try {
            val image = assertNotNull(IosSkinImages.decode(file, 8, 8))
            assertEquals(8, image.width); assertEquals(4, image.height)
            val pixels = IntArray(32); image.readPixels(pixels)
            assertEquals(0xFFFF0000.toInt(), pixels[0])
            assertEquals(0, pixels[7] ushr 24)
        } finally { FileSystem.SYSTEM.delete(file) }
    }

    @Test fun pdfRetainsTransparencyOrientationAndRequestedSize() {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "skin-${Random.nextLong().toULong()}.pdf"
        file.writeBytes(pdf())
        try {
            val image = assertNotNull(IosSkinImages.decode(file, 80, 120))
            assertEquals(80, image.width); assertEquals(120, image.height)
            val pixels = IntArray(80 * 120)
            image.readPixels(pixels)
            assertEquals(0, pixels[10 * 80 + 10] ushr 24, "top-left must be transparent")
            assertEquals(0xFFFF0000.toInt(), pixels[100 * 80 + 10], "PDF's lower-left red square")
            assertEquals(0, pixels[100 * 80 + 70] ushr 24, "lower-right must be transparent")
        } finally { FileSystem.SYSTEM.delete(file) }
    }
}

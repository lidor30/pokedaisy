package render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A Delta skin of our own for the TOUCH CONTROLS > SKINS shots (no real skin's art is ever used): drawn here with
 * java.awt into PNGs, with an info.json in Delta's shape, imported into the fake files/skins/ the way the app does.
 * [SkinImages.decode] reads PNGs here (the app's also renders PDFs).
 */
object DemoSkin {
    const val ID = "com.pokedaisy.demo"

    fun install(filesDir: File): com.pokedaisy.app.companion.InstalledSkin {
        com.pokedaisy.app.companion.ui.SkinImages.decode = ::decode
        val store = com.pokedaisy.app.companion.DeltaSkinStore(File(filesDir, "skins"))
        store.load(ID)?.let { return it }
        val tmp = File.createTempFile("demo", ".deltaskin")
        java.util.zip.ZipOutputStream(tmp.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("info.json", INFO.toByteArray())
            put("landscape.png", png(LANDSCAPE_W, LANDSCAPE_H, landscape = true))
            put("portrait.png", png(PORTRAIT_W, PORTRAIT_H, landscape = false))
        }
        val r = tmp.inputStream().use { store.import(it) }
        tmp.delete()
        return r.added.firstOrNull() ?: error("demo skin refused: ${r.refused}")
    }

    private fun decode(file: File, w: Int, h: Int): ImageBitmap? {
        val src = ImageIO.read(file) ?: return null
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        return out.toComposeImageBitmap()
    }

    private const val LANDSCAPE_W = 667
    private const val LANDSCAPE_H = 375
    private const val PORTRAIT_W = 414
    private const val PORTRAIT_H = 896

    // Points (mapping size); the PNGs are drawn at 3x.
    private const val SCALE = 3

    private class B(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val round: Boolean, val inputs: String)

    private val landscapeButtons = listOf(
        B("dpad", 30, 150, 120, 120, false, """{ "up": "up", "down": "down", "left": "left", "right": "right" }"""),
        B("A", 590, 160, 56, 56, true, """["a"]"""),
        B("B", 530, 210, 56, 56, true, """["b"]"""),
        B("L", 20, 20, 90, 34, false, """["l"]"""),
        B("R", 557, 20, 90, 34, false, """["r"]"""),
        B("SELECT", 40, 310, 64, 26, false, """["select"]"""),
        B("START", 563, 310, 64, 26, false, """["start"]"""),
        B("MENU", 303, 345, 60, 22, false, """["menu"]"""),
    )
    private val landscapeScreen = intArrayOf(158, 15, 351, 234)

    private val portraitButtons = listOf(
        B("dpad", 30, 560, 140, 140, false, """{ "up": "up", "down": "down", "left": "left", "right": "right" }"""),
        B("A", 320, 560, 66, 66, true, """["a"]"""),
        B("B", 250, 620, 66, 66, true, """["b"]"""),
        B("L", 20, 470, 100, 36, false, """["l"]"""),
        B("R", 294, 470, 100, 36, false, """["r"]"""),
        B("SELECT", 120, 760, 72, 28, false, """["select"]"""),
        B("START", 222, 760, 72, 28, false, """["start"]"""),
        B("MENU", 177, 830, 60, 24, false, """["menu"]"""),
    )
    private val portraitScreen = intArrayOf(0, 60, 414, 276)

    private fun items(list: List<B>) = list.joinToString(",\n") { b ->
        """{ "inputs": ${b.inputs}, "frame": { "x": ${b.x}, "y": ${b.y}, "width": ${b.w}, "height": ${b.h} } }"""
    }

    private fun frame(r: IntArray) = """{ "x": ${r[0]}, "y": ${r[1]}, "width": ${r[2]}, "height": ${r[3]} }"""

    private val INFO = """
        { "name": "Daisy Demo", "identifier": "$ID", "gameTypeIdentifier": "com.rileytestut.delta.game.gba", "debug": false,
          "representations": { "iphone": {
            "standard": { "landscape": {
              "assets": { "large": "landscape.png" }, "items": [ ${items(landscapeButtons)} ],
              "screens": [ { "inputFrame": { "x": 0, "y": 0, "width": 240, "height": 160 }, "outputFrame": ${frame(landscapeScreen)} } ],
              "mappingSize": { "width": $LANDSCAPE_W, "height": $LANDSCAPE_H }, "extendedEdges": { "top": 8, "bottom": 8, "left": 8, "right": 8 } } },
            "edgeToEdge": { "portrait": {
              "assets": { "large": "portrait.png" }, "items": [ ${items(portraitButtons)} ],
              "screens": [ { "inputFrame": { "x": 0, "y": 0, "width": 240, "height": 160 }, "outputFrame": ${frame(portraitScreen)} } ],
              "mappingSize": { "width": $PORTRAIT_W, "height": $PORTRAIT_H }, "extendedEdges": { "top": 8, "bottom": 8, "left": 8, "right": 8 } } }
          } } }
    """.trimIndent()

    /** A teal shell with a cream face plate, the buttons drawn where info.json says, the screen cut out. */
    private fun png(w: Int, h: Int, landscape: Boolean): ByteArray {
        val img = BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(SCALE.toDouble(), SCALE.toDouble())
        g.paint = java.awt.GradientPaint(0f, 0f, Color(0x2E8C8A), 0f, h.toFloat(), Color(0x1F5F66))
        g.fillRect(0, 0, w, h)
        val screen = if (landscape) landscapeScreen else portraitScreen
        // The bezel round the screen, the screen itself left see-through (the game shows there).
        g.color = Color(0x1A2228)
        g.fill(RoundRectangle2D.Float(screen[0] - 8f, screen[1] - 8f, screen[2] + 16f, screen[3] + 16f, 18f, 18f))
        val c = g.composite
        g.composite = java.awt.AlphaComposite.Clear
        g.fillRect(screen[0], screen[1], screen[2], screen[3])
        g.composite = c
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        for (b in if (landscape) landscapeButtons else portraitButtons) {
            when {
                b.name == "dpad" -> {
                    val arm = b.w / 3
                    g.color = Color(0x15191E)
                    g.fillRoundRect(b.x + arm, b.y + 3, arm, b.h, 8, 8)
                    g.fillRoundRect(b.x, b.y + arm + 3, b.w, arm, 8, 8)
                    g.color = Color(0x2A3138)
                    g.fillRoundRect(b.x + arm, b.y, arm, b.h, 8, 8)
                    g.fillRoundRect(b.x, b.y + arm, b.w, arm, 8, 8)
                }
                b.round -> {
                    g.color = Color(0x7A1636)
                    g.fillOval(b.x, b.y + 3, b.w, b.h)
                    g.color = Color(0xC22D5C)
                    g.fillOval(b.x, b.y, b.w, b.h)
                    label(g, b, Color.WHITE)
                }
                else -> {
                    g.color = Color(0x55606A)
                    g.fill(RoundRectangle2D.Float(b.x.toFloat(), b.y + 2f, b.w.toFloat(), b.h.toFloat(), b.h.toFloat(), b.h.toFloat()))
                    g.color = Color(0xE8E2D0)
                    g.fill(RoundRectangle2D.Float(b.x.toFloat(), b.y.toFloat(), b.w.toFloat(), b.h.toFloat(), b.h.toFloat(), b.h.toFloat()))
                    g.color = Color(0x55606A)
                    g.stroke = BasicStroke(1.5f)
                    g.draw(RoundRectangle2D.Float(b.x.toFloat(), b.y.toFloat(), b.w.toFloat(), b.h.toFloat(), b.h.toFloat(), b.h.toFloat()))
                    label(g, b, Color(0x2A3138))
                }
            }
        }
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun label(g: java.awt.Graphics2D, b: B, color: Color) {
        val fm = g.fontMetrics
        g.color = color
        g.drawString(b.name, b.x + (b.w - fm.stringWidth(b.name)) / 2, b.y + (b.h + fm.ascent - fm.descent) / 2)
    }
}

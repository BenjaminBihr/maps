package com.example.maps.globe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader

/**
 * Paints the equirectangular base map (oceans, graticule, filled countries) that wraps the
 * sphere. Coastlines and borders are drawn separately as vector lines so they stay sharp
 * when zooming in.
 */
internal object WorldTexture {

    private const val OCEAN_POLE = 0xFF0E3557.toInt()
    private const val OCEAN_EQUATOR = 0xFF1D5F8F.toInt()
    private const val GRATICULE = 0x22FFFFFF
    private const val GRATICULE_STEP_DEG = 15
    private const val LAND_MASK = 0xFF000000.toInt()

    /** Index 0 = ice, 1..7 = Natural Earth MAPCOLOR7 (neighbors never share a color). */
    private val LAND_COLORS = intArrayOf(
        0xFFE9EEF2.toInt(),
        0xFFD9C79B.toInt(),
        0xFFB2C99A.toInt(),
        0xFFE0B596.toInt(),
        0xFFC2BCD6.toInt(),
        0xFFA6C9BE.toInt(),
        0xFFE3CF92.toInt(),
        0xFFD3AFAF.toInt(),
    )

    /** Land/water mask for the sun glint: alpha 1 on land, 0 on water. */
    fun renderLandMask(world: WorldData, width: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, width / 2, Bitmap.Config.ALPHA_8)
        drawLand(Canvas(bitmap), world, width) { LAND_MASK }
        return bitmap
    }

    /** The political map: ocean gradient, a 15° graticule, then the countries on top. */
    fun render(world: WorldData, width: Int): Bitmap {
        val height = width / 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sx = width / 360f
        val sy = height / 180f

        val ocean = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(OCEAN_POLE, OCEAN_EQUATOR, OCEAN_POLE),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ocean)

        val graticule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRATICULE
            style = Paint.Style.STROKE
            strokeWidth = width / 2048f
        }
        for (lon in -180..180 step GRATICULE_STEP_DEG) {
            val x = (lon + 180) * sx
            canvas.drawLine(x, 0f, x, height.toFloat(), graticule)
        }
        for (lat in -90..90 step GRATICULE_STEP_DEG) {
            val y = (90 - lat) * sy
            canvas.drawLine(0f, y, width.toFloat(), y, graticule)
        }

        drawLand(canvas, world, width) { polygon -> LAND_COLORS[polygon.colorIndex.coerceIn(0, LAND_COLORS.lastIndex)] }
        return bitmap
    }

    /** Fills every country polygon (holes left open) in equirectangular projection. */
    private fun drawLand(canvas: Canvas, world: WorldData, width: Int, colorOf: (WorldData.Polygon) -> Int) {
        val sx = width / 360f
        val sy = width / 2 / 180f
        val land = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (polygon in world.polygons) {
            path.rewind()
            for (ring in polygon.rings) {
                if (ring.size < 6) continue
                path.moveTo((ring[0] + 180f) * sx, (90f - ring[1]) * sy)
                for (i in 2 until ring.size step 2) {
                    path.lineTo((ring[i] + 180f) * sx, (90f - ring[i + 1]) * sy)
                }
                path.close()
            }
            land.color = colorOf(polygon)
            canvas.drawPath(path, land)
        }
    }
}

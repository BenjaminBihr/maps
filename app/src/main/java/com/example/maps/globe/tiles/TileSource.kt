package com.example.maps.globe.tiles

import java.util.Locale

/**
 * NASA GIBS (Global Imagery Browse Services): Blue Marble with shaded relief and bathymetry,
 * public domain, no API key, Web Mercator tiles of 256 px up to level 8 (about 600 m/px).
 */
internal object TileSource {

    const val MAX_LEVEL = 8

    private const val URL_PATTERN = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/" +
        "BlueMarble_ShadedRelief_Bathymetry/default/GoogleMapsCompatible_Level8/%d/%d/%d.jpeg"

    /** GIBS orders the path z / row / column. */
    fun url(tile: TileKey): String = String.format(Locale.ROOT, URL_PATTERN, tile.z, tile.y, tile.x)
}

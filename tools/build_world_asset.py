#!/usr/bin/env python3
"""Builds app/src/main/assets/world.bin from Natural Earth admin-0 countries.

Source data (public domain): https://www.naturalearthdata.com/
    curl -L -o ne_50m_admin_0_countries.geojson \
      https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson

Usage:
    python3 tools/build_world_asset.py ne_50m_admin_0_countries.geojson app/src/main/assets/world.bin

Binary format (little-endian), read by WorldData.kt:
    magic   b'GLB1'
    int32   polygonCount
      int32 colorIndex        0 = ice (Antarctica), 1..7 = Natural Earth MAPCOLOR7
      int32 ringCount         first ring = outer boundary, others = holes
        int32 pointCount, then pointCount x (float32 lon, float32 lat)
    int32   lineCount
      int32 kind              0 = coastline, 1 = land border between two countries
      int32 pointCount, then pointCount x (float32 lon, float32 lat)

Every shared border segment is emitted once, and the artificial edges Natural Earth
adds to close polygons along the antimeridian and the south pole are dropped.
"""
import json
import struct
import sys
from collections import Counter

COASTLINE = 0
BORDER = 1


def is_artificial(a, b):
    """True for an edge that only closes a polygon along the antimeridian or the south pole."""
    along_antimeridian = abs(a[0]) >= 179.99 and abs(b[0]) >= 179.99
    along_south_pole = a[1] <= -89.99 and b[1] <= -89.99
    return along_antimeridian or along_south_pole


def segment_key(a, b):
    """Same key for a segment whichever direction it is walked in."""
    return (a, b) if a <= b else (b, a)


def load_polygons(path):
    """Every polygon of every country as (colorIndex, rings), MultiPolygons split apart."""
    with open(path) as f:
        features = json.load(f)['features']
    polygons = []
    for feature in features:
        props = feature['properties']
        geometry = feature['geometry']
        color = 0 if props['NAME'] == 'Antarctica' else int(props['MAPCOLOR7'])
        parts = geometry['coordinates'] if geometry['type'] == 'MultiPolygon' else [geometry['coordinates']]
        for part in parts:
            rings = [[(float(p[0]), float(p[1])) for p in ring] for ring in part]
            polygons.append((color, rings))
    return polygons


def build_lines(polygons):
    """Splits polygon outlines into coastline and border polylines.

    A segment used by two polygons is a border between them, one used once is coastline.
    Consecutive segments of the same kind are chained into one polyline.
    """
    usage = Counter()
    for _, rings in polygons:
        for ring in rings:
            for a, b in zip(ring, ring[1:]):
                if a != b:
                    usage[segment_key(a, b)] += 1

    lines = []
    emitted = set()
    for _, rings in polygons:
        for ring in rings:
            current, kind = [], None

            def flush():
                if len(current) >= 2:
                    lines.append((kind, list(current)))
                current.clear()

            for a, b in zip(ring, ring[1:]):
                key = segment_key(a, b)
                if a == b or is_artificial(a, b) or key in emitted:
                    flush()
                    continue
                emitted.add(key)
                segment_kind = BORDER if usage[key] > 1 else COASTLINE
                if current and kind == segment_kind and current[-1] == a:
                    current.append(b)
                else:
                    flush()
                    current.extend([a, b])
                    kind = segment_kind
            flush()
    return lines


def pack_points(points):
    """int32 count, then the (lon, lat) float32 pairs."""
    flat = [c for p in points for c in p]
    return struct.pack('<i', len(points)) + struct.pack('<%df' % len(flat), *flat)


def main(src, dst):
    polygons = load_polygons(src)
    lines = build_lines(polygons)

    out = bytearray(b'GLB1')
    out += struct.pack('<i', len(polygons))
    for color, rings in polygons:
        out += struct.pack('<ii', color, len(rings))
        for ring in rings:
            out += pack_points(ring)
    out += struct.pack('<i', len(lines))
    for kind, points in lines:
        out += struct.pack('<i', kind)
        out += pack_points(points)

    with open(dst, 'wb') as f:
        f.write(out)

    coast = sum(len(p) - 1 for k, p in lines if k == COASTLINE)
    border = sum(len(p) - 1 for k, p in lines if k == BORDER)
    print(f'{len(polygons)} polygons, {len(lines)} lines '
          f'({coast} coastline segments, {border} border segments), {len(out)} bytes -> {dst}')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])

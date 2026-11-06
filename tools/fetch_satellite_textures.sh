#!/bin/sh
# Satellite textures for the globe: NASA imagery (public domain), equirectangular,
# north up, longitude -180 on the left (same layout as the political map texture).
#   earth_day.jpg    NASA Blue Marble, downscaled to 2048x1024: the base image; sharper
#                    imagery streams in as tiles from NASA GIBS when zooming in
#   earth_night.jpg  NASA Earth at Night (city lights), downscaled to 2048x1024
#   earth_clouds.jpg Natural Earth III clouds by Tom Patterson (shadedrelief.com, public
#                    domain), grayscale, downscaled to 2048x1024
# NASA images are redistributed by the three-globe npm package (fetched through jsDelivr),
# the clouds come from Wikimedia Commons.
# Run from the repository root (macOS: uses sips to resize).
set -eu

base=https://cdn.jsdelivr.net/npm/three-globe@2.45.6/example/img
out=app/src/main/assets
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

curl -fsSL -o "$tmp/day.jpg" "$base/earth-blue-marble.jpg"
sips -z 1024 2048 -s formatOptions 88 "$tmp/day.jpg" --out "$out/earth_day.jpg" >/dev/null
curl -fsSL -o "$tmp/night.jpg" "$base/earth-night.jpg"
sips -z 1024 2048 -s formatOptions 88 "$tmp/night.jpg" --out "$out/earth_night.jpg" >/dev/null

curl -fsSL -A "maps-globe-assets/1.0" -o "$tmp/clouds.jpg" \
  https://upload.wikimedia.org/wikipedia/commons/9/9d/Australia_clouds_8k.jpg
sips -z 1024 2048 -s formatOptions 85 "$tmp/clouds.jpg" --out "$out/earth_clouds.jpg" >/dev/null

ls -l "$out/earth_day.jpg" "$out/earth_night.jpg" "$out/earth_clouds.jpg"

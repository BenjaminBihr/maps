package com.example.maps.globe

/**
 * GLSL ES 1.00 sources of the four programs: the globe (sphere and tile patches), the
 * coastline and border lines, the tour markers and the atmosphere halo.
 */
internal object Shaders {

    // highp in fragment shaders: mediump UVs are too coarse for a 4096 px texture.
    private const val FRAGMENT_PRECISION = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
    """

    // aUv: equirectangular uv of the whole-world textures. aDayUv: uv in the daylight image,
    // the same for the base globe, inside the tile image for a satellite tile patch.
    const val SPHERE_VERTEX = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        attribute vec3 aPosition;
        attribute vec2 aUv;
        attribute vec2 aDayUv;
        varying vec2 vUv;
        varying vec2 vDayUv;
        varying vec3 vNormal;
        void main() {
            vUv = aUv;
            vDayUv = aDayUv;
            vNormal = (uModel * vec4(aPosition, 0.0)).xyz;
            gl_Position = uMvp * vec4(aPosition, 1.0);
        }
    """

    const val SPHERE_FRAGMENT = FRAGMENT_PRECISION + """
        uniform sampler2D uTexture; // political map
        uniform sampler2D uDay;     // satellite imagery (whole world, or one tile)
        uniform sampler2D uNight;   // city lights
        uniform sampler2D uLand;    // alpha: 1 on land, 0 on water (sun glint)
        uniform sampler2D uClouds;  // cloud cover, white = overcast
        uniform vec3 uLightDir;     // studio light for the political map (view space)
        uniform vec3 uSunDir;       // real sun direction (view space)
        uniform float uSatellite;   // 0 = political map, 1 = satellite with day and night
        uniform float uPatch;       // 1 while drawing tile patches over the globe
        uniform float uSunlight;    // 1 = real sun with day and night, 0 = always daylight
        uniform vec3 uBackground;
        uniform float uReveal;
        varying vec2 vUv;
        varying vec2 vDayUv;
        varying vec3 vNormal;
        void main() {
            // Tile patches are drawn without depth test: drop the far side of the globe.
            if (uPatch > 0.5 && vNormal.z < 0.0) discard;
            vec3 n = normalize(vNormal);
            vec3 sky = vec3(0.45, 0.70, 1.0);
            float rim = pow(1.0 - max(n.z, 0.0), 3.0);

            vec3 color = texture2D(uTexture, vUv).rgb * (0.32 + 0.78 * max(dot(n, uLightDir), 0.0));
            color = mix(color, sky, rim * 0.55);

            if (uSatellite > 0.0) {
                // uSunlight 1: lit by the real sun (night side, glint, dusk); 0: daylight everywhere.
                float sun = dot(n, uSunDir);
                float studio = 0.55 + 0.45 * max(dot(n, uLightDir), 0.0);
                float lit = mix(studio, clamp(sun, 0.0, 1.0), uSunlight);
                float daylight = mix(1.0, smoothstep(-0.04, 0.10, sun), uSunlight);
                float water = 1.0 - texture2D(uLand, vUv).a;
                float cloud = smoothstep(0.08, 0.9, texture2D(uClouds, vUv).r);
                vec3 dayImage = texture2D(uDay, vDayUv).rgb;

                vec3 day = dayImage * (0.22 + 0.95 * pow(lit, 0.6));
                day += vec3(0.0, 0.05, 0.12) * water * lit;
                // Clouds lit by the sun, warmer near the terminator.
                float warm = (1.0 - smoothstep(0.0, 0.25, sun)) * uSunlight;
                vec3 cloudLight = (0.18 + 0.92 * sqrt(lit)) * (vec3(1.0, 0.97, 0.94) - vec3(0.0, 0.12, 0.25) * warm);
                day = mix(day, cloudLight, cloud * 0.92);
                // Atmospheric haze: bluer, stronger towards the limb.
                day = mix(day, vec3(0.30, 0.50, 0.85), (0.12 + 0.45 * rim) * lit);

                // Night: dim grey ground, faint clouds and warm city lights (veiled by clouds).
                // The NASA night image also shows the moonlit ground in blue; only the lights
                // carry red, so keep just those.
                float lights = smoothstep(0.06, 0.32, texture2D(uNight, vUv).r) * (1.0 - 0.75 * cloud);
                float ground = dot(dayImage, vec3(0.3, 0.55, 0.15));
                vec3 night = vec3(ground * 0.09) + vec3(0.01, 0.012, 0.02) + vec3(0.05, 0.055, 0.065) * cloud
                    + vec3(1.0, 0.85, 0.55) * lights * 1.25;
                vec3 satellite = mix(night, day, daylight);

                // Sun glint: the sun mirrored by open water (Blinn-Phong, viewer looking down -z).
                vec3 halfway = normalize(uSunDir + vec3(0.0, 0.0, 1.0001));
                float nh = max(dot(n, halfway), 0.0);
                float glint = water * (1.0 - cloud) * smoothstep(0.0, 0.2, sun) * uSunlight
                    * (pow(nh, 90.0) * 0.55 + pow(nh, 14.0) * 0.22);
                satellite += vec3(1.0, 0.95, 0.85) * glint;

                // Soft warm band along the terminator, then the atmosphere only where it is lit.
                float dusk = (1.0 - smoothstep(0.0, 0.07, abs(sun - 0.01))) * uSunlight;
                satellite += vec3(1.0, 0.5, 0.2) * dusk * 0.10;
                satellite = mix(satellite, sky, rim * 0.45 * mix(1.0, smoothstep(-0.1, 0.3, sun), uSunlight));
                color = mix(color, satellite, uSatellite);
            }
            gl_FragColor = vec4(mix(uBackground, color, uReveal), 1.0);
        }
    """

    // Lines are lifted by uLift above the surface and fade out (vFade) toward the globe's edge.
    const val LINE_VERTEX = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform float uLift;
        attribute vec3 aPosition;
        varying float vFade;
        void main() {
            float facing = (uModel * vec4(aPosition, 0.0)).z;
            vFade = smoothstep(0.0, 0.2, facing);
            gl_Position = uMvp * vec4(aPosition * uLift, 1.0);
        }
    """

    const val LINE_FRAGMENT = FRAGMENT_PRECISION + """
        uniform vec4 uColor;
        uniform float uReveal;
        varying float vFade;
        void main() {
            gl_FragColor = vec4(uColor.rgb, uColor.a * vFade * uReveal);
        }
    """

    // Point sprites slightly above the surface, fading out toward the globe's edge.
    const val MARKER_VERTEX = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform float uPointSize;
        attribute vec3 aPosition;
        varying float vFade;
        void main() {
            float facing = (uModel * vec4(aPosition, 0.0)).z;
            vFade = smoothstep(0.05, 0.2, facing);
            gl_PointSize = uPointSize;
            gl_Position = uMvp * vec4(aPosition * 1.003, 1.0);
        }
    """

    // uRing = 0: filled dot with a dark outline; uRing = 1: ring starting at uRingInner
    // (0..1 of the radius), the expanding pulse around the current tour stop.
    const val MARKER_FRAGMENT = FRAGMENT_PRECISION + """
        uniform vec3 uColor;
        uniform float uAlpha;
        uniform float uRing;
        uniform float uRingInner;
        varying float vFade;
        void main() {
            float r = length(gl_PointCoord * 2.0 - 1.0);
            float disc = 1.0 - smoothstep(0.85, 1.0, r);
            float soft = (1.0 - uRingInner) * 0.5;
            float ring = smoothstep(uRingInner - soft, uRingInner, r) * (1.0 - smoothstep(1.0 - soft, 1.0, r));
            vec3 color = mix(uColor, vec3(0.04, 0.10, 0.16), smoothstep(0.55, 0.7, r) * (1.0 - uRing));
            float alpha = mix(disc, ring, uRing) * uAlpha * vFade;
            if (alpha <= 0.0) discard;
            gl_FragColor = vec4(color, alpha);
        }
    """

    // vPosition: position in globe radii, the globe's edge at length 1.
    const val HALO_VERTEX = """
        uniform mat4 uProjection;
        attribute vec2 aPosition;
        varying vec2 vPosition;
        void main() {
            vPosition = aPosition;
            gl_Position = uProjection * vec4(aPosition, 0.0, 1.0);
        }
    """

    // Premultiplied alpha glow that fades out from the globe's edge.
    const val HALO_FRAGMENT = FRAGMENT_PRECISION + """
        uniform float uOuterRadius;
        uniform float uReveal;
        varying vec2 vPosition;
        void main() {
            float d = length(vPosition);
            float glow = exp(-max(d - 1.0, 0.0) * 9.0) * 0.6 * uReveal;
            glow *= 1.0 - smoothstep(uOuterRadius - 0.1, uOuterRadius, d);
            gl_FragColor = vec4(vec3(0.35, 0.62, 1.0) * glow, glow);
        }
    """
}

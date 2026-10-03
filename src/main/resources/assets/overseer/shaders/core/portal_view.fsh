#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// ColorModulator.x carries how sealed the doorways are, 0 (anyone may pass) to 1 (only the
// Overseer may), rather than a colour.

uniform sampler2D PortalView;

in vec2 quadUv;
in vec2 halfSize;
in vec4 tint;
in vec4 clipPos;

out vec4 fragColor;

const vec3 RIM_OPEN = vec3(0.42, 0.60, 1.00);
const vec3 RIM_HOT_OPEN = vec3(0.86, 0.93, 1.00);

// A sealed doorway glows the colour of a warning instead.
const vec3 RIM_SEALED = vec3(1.00, 0.20, 0.26);
const vec3 RIM_HOT_SEALED = vec3(1.00, 0.72, 0.66);

// Set at the top of main(), from how sealed the doorways are. Everything that glows takes its
// colour from these two, so the whole frame changes together.
vec3 rim;
vec3 rimHot;
const vec3 DEEP = vec3(0.012, 0.014, 0.026);

// The far side is rendered full-screen from a virtual camera sharing this frame's projection and
// screen alignment, so the correct texel for a point on the quad is the one at that point's own
// screen position. A projective lookup, not a lookup by quad UV.
vec2 screenUv() {
    return (clipPos.xy / clipPos.w) * 0.5 + 0.5;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(
        mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
        mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x),
        f.y
    );
}

float fbm(vec2 p) {
    float total = 0.0;
    float amplitude = 0.5;

    for (int i = 0; i < 4; i++) {
        total += noise(p) * amplitude;
        p = p * 2.07 + vec2(11.3, 5.7);
        amplitude *= 0.5;
    }

    return total;
}

// Seconds. GameTime runs 0..1 over one day of 24000 ticks.
float seconds() {
    return GameTime * 1200.0;
}

// Fallback when no live view exists: slow smoke folding in on itself, over a field of stars.
//
// The smoke is anchored to the doorway and the stars to the screen. Things fixed to the screen do
// not move as you walk past, which is exactly how something infinitely far away behaves -- so the
// two layers slide against each other and the opening reads as deep rather than as a flat panel.
vec3 proceduralVoid(vec2 blocks) {
    float t = seconds();
    vec2 p = blocks * 0.9;

    // Warp the domain by another noise field. Plain layered noise only drifts; warped, it curls.
    vec2 warp = vec2(
        fbm(p + vec2(0.0, t * 0.05)),
        fbm(p + vec2(7.3, -t * 0.04))
    );
    float smoke = fbm(p * 1.3 + warp * 1.9 + vec2(0.0, t * 0.03));
    smoke = smoothstep(0.25, 0.85, smoke);

    // Brightest toward the middle, so it recedes rather than sitting on the surface.
    float falloff = 1.0 - smoothstep(0.0, 1.15, length(blocks / halfSize));

    vec3 color = mix(DEEP, rim * 0.42, smoke * (0.25 + 0.75 * falloff));

    vec2 cell = floor(gl_FragCoord.xy / 3.0);
    float seed = hash(cell);
    float star = step(0.9965, seed);
    float twinkle = 0.55 + 0.45 * sin(t * 2.6 + seed * 913.0);

    // Smoke in front of a star dims it.
    color += rimHot * star * twinkle * (1.0 - smoke * 0.8) * 0.9;

    return color;
}

// The sides of the recess. Shading these as walls rather than as more view is the only thing that
// makes the doorway read as having thickness: the aperture samples in screen space, so moving it
// back would otherwise change nothing you can see.
//
// A flat gradient is not enough for that, because all four faces then come out identical and the
// eye reads four identical faces as one flat border. Three things give them form:
//
//   - each face is lit differently, the way Minecraft lights the faces of a block, so the floor
//     of the recess, its ceiling and its two sides are visibly different surfaces;
//   - the faces darken into the corners where they meet, which is what makes a corner a corner;
//   - the surface is drawn on the same 16-to-a-block pixel grid as everything around it, so it
//     reads as material with a grain rather than as a smooth shader effect pasted into the world.
//
// depth runs 0 at the lip to 1 at the aperture. along is in blocks from the middle of the face,
// halfLength is half the face's length, and light is the face's own brightness.
vec3 recessWall(float depth, float along, float halfLength, float light) {
    float t = seconds();

    // Snap to the block pixel grid. The recess is half a block deep, so eight pixels.
    vec2 pixel = floor(vec2((along + halfLength) * 16.0, depth * 8.0));
    float pixelDepth = (pixel.y + 0.5) / 8.0;
    float grain = hash(pixel);

    vec3 stone = mix(vec3(0.21, 0.23, 0.33), vec3(0.030, 0.032, 0.055), pow(pixelDepth, 0.7));
    stone *= 0.80 + 0.40 * grain;

    // Courses, like laid blocks: a darker joint every half block along, and one midway in.
    float joint = step(15.0, mod(pixel.x, 8.0) + 8.0) + step(3.5, pixel.y) * step(pixel.y, 4.5) * 0.6;
    stone *= 1.0 - 0.35 * clamp(joint, 0.0, 1.0);

    // Occlusion into the corners where two faces meet.
    float toCorner = halfLength - abs(along);
    float occlusion = mix(0.45, 1.0, smoothstep(0.0, 0.22, toCorner));

    vec3 color = stone * light * occlusion;

    // Veins of light running through the stone toward the aperture, brightening in slow waves
    // that travel inward, as if the doorway were drawing something through the frame.
    float vein = noise(pixel * vec2(0.55, 0.9) + 3.7);
    vein = smoothstep(0.60, 0.72, vein);
    float wave = 0.5 + 0.5 * sin(pixelDepth * 5.0 - t * 1.4 + grain * 1.5);
    color += rim * vein * wave * (0.20 + 0.55 * pixelDepth);

    // Light spilling out of the aperture onto the last stretch of wall. This one is smooth on
    // purpose: it is light falling on the surface, not part of the surface.
    float spill = exp(-(1.0 - depth) * 5.5);
    float flicker = 0.75 + 0.25 * noise(vec2(along * 4.0, t * 0.7));
    color += rim * spill * flicker * 0.60 * light;

    // The outer edge catches a little light, so the opening has a crisp lip.
    color += rimHot * (1.0 - smoothstep(0.0, 0.07, depth)) * 0.07 * light;

    return color;
}

void main() {
    float sealed = clamp(ColorModulator.x, 0.0, 1.0);
    rim = mix(RIM_OPEN, RIM_SEALED, sealed);
    rimHot = mix(RIM_HOT_OPEN, RIM_HOT_SEALED, sealed);

    // Diagnostic mode: flat magenta, so the aperture's true shape and screen position can be seen
    // independently of whatever it would otherwise be sampling.
    if (tint.a < 0.65) {
        fragColor = vec4(1.0, 0.0, 1.0, 1.0);
        return;
    }

    // Passing through: the camera is beyond the doorway and the server has not caught up. The
    // far view fills the screen, plain, exactly as it will look once the crossing lands.
    if (tint.a < 0.9) {
        fragColor = vec4(texture(PortalView, clamp(screenUv(), 0.0, 1.0)).rgb, 1.0);
        return;
    }

    if (tint.g > 0.5) {
        // For a wall, the vertex shader's "half size" is the face's half-length and its light.
        fragColor = vec4(recessWall(tint.r, quadUv.x, halfSize.x, halfSize.y), 1.0);
        return;
    }

    float t = seconds();

    // The portal an eviction opens in the sky: round, frameless, and never sealed.
    bool isRound = tint.r < 0.75;

    // Distance in from the nearest edge of the opening, in blocks.
    vec2 inset = halfSize - abs(quadUv);
    float edge = max(min(inset.x, inset.y), 0.0);

    if (isRound) {
        float fromCentre = length(quadUv / halfSize);
        if (fromCentre > 1.0) {
            discard;
        }

        sealed = 0.0;
        rim = RIM_OPEN;
        rimHot = RIM_HOT_OPEN;

        // It is many times the size of a doorway and seen from much further away, so everything
        // measured from its edge -- the ripple, the shading, the rim -- is scaled up to match,
        // or it would all be a hairline.
        edge = (1.0 - fromCentre) * min(halfSize.x, halfSize.y) / 9.0;
    }

    // Near the frame the view swims slightly, as though seen through the skin of something. It
    // dies out a third of a block in, so the middle of the doorway is a clean window.
    float nearEdge = 1.0 - smoothstep(0.0, 0.35, edge);
    vec2 ripple = vec2(
        noise(quadUv * 6.0 + vec2(0.0, t * 0.9)),
        noise(quadUv * 6.0 + vec2(31.7, -t * 0.8))
    ) - 0.5;

    vec2 uv = screenUv() + ripple * 0.006 * nearEdge;
    bool onScreen = uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;

    // Blue carries how far the live view has faded in over the procedural one.
    float live = onScreen ? tint.b : 0.0;

    vec3 color = proceduralVoid(quadUv);
    if (live > 0.0) {
        color = mix(color, texture(PortalView, uv).rgb, live);
    }

    // Sealed: the view is still there, but drained and dimmed behind a lattice of bars, so it
    // reads at a glance as something you can see into and cannot enter.
    if (sealed > 0.0) {
        float grey = dot(color, vec3(0.299, 0.587, 0.114));
        vec3 shut = mix(vec3(grey), RIM_SEALED * 0.25, 0.35) * 0.42;

        // Two sets of diagonal bars, measured in blocks so they are the same size on any doorway.
        float a = abs(fract((quadUv.x + quadUv.y) * 1.5) - 0.5);
        float b = abs(fract((quadUv.x - quadUv.y) * 1.5) - 0.5);
        float bars = smoothstep(0.075, 0.035, min(a, b));

        // A pulse running outward along the bars from the middle.
        float pulse = 0.55 + 0.45 * sin(t * 2.2 - length(quadUv) * 3.0);

        shut = mix(shut, rim * pulse, bars * 0.85);
        color = mix(color, shut, sealed);
    }

    // The recess shades the edge of whatever is behind it.
    color *= mix(0.45, 1.0, smoothstep(0.0, 0.30, edge));

    // The rim: a tight bright line on the frame with a softer halo inside it, both breathing.
    float around = quadUv.x + quadUv.y;
    float pulse = 0.75 + 0.25 * noise(vec2(around * 2.5 - t * 0.6, t * 0.35));
    float halo = exp(-edge * 9.0);
    float line = exp(-edge * 55.0);

    color += rim * halo * pulse * 0.38;
    color += rimHot * line * pulse * 0.65;

    fragColor = vec4(color, 1.0);
}

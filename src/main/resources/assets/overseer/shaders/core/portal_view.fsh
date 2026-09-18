#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D PortalView;

in vec2 quadUv;
in vec4 tint;
in vec4 clipPos;

out vec4 fragColor;

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

// Fallback when no live view exists: a receding void with drifting depth, so the aperture still
// reads as a hole into somewhere rather than a flat panel.
vec3 proceduralVoid(vec2 uv) {
    vec2 centred = uv - 0.5;
    float radius = length(centred);

    float t = GameTime * 240.0;

    float depth = 0.0;
    float amplitude = 0.55;
    vec2 p = centred * 3.5;

    for (int i = 0; i < 4; i++) {
        depth += noise(p + vec2(t * 0.12, -t * 0.08)) * amplitude;
        p *= 2.1;
        amplitude *= 0.5;
    }

    // Pull toward black at the centre so it feels like it recedes.
    float falloff = smoothstep(0.55, 0.05, radius);
    vec3 deep = vec3(0.016, 0.018, 0.031);
    vec3 glow = vec3(0.20, 0.30, 0.52);

    return mix(deep, glow, depth * falloff * 0.75);
}

// The sides of the recess. Shading these as walls rather than as more view is the only thing that
// makes the doorway read as having thickness: the aperture samples in screen space, so moving it
// back would otherwise change nothing you can see.
vec3 recessWall(float depth) {
    vec3 lip = vec3(0.34, 0.43, 0.56);
    vec3 deep = vec3(0.015, 0.020, 0.032);

    // Falls off fast, so the lip catches the light and the rest goes to shadow.
    return mix(lip, deep, sqrt(depth));
}

void main() {
    vec2 uv = screenUv();

    // Diagnostic mode: flat magenta, so the aperture's true shape and screen position can be seen
    // independently of whatever it would otherwise be sampling.
    if (tint.a > 0.4 && tint.a < 0.6) {
        fragColor = vec4(1.0, 0.0, 1.0, 1.0);
        return;
    }

    if (tint.g > 0.5) {
        fragColor = vec4(recessWall(tint.r), 1.0);
        return;
    }

    bool hasView = tint.a > 0.5;
    bool onScreen = uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;

    vec3 color = (hasView && onScreen)
        ? texture(PortalView, uv).rgb
        : proceduralVoid(quadUv);

    // A soft darkening where the aperture meets the walls of the recess, so the join reads as a
    // corner rather than a seam. The recess itself now supplies the edge, so this is much lighter
    // than the hard rim the flat quad needed.
    vec2 toEdge = min(quadUv, vec2(1.0) - quadUv);
    float edge = min(toEdge.x, toEdge.y);
    float corner = 1.0 - smoothstep(0.0, 0.07, edge);

    color = mix(color, color * 0.35, corner * 0.8);

    fragColor = vec4(color, 1.0);
}

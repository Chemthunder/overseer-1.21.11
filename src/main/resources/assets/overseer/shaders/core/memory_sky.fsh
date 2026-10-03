#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// The eviction sequence arrives in three fields of the transform block that this shader has no
// other use for, which saves it a uniform block of its own:
//
//   ModelOffset       the direction of the eye from the camera
//   ColorModulator    x presence   whether there is anything there at all, 0 to 1
//                     y dread      how dark and troubled the sky has become
//                     z flare      how far the sky has whited out
//                     w unit       the angle the finished portal's radius is seen at, in radians;
//                                  every size in the eye is a multiple of it
//   TextureMat[0]     x lid        how far the lids have parted
//                     y,z gaze     where the iris is looking, -1 to 1 each way; 0,0 is at you
//                     w pupil      the pupil's radius, in units
//   TextureMat[1]     x roundness  0 a slit pupil, 1 a round one
//                     y turned     how far the eye has become the portal
//                     z shock      the shockwave's progress across the sky
//                     w pull       how hard the sky is being dragged toward the eye
//
// The eye is not a fixed feature of this sky. It opens around the portal an eviction tears
// there, which is a real object at a real place, so its direction and size are worked out from
// that portal and the camera each frame and it stays wrapped around the portal from anywhere.

in vec3 viewDir;

out vec4 fragColor;

// Must match the biome's fog colour. Terrain fades to this with distance, so the sky has to be
// exactly this at the horizon or the edge of the world shows as a hard line against it.
const vec3 FOG = vec3(0.043, 0.047, 0.063);

const vec3 DEEP = vec3(0.010, 0.012, 0.024);
const vec3 BLUE = vec3(0.16, 0.26, 0.55);
const vec3 VIOLET = vec3(0.34, 0.18, 0.52);
const vec3 PALE = vec3(0.86, 0.93, 1.00);

// The direction of the eye. Set in main() from ModelOffset; meaningless while it is closed.
vec3 EYE;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float hash3(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

float noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(
        mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x),
            mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
        mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x),
            mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y),
        f.z
    );
}

float fbm(vec3 p) {
    float total = 0.0;
    float amplitude = 0.5;

    for (int i = 0; i < 4; i++) {
        total += noise(p) * amplitude;
        p = p * 2.03 + vec3(17.1, 9.2, 5.3);
        amplitude *= 0.5;
    }

    return total;
}

// Seconds. GameTime runs 0..1 over one day of 24000 ticks.
float seconds() {
    return GameTime * 1200.0;
}

// One layer of stars. The direction is cut into cells on the faces of a cube, and each cell
// holds at most one star at a random spot inside it -- so they are round points scattered
// unevenly, rather than lit grid squares.
float starLayer(vec3 dir, float scale, float density, float t) {
    vec3 a = abs(dir);
    vec2 uv;
    float face;

    if (a.x >= a.y && a.x >= a.z) { uv = dir.yz / a.x; face = dir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)          { uv = dir.xz / a.y; face = dir.y > 0.0 ? 2.0 : 3.0; }
    else                          { uv = dir.xy / a.z; face = dir.z > 0.0 ? 4.0 : 5.0; }

    vec2 grid = uv * scale;
    vec2 cell = floor(grid) + face * 57.0;
    vec2 local = fract(grid);

    float seed = hash(cell);
    if (seed > density) {
        return 0.0;
    }

    vec2 centre = 0.2 + 0.6 * vec2(hash(cell + 3.1), hash(cell + 7.7));
    float d = length(local - centre);

    float brightness = 0.35 + 0.65 * hash(cell + 13.3);
    float twinkle = 0.65 + 0.35 * sin(t * (1.0 + 3.0 * hash(cell + 21.9)) + seed * 500.0);

    return smoothstep(0.11, 0.0, d) * brightness * twinkle;
}

// The eye.
//
// Worked in a flat frame laid over the sky around EYE, measured in units of the finished portal's
// radius: p is the point being shaded, x along the eye and y up it.
//
// It is built back to front the way a real one is layered -- white, then iris, then pupil, then
// the lids cutting it all to shape -- and each layer is a mask times a colour, so every edge can
// be softened by a fraction of a unit instead of stair-stepping.
vec3 eye(vec2 p, float t, inout vec3 sky) {
    float lid = TextureMat[0].x;
    vec2 gaze = TextureMat[0].yz;
    float pupilRadius = TextureMat[0].w;
    float roundness = TextureMat[1].x;
    float turned = TextureMat[1].y;
    float locked = smoothstep(0.0, 0.15, TextureMat[1].z);

    // --- The lids -------------------------------------------------------------------------
    // Two arcs meeting in a point at each corner. As the eye turns into the portal they are
    // thrown wide and fade, until what is left is round.
    float halfWidth = 2.7 * (1.0 + 0.35 * turned);
    float height = 1.5 * lid * (1.0 + 1.6 * turned);

    float taper = max(1.0 - (p.x * p.x) / (halfWidth * halfWidth), 0.0);
    float upper = height * taper - p.y;
    float lower = p.y + 0.72 * height * taper;

    // Positive inside the opening, negative under a lid, in units.
    float inside = min(upper, lower);
    float within = smoothstep(0.0, 0.06, inside) * step(abs(p.x), halfWidth);
    float corners = smoothstep(0.0, 0.25, taper);

    // --- The white ------------------------------------------------------------------------
    // Not white: this is not a kind eye. Dark and wet, with veins that stand out toward the
    // corners and a shadow under each lid that makes it a ball rather than a disc.
    float vein = 1.0 - abs(2.0 * noise(vec3(p * 2.3, 4.0)) - 1.0);
    vein = pow(vein, 7.0) * smoothstep(0.6, 2.4, abs(p.x));

    vec3 white = vec3(0.030, 0.032, 0.055);
    white += vec3(0.42, 0.07, 0.16) * vein * (0.5 + 0.5 * locked);
    white *= smoothstep(0.0, 0.55, inside) * 0.75 + 0.25;

    // --- The iris -------------------------------------------------------------------------
    const float IRIS = 1.42;
    vec2 centre = vec2(gaze.x * (2.7 - IRIS) * 0.92, gaze.y) * (1.0 - turned);
    vec2 q = p - centre;
    float r = length(q);
    float bearing = atan(q.y, q.x);

    // Fibres running from the pupil to the rim. Sines of whole multiples of the bearing, so
    // they meet themselves where the angle wraps, broken up by noise so no two are alike.
    float fibre = 0.5
        + 0.28 * sin(bearing * 31.0 + 3.0 * noise(vec3(q * 3.0, t * 0.15)))
        + 0.22 * sin(bearing * 13.0 - r * 4.0 + t * 0.4);
    fibre *= 0.7 + 0.3 * noise(vec3(q * 9.0, 1.0));

    float span = clamp((r - pupilRadius) / max(IRIS - pupilRadius, 1.0e-3), 0.0, 1.0);

    // Pale and hot at the pupil's edge, through blue, to a dark ring at the rim.
    vec3 iris = mix(PALE, BLUE * 1.5, smoothstep(0.0, 0.45, span));
    iris = mix(iris, VIOLET * 0.9, smoothstep(0.45, 0.95, span));
    iris *= 0.35 + 0.95 * fibre;
    iris *= 1.0 - 0.75 * smoothstep(0.80, 1.0, span);

    // It brightens when it finds you, and stays that way.
    iris *= 1.0 + 1.3 * locked;

    float irisMask = smoothstep(IRIS, IRIS - 0.05, r);

    // --- The pupil ------------------------------------------------------------------------
    // A vertical slit, squeezed in x, that rounds out as it becomes the portal.
    float squeeze = mix(0.34, 1.0, roundness);
    float pr = length(vec2(q.x / squeeze, q.y));
    float pupilMask = smoothstep(pupilRadius, pupilRadius - 0.035, pr);

    // A thin bright rim to the pupil, like light catching the edge of a lens.
    float pupilRim = exp(-abs(pr - pupilRadius) * 38.0);

    // --- Put together ---------------------------------------------------------------------
    vec3 ball = mix(white, iris, irisMask);
    ball += PALE * pupilRim * (0.35 + 0.9 * locked) * irisMask;
    ball = mix(ball, vec3(0.0), pupilMask);

    // The eye proper hides the sky behind it; as it turns, it thins away to leave only what
    // the portal needs.
    float body = within * (1.0 - turned);
    sky = mix(sky, ball, body);

    // The pupil is a hole whatever else is happening: the real portal is drawn into it.
    sky *= 1.0 - pupilMask * max(within, turned);

    // --- Light ----------------------------------------------------------------------------
    vec3 light = vec3(0.0);

    // The seam, and then the edges of the lids: a line of light along each. Shut, the two lie
    // on top of each other and that is the crack that appears in the sky at the very start.
    float seam = exp(-abs(inside) * mix(9.0, 16.0, lid)) * corners * (1.0 - turned);
    light += mix(BLUE, PALE, 0.65) * seam * (0.9 + 0.5 * locked);

    // Light pouring out of the opening onto the sky around it.
    float spill = exp(-max(-inside, 0.0) * 1.5) * corners * lid * (1.0 - turned);
    light += BLUE * spill * 0.22 * (1.0 + locked);

    // Turned: the iris has become a corona. A hard ring at the pupil's edge and rays beyond it,
    // spinning, uneven, built from whole multiples of the bearing so they join up with themselves.
    float beyond = max(pr - pupilRadius, 0.0);
    float spin = t * 5.0;
    float rays = 0.55
        + 0.25 * sin(bearing * 5.0 + spin * 0.11)
        + 0.20 * sin(bearing * 9.0 - spin * 0.17 + 1.3)
        + 0.12 * sin(bearing * 17.0 + spin * 0.23 + 4.0);

    float ring = exp(-abs(pr - pupilRadius) * 30.0);
    float corona = exp(-beyond * (2.6 - 1.3 * rays)) * step(pupilRadius, pr);

    light += PALE * ring * 2.6 * turned;
    light += mix(BLUE, PALE, 0.4) * corona * rays * 1.7 * turned;
    light += BLUE * exp(-beyond * 0.7) * 0.5 * turned;

    return light;
}

void main() {
    vec3 dir = normalize(viewDir);
    float t = seconds();

    float presence = clamp(ColorModulator.x, 0.0, 1.0);
    float dread = clamp(ColorModulator.y, 0.0, 1.0);
    float flare = clamp(ColorModulator.z, 0.0, 1.0);
    float unit = max(ColorModulator.w, 1.0e-4);
    float shock = TextureMat[1].z;
    float pull = TextureMat[1].w;

    bool awake = presence > 0.0 && dot(ModelOffset, ModelOffset) > 1.0e-6;
    EYE = awake ? normalize(ModelOffset) : vec3(0.0, 1.0, 0.0);

    // The angle between this pixel and the eye, and a frame around the eye to place it in.
    float angle = acos(clamp(dot(dir, EYE), -1.0, 1.0));
    vec3 side = normalize(cross(EYE, vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(side, EYE);

    // 0 at the horizon and below, rising to 1 overhead.
    float height = clamp(dir.y, 0.0, 1.0);

    // Nebula: two colours of slow-moving cloud, each warped by a third field so it curls.
    vec3 p = dir * 2.2;
    vec3 warp = vec3(fbm(p + vec3(0.0, t * 0.010, 0.0)), fbm(p + vec3(5.2, 0.0, t * 0.008)), fbm(p + 9.1));

    float cloudA = smoothstep(0.42, 0.78, fbm(p * 1.1 + warp * 1.6));
    float cloudB = smoothstep(0.48, 0.82, fbm(p * 1.7 - warp * 1.3 + 4.0));

    vec3 sky = DEEP;
    sky += BLUE * cloudA * 0.34;
    sky += VIOLET * cloudB * 0.26;

    // Stars, in three sizes. Cloud in front of a star dims it.
    float stars = starLayer(dir, 34.0, 0.10, t) * 0.9
        + starLayer(dir, 62.0, 0.14, t) * 0.6
        + starLayer(dir, 110.0, 0.20, t) * 0.35;
    sky += PALE * stars * (1.0 - 0.7 * max(cloudA, cloudB));

    // Ribbons of light low over the horizon, drifting sideways: an aurora.
    float bearing = atan(dir.z, dir.x);
    float ribbon = sin(bearing * 3.0 + t * 0.05 + 2.0 * fbm(vec3(bearing * 1.5, t * 0.03, 0.0)));
    float band = smoothstep(0.10, 0.22, height) * smoothstep(0.55, 0.26, height);
    float curtain = smoothstep(0.35, 1.0, ribbon) * band
        * (0.6 + 0.4 * noise(vec3(bearing * 14.0, height * 5.0 - t * 0.12, t * 0.05)));
    sky += mix(BLUE, vec3(0.20, 0.62, 0.66), 0.45) * curtain * 0.30;

    if (awake) {
        // The sky itself answers. It dims, and goes the colour of a bruise, so that the eye is
        // the brightest thing in it by a long way.
        float grey = dot(sky, vec3(0.299, 0.587, 0.114));
        vec3 bruised = mix(vec3(grey), VIOLET * grey * 2.2 + vec3(0.03, 0.0, 0.015), 0.6) * 0.45;
        sky = mix(sky, bruised, dread * 0.85);

        // Cracks of light spreading out across the sky from where the eye is. Ridges of noise
        // taken straight from the direction, so there is no seam anywhere in them, strongest
        // near the eye and fading with distance. They flicker like something under strain.
        float ridge = 1.0 - abs(2.0 * fbm(dir * 3.4 + EYE * 2.0) - 1.0);
        float crack = smoothstep(0.90, 0.985, ridge) * exp(-angle * 1.25);
        float strain = 0.55 + 0.45 * noise(vec3(dir * 9.0 + t * 0.9));
        sky += mix(VIOLET, PALE, 0.55) * crack * strain * dread * 1.5;

        // The eye, in a flat frame around its direction, in units of the portal's radius. Only
        // on its own side of the sky: the same frame behind the viewer would draw a second one.
        if (dot(dir, EYE) > 0.0) {
            vec2 local = vec2(dot(dir, side), dot(dir, up)) / sin(max(angle, 1.0e-4)) * angle / unit;

            vec3 behind = sky;
            vec3 light = eye(local, t, behind);

            sky = mix(sky, behind + light, presence);
        }

        // The shockwave: a ring of light that leaves the eye the instant it finds you and rolls
        // out across the whole sky, thinning as it goes.
        if (shock > 0.0 && shock < 1.0) {
            float front = shock * 3.2;
            float wave = exp(-abs(angle - front) * 22.0) * (1.0 - shock) * (1.0 - shock);
            sky += PALE * wave * 1.9;
        }

        // The pull drags the sky with it. Lines of light streaming in toward the eye: the
        // direction with its component toward the eye removed is where round the eye a pixel
        // lies, and noise of that alone is constant along every line into it.
        if (pull > 0.0) {
            vec3 around = normalize(dir - EYE * dot(dir, EYE) + 1.0e-5);
            float line = pow(noise(vec3(around * 26.0 + EYE * 7.0) + vec3(0.0, 0.0, t * 5.0)), 5.0);
            float reach = smoothstep(0.05, 0.5, angle) * exp(-angle * 0.7);
            sky += mix(BLUE, PALE, 0.7) * line * reach * pull * 5.0;
        }
    }

    // Everything fades into the fog colour toward the horizon, and below it there is only fog.
    float lift = smoothstep(0.0, 0.16, dir.y);

    // A faint glow sitting right on the horizon, so the edge of the world is lit rather than
    // simply ending.
    float horizon = exp(-abs(dir.y) * 16.0);
    vec3 base = FOG + BLUE * horizon * 0.10;

    // While the eye is open it is not hidden by the horizon's fade: it is too close for that.
    vec3 color = mix(base, sky, max(lift, awake ? presence * exp(-angle * 1.5) : 0.0));

    // The pull ends in light. Everything, the ground's horizon included, washes out toward the
    // colour of the eye's centre, brightest in its own direction.
    float toward = 0.5 + 0.5 * dot(dir, EYE);
    color = mix(color, PALE, flare * (0.55 + 0.45 * toward));

    fragColor = vec4(color, 1.0);
}

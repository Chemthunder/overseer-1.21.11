#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// The sky of the memory dimension: a pane of glass that has been broken.
//
// There are no stars here and no colour. The whole sky is one shattered sheet, lit from behind
// through the cracks, and every piece of it still holds what it was showing when it broke: a
// low pale horizon, a bank of cloud, a disc hanging over a line of hills, a doorway standing
// open with the light on behind it. No two pieces agree about which way up that was. Some have
// gone dark. A light moves across them slowly and each catches it in turn.
//
// It does not stay as it is. Every so often it is struck again -- somewhere new, or at the old
// break -- and the pieces round the blow jump apart, what they hold splits into smaller pieces
// that no longer line up, and a ring of light runs out across the glass. Then it draws slowly
// back together. And now and then, without any of that, an eye comes to one of the pieces,
// looks through it for a while, and goes.
//
// Everything is worked out per pixel from the direction being looked in, so there are no
// textures, and nothing to tile, stretch or show a seam.
//
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
//   TextureMat[2]     x detail     0 the plain sky, 1 the full one; see DETAIL below
//
// The eye an eviction opens is not a fixed feature of this sky. It opens around the portal the
// eviction tears there, which is a real object at a real place, so its direction and size are
// worked out from that portal and the camera each frame and it stays wrapped around the portal
// from anywhere. It is the only thing in this sky that is ever in colour.

in vec3 viewDir;

// Which piece the eye is watching through, and for how long it has been; see the vertex shader.
flat in vec4 watch;
flat in vec3 watchDir;

out vec4 fragColor;

// Must match the biome's fog colour. Terrain fades to this with distance, so the sky has to be
// exactly this at the horizon or the edge of the world shows as a hard line against it.
const vec3 FOG = vec3(0.043, 0.047, 0.063);

// The eye's own colours. Nothing else here uses them.
const vec3 BLUE = vec3(0.16, 0.26, 0.55);
const vec3 VIOLET = vec3(0.34, 0.18, 0.52);
const vec3 PALE = vec3(0.86, 0.93, 1.00);

// What is behind the glass, and the glass with light on it. White, with the faintest lean toward
// the cold so it sits with the fog rather than looking like paper.
const vec3 VOID = vec3(0.004, 0.005, 0.007);
const vec3 GLASS = vec3(0.94, 0.96, 1.00);

// The angle one unit of the watching eye is seen at, in radians. It is a little over five units
// from corner to corner, which is most of the way across an ordinary piece.
const float WATCH_UNIT = 0.046;

// The direction of the eye an eviction opens. Set in main() from ModelOffset; meaningless while
// it is closed.
vec3 EYE;

// How much of the sky to draw, 0 to 1, from TextureMat[2].x. Set in main().
float DETAIL;

// Seconds. GameTime runs 0..1 over one day of 24000 ticks, which is twenty minutes, and then
// starts again. Everything here that moves is made to come back to where it started in exactly
// that long, so the sky does not jump when the day turns over.
const float DAY = 1200.0;
const float TAU = 6.2831853;

float seconds() {
    return GameTime * DAY;
}

// An angle that goes round a whole number of times a day.
float turns(float t, float perDay) {
    return TAU * t * perDay / DAY;
}

// Random numbers with no sine in them. These are asked for many times for every pixel of sky,
// so they want to be cheap; and both shaders ask about the same piece and have to be told the
// same thing, which plain arithmetic on small whole numbers gives on every card and the sine of
// something large does not.
float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);

    return fract((q.x + q.y) * q.z);
}

vec2 hash22(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);

    return fract((q.xx + q.yz) * q.zy);
}

// Where the sky was struck: high in the north. The cracks run out from here and the light
// behind the glass is strongest here.
const vec3 BREAK = vec3(-0.2500, 0.6200, -0.7437);

// How many pieces the sky is in. Larger is more, smaller pieces.
const float SHARDS = 3.3;

// The same place on the flat sheet the pieces are cut from; see sheet().
const vec2 BREAK_FLAT = vec2(-0.2874, -0.8550);

// A direction, as a place on a flat sheet: straight up at the middle, the horizon a quarter
// turn out, every bearing where it should be. The pieces are cut from this sheet and it is
// then laid back over the sky. Flat, because cutting pieces in two dimensions means looking at
// nine neighbours for every pixel where three would mean twenty-seven; and the one place such
// a sheet cannot reach without tearing is straight down, which is underground.
//
// It is stretched outward from the break before it is cut, which packs more pieces in there:
// glass breaks smaller the nearer the blow.
vec2 sheet(vec3 dir) {
    vec2 laid = normalize(dir.xz + vec2(1.0e-6)) * acos(clamp(dir.y, -1.0, 1.0));
    vec2 out_ = laid - BREAK_FLAT;

    return (BREAK_FLAT + out_ * (1.0 + 0.85 * exp(-2.4 * length(out_)))) * SHARDS;
}

// Where the point that owns one square of the sheet sits inside it. They drift, slowly, each
// round its own small loop, so the pieces are never quite still against each other; and when
// the sky is struck they are all thrown a little way and settle back.
vec2 shardPoint(vec2 cell, float t, float jolt) {
    vec2 seed = hash22(cell);
    return 0.5 + 0.30 * sin(turns(t, 8.0) + TAU * seed) + (seed.yx - 0.5) * jolt;
}

float hash3(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);

    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);

    return fract((p.xxy + p.yxx) * p.zyx);
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

// Four layers, for the eviction: its cracks are the thin ridges of this and want all of them.
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

// Three, for the cloud in a remembered sky, which is soft and seen small and is drawn every frame.
float haze(vec3 p) {
    float total = 0.0;
    float amplitude = 0.5;

    for (int i = 0; i < 3; i++) {
        total += noise(p) * amplitude;
        p = p * 2.03 + vec3(17.1, 9.2, 5.3);
        amplitude *= 0.5;
    }

    return total;
}

// Turn v about the unit axis k by an angle.
vec3 turn(vec3 v, vec3 k, float angle) {
    float c = cos(angle);
    float s = sin(angle);

    return v * c + cross(k, v) * s + k * dot(k, v) * (1.0 - c);
}

// One piece of the sky.
//
// The pieces are the cells of a Voronoi pattern: scatter a point in every square of the sheet,
// and each piece is everything nearer to one point than to any other. That gives irregular
// straight-sided polygons that fit together exactly, which is what broken glass does.
struct Shard {
    float edge;     // distance in from the nearest crack, zero on it
    vec2 id;        // which piece: the same everywhere inside it
    vec2 inward;    // from here to the point at the middle of the piece
};

Shard shardAt(vec2 p, float t, float jolt) {
    vec2 cell = floor(p);
    vec2 local = fract(p);

    // Every point in the neighbourhood, kept, because finding the cracks takes a second look
    // at all of them once the nearest is known.
    vec2 to[9];
    int count = 0;
    int nearestIndex = 0;
    float nearest = 64.0;
    vec2 id = cell;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 offset = vec2(float(x), float(y));
            vec2 toPoint = offset + shardPoint(cell + offset, t, jolt) - local;
            float d = dot(toPoint, toPoint);

            to[count] = toPoint;

            if (d < nearest) {
                nearest = d;
                nearestIndex = count;
                id = cell + offset;
            }

            count++;
        }
    }

    // The crack between two pieces is the line half way between their points, and the edge of
    // this piece is whichever of those lines is closest. The second-nearest point is not
    // always the one it belongs to, so every neighbour is asked.
    vec2 inward = to[nearestIndex];
    float edge = 8.0;

    for (int i = 0; i < 9; i++) {
        if (i == nearestIndex) {
            continue;
        }

        vec2 across = to[i] - inward;
        edge = min(edge, dot(0.5 * (inward + to[i]), across / max(length(across), 1.0e-4)));
    }

    return Shard(edge, id, inward);
}

// What a piece remembers, as a brightness.
//
// Every piece is looking at the same remembered place -- a sky with a low pale horizon, cloud,
// a disc hanging over a line of hills, and a doorway on the hills with the light on behind it --
// but through its own turn of the head, so each shows a different part of it at a different
// slant, and the horizon in one never lines up with the horizon in the next.
float memory(vec3 d, vec2 id, float t) {
    vec3 seed = hash33(vec3(id, 5.5));

    // Height above this piece's own horizon.
    float h = d.y + (seed.x - 0.5) * 0.45;

    // Light along the horizon, thinning upward.
    float dawn = exp(-max(h, 0.0) * 3.0);

    // Cloud, going round rather than along so that it is back where it began when the day is.
    vec3 drift = 0.5 * vec3(cos(turns(t, 2.0)), 0.0, sin(turns(t, 2.0)));
    float cloud = DETAIL > 0.5
        ? haze(d * 3.1 + seed * 17.0 + drift)
        : noise(d * 3.1 + seed * 17.0 + drift) * 0.8;
    cloud = smoothstep(0.32, 0.72, cloud);

    float tone = 0.10 + 0.74 * dawn * (0.58 + 0.42 * cloud) + 0.30 * cloud * (1.0 - dawn);

    // The disc, and the haze around it.
    float toward = dot(d, vec3(0.3040, 0.4560, -0.8364));
    tone += smoothstep(0.9958, 0.9972, toward) * 0.95 + exp(-(1.0 - toward) * 40.0) * 0.26;

    // The hills: black against it, a different outline in every piece.
    vec2 around = normalize(d.xz + vec2(1.0e-5));
    float ridge = 0.015
        + 0.130 * noise(vec3(around * 2.6, seed.y * 40.0))
        + 0.034 * noise(vec3(around * 11.0, seed.z * 40.0));
    float land = smoothstep(ridge + 0.005, ridge - 0.005, h);

    float remembered = mix(tone, 0.012, land);

    // The doorway. Measured round the horizon from where it stands, and up from the ground it
    // stands on.
    vec2 from = vec2(around.x * 0.8829 + around.y * 0.4695, around.y * 0.8829 - around.x * 0.4695);
    float aside = atan(from.y, from.x);
    float above = h - ridge + 0.010;
    float door = smoothstep(0.027, 0.024, abs(aside)) * smoothstep(0.0, 0.004, above) * smoothstep(0.096, 0.091, above);
    float spill = exp(-abs(aside) * 26.0) * exp(-max(above, 0.0) * 9.0) * smoothstep(-0.03, 0.0, above);

    return remembered + door * 0.80 + spill * 0.14;
}

// Splinters: the small pieces, hanging where they came loose.
//
// Not points of light. Each is a sliver with a length and a direction, turning slowly, and dark
// nearly all of the time -- it shows only for the moment it happens to catch the light, the way
// broken glass on a floor does as you walk past it.
float splinters(vec3 dir, float scale, float density, float t) {
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

    vec2 q = local - (0.3 + 0.4 * vec2(hash(cell + 3.1), hash(cell + 7.7)));
    float spin = hash(cell + 9.4) * TAU + turns(t, floor(hash(cell + 2.2) * 13.0) - 6.0);
    q = vec2(cos(spin) * q.x + sin(spin) * q.y, cos(spin) * q.y - sin(spin) * q.x);

    // Long one way and thin the other, coming to a point at each end.
    float reach = 0.10 + 0.16 * hash(cell + 13.3);
    float along = abs(q.x) / reach;
    float across = abs(q.y) / (0.030 * max(1.0 - along, 1.0e-3));
    float shape = (1.0 - smoothstep(0.55, 1.0, across)) * step(along, 1.0);

    float flash = pow(0.5 + 0.5 * sin(turns(t, floor(70.0 + 230.0 * hash(cell + 21.9))) + seed * 500.0), 14.0);

    return shape * (0.10 + 0.90 * flash);
}

// The sky being struck.
//
// Thirty times a day, which is every forty seconds, at a different moment within each forty so
// that it does not keep time. Most land somewhere new and break only what is round them. One in
// three is the old break going again, and that one is felt across the whole sky.
struct Strike {
    vec3 at;        // where
    float since;    // seconds ago; negative if it has not landed yet
    float force;    // how hard, up to 1
    float whole;    // 1 if it is the break itself
};

Strike strikeNow(float t) {
    float span = DAY / 30.0;
    float which = floor(t / span);
    float began = which * span + 4.0 + 14.0 * hash(vec2(which, 1.7));

    float whole = step(0.66, hash(vec2(which, 4.1)));
    float bearing = TAU * hash(vec2(which, 1.3));
    float lift = 0.25 + 0.90 * hash(vec2(which, 2.6));
    vec3 somewhere = vec3(cos(bearing) * cos(lift), sin(lift), sin(bearing) * cos(lift));

    return Strike(
        mix(somewhere, BREAK, whole),
        t - began,
        mix(0.55 + 0.35 * hash(vec2(which, 8.8)), 1.0, whole),
        whole
    );
}

// The eye.
//
// Worked in a flat frame laid over the sky around EYE, measured in units of the finished portal's
// radius: p is the point being shaded, x along the eye and y up it.
//
// It is built back to front the way a real one is layered -- white, then iris, then pupil, then
// the lids cutting it all to shape -- and each layer is a mask times a colour, so every edge can
// be softened by a fraction of a unit instead of stair-stepping.
//
// It is drawn in two places. An eviction opens it across the sky around the portal it tears, and
// drives every one of these from the server. Between times it comes to one of the pieces of the
// sky, looks through, and goes away again; see watchNow in the vertex shader.
//
//   lid          how far the lids have parted
//   gaze         where the iris is looking, -1 to 1 each way; 0,0 is at you
//   pupilRadius  in units
//   roundness    0 a slit pupil, 1 a round one
//   turned       how far the eye has become the portal
//   locked       how surely it has found you, 0 to 1
vec3 eye(vec2 p, float t, float lid, vec2 gaze, float pupilRadius, float roundness, float turned,
         float locked, inout vec3 sky) {

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

// The glass: everything in this sky that is there whether or not an eviction is under way.
vec3 glassAt(vec3 dir, float t, bool awake) {
    // --- The blow, if there has been one lately ----------------------------------------------
    Strike strike = strikeNow(t);

    float struck = 0.0;     // how broken things are just here, 0 to 1
    float thrown = 0.0;     // how far every piece has been knocked from its place
    float wave = 0.0;       // the ring of light running out from the blow
    float burst = 0.0;      // the light at the blow itself

    if (strike.since > 0.0 && strike.since < 24.0) {
        float since = strike.since;
        float landed = 1.0 - exp(-since * 18.0);
        float fading = landed * exp(-since * 0.26) * strike.force;
        float fromStrike = acos(clamp(dot(dir, strike.at), -1.0, 1.0));

        // A blow somewhere new breaks what is near it. The break going again reaches everywhere.
        float reach = mix(exp(-fromStrike * 1.7), 0.35 + 0.65 * exp(-fromStrike * 0.8), strike.whole);

        struck = fading * reach;

        // Thrown, ringing for a moment, and then the slow way back.
        thrown = strike.force * landed * (0.16 * exp(-since * 1.5) * (0.6 + 0.4 * cos(since * 10.0)) + 0.09 * exp(-since * 0.26));

        wave = exp(-abs(fromStrike - since * 0.85) * 13.0) * exp(-since * 0.8) * strike.force;
        burst = exp(-fromStrike * fromStrike * 42.0) * landed * exp(-since * 0.9) * strike.force * (1.0 - strike.whole);
    }

    // --- The break ------------------------------------------------------------------------
    // How far round the sky this pixel is from where it was first struck, and which way out
    // from there it lies.
    float fromBreak = acos(clamp(dot(dir, BREAK), -1.0, 1.0));
    vec3 outward = normalize(dir - BREAK * dot(dir, BREAK) + 1.0e-5);

    // The light is behind the glass and comes through wherever it is open. Most of it is at
    // the break; some reaches everywhere.
    float backlight = 0.17 + 0.83 * exp(-fromBreak * 1.25);

    // --- The pieces -----------------------------------------------------------------------
    vec2 at = sheet(dir);
    Shard shard = shardAt(at, t, thrown);
    vec3 chance = hash33(vec3(shard.id, 11.7));

    // The pieces right at the break are not there any more. Judged by the middle of the piece,
    // so that each goes or stays whole.
    float gone = smoothstep(1.10, 0.75, length(at + shard.inward - BREAK_FLAT * SHARDS));

    // The rest stand a little apart: further apart the nearer the break, further still where
    // they have just been struck, and the gaps open and close very slightly as they drift.
    float gap = 0.008 + 0.030 * exp(-fromBreak * 1.9) + 0.004 * sin(turns(t, 16.0) + chance.x * TAU) + 0.040 * struck;
    float solid = smoothstep(gap, gap + 0.008, shard.edge) * (1.0 - gone);

    // Each piece looks at the same memory with its head turned its own way.
    vec3 axis = normalize(chance - 0.5 + 1.0e-3);
    float slant = (hash3(vec3(shard.id, 3.3)) - 0.5) * 1.7;

    // Where it has been struck the piece is itself in pieces, and each of those has slipped a
    // little: what it holds comes apart along hairline cracks, and heals as they close.
    float hairline = 0.0;

    if (struck > 0.04) {
        Shard fragment = shardAt(at * 2.7 + 13.0, t, 0.0);

        hairline = smoothstep(0.034, 0.0, fragment.edge) * struck;
        slant += (hash3(vec3(fragment.id, 1.9)) - 0.5) * 0.75 * struck + (chance.x - 0.5) * 0.5 * struck;
    }

    float remembered = memory(turn(dir, axis, slant), shard.id, t);

    // Some have gone dark altogether, and the rest come and go.
    float kept = smoothstep(0.14, 0.70, hash3(vec3(shard.id, 29.1)));
    kept *= 0.62 + 0.38 * sin(turns(t, floor(5.0 + 9.0 * chance.y)) + chance.z * TAU);

    vec3 piece = GLASS * (0.014 + remembered * kept * 0.92);

    // --- The watcher ------------------------------------------------------------------------
    // One piece, some of the time, has the eye behind it instead. It opens, looks about, finds
    // whoever is looking at it, holds them for a while, and closes. Never while an eviction has
    // the real one open.
    if (watch.w >= 0.0 && !awake && all(lessThan(abs(shard.id - watch.xy), vec2(0.5)))) {
        float since = watch.w;
        float shown = smoothstep(0.0, 0.8, since) * (1.0 - smoothstep(15.0, 16.0, since));

        // Two blinks: one while it is looking about, one after it has found you.
        float blink = 1.0
            - 0.95 * exp(-pow((since - 4.7) / 0.10, 2.0))
            - 0.95 * exp(-pow((since - 10.2) / 0.11, 2.0));
        float lid = smoothstep(0.4, 2.2, since) * (1.0 - smoothstep(13.4, 15.0, since)) * blink;

        float found = smoothstep(6.0, 6.5, since);
        vec2 wander = vec2(0.80 * sin(since * 0.9 + 1.0), 0.40 * sin(since * 1.4 + 2.0));

        // In a frame laid over the sky where it is looking from, as the other eye is.
        float off = acos(clamp(dot(dir, watchDir), -1.0, 1.0));
        vec3 sideways = normalize(cross(watchDir, vec3(0.0, 1.0, 0.0)));
        vec3 upward = cross(sideways, watchDir);
        vec2 local = vec2(dot(dir, sideways), dot(dir, upward)) / sin(max(off, 1.0e-4)) * off / WATCH_UNIT;

        vec3 seen = vec3(0.010);
        seen += eye(local, t, lid, mix(wander, vec2(0.0), found), mix(0.50, 0.33, found), 0.0, 0.0, found * 0.5, seen);

        // Behind glass, and in this sky: most of its colour is left on the other side.
        seen = mix(vec3(dot(seen, vec3(0.299, 0.587, 0.114))), seen, 0.38);

        piece = mix(piece, seen + piece * 0.10, shown);
    }

    // --- The glass itself -------------------------------------------------------------------
    // The light crossing the sky. Every piece faces very slightly its own way, so they do not
    // brighten together: each catches it for a moment as it goes over.
    vec2 round = vec2(cos(turns(t, 8.0)), sin(turns(t, 8.0)));
    vec3 lamp = normalize(vec3(round.x, 0.50, round.y));
    vec3 facing = normalize(dir + (chance - 0.5) * 0.55);
    piece += GLASS * pow(max(dot(facing, lamp), 0.0), 26.0) * 0.28;

    // The cut edge of a piece is not the same as its face: it catches light along one side
    // and is in shadow along the other, which is what gives the glass its thickness.
    float bevel = smoothstep(gap + 0.040, gap + 0.004, shard.edge);
    float lit = 0.5 + 0.5 * dot(normalize(shard.inward + 1.0e-5), round);
    piece *= 1.0 - 0.35 * bevel * (1.0 - lit);
    piece += GLASS * bevel * (0.02 + 0.30 * lit * lit * lit) * (0.30 + 0.70 * backlight);

    // The hairline cracks, lit from behind like the rest.
    piece += GLASS * hairline * (0.30 + 0.70 * backlight) * 0.85;

    // Light through the gap itself: a hard white line down the middle of each, strongest
    // where the light behind is, and flaring as the ring from a blow goes over it.
    float seam = smoothstep(gap, 0.0, shard.edge);
    float leak = (0.08 + 0.92 * seam * seam) * backlight * (1.0 + 4.0 * wave + 1.5 * struck);

    // And through the hole where the pieces are gone, all of it, falling away from the middle.
    float open = 0.30 + 1.60 * exp(-fromBreak * fromBreak * 34.0) + 0.35 * exp(-fromBreak * 7.0);
    open *= 1.0 + 1.4 * struck * strike.whole;

    vec3 sky = mix(VOID + GLASS * leak * 1.2, piece, solid);
    sky = mix(sky, GLASS * open, gone);

    // --- The cracks from the break ---------------------------------------------------------
    // Straight cracks running out from where it was struck. Noise that depends only on which
    // way out a pixel lies is constant along every line through the break, so its ridges are
    // lines; nudged a little with distance, because a crack does not run true. And rings
    // crossing them, broken into arcs: a struck pane cracks round the blow as well as away.
    if (fromBreak < 1.5) {
        vec3 wandering = outward + 0.022 * (noise(vec3(fromBreak * 7.0, 1.0, 2.0)) - 0.5);
        float spoke = 1.0 - abs(2.0 * noise(wandering * 8.5 + 31.0) - 1.0);
        spoke = smoothstep(0.972, 0.997, spoke) * smoothstep(0.10, 0.22, fromBreak) * exp(-fromBreak * 2.0);

        float ringAt = fromBreak * 10.0 + 1.5 * noise(outward * 2.4 + 7.0);
        float ring = 1.0 - abs(2.0 * fract(ringAt) - 1.0);
        float arc = step(0.55, noise(outward * 5.0 + floor(ringAt) * 3.7));
        ring = smoothstep(0.94, 0.99, ring) * arc * smoothstep(0.12, 0.20, fromBreak) * exp(-fromBreak * 2.8);

        sky += GLASS * (spoke * 0.70 + ring * 0.45) * solid * (1.0 + 2.0 * struck * strike.whole);
    }

    // The glare of it: light scattering in the glass all round the hole.
    sky += GLASS * exp(-fromBreak * 4.2) * 0.16;

    // The ring running out from a blow, and the light where it landed.
    sky += GLASS * (wave * 0.26 + burst * 1.10);

    // --- Splinters -------------------------------------------------------------------------
    if (DETAIL > 0.5) {
        float loose = splinters(dir, 26.0, 0.16, t) * 0.9 + splinters(dir, 47.0, 0.12, t) * 0.5;
        sky += GLASS * loose * (0.16 + 0.84 * exp(-fromBreak * 0.9)) * (1.0 + 3.0 * struck);
    }

    return sky;
}

void main() {
    vec3 dir = normalize(viewDir);
    float t = seconds();

    DETAIL = clamp(TextureMat[2].x, 0.0, 1.0);

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

    vec3 sky = VOID;

    // Below the horizon there is only fog, and none of the glass would be seen through it. In a
    // dimension that is mostly open void that is half of every frame not paid for. An eviction
    // lifts the fog off the eye, so while one is running the whole sky is drawn.
    if (dir.y > -0.02 || awake) {
        sky = glassAt(dir, t, awake);
    }

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
            vec3 light = eye(
                local, t,
                TextureMat[0].x, TextureMat[0].yz, TextureMat[0].w,
                TextureMat[1].x, TextureMat[1].y, smoothstep(0.0, 0.15, TextureMat[1].z),
                behind
            );

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
    vec3 base = FOG + GLASS * horizon * 0.035;

    // While the eye is open it is not hidden by the horizon's fade: it is too close for that.
    vec3 color = mix(base, sky, max(lift, awake ? presence * exp(-angle * 1.5) : 0.0));

    // The pull ends in light. Everything, the ground's horizon included, washes out toward the
    // colour of the eye's centre, brightest in its own direction.
    float toward = 0.5 + 0.5 * dot(dir, EYE);
    color = mix(color, PALE, flare * (0.55 + 0.45 * toward));

    fragColor = vec4(color, 1.0);
}

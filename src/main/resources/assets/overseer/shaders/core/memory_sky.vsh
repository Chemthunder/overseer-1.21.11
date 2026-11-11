#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec3 viewDir;

// The watch: which piece of the sky the eye is behind, how long it has been there, and exactly
// which way it lies.
//
//   watch.xy  the piece, as the fragment shader names them
//   watch.w   seconds since it came, or negative when it is not there
//
// Worked out here rather than per pixel because it is one answer for the whole sky, and finding
// which piece lies in a given direction is the most expensive thing the fragment shader does.
// Flat, so that answer reaches every pixel exactly as it left.
flat out vec4 watch;
flat out vec3 watchDir;

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

// The eye comes ten times a day, which is once every two minutes, to a different piece each
// time and at a different moment within its two minutes, so that it cannot be waited for. It
// stays a quarter of a minute. It makes no sound and nothing else marks it.
const float WATCHES = 10.0;
const float WATCH_SECONDS = 16.0;

// The direction a place on the sheet came from: sheet(), backwards. The stretch round the break
// has no neat inverse, so the distance out from it is closed in on in a few steps; it only has
// to be done once a frame, here, rather than for every pixel.
vec3 unsheet(vec2 p) {
    vec2 out_ = p / SHARDS - BREAK_FLAT;
    float stretched = length(out_);
    float r = stretched / 1.4;

    for (int i = 0; i < 6; i++) {
        float falloff = 0.85 * exp(-2.4 * r);
        r -= (r * (1.0 + falloff) - stretched) / (1.0 + falloff * (1.0 - 2.4 * r));
    }

    vec2 laid = BREAK_FLAT + out_ / max(stretched, 1.0e-5) * r;
    float down = length(laid);
    vec2 bearing = laid / max(down, 1.0e-5);

    return vec3(sin(down) * bearing.x, cos(down), sin(down) * bearing.y);
}

vec4 watchNow(float t, out vec3 toward) {
    float span = DAY / WATCHES;
    float which = floor(t / span);
    float began = which * span + 18.0 + 70.0 * hash(vec2(which, 7.0));
    float since = t - began;

    // Somewhere it will be seen: not underfoot, not straight overhead.
    float bearing = TAU * hash(vec2(which, 3.1));
    float lift = 0.30 + 0.50 * hash(vec2(which, 5.9));
    toward = vec3(cos(bearing) * cos(lift), sin(lift), sin(bearing) * cos(lift));

    // Not at the break. The pieces there are gone, and there would be nothing to look through.
    if (dot(toward, BREAK) > 0.80) {
        toward = vec3(-toward.x, toward.y, -toward.z);
    }

    if (since < 0.0 || since > WATCH_SECONDS) {
        return vec4(0.0, 0.0, 0.0, -1.0);
    }

    // Whichever piece lies that way, as the pieces stood when it came. They go on drifting
    // while it is there, and it has to stay behind the one it chose.
    vec2 at = sheet(toward);
    vec2 cell = floor(at);
    vec2 local = fract(at);

    float nearest = 64.0;
    vec2 piece = cell;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 offset = vec2(float(x), float(y));
            vec2 to = offset + shardPoint(cell + offset, began, 0.0) - local;
            float d = dot(to, to);

            if (d < nearest) {
                nearest = d;
                piece = cell + offset;
            }
        }
    }

    // And it looks out from the middle of that piece, not from wherever in it was first
    // pointed at: the middle as it will be half way through, since the piece drifts.
    toward = unsheet(piece + shardPoint(piece, began + 0.5 * WATCH_SECONDS, 0.0));

    return vec4(piece, 0.0, since);
}

void main() {
    // The cube is centred on the camera, so a vertex's position is also the direction to it.
    viewDir = Position;
    watch = watchNow(seconds(), watchDir);

    vec4 clip = ProjMat * ModelViewMat * vec4(Position, 1.0);

    // z = w puts every fragment at the far plane exactly. The depth test then passes only where
    // nothing has been drawn, so terrain and entities cover the sky without the sky needing to
    // know how far away anything is -- and it can never be clipped, however the projection's
    // near plane has been bent for a portal view.
    gl_Position = clip.xyww;
}

#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 quadUv;
out vec2 halfSize;
out vec4 tint;
out vec4 clipPos;

void main() {
    quadUv = UV0;
    tint = Color;

    // The aperture's UVs are in blocks, measured from its centre, so every one of its corners has
    // the same absolute value: half the opening's size. Interpolating a constant leaves it
    // constant, which hands the fragment shader the size without a uniform to carry it.
    //
    // The recess walls use the same trick for two per-face constants: u is the distance along the
    // face from its middle, so |u| at the corners is its half-length, and v is the face's light.
    halfSize = abs(UV0);

    clipPos = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = clipPos;

    // Two kinds of surface are drawn pressed against the lens rather than where they stand: one
    // the camera is about to walk through, which the near plane would otherwise cut a hole in,
    // and the mask over a doorway's opening, which has to be in front of whatever follows it.
    // The alpha of the vertex colour says which kind this is; see the compositor.
    //
    // Putting z on the near plane does it. The position on screen is untouched, the depth comes
    // out as near as depth goes, and since the near plane can no longer cut the surface it is
    // clipped only where it passes behind the camera itself. Just inside the plane rather than
    // on it, so that rounding cannot put it outside.
    bool pressed = Color.a > 0.90 && Color.a < 0.975;
    bool mask = Color.a > 0.65 && Color.a < 0.75;

    if (pressed || mask) {
        gl_Position.z = -gl_Position.w * 0.9999;
    }
}

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
}

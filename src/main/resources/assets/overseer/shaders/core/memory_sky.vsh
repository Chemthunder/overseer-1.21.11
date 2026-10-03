#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec3 viewDir;

void main() {
    // The cube is centred on the camera, so a vertex's position is also the direction to it.
    viewDir = Position;

    vec4 clip = ProjMat * ModelViewMat * vec4(Position, 1.0);

    // z = w puts every fragment at the far plane exactly. The depth test then passes only where
    // nothing has been drawn, so terrain and entities cover the sky without the sky needing to
    // know how far away anything is -- and it can never be clipped, however the projection's
    // near plane has been bent for a portal view.
    gl_Position = clip.xyww;
}

#version 330

#moj_import <minecraft:globals.glsl>

// Last pass of the memory dimension's glow: the picture, with the light bleeding back into it.
//
// Three things, all slight. Bright things glow -- the cracks in the sky, the break, a lamp. The
// corners of the view fall away a little, the way the edges of something remembered do. And
// there is a trace of grain, which keeps the big dark areas this dimension is made of from
// looking like flat paint.
//
// How much of it there is comes from a texture one pixel in size, which the game repaints as the
// player comes and goes. The whole effect eases in over a second or so on stepping into the
// dimension and out again on leaving: a doorway is supposed to have no seam, and a filter
// switching on in the middle of one would be a seam.

uniform sampler2D SceneSampler;
uniform sampler2D GlowSampler;
uniform sampler2D FadeSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 SceneSize;
    vec2 GlowSize;
    vec2 FadeSize;
};

layout(std140) uniform HazeConfig {
    float Glow;
    float Vignette;
    float Grain;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    // Green, because if that texture is ever missing the game substitutes its black and magenta
    // one, and the one thing those two colours agree on is that there is no green in them. No
    // texture then means no effect, rather than all of it.
    float fade = texture(FadeSampler, vec2(0.5)).g;

    vec3 scene = texture(SceneSampler, texCoord).rgb;
    vec3 glow = texture(GlowSampler, texCoord).rgb;

    // Added, then folded back under white: the glow lifts what is dark around a light without
    // burning out what was already bright.
    vec3 lit = glow * Glow * fade;
    vec3 color = scene + lit * (1.0 - clamp(scene, 0.0, 1.0) * 0.6);

    // 0 in the middle of the view, 1 in its corners.
    vec2 fromMiddle = texCoord - 0.5;
    float edge = dot(fromMiddle, fromMiddle) * 2.0;
    color *= 1.0 - Vignette * fade * smoothstep(0.22, 1.0, edge);

    // Per pixel of the screen, and different every frame.
    vec2 cell = floor(texCoord * OutSize);
    float grain = fract(sin(dot(cell + fract(GameTime * 977.0) * 113.0, vec2(12.9898, 78.233))) * 43758.5453);
    color += (grain - 0.5) * Grain * fade;

    fragColor = vec4(color, 1.0);
}

#version 330

// First pass of the memory dimension's glow: what in the picture is bright enough to bleed.
//
// Drawn into a target a fraction of the size of the screen, which does two things at once. It
// makes every later pass cheap -- they all work on this small picture, and how small does not
// depend on how large the window is -- and it is most of the blur already, since shrinking a
// picture is averaging it.

uniform sampler2D SceneSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 SceneSize;
};

layout(std140) uniform BrightConfig {
    float Threshold;
    float Knee;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    // Four taps a quarter of an output pixel out from the middle. The scene is sampled with
    // filtering, so between them they take in everything this pixel stands for, and a crack one
    // pixel wide is caught wherever it falls instead of flickering as the view moves.
    vec2 spread = 0.25 / OutSize;

    vec3 color = 0.25 * (
        texture(SceneSampler, texCoord + vec2(-spread.x, -spread.y)).rgb
        + texture(SceneSampler, texCoord + vec2(spread.x, -spread.y)).rgb
        + texture(SceneSampler, texCoord + vec2(-spread.x, spread.y)).rgb
        + texture(SceneSampler, texCoord + vec2(spread.x, spread.y)).rgb
    );

    // Keep only what is above the threshold, easing in over the knee below it so that
    // something brightening does not snap into glowing.
    float level = max(color.r, max(color.g, color.b));
    float soft = clamp(level - Threshold + Knee, 0.0, 2.0 * Knee);
    soft = soft * soft / (4.0 * Knee + 1.0e-4);

    float kept = max(soft, level - Threshold) / max(level, 1.0e-4);

    fragColor = vec4(color * kept, 1.0);
}

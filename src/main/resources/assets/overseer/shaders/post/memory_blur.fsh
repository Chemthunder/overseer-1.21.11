#version 330

// One direction of a Gaussian blur. Run across and then down, twice, the second time with its
// taps further apart: the first pair makes the tight glow hugging a crack, the second the wide
// soft one around it.
//
// Nine taps' worth from five lookups: the picture is sampled with filtering, so a lookup placed
// between two pixels returns both, weighted by where between them it was put.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlurConfig {
    vec2 BlurDir;
    float Spread;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 pixel = BlurDir * Spread / InSize;

    vec3 sum = texture(InSampler, texCoord).rgb * 0.2270270270;

    sum += (texture(InSampler, texCoord + pixel * 1.3846153846).rgb
        + texture(InSampler, texCoord - pixel * 1.3846153846).rgb) * 0.3162162162;

    sum += (texture(InSampler, texCoord + pixel * 3.2307692308).rgb
        + texture(InSampler, texCoord - pixel * 3.2307692308).rgb) * 0.0702702703;

    fragColor = vec4(sum, 1.0);
}

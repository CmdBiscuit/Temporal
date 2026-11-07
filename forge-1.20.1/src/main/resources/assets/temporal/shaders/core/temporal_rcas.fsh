#version 150

// FidelityFX Super Resolution 1.0 - RCAS (Robust Contrast Adaptive Sharpening).
// Ported to GLSL from AMD's ffx_fsr1.h. Copyright (c) 2021 Advanced Micro Devices, Inc.
// MIT License - see LICENSE-FSR1.txt.

uniform sampler2D InSampler;
uniform float Sharpness; // exp2(-stops), 1.0 = strongest

out vec4 fragColor;

#define RCAS_LIMIT (0.25 - (1.0 / 16.0))

void main() {
    ivec2 maxP = textureSize(InSampler, 0) - 1;
    ivec2 p = ivec2(gl_FragCoord.xy);

    //   b
    // d e f
    //   h
    vec3 b = texelFetch(InSampler, clamp(p + ivec2( 0,  1), ivec2(0), maxP), 0).rgb;
    vec3 d = texelFetch(InSampler, clamp(p + ivec2(-1,  0), ivec2(0), maxP), 0).rgb;
    vec3 e = texelFetch(InSampler, p, 0).rgb;
    vec3 f = texelFetch(InSampler, clamp(p + ivec2( 1,  0), ivec2(0), maxP), 0).rgb;
    vec3 h = texelFetch(InSampler, clamp(p + ivec2( 0, -1), ivec2(0), maxP), 0).rgb;

    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));

    // Largest negative lobe that keeps the result inside [0, 1] for this neighbourhood.
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0e-5));
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * min(mn4, e) - 4.0, vec3(-1.0e-5));
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-RCAS_LIMIT, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * Sharpness;

    vec3 color = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}

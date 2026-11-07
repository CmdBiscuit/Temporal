#version 150

// FSR 3's reactive mask: where the latest layers changed a pixel, FSR 3 trusts its history less there, so those
// things don't smear. AMD's own recipe (tonemap, then the largest channel change), split in two: particles, clouds and
// weather (drawn after the translucent blocks) count fully; water, glass and other translucent blocks only partly, so
// they keep most of their smoothing. Rows are written top-down like every Vulkan-side image.

uniform sampler2D SceneSampler;
uniform sampler2D OpaqueSampler;
uniform sampler2D TranslucentSampler;

out vec4 fragColor;

vec3 tonemap(vec3 c) {
    return c / (max(c.r, max(c.g, c.b)) + 1.0);
}

float change(vec3 before, vec3 after) {
    vec3 delta = abs(tonemap(after) - tonemap(before));
    return max(delta.r, max(delta.g, delta.b));
}

void main() {
    ivec2 size = textureSize(SceneSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.x, size.y - 1 - int(gl_FragCoord.y));
    vec3 opaque = texelFetch(OpaqueSampler, p, 0).rgb;
    vec3 translucent = texelFetch(TranslucentSampler, p, 0).rgb;
    vec3 scene = texelFetch(SceneSampler, p, 0).rgb;

    float effects = change(translucent, scene);
    float surfaces = change(opaque, translucent);
    float reactive = max(effects < 0.02 ? 0.0 : min(effects * 4.0, 0.9), surfaces < 0.02 ? 0.0 : min(surfaces * 2.0, 0.5));
    fragColor = vec4(reactive, 0.0, 0.0, 1.0);
}

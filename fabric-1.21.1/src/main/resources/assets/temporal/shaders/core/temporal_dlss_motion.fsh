#version 150

// Per-pixel motion vectors for DLSS / FSR 3 / frame generation, from depth, the camera's movement since last frame and
// the movement of entities (see entity_motion.glsl).
// Output is (previous UV - current UV) in texture-space units, unjittered, in the top-down layout of the Vulkan-side
// images: row 0 is the top of the picture and V grows downwards (GL's own V grows upwards, hence the flips).

#moj_import <temporal:entity_motion.glsl>

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;   // current frame, unjittered, camera-relative
uniform mat4 PrevViewProj;  // previous frame, unjittered, camera-relative
uniform vec3 CameraDelta;   // current camera position - previous camera position
uniform vec2 Jitter;        // current sub-pixel jitter in NDC units

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(DepthSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 source = ivec2(p.x, size.y - 1 - p.y);
    float depth = texelFetch(DepthSampler, source, 0).r;

    vec2 uv = (vec2(source) + 0.5) / vec2(size) - 0.5 * Jitter;
    vec4 world = InvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    world.xyz /= world.w;
    vec4 previousClip = PrevViewProj * vec4(world.xyz + CameraDelta - entityMovement(world.xyz), 1.0);
    vec2 previousUv = previousClip.xy / previousClip.w * 0.5 + 0.5;

    vec2 motion = isHand(depth) ? vec2(0.0) : previousUv - uv;
    fragColor = vec4(motion.x, -motion.y, 0.0, 1.0);
}

// Moving entities, for motion vectors and reprojection (see com.temporal.client.EntityMotion).
// EntitySampler is RGBA32F, one column per entity (nearest first): row 0 = box min, row 1 = box max (camera-relative,
// this frame), row 2 = how far the entity moved since last frame, in blocks.

uniform sampler2D EntitySampler;
uniform float EntityCount;

// With an Iris shader pack the first-person hand is already in the scene, at a depth below this; it moves with the
// camera, not with the world behind it (see com.temporal.client.ShaderPacks). 0 when there is no such hand.
uniform float HandDepth;

bool isHand(float depth) {
    return depth < HandDepth;
}

// Movement since last frame of whatever is at camera-relative position `world`: a moving entity's, else zero.
vec3 entityMovement(vec3 world) {
    int count = int(EntityCount);
    for (int i = 0; i < count; i++) {
        vec3 lo = texelFetch(EntitySampler, ivec2(i, 0), 0).xyz;
        vec3 hi = texelFetch(EntitySampler, ivec2(i, 1), 0).xyz;
        if (all(greaterThanEqual(world, lo)) && all(lessThanEqual(world, hi))) {
            return texelFetch(EntitySampler, ivec2(i, 2), 0).xyz;
        }
    }
    return vec3(0.0);
}

#version 150

// Temporal anti-aliasing resolve.
// Motion comes from reprojecting each pixel's depth with the previous frame's camera, plus the movement of the entity
// at that pixel if one is moving (entity_motion.glsl); neighbourhood clipping rejects whatever history is still stale
// (animated limbs, things that aren't entities).

#moj_import <temporal:entity_motion.glsl>

uniform sampler2D CurrentSampler;
uniform sampler2D DepthSampler;
uniform sampler2D HistorySampler;

uniform mat4 InvViewProj;   // current frame, unjittered, camera-relative
uniform mat4 PrevViewProj;  // previous frame, unjittered, camera-relative
uniform vec3 CameraDelta;   // current camera position - previous camera position
uniform vec2 Jitter;        // current sub-pixel jitter in NDC units
uniform float HistoryValid;
uniform float Feedback;     // fraction of history kept
uniform float DebugMode;    // 1 = show |current - reprojected history| (debugging only)

out vec4 fragColor;

vec3 toYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Bicubic Catmull-Rom from 9 bilinear taps; keeps the history sharp while it is resampled every frame.
vec3 sampleHistory(vec2 uv, vec2 size) {
    vec2 samplePos = uv * size;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 tc0 = (texPos1 - 1.0) / size;
    vec2 tc3 = (texPos1 + 2.0) / size;
    vec2 tc12 = (texPos1 + w2 / w12) / size;

    vec3 result = texture(HistorySampler, vec2(tc0.x, tc0.y)).rgb * w0.x * w0.y
            + texture(HistorySampler, vec2(tc12.x, tc0.y)).rgb * w12.x * w0.y
            + texture(HistorySampler, vec2(tc3.x, tc0.y)).rgb * w3.x * w0.y
            + texture(HistorySampler, vec2(tc0.x, tc12.y)).rgb * w0.x * w12.y
            + texture(HistorySampler, vec2(tc12.x, tc12.y)).rgb * w12.x * w12.y
            + texture(HistorySampler, vec2(tc3.x, tc12.y)).rgb * w3.x * w12.y
            + texture(HistorySampler, vec2(tc0.x, tc3.y)).rgb * w0.x * w3.y
            + texture(HistorySampler, vec2(tc12.x, tc3.y)).rgb * w12.x * w3.y
            + texture(HistorySampler, vec2(tc3.x, tc3.y)).rgb * w3.x * w3.y;
    return max(result, vec3(0.0));
}

// Pulls the history colour toward the box centre until it lies inside the box.
vec3 clipToBox(vec3 history, vec3 boxMin, vec3 boxMax) {
    vec3 center = 0.5 * (boxMax + boxMin);
    vec3 extents = 0.5 * (boxMax - boxMin) + 1.0e-4;
    vec3 offset = history - center;
    vec3 units = abs(offset / extents);
    float maxUnit = max(units.x, max(units.y, units.z));
    return maxUnit > 1.0 ? center + offset / maxUnit : history;
}

void main() {
    ivec2 isize = textureSize(CurrentSampler, 0);
    vec2 size = vec2(isize);
    ivec2 maxP = isize - 1;
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 current = texelFetch(CurrentSampler, p, 0).rgb;

    // 3x3 neighbourhood: colour mean/variance for clipping, and the closest depth for motion (keeps edges crisp).
    vec3 m1 = vec3(0.0);
    vec3 m2 = vec3(0.0);
    float closestDepth = 1.0;
    ivec2 closestP = p;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 q = clamp(p + ivec2(x, y), ivec2(0), maxP);
            vec3 c = toYCoCg(texelFetch(CurrentSampler, q, 0).rgb);
            m1 += c;
            m2 += c * c;
            float d = texelFetch(DepthSampler, q, 0).r;
            if (d < closestDepth) {
                closestDepth = d;
                closestP = q;
            }
        }
    }

    if (HistoryValid < 0.5) {
        fragColor = vec4(current, 1.0);
        return;
    }

    // Reproject the closest surface into the previous frame.
    vec2 closestUv = (vec2(closestP) + 0.5) / size - 0.5 * Jitter;
    vec4 world = InvViewProj * vec4(closestUv * 2.0 - 1.0, closestDepth * 2.0 - 1.0, 1.0);
    world.xyz /= world.w;
    vec4 previousClip = PrevViewProj * vec4(world.xyz + CameraDelta - entityMovement(world.xyz), 1.0);
    vec2 velocity = isHand(closestDepth) ? vec2(0.0) : (previousClip.xy / previousClip.w) * 0.5 + 0.5 - closestUv;
    vec2 historyUv = (vec2(p) + 0.5) / size + velocity;

    if (previousClip.w <= 0.0 || any(lessThan(historyUv, vec2(0.0))) || any(greaterThan(historyUv, vec2(1.0)))) {
        fragColor = vec4(current, 1.0);
        return;
    }

    vec3 rawHistory = sampleHistory(historyUv, size);
    if (DebugMode > 0.5) {
        fragColor = vec4(abs(current - rawHistory) * 4.0, 1.0);
        return;
    }

    vec3 mean = m1 / 9.0;
    vec3 sigma = sqrt(max(m2 / 9.0 - mean * mean, vec3(0.0)));
    vec3 history = clipToBox(toYCoCg(rawHistory), mean - 1.25 * sigma, mean + 1.25 * sigma);
    vec3 currentY = toYCoCg(current);

    // Every frame the history is resampled at a new sub-pixel offset, which softens it a little; while the image
    // moves, lean on the current frame so that softening can't build up (aliasing is hard to see in motion anyway).
    float speed = length(velocity * size);
    float feedback = mix(Feedback, 0.6, smoothstep(0.0, 2.0, speed));

    // Luma-weighted blend reduces flicker from bright pixels.
    float currentWeight = (1.0 - feedback) / (1.0 + currentY.x);
    float historyWeight = feedback / (1.0 + history.x);
    vec3 resolved = (currentY * currentWeight + history * historyWeight) / (currentWeight + historyWeight);

    fragColor = vec4(max(fromYCoCg(resolved), vec3(0.0)), 1.0);
}

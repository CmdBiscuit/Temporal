#version 150

// First-person hand fix-up for frame generation. The hand is drawn after the world's depth and motion vectors were
// captured, onto a depth buffer cleared just for it, so that depth is non-empty exactly where the hand is. There the
// motion vectors become zero (the hand moves with the camera) and the depth becomes the hand's, so frame generation
// keeps the hand in place instead of dragging it along with the world behind it.
// Output is top-down like the shared images; GL textures are bottom-up.

uniform sampler2D HandDepthSampler;

uniform vec2 OutSize;
uniform float Mode;  // 0: write motion (zero), 1: write depth

out vec4 fragColor;

void main() {
    vec2 uv = vec2(gl_FragCoord.x / OutSize.x, 1.0 - gl_FragCoord.y / OutSize.y);
    float depth = texture(HandDepthSampler, uv).r;
    if (depth >= 1.0) {
        discard;
    }
    fragColor = Mode < 0.5 ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(depth, 0.0, 0.0, 1.0);
}

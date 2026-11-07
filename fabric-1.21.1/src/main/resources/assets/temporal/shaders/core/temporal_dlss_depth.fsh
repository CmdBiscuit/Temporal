#version 150

// Copies the scene depth into a float colour image DLSS can read (GL depth textures can't be shared directly).
// Rows are written top-down (row 0 = top of the picture) like every Vulkan-side image; GL stores them bottom-up.

uniform sampler2D DepthSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(DepthSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    fragColor = vec4(texelFetch(DepthSampler, ivec2(p.x, size.y - 1 - p.y), 0).r, 0.0, 0.0, 1.0);
}

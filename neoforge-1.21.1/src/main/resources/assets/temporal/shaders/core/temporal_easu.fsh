#version 150

// FidelityFX Super Resolution 1.0 - EASU (Edge Adaptive Spatial Upsampling).
// Ported to GLSL from AMD's ffx_fsr1.h. Copyright (c) 2021 Advanced Micro Devices, Inc.
// MIT License - see LICENSE-FSR1.txt.

uniform sampler2D InSampler;
uniform vec2 OutSize;

out vec4 fragColor;

ivec2 inMax;

vec3 tap(ivec2 p) {
    return texelFetch(InSampler, clamp(p, ivec2(0), inMax), 0).rgb;
}

float luma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// Accumulates edge direction and length from a plus-shaped neighbourhood (A above, B left, C centre, D right, E below).
void setDir(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float dirX = lD - lB;
    float lenX = max(abs(lD - lC), abs(lC - lB));
    lenX = clamp(abs(dirX) / max(lenX, 1.0e-5), 0.0, 1.0);
    dir.x += dirX * w;
    len += lenX * lenX * w;

    float dirY = lE - lA;
    float lenY = max(abs(lE - lC), abs(lC - lA));
    lenY = clamp(abs(dirY) / max(lenY, 1.0e-5), 0.0, 1.0);
    dir.y += dirY * w;
    len += lenY * lenY * w;
}

// One tap of the rotated, anisotropic Lanczos-like kernel.
void accumulate(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {
    vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x))) * len2;
    float d2 = min(dot(v, v), clp);
    float wB = 0.4 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 1.5625 * wB - 0.5625;
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void main() {
    ivec2 inSize = textureSize(InSampler, 0);
    inMax = inSize - 1;

    vec2 pp = gl_FragCoord.xy * (vec2(inSize) / OutSize) - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 o = ivec2(fp);

    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 bC = tap(o + ivec2( 0, -1));
    vec3 cC = tap(o + ivec2( 1, -1));
    vec3 eC = tap(o + ivec2(-1,  0));
    vec3 fC = tap(o + ivec2( 0,  0));
    vec3 gC = tap(o + ivec2( 1,  0));
    vec3 hC = tap(o + ivec2( 2,  0));
    vec3 iC = tap(o + ivec2(-1,  1));
    vec3 jC = tap(o + ivec2( 0,  1));
    vec3 kC = tap(o + ivec2( 1,  1));
    vec3 lC = tap(o + ivec2( 2,  1));
    vec3 nC = tap(o + ivec2( 0,  2));
    vec3 oC = tap(o + ivec2( 1,  2));

    float bL = luma(bC), cL = luma(cC), eL = luma(eC), fL = luma(fC);
    float gL = luma(gC), hL = luma(hC), iL = luma(iC), jL = luma(jC);
    float kL = luma(kC), lL = luma(lC), nL = luma(nC), oL = luma(oC);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    setDir(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    setDir(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    setDir(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    setDir(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);

    float dirR = dot(dir, dir);
    bool zero = dirR < (1.0 / 32768.0);
    dirR = zero ? 1.0 : inversesqrt(dirR);
    dir.x = zero ? 1.0 : dir.x;
    dir *= dirR;

    len = len * 0.5;
    len *= len;
    float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 - 0.29 * len;
    float clp = 1.0 / lob;

    vec3 aC = vec3(0.0);
    float aW = 0.0;
    accumulate(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, bC);
    accumulate(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, cC);
    accumulate(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, iC);
    accumulate(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, jC);
    accumulate(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, fC);
    accumulate(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, eC);
    accumulate(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, kC);
    accumulate(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, lC);
    accumulate(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, hC);
    accumulate(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, gC);
    accumulate(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, oC);
    accumulate(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, nC);

    // Deringing: clamp to the 2x2 neighbourhood around the sample point.
    vec3 min4 = min(min(fC, gC), min(jC, kC));
    vec3 max4 = max(max(fC, gC), max(jC, kC));
    fragColor = vec4(clamp(aC / aW, min4, max4), 1.0);
}

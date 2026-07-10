#version 430 core

// ============================================================
//  Amdium v2.2 — Hi-Z Depth Pyramid Builder
//
//  Строит mip-цепочку из исходной depth-текстуры ( mip 0 = full-res depth ),
//  где каждый следующий mip = MIN от 4 соседних текселей предыдущего.
//
//  MIN-reduction потому что:
//    depth в OpenGL: 0 = near, 1 = far
//    ближайший occluder в тайле = МИНИМАЛЬНОЕ значение depth
//    если AABB-точка дальше этого минимума → AABB заслонён.
//
//  Использование:
//    Один dispatch на mip level. source mip N → destination mip N+1.
//    image2D у src и dst разные (или один и тот же texture с разными levels
//    через glBindImageTexture(level=...)).
// ============================================================

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// Источник: mip level N (read-only).
layout(binding = 0, r32f) readonly uniform image2D u_SrcMip;

// Назначение: mip level N+1 (write-only).
layout(binding = 1, r32f) writeonly uniform image2D u_DstMip;

void main() {
    ivec2 dstCoord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 dstSize  = imageSize(u_DstMip);
    if (dstCoord.x >= dstSize.x || dstCoord.y >= dstSize.y) return;

    // Каждый destination texel = MIN от 4 source texels (2x2 block).
    ivec2 srcCoord = dstCoord * 2;
    ivec2 srcSize  = imageSize(u_SrcMip);

    // Защита от выхода за границы (на границах mip'а размер может быть нечётным).
    float d00 = imageLoad(u_SrcMip, srcCoord).r;
    float d10 = (srcCoord.x + 1 < srcSize.x) ? imageLoad(u_SrcMip, srcCoord + ivec2(1, 0)).r : 1.0;
    float d01 = (srcCoord.y + 1 < srcSize.y) ? imageLoad(u_SrcMip, srcCoord + ivec2(0, 1)).r : 1.0;
    float d11 = (srcCoord.x + 1 < srcSize.x && srcCoord.y + 1 < srcSize.y)
                ? imageLoad(u_SrcMip, srcCoord + ivec2(1, 1)).r : 1.0;

    float minDepth = min(min(d00, d10), min(d01, d11));
    imageStore(u_DstMip, dstCoord, vec4(minDepth, 0.0, 0.0, 1.0));
}

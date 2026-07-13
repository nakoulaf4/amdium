#version 430 core

// ============================================================
//  Amdium v2.3.3 — Hi-Z Pyramid Builder (2 mip levels per dispatch)
//
//  v2.2 / v2.3.0 ПРОБЛЕМА / v2.2 / v2.3.0 PROBLEM:
//    Один dispatch на mip level = 10-11 dispatch+barrier пар для 1080p.
//    Каждый dispatch ~50-100 us overhead на AMD, каждый barrier сериализует
//    pipeline. Итог ~1.5-3 ms на кадр.
//    / One dispatch per mip level = 10-11 dispatch+barrier pairs for 1080p.
//    Each dispatch ~50-100 us overhead on AMD, each barrier serializes the
//    pipeline. Total ~1.5-3 ms per frame.
//
//  v2.3.3 РЕШЕНИЕ / v2.3.3 SOLUTION:
//    Строим 2 mip level'а за один dispatch через shared-memory редукцию.
//    Каждый workgroup 8x8 (= 64 threads = 1 AMD wavefront):
//      1. Загружает 16x16 текселей из src mip в shared memory (по 4 на thread).
//      2. Редуцирует 16x16 → 8x8 (MIN), пишет в mip N+1 (imageStore).
//      3. Редуцирует 8x8 → 4x4 (MIN), пишет в mip N+2 (imageStore).
//    / Build 2 mip levels per dispatch via shared-memory reduction.
//    Each 8x8 workgroup (= 64 threads = 1 AMD wavefront):
//      1. Loads 16x16 texels from the src mip into shared memory (4 per thread).
//      2. Reduces 16x16 → 8x8 (MIN), writes to mip N+1 (imageStore).
//      3. Reduces 8x8 → 4x4 (MIN), writes to mip N+2 (imageStore).
//
//    3 image bindings вместо 8-13 (как требовал бы полный FidelityFX SPD).
//    Это укладывается в GL_MAX_IMAGE_UNITS = 8 даже на самых ограниченных AMD.
//    / 3 image bindings instead of 8-13 (as full FidelityFX SPD would require).
//    This fits within GL_MAX_IMAGE_UNITS = 8 even on the most limited AMD.
//
//    Количество dispatch+barrier пар сокращается ~вдвое (10-11 → 5-6).
//    / The number of dispatch+barrier pairs is roughly halved (10-11 → 5-6).
//
//  ГРАНИЧНЫЙ СЛУЧАЙ / EDGE CASE:
//    Если остался только 1 mip для построения (последний level), Java-сторона
//    использует отдельный shader pass или просто пропускает. В этой версии
//    мы всегда пишем mip N+1; если mip N+2 не существует (level+2 >= levels),
//    Java НЕ биндит image 2 и шейдер пропускает вторую запись (u_BuildSecondMip=0).
//    / If only 1 mip remains to build (the last level), the Java side uses a
//    separate shader pass or just skips. In this version we always write mip N+1;
//    if mip N+2 doesn't exist (level+2 >= levels), Java does NOT bind image 2
//    and the shader skips the second write (u_BuildSecondMip=0).
// ============================================================

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// Src mip (read) — bind'ится CPU на image unit 0.
// / Src mip (read) — bound by the CPU on image unit 0.
layout(binding = 0, r32f) readonly uniform image2D u_srcMip;

// Dst mip N+1 (write) — bind'ится CPU на image unit 1.
// / Dst mip N+1 (write) — bound by the CPU on image unit 1.
layout(binding = 1, r32f) writeonly uniform image2D u_dstMip1;

// Dst mip N+2 (write) — bind'ится CPU на image unit 2 (только если u_BuildSecondMip=1).
// / Dst mip N+2 (write) — bound by the CPU on image unit 2 (only if u_BuildSecondMip=1).
layout(binding = 2, r32f) writeonly uniform image2D u_dstMip2;

// Размер src mip (dst1 = src/2, dst2 = src/4).
// / Src mip dimensions (dst1 = src/2, dst2 = src/4).
uniform int u_SrcWidth;
uniform int u_SrcHeight;

// 1 = строить второй mip (N+2), 0 = только первый (N+1).
// / 1 = build the second mip (N+2), 0 = only the first (N+1).
uniform int u_BuildSecondMip;

// Shared memory: 16x16 float = 1024 байта на workgroup.
// / Shared memory: 16x16 float = 1024 bytes per workgroup.
shared float s_tile[16][16];

void main() {
    ivec2 tid = ivec2(gl_LocalInvocationID.xy);
    int  tidx = int(gl_LocalInvocationIndex); // 0..63

    // Базовый offset в src mip для этого workgroup'а: workgroup покрывает 16x16
    // текселей src, что соответствует 8x8 текселям dst1 и 4x4 текселям dst2.
    // / Base offset in the src mip for this workgroup: the workgroup covers 16x16
    // src texels, which corresponds to 8x8 dst1 texels and 4x4 dst2 texels.
    ivec2 base0 = ivec2(gl_WorkGroupID.xy) * ivec2(16, 16);

    // ─── Шаг 1: загрузить 16x16 из src mip в shared memory ───
    // Каждый thread (64) читает 4 текселя (2x2 block).
    // / Step 1: load 16x16 from the src mip into shared memory.
    // Each thread (64) reads 4 texels (a 2x2 block).
    for (int i = 0; i < 4; i++) {
        int dx = (i % 2) * 2;
        int dy = (i / 2) * 2;
        ivec2 p = base0 + tid * 2 + ivec2(dx, dy);
        if (p.x < u_SrcWidth && p.y < u_SrcHeight) {
            s_tile[tid.y * 2 + dy][tid.x * 2 + dx] = imageLoad(u_srcMip, p).r;
        } else {
            s_tile[tid.y * 2 + dy][tid.x * 2 + dx] = 1.0; // far plane
        }
    }
    memoryBarrierShared();
    barrier();

    // ─── Шаг 2: редуцировать 16x16 → 8x8, писать в mip N+1 ───
    // Только первые 64 thread (8x8) активны — по одному на выходной тексель dst1.
    // / Step 2: reduce 16x16 → 8x8, write to mip N+1.
    // Only the first 64 threads (8x8) are active — one per dst1 output texel.
    float m1;
    {
        int tx = tidx % 8;
        int ty = tidx / 8;
        int sx = tx * 2;
        int sy = ty * 2;
        float a = s_tile[sy][sx];
        float b = s_tile[sy][sx + 1];
        float c = s_tile[sy + 1][sx];
        float d = s_tile[sy + 1][sx + 1];
        m1 = min(min(a, b), min(c, d));
        s_tile[ty][tx] = m1;  // перезаписываем shared mem для шага 3

        ivec2 outPos = ivec2(gl_WorkGroupID.xy) * ivec2(8, 8) + ivec2(tx, ty);
        int mip1W = max(1, u_SrcWidth / 2);
        int mip1H = max(1, u_SrcHeight / 2);
        if (outPos.x < mip1W && outPos.y < mip1H) {
            imageStore(u_dstMip1, outPos, vec4(m1, 0.0, 0.0, 0.0));
        }
    }
    memoryBarrierShared();
    barrier();

    // ─── Шаг 3: редуцировать 8x8 → 4x4, писать в mip N+2 ───
    // Только первые 16 thread (4x4) активны.
    // / Step 3: reduce 8x8 → 4x4, write to mip N+2.
    // Only the first 16 threads (4x4) are active.
    if (u_BuildSecondMip == 1 && tidx < 16) {
        int tx = tidx % 4;
        int ty = tidx / 4;
        int sx = tx * 2;
        int sy = ty * 2;
        float a = s_tile[sy][sx];
        float b = s_tile[sy][sx + 1];
        float c = s_tile[sy + 1][sx];
        float d = s_tile[sy + 1][sx + 1];
        float m2 = min(min(a, b), min(c, d));

        ivec2 outPos = ivec2(gl_WorkGroupID.xy) * ivec2(4, 4) + ivec2(tx, ty);
        int mip2W = max(1, u_SrcWidth / 4);
        int mip2H = max(1, u_SrcHeight / 4);
        if (outPos.x < mip2W && outPos.y < mip2H) {
            imageStore(u_dstMip2, outPos, vec4(m2, 0.0, 0.0, 0.0));
        }
    }
}

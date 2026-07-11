#version 430 core

// ============================================================
//  Amdium v2.2 — Per-command GPU Culling (frustum + fog + Hi-Z occlusion)
//
//  Принцип (Nvidium-style, zero readback):
//    1. CPU копирует MultiDrawBatch Embedium → input SSBO (DrawCmd[]).
//    2. CPU копирует per-command AABB → chunkInfo SSBO (один вход на batch entry).
//    3. CPU зануляет atomic counter.
//    4. Compute-шейдер (этот файл): каждый thread обрабатывает ОДНУ команду:
//         - тест AABB против 6 frustum planes
//         - тест AABB против fog end (distance culling)
//         - тест AABB против Hi-Z depth pyramid (occlusion culling, 1-frame latency)
//       Видимые команды: atomicAdd → compacted output buffer.
//    5. glMultiDrawElementsIndirectCount — GPU читает drawCount из parameter buffer.
//       CPU НЕ участвует. Нуль readback.
//
//  На AMD RDNA wavefront = 64 → local_size_x = 64 идеально.
// ============================================================

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

// --- Структуры ---

struct ChunkInfo {
    vec4 aabbMin_origin;  // xyz = AABB min, w = origin.x
    vec4 aabbMax_originY; // xyz = AABB max, w = origin.y
    vec4 aabbMin_originZ; // xyz = unused (для будущего), w = origin.z
};

struct DrawElementsCommand {
    uint count;
    uint instanceCount;
    uint firstIndex;
    int  baseVertex;
    uint baseInstance;
};

// --- SSBO bindings ---

layout(std430, binding = 0) readonly buffer ChunkInfoBuffer {
    ChunkInfo chunks[];
};

layout(std430, binding = 1) readonly buffer InputCommands {
    DrawElementsCommand inCommands[];
};

layout(std430, binding = 2) writeonly buffer CompactedCommands {
    DrawElementsCommand outCommands[];
};

layout(std430, binding = 3) buffer AtomicCounter {
    uint drawCount;
};

// --- Hi-Z depth pyramid (texture) ---
// Биндится на image unit 5 (см. InteropComputeCuller.bindHiZ()).
// mip level 0 = full-res depth (R32F), более высокие mip = MIN-reduction.
layout(binding = 5) uniform sampler2D u_HiZPyramid;

// Параметры пирамиды: размеры mip 0.
uniform uint u_HiZWidth;
uniform uint u_HiZHeight;
uniform uint u_HiZLevels;  // количество mip levels

// Включатели проходов (если 0 — соответствующий тест пропускается).
uniform uint u_EnableFrustum;   // 1 = frustum test включён
uniform uint u_EnableFog;       // 1 = fog-distance test включён
uniform uint u_EnableHiZ;       // 1 = Hi-Z occlusion test включён

// --- Кадровые данные (передаются через UBO в v2.2 — здесь упрощённо uniforms) ---
uniform mat4 u_ProjViewMatrix;
uniform mat4 u_ViewMatrix;       // для перевода world → view (для Hi-Z)
uniform mat4 u_ProjectionMatrix; // для перевода view → clip
uniform vec3 u_CameraPos;
uniform uint u_ChunkCount;
uniform float u_FogStart;
uniform float u_FogEnd;

// --- Frustum extraction (Gribb & Hartmann) ---
// Извлекаем 6 planes из projView (column-major JOML).

vec4 frustumPlanes[6];

void extractFrustumPlanes() {
    mat4 m = u_ProjViewMatrix;

    // Column-major: m[col][row]
    frustumPlanes[0] = vec4(m[0][3] + m[0][0], m[1][3] + m[1][0],
                            m[2][3] + m[2][0], m[3][3] + m[3][0]);  // Left
    frustumPlanes[1] = vec4(m[0][3] - m[0][0], m[1][3] - m[1][0],
                            m[2][3] - m[2][0], m[3][3] - m[3][0]);  // Right
    frustumPlanes[2] = vec4(m[0][3] + m[0][1], m[1][3] + m[1][1],
                            m[2][3] + m[2][1], m[3][3] + m[3][1]);  // Bottom
    frustumPlanes[3] = vec4(m[0][3] - m[0][1], m[1][3] - m[1][1],
                            m[2][3] - m[2][1], m[3][3] - m[3][1]);  // Top
    frustumPlanes[4] = vec4(m[0][3] + m[0][2], m[1][3] + m[1][2],
                            m[2][3] + m[2][2], m[3][3] + m[3][2]);  // Near
    frustumPlanes[5] = vec4(m[0][3] - m[0][2], m[1][3] - m[1][2],
                            m[2][3] - m[2][2], m[3][3] - m[3][2]);  // Far

    // Нормализация — улучшает численную стабильность fog-distance теста.
    for (int i = 0; i < 6; i++) {
        float len = length(frustumPlanes[i].xyz);
        if (len > 0.0001) frustumPlanes[i] /= len;
    }
}

// --- AABB vs Frustum: positive-vertex тест ---
bool isAABBVisible(vec3 minPos, vec3 maxPos) {
    for (int i = 0; i < 6; i++) {
        vec3 normal = frustumPlanes[i].xyz;
        float d     = frustumPlanes[i].w;

        vec3 pv = vec3(
            normal.x >= 0.0 ? maxPos.x : minPos.x,
            normal.y >= 0.0 ? maxPos.y : minPos.y,
            normal.z >= 0.0 ? maxPos.z : minPos.z
        );

        if (dot(normal, pv) + d < 0.0) return false;
    }
    return true;
}

// --- Distance culling: чанки дальше fog end ---
bool isWithinFogDistance(vec3 minPos, vec3 maxPos) {
    if (u_FogEnd <= 0.0) return true;
    vec3 closest = clamp(u_CameraPos, minPos, maxPos);
    float dist = length(closest - u_CameraPos);
    return dist <= u_FogEnd;
}

// --- Hi-Z occlusion culling ---
// Возвращает true если AABB НЕ заслонён (виден).
// Алгоритм (стандартный Hi-Z):
//   1. Проецируем 8 углов AABB в clip space, затем в NDC.
//   2. Находим bbox в NDC: [minXY, maxXY] и minDepth (ближайшая к камере).
//   3. Выбираем mip level так, чтобы texel покрывал ~1 texel на пиксель bbox'а.
//   4. Сэмплируем Hi-Z pyramid на выбранном mip — получаем closest occluder depth.
//   5. Если minDepth AABB'а ДАЛЬШЕ occluder depth → заслонён → return false.
bool isNotOccluded(vec3 minPos, vec3 maxPos) {
    if (u_HiZLevels == 0u) return true;

    // 8 углов AABB в world space.
    vec3 corners[8] = vec3[8](
        vec3(minPos.x, minPos.y, minPos.z),
        vec3(maxPos.x, minPos.y, minPos.z),
        vec3(minPos.x, maxPos.y, minPos.z),
        vec3(maxPos.x, maxPos.y, minPos.z),
        vec3(minPos.x, minPos.y, maxPos.z),
        vec3(maxPos.x, minPos.y, maxPos.z),
        vec3(minPos.x, maxPos.y, maxPos.z),
        vec3(maxPos.x, maxPos.y, maxPos.z)
    );

    // Проецируем в NDC.
    vec2 minNDC = vec2( 1e9);
    vec2 maxNDC = vec2(-1e9);
    float minDepth = 1e9;       // ближайшая к камере (минимальное значение depth)
    bool anyInFront = false;

    for (int i = 0; i < 8; i++) {
        vec4 clip = u_ProjViewMatrix * vec4(corners[i], 1.0);
        if (clip.w <= 0.0001) {
            anyInFront = true;  // вершина за камерой —保守но считаем видимой
            continue;
        }
        vec3 ndc = clip.xyz / clip.w;
        minNDC = min(minNDC, ndc.xy);
        maxNDC = max(maxNDC, ndc.xy);
        minDepth = min(minDepth, ndc.z);
        anyInFront = true;
    }
    if (!anyInFront) return false;  // весь AABB за камерой

    // Если bbox частично за камерой —保守но видимый.
    if (minNDC.x < -1.0 || maxNDC.x > 1.0 ||
        minNDC.y < -1.0 || maxNDC.y > 1.0) {
        return true;
    }

    // Переводим NDC [-1,1] → UV [0,1].
    vec2 minUV = minNDC * 0.5 + 0.5;
    vec2 maxUV = maxNDC * 0.5 + 0.5;
    vec2 size  = maxUV - minUV;

    // Выбираем mip: texel на этом mip ≈ размеру bbox на экране.
    // log2(max(w,h)) даёт количество удвоений от пикселя до bbox'а.
    float maxDimPx = max(size.x * float(u_HiZWidth), size.y * float(u_HiZHeight));
    float mipF = log2(max(maxDimPx, 1.0));
    int mip = int(clamp(mipF, 0.0, float(u_HiZLevels - 1u)));

    // Сэмплируем Hi-Z pyramid (MIN-reduction → ближайший occluder).
    vec2 centerUV = (minUV + maxUV) * 0.5;
    float occluderDepth = textureLod(u_HiZPyramid, centerUV, float(mip)).r;

    // OpenGL depth: 0 = near, 1 = far.
    // Если minDepth AABB'а дальше occluderDepth → AABB заслонён.
    // Небольшой bias (0.001) против z-fighting'а на границах.
    return minDepth <= occluderDepth + 0.001;
}

// ============================================================
//  MAIN
// ============================================================
void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= u_ChunkCount) return;

    extractFrustumPlanes();

    ChunkInfo chunk = chunks[idx];
    vec3 minP = chunk.aabbMin_origin.xyz;
    vec3 maxP = chunk.aabbMax_originY.xyz;

    // 1. Frustum test
    if (u_EnableFrustum == 1u && !isAABBVisible(minP, maxP)) return;

    // 2. Fog-distance test
    if (u_EnableFog == 1u && !isWithinFogDistance(minP, maxP)) return;

    // 3. Hi-Z occlusion test (1-frame latency)
    if (u_EnableHiZ == 1u && !isNotOccluded(minP, maxP)) return;

    // Видим — атомарно захватываем слот в output буфере.
    uint outIdx = atomicAdd(drawCount, 1u);
    outCommands[outIdx] = inCommands[idx];
}

#version 430 core

// ============================================================
//  Amdium — Compute Shader: GPU-generated draw commands
//
//  Принцип (новый): / Principle (new):
//    - Input:  ChunkInfo[] (AABB + origin) + InputCommands[]
//    - Output: CompactedCommands[] (только видимые) + atomicCounter
//      / Output: CompactedCommands[] (only visible) + atomicCounter
//    - atomicCounter становится drawCount для glMultiDrawElementsIndirectCount
//      / atomicCounter becomes drawCount for glMultiDrawElementsIndirectCount
//
//  На AMD RDNA wavefront = 64 → local_size_x = 64 идеально.
//  / On AMD RDNA wavefront = 64 → local_size_x = 64 is ideal.
// ============================================================

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

// --- Структуры / Structures ---

struct ChunkInfo {
    vec4 aabbMin_origin;  // xyz = AABB min, w = origin.x
    vec4 aabbMax_originY; // xyz = AABB max, w = origin.y
};

struct DrawElementsCommand {
    uint count;
    uint instanceCount;
    uint firstIndex;
    int  baseVertex;
    uint baseInstance;
};

// --- SSBO bindings / Привязки SSBO ---

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

// --- Uniforms / Униформы ---

uniform mat4 u_ProjViewMatrix;
uniform vec3 u_CameraPos;
uniform uint u_ChunkCount;
uniform float u_FogStart;
uniform float u_FogEnd;

// --- Frustum extraction (Gribb & Hartmann) / Извлечение пирамиды видимости ---

vec4 frustumPlanes[6];

void extractFrustumPlanes() {
    mat4 m = u_ProjViewMatrix;

    // Column-major JOML матрица: m[col][row] / Column-major JOML matrix: m[col][row]
    frustumPlanes[0] = vec4(m[0][3] + m[0][0], m[1][3] + m[1][0],
                            m[2][3] + m[2][0], m[3][3] + m[3][0]);  // Left / Левая
    frustumPlanes[1] = vec4(m[0][3] - m[0][0], m[1][3] - m[1][0],
                            m[2][3] - m[2][0], m[3][3] - m[3][0]);  // Right / Правая
    frustumPlanes[2] = vec4(m[0][3] + m[0][1], m[1][3] + m[1][1],
                            m[2][3] + m[2][1], m[3][3] + m[3][1]);  // Bottom / Нижняя
    frustumPlanes[3] = vec4(m[0][3] - m[0][1], m[1][3] - m[1][1],
                            m[2][3] - m[2][1], m[3][3] - m[3][1]);  // Top / Верхняя
    frustumPlanes[4] = vec4(m[0][3] + m[0][2], m[1][3] + m[1][2],
                            m[2][3] + m[2][2], m[3][3] + m[3][2]);  // Near / Ближняя
    frustumPlanes[5] = vec4(m[0][3] - m[0][2], m[1][3] - m[1][2],
                            m[2][3] - m[2][2], m[3][3] - m[3][2]);  // Far / Дальняя

    // Нормализация для точного теста / Normalization for an exact test
    for (int i = 0; i < 6; i++) {
        float len = length(frustumPlanes[i].xyz);
        if (len > 0.0001) frustumPlanes[i] /= len;
    }
}

// --- AABB vs Frustum: positive-vertex тест / AABB vs Frustum: positive-vertex test ---
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

// --- Distance culling: чанки дальше fog end / Distance culling: chunks beyond fog end ---
bool isWithinFogDistance(vec3 minPos, vec3 maxPos) {
    if (u_FogEnd <= 0.0) return true;
    vec3 closest = clamp(u_CameraPos, minPos, maxPos);
    float dist = length(closest - u_CameraPos);
    return dist <= u_FogEnd;
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

    // Cull невидимые / Cull invisible
    if (!isAABBVisible(minP, maxP)) return;
    if (!isWithinFogDistance(minP, maxP)) return;

    // Видим: атомарно захватываем слот в output буфере
    // / Visible: atomically claim a slot in the output buffer
    uint outIdx = atomicAdd(drawCount, 1u);

    // Копируем draw command (с тем же baseInstance = slot)
    // / Copy the draw command (with the same baseInstance = slot)
    outCommands[outIdx] = inCommands[idx];
}

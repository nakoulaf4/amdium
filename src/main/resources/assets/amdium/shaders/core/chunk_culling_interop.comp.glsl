#version 430 core
// =============================================================================
//  Amdium — Interop GPU Culling compute shader (Embeddium interop path)
//
//  Принимает batch draw-команд Embeddium (MultiDrawBatch → DrawElementsIndirectCommand[])
//  и компактирует их на GPU с опциональным frustum+fog куллингом. Результат —
//  compacted indirect-буфер + atomic counter в parameter buffer.
//  glMultiDrawElementsIndirectCount читает count прямо с GPU — нуль readback.
//
//  Гранулярность куллинга: REGION (8x4x8 chunk-секций = 128x64x128 блоков).
//  Все команды в одном batch Embeddium принадлежат одному региону, поэтому
//  region-level тест применяется один раз (thread 0). Если регион невидим —
//  outCount остаётся 0 (CPU зануляет counter перед dispatch), draw пропускается.
//
//  Архитектура готова для будущего per-command куллинга: достаточно populate
//  per-command AABB SSBO и добавить цикл теста (закомментирован ниже).
//
//  Workgroup: 64 = 1 wavefront на AMD (RDNA/GCN).
//  Dispatch: ceil(commandCount / 64) groups.
// =============================================================================

layout(local_size_x = 64) in;

// ---------------------------------------------------------------------------
//  UBO (std140, binding 0) — данные кадра.
//  layout соответствует ByteBuffer в InteropComputeCuller.drawWithCulling():
//    offset   0: vec4 frustumPlanes[6]            (96 байт)
//    offset  96: vec4 cameraPos.xyz + fogEnd.w    (16 байт)
//    offset 112: vec4 regionMin.xyz + pad.w       (16 байт)
//    offset 128: vec4 regionMax.xyz + pad.w       (16 байт)
//    offset 144: uvec4 commandCount + 3 pad uint  (16 байт)
//  Итого: 160 байт.
//
//  ВАЖНО: используем vec4/uvec4 вместо vec3+scalar, чтобы избежать
//  неоднозначности std140-упаковки vec3 (разные драйверы могут паддить
//  vec3 до 16 байт по-разному). vec4 = всегда 16 байт, однозначно.
// / IMPORTANT: we use vec4/uvec4 instead of vec3+scalar to avoid std140
// packing ambiguity for vec3 (different drivers may pad vec3 to 16 bytes
// differently). vec4 = always 16 bytes, unambiguous.
// ---------------------------------------------------------------------------
layout(std140, binding = 0) uniform FrameData {
    vec4  frustumPlanes[6];   // xyz = normal, w = d (Gribb/Hartmann)
    vec4  cameraPosFog;       // .xyz = cameraPos, .w = fogEnd
    vec4  regionMinPad;       // .xyz = region AABB min, .w = unused
    vec4  regionMaxPad;       // .xyz = region AABB max, .w = unused
    uvec4 commandCountPad;    // .x = command count, .yzw = unused
};

// DrawElementsIndirectCommand (20 байт, std430 — без padding).
struct DrawCmd {
    uint count;          // кол-во индексов
    uint instanceCount;  // 1
    uint firstIndex;     // offset в IBO (в индексах)
    int  baseVertex;     // смещение вершин
    uint baseInstance;   // 0
};

layout(std430, binding = 1) readonly  buffer InputBuffer  { DrawCmd inCommands[];  };
layout(std430, binding = 2) writeonly buffer OutputBuffer { DrawCmd outCommands[]; };

// Counter buffer: одновременно SSBO (binding 3) для compute и
// GL_PARAMETER_BUFFER для glMultiDrawElementsIndirectCount.
layout(std430, binding = 3) coherent buffer CounterBuffer {
    uint outCount;
};

// shared-флаг результата region-test (один на workgroup; т.к. весь batch
// обрабатывается одним dispatch'ем с ceil(count/64) группами, используем
// atomic для глобального решения — но region test делает только thread 0
// каждой группы, и результат одинаковый, поэтому shared достаточно).
shared bool regionVisible;

// ---------------------------------------------------------------------------
//  Positive-vertex AABB test против 6 frustum planes (Gribb & Hartmann).
//  Возвращает true если AABB пересекает frustum (возможно частично).
// ---------------------------------------------------------------------------
bool aabbInFrustum(vec3 mn, vec3 mx) {
    for (int i = 0; i < 6; i++) {
        vec4 p = frustumPlanes[i];
        // positive vertex = вершина AABB, наиболее удалённая вдоль нормали плоскости
        vec3 pv = vec3(
            (p.x >= 0.0) ? mx.x : mn.x,
            (p.y >= 0.0) ? mx.y : mn.y,
            (p.z >= 0.0) ? mx.z : mn.z
        );
        // если positive vertex за плоскостью — AABB полностью невидим
        if (dot(p.xyz, pv) + p.w < 0.0) {
            return false;
        }
    }
    return true;
}

void main() {
    uint tid = gl_GlobalInvocationID.x;

    // Распаковка vec4 → vec3 + scalar (см. комментарий к UBO).
    vec3  cameraPos = cameraPosFog.xyz;
    float fogEnd    = cameraPosFog.w;
    vec3  regionMin = regionMinPad.xyz;
    vec3  regionMax = regionMaxPad.xyz;
    uint  commandCount = commandCountPad.x;

    // Thread 0 каждой группы считает region-test. Результат одинаков для всех
    // групп (region один на batch), поэтому shared-флаг корректен.
    if (gl_LocalInvocationIndex == 0u) {
        bool vis = aabbInFrustum(regionMin, regionMax);
        // Дополнительный fog-distance тест: если центр региона дальше fog end —
        // весь регион скрыт туманом, draw пропускается.
        if (vis) {
            vec3 center = (regionMin + regionMax) * 0.5;
            float dist = length(center - cameraPos);
            if (dist > fogEnd) {
                vis = false;
            }
        }
        regionVisible = vis;
    }
    barrier();

    // Регион невидим — ничего не делаем. outCount остаётся 0 (CPU занулил).
    if (!regionVisible) {
        return;
    }

    // Compaction: каждый видимый поток копирует свою команду в output.
    // outCount инкрементируется через atomicAdd → становится drawCount для
    // glMultiDrawElementsIndirectCount. CPU НЕ читает это значение.
    //
    // TODO (future per-command culling): здесь можно добавить per-command
    // AABB тест, если Amdium будет поставлять per-command origin SSBO:
    //   if (tid < commandCount && commandVisible[tid]) { ... atomicAdd ... }
    if (tid < commandCount) {
        uint idx = atomicAdd(outCount, 1u);
        outCommands[idx] = inCommands[tid];
    }
}

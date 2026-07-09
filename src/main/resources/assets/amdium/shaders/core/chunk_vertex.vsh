#version 430 core

// Включаем расширение для поддержки параметров отрисовки (MDI) на строгих драйверах (Mesa/Linux)
#extension GL_ARB_shader_draw_parameters : enable

// Если чистый gl_BaseInstance не объявлен в рантайме, подменяем его на версию из расширения ARB
#ifndef gl_BaseInstance
#define gl_BaseInstance gl_BaseInstanceARB
#endif

// ============================================================
//  Amdium — Vertex Shader для MDI terrain rendering
//  / Amdium — Vertex Shader for MDI terrain rendering
//
//  Формат вершин: DefaultVertexFormat.BLOCK (32 байта)
//  / Vertex format: DefaultVertexFormat.BLOCK (32 bytes)
//    location 0: pos (3 floats)
//    location 1: color (4 ubytes, normalized ARGB)
//    location 2: uv0 (2 floats) — block atlas UV
//    location 3: uv1 (2 shorts) — lightmap UV
//    location 4: normal (4 bytes, normalized)
//
//  gl_BaseInstance (GL 4.2+) используется как chunk slot ID.
//  / gl_BaseInstance (GL 4.2+) is used as the chunk slot ID.
//  Из origins[slot] получаем world-space origin чанка.
//  / origins[slot] yields the world-space origin of the chunk.
// ============================================================

layout(location = 0) in vec3 a_Pos;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in vec2 a_TexCoord;
layout(location = 3) in ivec2 a_LightCoord;
layout(location = 4) in vec3 a_Normal;

uniform mat4 u_ProjView;
uniform vec3 u_CameraPos;
uniform vec2 u_TextureScale; // 1.0/atlasWidth, 1.0/atlasHeight

// SSBO с origins: vec4[slot] = vec3 origin + pad
// / SSBO with origins: vec4[slot] = vec3 origin + pad
layout(std430, binding = 4) readonly buffer ChunkOrigins {
    vec4 origins[];
};

out vec4 v_Color;
out vec2 v_TexCoord;
out vec2 v_LightCoord;
out float v_FogFactor;

uniform vec4 u_FogColor;
uniform float u_FogStart;
uniform float u_FogEnd;

void main() {
    // Теперь gl_BaseInstance гарантированно скомпилируется благодаря макросу выше
    int slot = int(gl_BaseInstance);
    vec3 origin = origins[slot].xyz;

    // World-space position / Позиция в мировых координатах
    vec4 worldPos = vec4(a_Pos + origin, 1.0);
    gl_Position = u_ProjView * worldPos;

    v_Color = a_Color;
    v_TexCoord = a_TexCoord;
    // LightTexture в 1.20.1 — 16x16 пикселей, lightCoord 0..240 (lightLevel * 16)
    // / LightTexture in 1.20.1 is 16x16 pixels, lightCoord 0..240 (lightLevel * 16)
    v_LightCoord = vec2(a_LightCoord) / 16.0;

    // Fog factor: 0 = нет тумана, 1 = полностью туман
    // / Fog factor: 0 = no fog, 1 = fully fogged
    float dist = length(worldPos.xyz - u_CameraPos);
    v_FogFactor = clamp((dist - u_FogStart) / max(u_FogEnd - u_FogStart, 0.001), 0.0, 1.0);
}
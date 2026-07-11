#version 430 core

// ============================================================
//  Amdium — Fragment Shader для MDI terrain rendering
//  / Amdium — Fragment Shader for MDI terrain rendering
//
//  Семплит block atlas + lightmap, применяет vertex color и fog.
//  / Samples the block atlas + lightmap, applies vertex color and fog.
//  Упрощён по сравнению с vanilla chunk.fsh, но корректно
//  отображает стандартный террейн без шейдерпаков.
//  / Simplified compared to vanilla chunk.fsh, but correctly
//  renders standard terrain without shader packs.
// ============================================================

in vec4 v_Color;
in vec2 v_TexCoord;
in vec2 v_LightCoord;
in float v_FogFactor;

uniform sampler2D u_BlockAtlas;   // binding = 0
uniform sampler2D u_Lightmap;     // binding = 2
uniform vec4 u_FogColor;

out vec4 fragColor;

void main() {
    vec4 tex = texture(u_BlockAtlas, v_TexCoord);
    if (tex.a < 0.1) discard; // cutout transparency / вырезание прозрачных пикселей

    vec4 light = texture(u_Lightmap, v_LightCoord);
    vec4 color = tex * v_Color * light;

    // Apply fog / Применяем туман
    color.rgb = mix(color.rgb, u_FogColor.rgb, v_FogFactor * u_FogColor.a);
    fragColor = color;
}

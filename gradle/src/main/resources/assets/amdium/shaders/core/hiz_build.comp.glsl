#version 430 core

// ============================================================
//  Amdium v2.3 — Hi-Z Pyramid Builder (lean single-mip)
//
//  Этот шейдер строит ОДИН mip level за один dispatch. Java-сторона
//  вызывает его в цикле для mips 1..N-1. Это проще и надёжнее, чем
//  single-pass multi-mip с shared memory (который требует до 14 image
//  bindings и превышает GL_MAX_IMAGE_UNITS = 8 на AMD).
//
//  / This shader builds ONE mip level per dispatch. The Java side calls
//  it in a loop for mips 1..N-1. This is simpler and more reliable than
//  a single-pass multi-mip shader with shared memory (which would require
//  up to 14 image bindings and exceed GL_MAX_IMAGE_UNITS = 8 on AMD).
//
//  v2.3 vs v2.2: сам шейдер идентичен. Оптимизация в Java-стороне:
//    - cached uniform locations (нет glGetUniformLocation каждый кадр),
//    - нет лишних glBindImageTexture между dispatch'ами (images rebind'ятся
//      только когда меняется src/dst, что и так нужно),
//    - один финальный барьер вместо барьера после каждого dispatch
//      (барьеры между dispatch'ами в одном кадре всё равно нужны для
//      корректности image load/store, но финальный барьер можно объединить
//      с barrier'ом перед culling-шейдером).
//
//  / v2.3 vs v2.2: the shader itself is identical. Optimization is on the
//  Java side: cached uniform locations, no redundant glBindImageTexture
//  between dispatches, one final barrier instead of a barrier after each
//  dispatch.
// ============================================================

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// Src mip (read) — bind'ится CPU на image unit 0.
// / Src mip (read) — bound by the CPU on image unit 0.
layout(binding = 0, r32f) readonly uniform image2D u_srcMip;

// Dst mip (write) — bind'ится CPU на image unit 1.
// / Dst mip (write) — bound by the CPU on image unit 1.
layout(binding = 1, r32f) writeonly uniform image2D u_dstMip;

// Размер src mip (dst = src / 2).
// / Src mip dimensions (dst = src / 2).
uniform int u_SrcWidth;
uniform int u_SrcHeight;

void main() {
    ivec2 dst = ivec2(gl_GlobalInvocationID.xy);
    if (dst.x >= max(1, u_SrcWidth / 2) || dst.y >= max(1, u_SrcHeight / 2)) return;

    // 2x2 MIN-reduction.
    ivec2 srcBase = dst * 2;
    float a = imageLoad(u_srcMip, srcBase + ivec2(0, 0)).r;
    float b = imageLoad(u_srcMip, srcBase + ivec2(1, 0)).r;
    float c = imageLoad(u_srcMip, srcBase + ivec2(0, 1)).r;
    float d = imageLoad(u_srcMip, srcBase + ivec2(1, 1)).r;

    float m = min(min(a, b), min(c, d));
    imageStore(u_dstMip, dst, vec4(m, 0.0, 0.0, 0.0));
}

package dev.amdium.render;

import com.mojang.logging.LogUtils;
import dev.amdium.Amdium;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;

/**
 * Hi-Z Depth Pyramid — для GPU occlusion culling.
 * / Hi-Z Depth Pyramid for GPU occlusion culling.
 *
 * Архитектура:
 *   1. На каждом кадре Amdium копирует depth-attachment главного FBO Minecraft
 *      в текстуру pyramid (mip 0) через shader copy (fullscreen quad).
 *   2. Dispatch'ит compute-шейдер hiz_build.comp.glsl для построения mip 1, 2, …
 *      Каждый mip = MIN от 2×2 текселей предыдущего.
 *   3. На следующем кадре culling-шейдер сэмплирует пирамиду через textureLod.
 *
 * Latency: 1 кадр (стандартный подход — Nvidium тоже так делает).
 *
 * Формат: GL_R32F. Depth копируется через shader pass (depth tex → R32F color).
 */
public final class HiZDepthPyramid {

    private static final Logger LOGGER = LogUtils.getLogger();

    // GL константы
    private static final int GL_R32F            = 0x822E;
    private static final int GL_RED             = 0x1903;
    private static final int GL_FLOAT           = 0x1406;
    private static final int GL_TEXTURE_2D      = GL11.GL_TEXTURE_2D;
    private static final int GL_TEXTURE_MIN_FILTER = GL11.GL_TEXTURE_MIN_FILTER;
    private static final int GL_TEXTURE_MAG_FILTER = GL11.GL_TEXTURE_MAG_FILTER;
    private static final int GL_TEXTURE_BASE_LEVEL = GL12.GL_TEXTURE_BASE_LEVEL;
    private static final int GL_TEXTURE_MAX_LEVEL  = GL12.GL_TEXTURE_MAX_LEVEL;
    private static final int GL_NEAREST          = GL11.GL_NEAREST;
    private static final int GL_READ_ONLY        = 0x88B8;
    private static final int GL_WRITE_ONLY       = 0x88B9;
    private static final int GL_READ_WRITE       = 0x88BA;
    private static final int GL_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x20;
    private static final int GL_TEXTURE_FETCH_BARRIER_BIT       = 0x8;
    private static final int GL_TEXTURE_UPDATE_BARRIER_BIT      = 0x100;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

    // Состояние пирамиды.
    private int pyramidTextureId = -1;
    private int buildProgramId   = -1;
    private int buildShaderId    = -1;

    // Shader copy pass (depth → R32F)
    private int copyProgramId = -1;
    private int copyVaoId     = -1;
    private int copyFboId     = -1;

    private int currentWidth  = 0;
    private int currentHeight = 0;
    private int currentLevels = 0;

    private boolean initialized = false;

    /** Инициализация. Вызывается из Amdium.initGPU() в interop-режиме. */
    public void init() {
        try {
            buildShaderId = compileShader(GL43.GL_COMPUTE_SHADER,
                    "/assets/amdium/shaders/core/hiz_build.comp.glsl");
            buildProgramId = GL20.glCreateProgram();
            GL20.glAttachShader(buildProgramId, buildShaderId);
            GL20.glLinkProgram(buildProgramId);
            if (GL20.glGetProgrami(buildProgramId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(buildProgramId);
                throw new RuntimeException("Hi-Z build program link error:\n" + log);
            }
            GL20.glDeleteShader(buildShaderId);
            buildShaderId = -1;

            initCopyPass();

            initialized = true;
            LOGGER.info("[Amdium] Hi-Z pyramid builder готов. program={}", buildProgramId);
        } catch (Exception e) {
            LOGGER.error("[Amdium] Hi-Z pyramid init failed: {}", e.getMessage(), e);
            initialized = false;
        }
    }

    /**
     * Создаёт fullscreen quad + шейдер + FBO для копирования depth → R32F.
     * Это нужно потому что glCopyImageSubData не работает между
     * GL_DEPTH_COMPONENT и GL_R32F (разные формат-классы OpenGL).
     */
    private void initCopyPass() {
        // Fullscreen quad (2 треугольника, NDC)
        float[] quad = { -1, -1,  1, -1,  -1, 1,  -1, 1,  1, -1,  1, 1 };
        copyVaoId = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(copyVaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        GL15.glDeleteBuffers(vbo);

        // Шейдер: сэмплирует depth текстуру и пишет в R32F
        String vs = "#version 150 core\n" +
                "in vec2 a_pos;\n" +
                "void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }\n";
        String fs = "#version 150 core\n" +
                "uniform sampler2D u_depth;\n" +
                "uniform vec2 u_texelSize;\n" +
                "out float fragDepth;\n" +
                "void main() {\n" +
                "    vec2 uv = gl_FragCoord.xy * u_texelSize;\n" +
                "    fragDepth = texture(u_depth, uv).r;\n" +
                "}\n";

        int vsId = compileShaderInline(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER, vs);
        int fsId = compileShaderInline(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER, fs);
        copyProgramId = org.lwjgl.opengl.GL20.glCreateProgram();
        org.lwjgl.opengl.GL20.glAttachShader(copyProgramId, vsId);
        org.lwjgl.opengl.GL20.glAttachShader(copyProgramId, fsId);
        org.lwjgl.opengl.GL20.glLinkProgram(copyProgramId);
        if (org.lwjgl.opengl.GL20.glGetProgrami(copyProgramId, org.lwjgl.opengl.GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = org.lwjgl.opengl.GL20.glGetProgramInfoLog(copyProgramId);
            throw new RuntimeException("Hi-Z copy program link error:\n" + log);
        }
        org.lwjgl.opengl.GL20.glDeleteShader(vsId);
        org.lwjgl.opengl.GL20.glDeleteShader(fsId);

        // FBO (пересоздаётся в ensureSize при изменении размера)
        copyFboId = GL30.glGenFramebuffers();
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void ensureSize(int width, int height) {
        if (width == currentWidth && height == currentHeight && pyramidTextureId != -1) return;

        if (pyramidTextureId != -1) {
            GL11.glDeleteTextures(pyramidTextureId);
        }

        int maxDim = Math.max(width, height);
        if (maxDim <= 0) return;
        int levels = 32 - Integer.numberOfLeadingZeros(maxDim);
        levels = Math.max(1, Math.min(levels, 14));

        pyramidTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, pyramidTextureId);
        GL42.glTexStorage2D(GL_TEXTURE_2D, levels, GL_R32F, width, height);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, levels - 1);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL_TEXTURE_2D, 0);

        currentWidth  = width;
        currentHeight = height;
        currentLevels = levels;

        LOGGER.info("[Amdium] Hi-Z pyramid: {}x{}, {} levels", width, height, levels);
    }

    /**
     * Обновляет пирамиду: копирует depth через shader pass + строит mip-цепочку.
     */
    public void update() {
        if (!initialized) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null) return;

        int viewportWidth  = mc.getWindow().getWidth();
        int viewportHeight = mc.getWindow().getHeight();

        try {
            ensureSize(viewportWidth, viewportHeight);
        } catch (Throwable t) {
            LOGGER.warn("[Amdium] Hi-Z ensureSize failed: {}", t.getMessage());
            return;
        }

        int sourceDepthId = getMainFboDepthTextureId(mc);
        if (sourceDepthId <= 0) return;

        // 1. Скопировать depth → pyramid mip 0 через shader pass.
        //    glCopyImageSubData НЕ работает между GL_DEPTH_COMPONENT и GL_R32F.
        try {
            int prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int[] vp = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);

            // Bind pyramid mip 0 as color attachment
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFboId);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, pyramidTextureId, 0);
            GL11.glViewport(0, 0, viewportWidth, viewportHeight);
            GL11.glDrawBuffer(GL_COLOR_ATTACHMENT0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // Bind main depth texture for sampling
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL_TEXTURE_2D, sourceDepthId);

            // Draw fullscreen quad — shader reads depth, outputs to R32F
            org.lwjgl.opengl.GL20.glUseProgram(copyProgramId);
            int depthLoc = org.lwjgl.opengl.GL20.glGetUniformLocation(copyProgramId, "u_depth");
            int texelLoc = org.lwjgl.opengl.GL20.glGetUniformLocation(copyProgramId, "u_texelSize");
            org.lwjgl.opengl.GL20.glUniform1i(depthLoc, 0);
            org.lwjgl.opengl.GL20.glUniform2f(texelLoc,
                    1.0f / viewportWidth, 1.0f / viewportHeight);

            GL30.glBindVertexArray(copyVaoId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            GL30.glBindVertexArray(0);

            org.lwjgl.opengl.GL20.glUseProgram(0);

            // Restore state
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevFbo);
            GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
            GL11.glBindTexture(GL_TEXTURE_2D, 0);
        } catch (Throwable t) {
            LOGGER.warn("[Amdium] Hi-Z depth copy pass failed: {}", t.getMessage());
            return;
        }

        // 2. Построить mip-цепочку compute-шейдером.
        GL20.glUseProgram(buildProgramId);

        GL42.glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT);

        for (int level = 0; level < currentLevels - 1; level++) {
            int srcW = Math.max(1, currentWidth  >> level);
            int srcH = Math.max(1, currentHeight >> level);
            int dstW = Math.max(1, srcW >> 1);
            int dstH = Math.max(1, srcH >> 1);

            GL42.glBindImageTexture(0, pyramidTextureId, level,      false, 0, GL_READ_ONLY,  GL_R32F);
            GL42.glBindImageTexture(1, pyramidTextureId, level + 1,  false, 0, GL_WRITE_ONLY, GL_R32F);

            int groupsX = (dstW + 7) / 8;
            int groupsY = (dstH + 7) / 8;
            GL43.glDispatchCompute(groupsX, groupsY, 1);

            GL42.glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }

        GL20.glUseProgram(0);

        GL42.glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    public void bindAsSampler(int unit) {
        if (!initialized || pyramidTextureId == -1) return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL_TEXTURE_2D, pyramidTextureId);
    }

    public void unbind(int unit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL_TEXTURE_2D, 0);
    }

    public int getTextureId() { return pyramidTextureId; }
    public int getWidth()     { return currentWidth; }
    public int getHeight()    { return currentHeight; }
    public int getLevels()    { return currentLevels; }

    public void destroy() {
        if (copyProgramId != -1) { org.lwjgl.opengl.GL20.glDeleteProgram(copyProgramId); copyProgramId = -1; }
        if (copyVaoId     != -1) { GL30.glDeleteVertexArrays(copyVaoId); copyVaoId = -1; }
        if (copyFboId     != -1) { GL30.glDeleteFramebuffers(copyFboId); copyFboId = -1; }
        if (buildProgramId != -1) { GL20.glDeleteProgram(buildProgramId); buildProgramId = -1; }
        if (buildShaderId  != -1) { GL20.glDeleteShader(buildShaderId);  buildShaderId  = -1; }
        if (pyramidTextureId != -1) { GL11.glDeleteTextures(pyramidTextureId); pyramidTextureId = -1; }
        initialized = false;
    }

    private static int getMainFboDepthTextureId(Minecraft mc) {
        try {
            var rt = mc.getMainRenderTarget();
            if (rt == null) return -1;
            return rt.getDepthTextureId();
        } catch (Throwable t) {
            LOGGER.warn("[Amdium] getMainFboDepthTextureId failed: {}", t.getMessage());
            return -1;
        }
    }

    private int compileShader(int type, String path) throws Exception {
        String source = loadShaderSource(path);
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile error (" + path + "):\n" + log);
        }
        return shader;
    }

    private int compileShaderInline(int type, String source) {
        int shader = org.lwjgl.opengl.GL20.glCreateShader(type);
        org.lwjgl.opengl.GL20.glShaderSource(shader, source);
        org.lwjgl.opengl.GL20.glCompileShader(shader);
        if (org.lwjgl.opengl.GL20.glGetShaderi(shader, org.lwjgl.opengl.GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = org.lwjgl.opengl.GL20.glGetShaderInfoLog(shader);
            org.lwjgl.opengl.GL20.glDeleteShader(shader);
            throw new RuntimeException("Inline shader compile error:\n" + log);
        }
        return shader;
    }

    private String loadShaderSource(String path) throws Exception {
        try (InputStream is = HiZDepthPyramid.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Shader not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // Внутренний GL20 прокси
    private static class GL20 {
        static final int GL_VERTEX_SHADER   = 0x8B31;
        static final int GL_FRAGMENT_SHADER = 0x8B30;
        static final int GL_COMPILE_STATUS  = 0x8B81;
        static final int GL_LINK_STATUS     = 0x8B82;

        static int  glCreateShader(int t)          { return org.lwjgl.opengl.GL20.glCreateShader(t); }
        static void glShaderSource(int s, CharSequence src) { org.lwjgl.opengl.GL20.glShaderSource(s, src); }
        static void glCompileShader(int s)         { org.lwjgl.opengl.GL20.glCompileShader(s); }
        static int  glGetShaderi(int s, int p)     { return org.lwjgl.opengl.GL20.glGetShaderi(s, p); }
        static String glGetShaderInfoLog(int s)    { return org.lwjgl.opengl.GL20.glGetShaderInfoLog(s); }
        static void glDeleteShader(int s)          { org.lwjgl.opengl.GL20.glDeleteShader(s); }

        static int  glCreateProgram()              { return org.lwjgl.opengl.GL20.glCreateProgram(); }
        static void glAttachShader(int p, int s)   { org.lwjgl.opengl.GL20.glAttachShader(p, s); }
        static void glLinkProgram(int p)           { org.lwjgl.opengl.GL20.glLinkProgram(p); }
        static int  glGetProgrami(int p, int i)    { return org.lwjgl.opengl.GL20.glGetProgrami(p, i); }
        static String glGetProgramInfoLog(int p)   { return org.lwjgl.opengl.GL20.glGetProgramInfoLog(p); }
        static void glDeleteProgram(int p)         { org.lwjgl.opengl.GL20.glDeleteProgram(p); }
        static void glUseProgram(int p)            { org.lwjgl.opengl.GL20.glUseProgram(p); }
    }
}

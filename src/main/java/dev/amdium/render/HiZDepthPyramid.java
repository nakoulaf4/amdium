package dev.amdium.render;

import com.mojang.logging.LogUtils;
import dev.amdium.Amdium;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

public final class HiZDepthPyramid {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int GL_R32F            = 0x822E;
    private static final int GL_TEXTURE_2D      = GL11.GL_TEXTURE_2D;
    private static final int GL_TEXTURE_MIN_FILTER = GL11.GL_TEXTURE_MIN_FILTER;
    private static final int GL_TEXTURE_MAG_FILTER = GL11.GL_TEXTURE_MAG_FILTER;
    private static final int GL_READ_ONLY        = 0x88B8;
    private static final int GL_WRITE_ONLY       = 0x88B9;
    private static final int GL_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x20;
    private static final int GL_TEXTURE_FETCH_BARRIER_BIT       = 0x8;
    private static final int GL_TEXTURE_UPDATE_BARRIER_BIT      = 0x100;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

    private static final int BUILD_WG_X = 8;
    private static final int BUILD_WG_Y = 8;

    private int pyramidTextureId = -1;
    private int buildProgramId   = -1;
    private int buildShaderId    = -1;

    private int buildU_SrcWidth  = -1;
    private int buildU_SrcHeight = -1;
    private int buildU_BuildSecondMip = -1;

    private int copyProgramId = -1;
    private int copyVaoId     = -1;
    private int copyFboId     = -1;

    private int copyU_DepthLoc;
    private int copyU_TexelSizeLoc;

    private int currentWidth  = 0;
    private int currentHeight = 0;
    private int currentLevels = 0;

    private boolean initialized = false;

    public void init() {
        try {
            buildShaderId = compileShader(GL43.GL_COMPUTE_SHADER, "/assets/amdium/shaders/core/hiz_build.comp.glsl");
            buildProgramId = org.lwjgl.opengl.GL20.glCreateProgram();
            org.lwjgl.opengl.GL20.glAttachShader(buildProgramId, buildShaderId);
            org.lwjgl.opengl.GL20.glLinkProgram(buildProgramId);
            if (org.lwjgl.opengl.GL20.glGetProgrami(buildProgramId, org.lwjgl.opengl.GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = org.lwjgl.opengl.GL20.glGetProgramInfoLog(buildProgramId);
                throw new RuntimeException("Hi-Z build program link error:\n" + log);
            }
            org.lwjgl.opengl.GL20.glDeleteShader(buildShaderId);
            buildShaderId = -1;

            buildU_SrcWidth  = org.lwjgl.opengl.GL20.glGetUniformLocation(buildProgramId, "u_SrcWidth");
            buildU_SrcHeight = org.lwjgl.opengl.GL20.glGetUniformLocation(buildProgramId, "u_SrcHeight");
            buildU_BuildSecondMip = org.lwjgl.opengl.GL20.glGetUniformLocation(buildProgramId, "u_BuildSecondMip");

            initCopyPass();

            initialized = true;
            LOGGER.info("[Amdium] Hi-Z pyramid builder готов. program={}", buildProgramId);
        } catch (Exception e) {
            LOGGER.error("[Amdium] Hi-Z pyramid init failed: {}", e.getMessage(), e);
            initialized = false;
        }
    }

    private void initCopyPass() {
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

        String vs = "#version 150 core\n" + "in vec2 a_pos;\n" + "void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }\n";
        String fs = "#version 150 core\n" + "uniform sampler2D u_depth;\n" + "uniform vec2 u_texelSize;\n" + "out float fragDepth;\n" + "void main() {\n" + "    vec2 uv = gl_FragCoord.xy * u_texelSize;\n" + "    fragDepth = texture(u_depth, uv).r;\n" + "}\n";

        int vsId = compileShaderInline(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER, vs);
        int fsId = compileShaderInline(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER, fs);
        copyProgramId = org.lwjgl.opengl.GL20.glCreateProgram();
        org.lwjgl.opengl.GL20.glAttachShader(copyProgramId, vsId);
        org.lwjgl.opengl.GL20.glAttachShader(copyProgramId, fsId);
        org.lwjgl.opengl.GL20.glLinkProgram(copyProgramId);
        org.lwjgl.opengl.GL20.glDeleteShader(vsId);
        org.lwjgl.opengl.GL20.glDeleteShader(fsId);

        copyU_DepthLoc = org.lwjgl.opengl.GL20.glGetUniformLocation(copyProgramId, "u_depth");
        copyU_TexelSizeLoc = org.lwjgl.opengl.GL20.glGetUniformLocation(copyProgramId, "u_texelSize");

        copyFboId = GL30.glGenFramebuffers();
    }

    private void ensureSize(int width, int height) {
        if (width == currentWidth && height == currentHeight && pyramidTextureId != -1) return;
        if (pyramidTextureId != -1) GL11.glDeleteTextures(pyramidTextureId);

        int maxDim = Math.max(width, height);
        if (maxDim <= 0) return;
        int levels = Math.max(1, Math.min(32 - Integer.numberOfLeadingZeros(maxDim), 14));

        pyramidTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, pyramidTextureId);
        GL42.glTexStorage2D(GL_TEXTURE_2D, levels, GL_R32F, width, height);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL_TEXTURE_2D, 0);

        currentWidth = width; currentHeight = height; currentLevels = levels;
    }

    public void update() {
        if (!initialized) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null) return;

        int viewportWidth = mc.getWindow().getWidth();
        int viewportHeight = mc.getWindow().getHeight();
        ensureSize(viewportWidth, viewportHeight);

        int sourceDepthId = mc.getMainRenderTarget().getDepthTextureId();
        if (sourceDepthId <= 0) return;

        int prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFboId);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, pyramidTextureId, 0);
        GL11.glViewport(0, 0, viewportWidth, viewportHeight);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL_TEXTURE_2D, sourceDepthId);
        org.lwjgl.opengl.GL20.glUseProgram(copyProgramId);
        org.lwjgl.opengl.GL20.glUniform1i(copyU_DepthLoc, 0);
        org.lwjgl.opengl.GL20.glUniform2f(copyU_TexelSizeLoc, 1.0f / viewportWidth, 1.0f / viewportHeight);
        GL30.glBindVertexArray(copyVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        org.lwjgl.opengl.GL20.glUseProgram(0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevFbo);

        GL20.glUseProgram(buildProgramId);
        GL42.glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT);

        for (int level = 0; level < currentLevels - 1; level += 2) {
            int srcW = Math.max(1, currentWidth >> level);
            int srcH = Math.max(1, currentHeight >> level);
            boolean buildSecond = (level + 2 < currentLevels);

            GL42.glBindImageTexture(0, pyramidTextureId, level, false, 0, GL_READ_ONLY, GL_R32F);
            GL42.glBindImageTexture(1, pyramidTextureId, level + 1, false, 0, GL_WRITE_ONLY, GL_R32F);
            if (buildSecond) GL42.glBindImageTexture(2, pyramidTextureId, level + 2, false, 0, GL_WRITE_ONLY, GL_R32F);

            org.lwjgl.opengl.GL20.glUniform1i(buildU_SrcWidth, srcW);
            org.lwjgl.opengl.GL20.glUniform1i(buildU_SrcHeight, srcH);
            org.lwjgl.opengl.GL20.glUniform1i(buildU_BuildSecondMip, buildSecond ? 1 : 0);
            GL43.glDispatchCompute(( (Math.max(1, srcW >> 1)) + BUILD_WG_X - 1) / BUILD_WG_X,
                    ( (Math.max(1, srcH >> 1)) + BUILD_WG_Y - 1) / BUILD_WG_Y, 1);

            if (level + 2 < currentLevels - 1) GL42.glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        GL20.glUseProgram(0);
        GL42.glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    private int compileShader(int type, String path) throws Exception {
        String source = loadShaderSource(path);
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        return shader;
    }

    private int compileShaderInline(int type, String source) {
        int shader = org.lwjgl.opengl.GL20.glCreateShader(type);
        org.lwjgl.opengl.GL20.glShaderSource(shader, source);
        org.lwjgl.opengl.GL20.glCompileShader(shader);
        return shader;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getTextureId() {
        return pyramidTextureId;
    }

    public int getWidth() {
        return currentWidth;
    }

    public int getHeight() {
        return currentHeight;
    }

    public int getLevels() {
        return currentLevels;
    }

    public void bindAsSampler(int textureUnit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL_TEXTURE_2D, pyramidTextureId);
    }

    public void unbind(int textureUnit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL_TEXTURE_2D, 0);
    }

    public void destroy() {
        if (pyramidTextureId != -1) {
            GL11.glDeleteTextures(pyramidTextureId);
            pyramidTextureId = -1;
        }
        if (buildProgramId != -1) {
            GL20.glDeleteProgram(buildProgramId);
            buildProgramId = -1;
        }
        if (copyProgramId != -1) {
            GL20.glDeleteProgram(copyProgramId);
            copyProgramId = -1;
        }
        if (copyVaoId != -1) {
            GL30.glDeleteVertexArrays(copyVaoId);
            copyVaoId = -1;
        }
        if (copyFboId != -1) {
            GL30.glDeleteFramebuffers(copyFboId);
            copyFboId = -1;
        }
        currentWidth = 0;
        currentHeight = 0;
        currentLevels = 0;
        initialized = false;
    }

    private String loadShaderSource(String path) throws Exception {
        try (InputStream is = HiZDepthPyramid.class.getResourceAsStream(path)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static class GL20 {
        static void glAttachShader(int p, int s)   { org.lwjgl.opengl.GL20.glAttachShader(p, s); }
        static void glLinkProgram(int p)           { org.lwjgl.opengl.GL20.glLinkProgram(p); }
        static void glDeleteProgram(int p)         { org.lwjgl.opengl.GL20.glDeleteProgram(p); }
        static void glUseProgram(int p)            { org.lwjgl.opengl.GL20.glUseProgram(p); }
        static int glCreateShader(int t)           { return org.lwjgl.opengl.GL20.glCreateShader(t); }
        static void glShaderSource(int s, CharSequence src) { org.lwjgl.opengl.GL20.glShaderSource(s, src); }
        static void glCompileShader(int s)         { org.lwjgl.opengl.GL20.glCompileShader(s); }
        static int glGetShaderi(int s, int p)      { return org.lwjgl.opengl.GL20.glGetShaderi(s, p); }
    }
}
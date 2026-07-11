package dev.amdium.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Центральный координатор Amdium рендерера (v2.3).
 * / Central coordinator of the Amdium renderer (v2.3).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * v2.3 ОПТИМИЗАЦИЯ ПРОИЗВОДИТЕЛЬНОСТИ / v2.3 PERFORMANCE OPTIMIZATION
 * ─────────────────────────────────────────────────────────────────────────
 *
 * v2.2 ПРОБЛЕМЫ / v2.2 PROBLEMS:
 *   1. uploadOrigin вызывал memAllocFloat(4) + memFree() на КАЖДЫЙ upload
 *      чанк-слоя. При rebuild 100+ чанков за раз — 100+ heap allocs.
 *      / uploadOrigin called memAllocFloat(4) + memFree() on EVERY chunk-layer
 *      upload. When rebuilding 100+ chunks at once — 100+ heap allocs.
 *
 *   2. drawMDIWithShader вызывал Minecraft.getInstance().getTextureManager()
 *      .getTexture(LOCATION_BLOCKS).getId() на КАЖДЫЙ слой КАЖДЫЙ кадр —
 *      HashMap lookup + неявная проверка loaded status.
 *      / drawMDIWithShader called Minecraft.getInstance().getTextureManager()
 *      .getTexture(LOCATION_BLOCKS).getId() on EVERY layer EVERY frame —
 *      a HashMap lookup + an implicit loaded-status check.
 *
 *   3. Лишние glBindBuffer(target, 0) после каждого buffer operation.
 *      / Redundant glBindBuffer(target, 0) after every buffer operation.
 *
 * v2.3 ИСПРАВЛЕНИЯ / v2.3 FIXES:
 *   1. uploadOrigin использует MemoryStack (stack-local, без heap alloc).
 *      / uploadOrigin uses MemoryStack (stack-local, no heap alloc).
 *
 *   2. Texture ID block atlas кэшируется и обновляется раз в секунду.
 *      / Block atlas texture ID cached and refreshed once per second.
 *
 *   3. Лишние unbind'ы убраны.
 *      / Redundant unbinds removed.
 */
public class AmdiumRenderer {

    public static final AmdiumRenderer INSTANCE = new AmdiumRenderer();
    private AmdiumRenderer() {}

    private static final int MAX_CHUNKS = 16384;
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;

    private final AmdiumVertexPool vertexPool = new AmdiumVertexPool();
    private final MDIDrawCommandBuffer mdiBuffer = new MDIDrawCommandBuffer();
    private final AmdiumComputeCuller culler = new AmdiumComputeCuller();

    private boolean useComputeCulling = false;
    private boolean useIndirectCount = false;
    private boolean initialized = false;

    private int chunkProgramId = -1;
    private int chunkVaoId = -1;
    private int originsSSBOId = -1;

    private int u_ProjView, u_CameraPos, u_FogColor, u_FogStart, u_FogEnd;
    private int u_BlockAtlas, u_Lightmap;
    private int u_TextureScale;

    // v2.3: кэш block atlas texture ID (обновляется раз в секунду).
    // / v2.3: cached block atlas texture ID (refreshed once per second).
    private int cachedBlockAtlasTexId = -1;
    private long lastAtlasTexIdCheckMs = 0;
    private static final long ATLAS_TEX_CHECK_INTERVAL_MS = 1000L;

    // v2.3: preallocated origin buffer для uploadOrigin (без alloc/free).
    // / v2.3: preallocated origin buffer for uploadOrigin (no alloc/free).
    private final float[] originArray = new float[4];

    // Статистика / Statistics
    private int frameChunksTotal = 0;
    private int frameChunksDrawn = 0;

    public void init(boolean computeAvailable, boolean persistentMappingAvailable,
                     boolean indirectParametersAvailable) {
        this.useComputeCulling = computeAvailable && AmdiumConfig.ENABLE_COMPUTE_CULLING.get();
        this.useIndirectCount = indirectParametersAvailable && AmdiumConfig.ENABLE_INDIRECT_COUNT.get();

        boolean useStaging = persistentMappingAvailable && AmdiumConfig.ENABLE_PERSISTENT_MAPPING.get();
        vertexPool.init(useStaging);

        mdiBuffer.init(MAX_CHUNKS, indirectParametersAvailable);

        if (useComputeCulling) {
            culler.init(MAX_CHUNKS, indirectParametersAvailable);
            if (!culler.isReady()) {
                Amdium.LOGGER.warn("[Amdium] Compute culler не инициализировался, fallback на CPU culling");
                useComputeCulling = false;
            }
        }

        try {
            chunkProgramId = createChunkProgram();
            cacheChunkUniforms();
        } catch (Exception e) {
            Amdium.LOGGER.error("[Amdium] Ошибка загрузки chunk shader: {}", e.getMessage(), e);
            Amdium.active = false;
            return;
        }

        originsSSBOId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, originsSSBOId);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
                (long) MAX_CHUNKS * 16L, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        chunkVaoId = vertexPool.getVaoId();

        initialized = true;
        Amdium.LOGGER.info("[Amdium] Рендерер готов (v2.3). compute={}, indirectCount={}, staging={}",
                useComputeCulling, useIndirectCount, useStaging);
    }

    public void beginFrame() {
        if (!initialized) return;
        mdiBuffer.beginFrame();
        frameChunksTotal = 0;
        frameChunksDrawn = 0;
    }

    public void registerChunk(long packedPos, int layerIndex) {
        if (!initialized) return;
        ChunkMetadataStore.ChunkMeta meta = ChunkMetadataStore.get(packedPos);
        if (meta == null || !meta.ready) return;
        ChunkMetadataStore.LayerData ld = meta.layers[layerIndex];
        if (ld == null || ld.vertexCount == 0) return;

        frameChunksTotal++;

        int quadCount = ld.vertexCount / 4;
        int indexCount = quadCount * 6;
        int baseVertex = meta.slot * AmdiumVertexPool.MAX_VERTS_PER_CHUNK + ld.vertexOffsetInSlot;
        int baseInstance = meta.slot;

        mdiBuffer.addCommand(indexCount, baseVertex, baseInstance,
                meta.aabbMinX, meta.aabbMinY, meta.aabbMinZ,
                meta.aabbMaxX, meta.aabbMaxY, meta.aabbMaxZ,
                meta.originX, meta.originY, meta.originZ);
    }

    public void drawLayer(float[] projView,
                          float camX, float camY, float camZ,
                          float[] frustum,
                          float[] fogColor, float fogStart, float fogEnd,
                          int atlasWidth, int atlasHeight) {
        if (!initialized || mdiBuffer.getPendingCount() == 0) return;

        int drawCount = mdiBuffer.getPendingCount();

        if (useComputeCulling) {
            mdiBuffer.flushCPU();

            int visibleCount = culler.dispatch(
                    mdiBuffer.getChunkInfoSSBOId(),
                    mdiBuffer.getIndirectBufferId(),
                    drawCount,
                    projView, camX, camY, camZ,
                    fogStart, fogEnd);

            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, culler.getCompactedCommandsId());

            if (useIndirectCount) {
                GL15.glBindBuffer(0x8EE0, culler.getAtomicCounterId()); // GL_PARAMETER_BUFFER
                drawMDIWithShader(projView, camX, camY, camZ, fogColor, fogStart, fogEnd,
                        atlasWidth, atlasHeight, drawCount);
                GL15.glBindBuffer(0x8EE0, 0);
            } else {
                frameChunksDrawn = visibleCount;
                drawMDIWithShader(projView, camX, camY, camZ, fogColor, fogStart, fogEnd,
                        atlasWidth, atlasHeight, visibleCount);
            }
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        } else {
            int visible = mdiBuffer.flushWithCPUCulling(frustum);
            frameChunksDrawn = visible;

            if (useIndirectCount) {
                mdiBuffer.uploadParameterCount(visible);
            }

            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, mdiBuffer.getIndirectBufferId());
            drawMDIWithShader(projView, camX, camY, camZ, fogColor, fogStart, fogEnd,
                    atlasWidth, atlasHeight, visible);
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        }

        if (AmdiumConfig.DEBUG_LOG_FRAME_STATS.get()) {
            Amdium.LOGGER.info("[Amdium] Слой: total={} drawn={}", frameChunksTotal, frameChunksDrawn);
        }
    }

    private void drawMDIWithShader(float[] projView, float camX, float camY, float camZ,
                                    float[] fogColor, float fogStart, float fogEnd,
                                    int atlasWidth, int atlasHeight, int drawCount) {
        GL20.glUseProgram(chunkProgramId);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            buf.put(projView).flip();
            GL20.glUniformMatrix4fv(u_ProjView, false, buf);
        }
        GL20.glUniform3f(u_CameraPos, camX, camY, camZ);
        GL20.glUniform4f(u_FogColor, fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        GL20.glUniform1f(u_FogStart, fogStart);
        GL20.glUniform1f(u_FogEnd, fogEnd);
        GL20.glUniform2f(u_TextureScale, 1.0f / atlasWidth, 1.0f / atlasHeight);

        // v2.3: кэшируем block atlas texture ID (обновляем раз в секунду).
        // / v2.3: cache the block atlas texture ID (refresh once per second).
        int atlasTexId = amdium$getBlockAtlasTexId();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(atlasTexId);
        GL20.glUniform1i(u_BlockAtlas, 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        GL20.glUniform1i(u_Lightmap, 2);

        GL30.glBindVertexArray(chunkVaoId);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, vertexPool.getIboId());

        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, originsSSBOId);

        if (useIndirectCount) {
            GL46.glMultiDrawElementsIndirectCount(
                    GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, 0L,
                    0L, drawCount, MDIDrawCommandBuffer.COMMAND_STRIDE);
        } else {
            GL43.glMultiDrawElementsIndirect(
                    GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, 0L,
                    drawCount, MDIDrawCommandBuffer.COMMAND_STRIDE);
        }

        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(0);
    }

    /**
     * v2.3: возвращает кэшированный block atlas texture ID.
     * Обновляется не чаще раза в секунду.
     * / v2.3: returns the cached block atlas texture ID.
     * Refreshed no more than once per second.
     */
    private int amdium$getBlockAtlasTexId() {
        long now = System.currentTimeMillis();
        if (now - lastAtlasTexIdCheckMs > ATLAS_TEX_CHECK_INTERVAL_MS || cachedBlockAtlasTexId <= 0) {
            lastAtlasTexIdCheckMs = now;
            try {
                cachedBlockAtlasTexId = Minecraft.getInstance().getTextureManager()
                        .getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
            } catch (Throwable ignored) {
                // keep previous value
            }
        }
        return cachedBlockAtlasTexId;
    }

    public int uploadChunkLayer(long packedPos, int vanillaVboId, int layerIndex,
                                 java.nio.ByteBuffer vertexData) {
        if (!initialized) return -1;
        return vertexPool.uploadChunkLayer(packedPos, vanillaVboId, vertexData);
    }

    /**
     * v2.3: использует preallocated array + MemoryStack вместо alloc/free.
     * / v2.3: uses a preallocated array + MemoryStack instead of alloc/free.
     */
    public void uploadOrigin(int slot, float originX, float originY, float originZ) {
        if (!initialized || slot < 0) return;
        originArray[0] = originX;
        originArray[1] = originY;
        originArray[2] = originZ;
        originArray[3] = 0.0f;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(4);
            buf.put(originArray).flip();
            // v2.3: DSA — без bind/unbind, прямой write по ID.
            // / v2.3: DSA — no bind/unbind, direct write by ID.
            org.lwjgl.opengl.GL45.glNamedBufferSubData(originsSSBOId, (long) slot * 16L, buf);
        }
    }

    public void releaseChunk(long packedPos) {
        if (!initialized) return;
        vertexPool.freeChunk(packedPos);
        ChunkMetadataStore.remove(packedPos);
    }

    public void endFrame() {
        if (!initialized) return;
        vertexPool.endFrame();
    }

    public AmdiumVertexPool getVertexPool() { return vertexPool; }
    public boolean isInitialized() { return initialized; }
    public boolean isActive() { return initialized && Amdium.active; }

    public void destroy() {
        if (!initialized) return;
        culler.destroy();
        mdiBuffer.destroy();
        vertexPool.destroy();
        if (chunkProgramId != -1) GL20.glDeleteProgram(chunkProgramId);
        if (originsSSBOId != -1) GL15.glDeleteBuffers(originsSSBOId);
        initialized = false;
        Amdium.LOGGER.info("[Amdium] Рендерер уничтожен.");
    }

    // ----- Shader compilation ----- / ----- Компиляция шейдеров -----

    private int createChunkProgram() throws Exception {
        int vsh = compileShader(GL20.GL_VERTEX_SHADER,
                "/assets/amdium/shaders/core/chunk_vertex.vsh");
        int fsh = compileShader(GL20.GL_FRAGMENT_SHADER,
                "/assets/amdium/shaders/core/chunk_fragment.fsh");

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vsh);
        GL20.glAttachShader(program, fsh);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new RuntimeException("Chunk program link error:\n" + log);
        }
        GL20.glDeleteShader(vsh);
        GL20.glDeleteShader(fsh);
        return program;
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

    private void cacheChunkUniforms() {
        u_ProjView   = GL20.glGetUniformLocation(chunkProgramId, "u_ProjView");
        u_CameraPos  = GL20.glGetUniformLocation(chunkProgramId, "u_CameraPos");
        u_FogColor   = GL20.glGetUniformLocation(chunkProgramId, "u_FogColor");
        u_FogStart   = GL20.glGetUniformLocation(chunkProgramId, "u_FogStart");
        u_FogEnd     = GL20.glGetUniformLocation(chunkProgramId, "u_FogEnd");
        u_BlockAtlas = GL20.glGetUniformLocation(chunkProgramId, "u_BlockAtlas");
        u_Lightmap   = GL20.glGetUniformLocation(chunkProgramId, "u_Lightmap");
        u_TextureScale = GL20.glGetUniformLocation(chunkProgramId, "u_TextureScale");
    }

    private String loadShaderSource(String path) throws Exception {
        try (InputStream is = AmdiumRenderer.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Shader not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

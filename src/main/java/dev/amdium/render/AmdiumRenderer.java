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
 * Центральный координатор Amdium рендерера.
 * Central coordinator of the Amdium renderer.
 *
 * Оркестрация кадра:
 * Frame orchestration:
 *
 *   beginFrame()
 *     → (vanilla renderSectionLayer для каждой видимой секции)
 *     → (vanilla renderSectionLayer for each visible section)
 *         → VertexBufferMixin перехватывает drawChunkLayer
 *         → VertexBufferMixin intercepts drawChunkLayer
 *         → registerChunk(packedPos, layerIndex) добавляет MDI команду
 *         → registerChunk(packedPos, layerIndex) adds an MDI command
 *         → ci.cancel() отменяет vanilla draw
 *         → ci.cancel() cancels the vanilla draw
 *     → drawLayer(layerIndex) в TAIL renderChunkLayer
 *     → drawLayer(layerIndex) in TAIL renderChunkLayer
 *         → compute culling (если доступен)
 *         → compute culling (if available)
 *         → glMultiDrawElementsIndirectCount / glMultiDrawElementsIndirect
 *         → glMultiDrawElementsIndirectCount / glMultiDrawElementsIndirect
 *         → один draw call на весь слой
 *         → a single draw call for the whole layer
 *
 * Singleton.
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

    // Shader program для MDI draw (свой, поскольку vanilla шейдер несовместим с MDI layout)
    // Shader program for MDI draw (our own, since the vanilla shader is incompatible with the MDI layout)
    private int chunkProgramId = -1;
    // наш VAO (может шариться с vertexPool) / our VAO (may be shared with vertexPool)
    private int chunkVaoId = -1;
    // vec4[totalSlots] — origin каждого чанка / vec4[totalSlots] — origin of each chunk
    private int originsSSBOId = -1;

    // Uniform locations / Локации uniform-ов
    private int u_ProjView, u_CameraPos, u_FogColor, u_FogStart, u_FogEnd;
    private int u_BlockAtlas, u_Lightmap;
    private int u_TextureScale;

    // Статистика / Statistics
    private int frameChunksTotal = 0;
    private int frameChunksDrawn = 0;

    public void init(boolean computeAvailable, boolean persistentMappingAvailable,
                     boolean indirectParametersAvailable) {
        this.useComputeCulling = computeAvailable && AmdiumConfig.ENABLE_COMPUTE_CULLING.get();
        this.useIndirectCount = indirectParametersAvailable && AmdiumConfig.ENABLE_INDIRECT_COUNT.get();

        // Vertex pool с staging / Vertex pool with staging
        boolean useStaging = persistentMappingAvailable && AmdiumConfig.ENABLE_PERSISTENT_MAPPING.get();
        vertexPool.init(useStaging);

        // MDI buffer с indirect parameters / MDI buffer with indirect parameters
        mdiBuffer.init(MAX_CHUNKS, indirectParametersAvailable);

        // Compute culler (если доступен) / Compute culler (if available)
        if (useComputeCulling) {
            culler.init(MAX_CHUNKS, indirectParametersAvailable);
            if (!culler.isReady()) {
                Amdium.LOGGER.warn("[Amdium] Compute culler не инициализировался, fallback на CPU culling");
                useComputeCulling = false;
            }
        }

        // Загружаем chunk shader / Load the chunk shader
        try {
            chunkProgramId = createChunkProgram();
            cacheChunkUniforms();
        } catch (Exception e) {
            Amdium.LOGGER.error("[Amdium] Ошибка загрузки chunk shader: {}", e.getMessage(), e);
            // Без shader MDI не отрисует — fallback на полное отключение
            // Without a shader MDI cannot render — fall back to full disable
            Amdium.active = false;
            return;
        }

        // SSBO для origins (vec4 на slot) / SSBO for origins (vec4 per slot)
        originsSSBOId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, originsSSBOId);
        // vec4 = 16 байт, totalSlots = MAX_CHUNKS → 256 KB max
        // vec4 = 16 bytes, totalSlots = MAX_CHUNKS → 256 KB max
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
                (long) MAX_CHUNKS * 16L, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        chunkVaoId = vertexPool.getVaoId();

        initialized = true;
        Amdium.LOGGER.info("[Amdium] Рендерер готов. compute={}, indirectCount={}, staging={}",
                useComputeCulling, useIndirectCount, useStaging);
    }

    public void beginFrame() {
        if (!initialized) return;
        mdiBuffer.beginFrame();
        frameChunksTotal = 0;
        frameChunksDrawn = 0;
    }

    /**
     * Регистрирует чанк-слой для текущего кадра.
     * Registers a chunk layer for the current frame.
     * Вызывается из VertexBufferMixin при перехвате drawChunkLayer.
     * Called from VertexBufferMixin when intercepting drawChunkLayer.
     *
     * @param packedPos     SectionPos.asLong()
     * @param layerIndex    0=Solid, 1=CutoutMipped, 2=Cutout, 3=Translucent
     */
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
        // chunkId == slot → origins[slot] / chunkId == slot → origins[slot]
        int baseInstance = meta.slot;

        mdiBuffer.addCommand(indexCount, baseVertex, baseInstance,
                meta.aabbMinX, meta.aabbMinY, meta.aabbMinZ,
                meta.aabbMaxX, meta.aabbMaxY, meta.aabbMaxZ,
                meta.originX, meta.originY, meta.originZ);
    }

    /**
     * Финализирует и рисует слой через MDI.
     * Finalizes and renders the layer via MDI.
     *
     * @param projView    матрица projection*view (16 floats)
     *                    projection*view matrix (16 floats)
     * @param camX/Y/Z    позиция камеры
     *                    camera position
     * @param frustum     6*4 floats frustum planes (для CPU fallback)
     *                   6*4 floats frustum planes (for CPU fallback)
     * @param fogColor    цвет тумана (rgba)
     *                   fog color (rgba)
     * @param fogStart    начало тумана
     *                   fog start
     * @param fogEnd      конец тумана
     *                   fog end
     * @param atlasWidth  ширина texture atlas (для UV normalize)
     *                   texture atlas width (for UV normalize)
     * @param atlasHeight высота texture atlas
     *                   texture atlas height
     */
    public void drawLayer(float[] projView,
                          float camX, float camY, float camZ,
                          float[] frustum,
                          float[] fogColor, float fogStart, float fogEnd,
                          int atlasWidth, int atlasHeight) {
        if (!initialized || mdiBuffer.getPendingCount() == 0) return;

        int drawCount = mdiBuffer.getPendingCount();

        if (useComputeCulling) {
            // Загружаем все команды + AABB в SSBO (без culling)
            // Upload all commands + AABB into SSBO (no culling)
            mdiBuffer.flushCPU();

            // Compute shader: cull → compacted output + atomic count
            // Compute shader: куллинг → компактный output + atomic count
            int visibleCount = culler.dispatch(
                    mdiBuffer.getChunkInfoSSBOId(),
                    mdiBuffer.getIndirectBufferId(),
                    drawCount,
                    projView, camX, camY, camZ,
                    fogStart, fogEnd);

            // Bind компактный output как indirect buffer
            // Bind the compacted output as the indirect buffer
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, culler.getCompactedCommandsId());

            if (useIndirectCount) {
                // IndirectCount: GPU читает count из parameter buffer (atomicCounterId)
                // IndirectCount: GPU reads count from the parameter buffer (atomicCounterId)
                GL15.glBindBuffer(0x8EE0, culler.getAtomicCounterId()); // GL_PARAMETER_BUFFER
                drawMDIWithShader(projView, camX, camY, camZ, fogColor, fogStart, fogEnd,
                        atlasWidth, atlasHeight, drawCount);
                GL15.glBindBuffer(0x8EE0, 0);
            } else {
                // Fallback: draw с известным CPU count (visibleCount из readback)
                // Fallback: draw with a known CPU count (visibleCount from readback)
                frameChunksDrawn = visibleCount;
                drawMDIWithShader(projView, camX, camY, camZ, fogColor, fogStart, fogEnd,
                        atlasWidth, atlasHeight, visibleCount);
            }
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        } else {
            // CPU culling / Куллинг на CPU
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

    /** Привязывает наш shader + VAO + делает MDI draw. / Binds our shader + VAO and performs the MDI draw. */
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

        // Bind textures (block atlas slot 0, lightmap slot 2)
        // Привязка текстур (block atlas слот 0, lightmap слот 2)
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(Minecraft.getInstance().getTextureManager()
                .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getId());
        GL20.glUniform1i(u_BlockAtlas, 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        // LightTexture в 1.20.1: turnOnLightLayer() биндит текстуру на unit 2
        // LightTexture in 1.20.1: turnOnLightLayer() binds the texture to unit 2
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        GL20.glUniform1i(u_Lightmap, 2);

        GL30.glBindVertexArray(chunkVaoId);
        // IBO уже привязан к VAO при init, но на всякий случай:
        // IBO is already bound to the VAO at init, but just in case:
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, vertexPool.getIboId());

        // Bind origins SSBO (binding = 4) / Bind origins SSBO (binding = 4)
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, originsSSBOId);

        if (useIndirectCount) {
            // glMultiDrawElementsIndirectCount — ядро OpenGL 4.6 / ARB_indirect_parameters
            // glMultiDrawElementsIndirectCount — OpenGL 4.6 core / ARB_indirect_parameters
            // Сигнатура: (mode, type, indirectBufferOffset, parameterBufferOffset, maxDrawCount, stride)
            // Signature: (mode, type, indirectBufferOffset, parameterBufferOffset, maxDrawCount, stride)
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

    /** Загружает вершины чанк-слоя в pool. Вызывается из SectionRenderDispatcherMixin.
     *  Uploads a chunk layer's vertices into the pool. Called from SectionRenderDispatcherMixin. */
    public int uploadChunkLayer(long packedPos, int vanillaVboId, int layerIndex,
                                 java.nio.ByteBuffer vertexData) {
        if (!initialized) return -1;
        return vertexPool.uploadChunkLayer(packedPos, vanillaVboId, vertexData);
    }

    /** Сохраняет origin чанка в origins SSBO (vertex shader читает origins[slot]).
     *  Stores the chunk origin in the origins SSBO (the vertex shader reads origins[slot]). */
    public void uploadOrigin(int slot, float originX, float originY, float originZ) {
        if (!initialized || slot < 0) return;
        FloatBuffer originBuf = org.lwjgl.system.MemoryUtil.memAllocFloat(4);
        originBuf.put(originX).put(originY).put(originZ).put(0.0f).flip();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, originsSSBOId);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) slot * 16L, originBuf);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        org.lwjgl.system.MemoryUtil.memFree(originBuf);
    }

    /** Освобождает слот при выгрузке чанка. / Frees the slot when the chunk is unloaded. */
    public void releaseChunk(long packedPos) {
        if (!initialized) return;
        vertexPool.freeChunk(packedPos);
        ChunkMetadataStore.remove(packedPos);
    }

    /** В конце кадра:推进ить staging fence.
     *  At end of frame:推进 push the staging fence. */
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

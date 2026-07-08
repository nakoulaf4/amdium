package dev.amdium.render;

import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import dev.amdium.gl.PersistentStagingBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/**
 * Глобальный vertex pool для ВСЕХ чанков.
 * Global vertex pool for ALL chunks.
 *
 * Содержит:
 * Contains:
 *   1. Один большой VBO (vertexPool) — все вершины всех чанков
 *      One large VBO (vertexPool) — all vertices of all chunks
 *   2. Один общий IBO с повторяющимся quad-паттерном 0,1,2,0,2,3
 *      (Minecraft рендерит чанки как quads, поэтому IBO одинаков для всех)
 *      One shared IBO with the repeating quad pattern 0,1,2,0,2,3
 *      (Minecraft renders chunks as quads, so the IBO is identical for all)
 *   3. Persistent-mapped staging buffer для zero-copy uploads
 *      Persistent-mapped staging buffer for zero-copy uploads
 *   4. Per-frame fence ring
 *      Per-frame fence ring
 *
 * Каждая секция получает slot в VBO. slot * MAX_VERTS_PER_CHUNK = baseVertex.
 * Each section gets a slot in the VBO. slot * MAX_VERTS_PER_CHUNK = baseVertex.
 * baseVertex передаётся в DrawElementsIndirectCommand.
 * baseVertex is passed into DrawElementsIndirectCommand.
 *
 * IBO шарится всеми чанками: для каждой команды firstIndex=0,
 * count = quadCount * 6, baseVertex = slot * MAX_VERTS_PER_CHUNK.
 * The IBO is shared by all chunks: for each command firstIndex=0,
 * count = quadCount * 6, baseVertex = slot * MAX_VERTS_PER_CHUNK.
 *
 * ВАЖНО: MAX_VERTS_PER_CHUNK должно покрывать worst case.
 * IMPORTANT: MAX_VERTS_PER_CHUNK must cover the worst case.
 * Minecraft чанк 16×16×16, все стороны видны = ~8000 verts на слой.
 * A Minecraft chunk 16×16×16 with all sides visible = ~8000 verts per layer.
 * Берём с запасом 8192.
 * We take 8192 with a margin.
 */
public class AmdiumVertexPool {

    /**
     * Максимальное число вершин на чанк (ВСЕ 4 слоя вместе).
     * Maximum number of vertices per chunk (ALL 4 layers together).
     * Solid + CutoutMipped + Cutout + Translucent.
     * Worst case ~8K verts на слой → 32K на чанк.
     * Worst case ~8K verts per layer → 32K per chunk.
     * 512 MB pool / (32768 × 32 байта) = 512 чанков — хватает для RD 12.
     * 512 MB pool / (32768 × 32 bytes) = 512 chunks — enough for RD 12.
     * Для RD 32 нужно 1024+ MB pool.
     * For RD 32 a 1024+ MB pool is needed.
     */
    public static final int MAX_VERTS_PER_CHUNK = 32768;

    /** Vertex stride в байтах (vanilla DefaultVertexFormat.BLOCK: pos+color+uv+uv1+normal).
     *  Vertex stride in bytes (vanilla DefaultVertexFormat.BLOCK: pos+color+uv+uv1+normal). */
    public static final int VERTEX_STRIDE = 32;

    /** Размер slot'а в байтах. / Slot size in bytes. */
    public static final long SLOT_BYTES = (long) MAX_VERTS_PER_CHUNK * VERTEX_STRIDE;

    /** Quad = 4 verts → 6 indices. Стандартный паттерн Minecraft.
     *  Quad = 4 verts → 6 indices. Standard Minecraft pattern. */
    private static final int[] QUAD_INDICES = {0, 1, 2, 0, 2, 3};

    // GL handles / GL-хендлы
    private int vboId = -1;
    private int iboId = -1;
    private int vaoId = -1;

    // Pool размер / Pool size
    private long poolSizeBytes;
    private int totalSlots;
    private boolean initialized = false;

    // Staging для uploads / Staging for uploads
    private PersistentStagingBuffer staging;

    // CPU-side аллокатор слотов / CPU-side slot allocator
    private final Queue<Integer> freeSlots = new ArrayDeque<>();
    private final Map<Long, Integer> chunkToSlot = new HashMap<>();

    public AmdiumVertexPool() {}

    public void init(boolean usePersistentStaging) {
        this.poolSizeBytes = (long) AmdiumConfig.MDI_VERTEX_POOL_MB.get() * 1024 * 1024;
        this.totalSlots = (int) (poolSizeBytes / SLOT_BYTES);

        // --- VAO --- / --- VAO ---
        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        // --- VBO (большой, для всех чанков) --- / --- VBO (large, for all chunks) ---
        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        // Используем glBufferStorage (GL 4.4) если доступно, иначе glBufferData
        // Use glBufferStorage (GL 4.4) if available, otherwise glBufferData
        // Persistent mapping самого VBO не делаем — пишем через staging
        // We do not persistent-map the VBO itself — we write via staging
        if (GL.getCapabilities().OpenGL44) {
            int flags = GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT;
            GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, poolSizeBytes, flags);
        } else {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, poolSizeBytes, GL15.GL_DYNAMIC_DRAW);
        }

        // --- Общий IBO с quad-паттерном --- / --- Shared IBO with the quad pattern ---
        iboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, iboId);
        // 12288 индексов на slot / 12288 indices per slot
        int indexCount = MAX_VERTS_PER_CHUNK / 4 * 6;
        IntBuffer iboData = MemoryUtil.memAllocInt(indexCount);
        int written = 0;
        while (written < indexCount) {
            // Каждый quad = 6 индексов, но они ссылаются на 4 verts относительно baseVertex
            // Each quad = 6 indices, but they reference 4 verts relative to baseVertex
            // baseVertex в DrawElementsIndirectCommand смещает все индексы
            // baseVertex in DrawElementsIndirectCommand offsets all indices
            for (int idx : QUAD_INDICES) {
                if (written >= indexCount) break;
                iboData.put(idx);
                written++;
            }
        }
        iboData.flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, iboData, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(iboData);

        // --- Vertex attributes (DefaultVertexFormat.BLOCK) ---
        // --- Атрибуты вершин (DefaultVertexFormat.BLOCK) ---
        setupVertexAttributes();

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        // --- Staging buffer --- / --- Staging-буфер ---
        if (usePersistentStaging) {
            try {
                // 64 MB max / 64 МБ максимум
                long stagingSize = Math.min(poolSizeBytes / 8, 64L * 1024 * 1024);
                staging = new PersistentStagingBuffer(stagingSize, 3);
            } catch (Exception e) {
                Amdium.LOGGER.warn("[Amdium] Persistent staging недоступен ({}), fallback на glBufferSubData",
                        e.getMessage());
                staging = null;
            }
        }

        // Заполняем очередь свободных слотов / Fill the free-slot queue
        for (int i = 0; i < totalSlots; i++) {
            freeSlots.add(i);
        }

        initialized = true;
        Amdium.LOGGER.info("[Amdium] Vertex pool: {} MB, {} слотов по {} verts, staging={}, IBO общий",
                AmdiumConfig.MDI_VERTEX_POOL_MB.get(), totalSlots, MAX_VERTS_PER_CHUNK,
                staging != null ? "persistent" : "glBufferSubData");
    }

    /**
     * Настройка атрибутов — DefaultVertexFormat.BLOCK (vanilla).
     * Attribute setup — DefaultVertexFormat.BLOCK (vanilla).
     *   location 0: pos (3 floats)
     *   location 1: color (4 ubytes, normalized)
     *   location 2: uv0  (2 floats) — block atlas UV
     *   location 3: uv1  (2 shorts) — lightmap UV
     *   location 4: normal (4 bytes, normalized) — 3 bytes normal + 1 padding
     */
    private void setupVertexAttributes() {
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, VERTEX_STRIDE, 0L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 4, GL15.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE, 12L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(2, 2, GL15.GL_FLOAT, false, VERTEX_STRIDE, 16L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(2);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(3, 2, GL15.GL_SHORT, false, VERTEX_STRIDE, 24L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(3);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(4, 4, GL15.GL_BYTE, true, VERTEX_STRIDE, 28L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(4);
    }

    /**
     * Загружает vanilla vertex data в pool.
     * Uploads vanilla vertex data into the pool.
     *
     * @param packedPos   SectionPos.asLong()
     * @param vanillaVboId  VBO ID vanilla (для resolve в VertexBufferMixin)
     *                    vanilla VBO ID (for resolve in VertexBufferMixin)
     * @param vertexData  ByteBuffer с vanilla vertices (DefaultVertexFormat.BLOCK, 32 байта/vertex)
     *                   ByteBuffer with vanilla vertices (DefaultVertexFormat.BLOCK, 32 bytes/vertex)
     * @return slot или -1 если pool переполнен
     *        slot or -1 if the pool is full
     */
    public int uploadChunkLayer(long packedPos, int vanillaVboId, ByteBuffer vertexData) {
        int vertexCount = vertexData.remaining() / VERTEX_STRIDE;
        if (vertexCount > MAX_VERTS_PER_CHUNK) {
            // Chunk слишком большой — пропускаем (vanilla отрендерит сама)
            // Chunk is too large — skip (vanilla will render it itself)
            Amdium.LOGGER.warn("[Amdium] Чанк {} превышает лимит вершин ({} > {}), fallback на vanilla",
                    packedPos, vertexCount, MAX_VERTS_PER_CHUNK);
            return -1;
        }

        // Найти или выделить slot / Find or allocate a slot
        Integer slot = chunkToSlot.get(packedPos);
        if (slot == null) {
            slot = freeSlots.poll();
            if (slot == null) {
                Amdium.LOGGER.warn("[Amdium] Vertex pool исчерпан ({} чанков)", chunkToSlot.size());
                return -1;
            }
            chunkToSlot.put(packedPos, slot);
        }

        long destOffset = slot * SLOT_BYTES;
        int length = vertexCount * VERTEX_STRIDE;

        if (staging != null) {
            staging.upload(vboId, destOffset, vertexData, length);
        } else {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, destOffset,
                    (ByteBuffer) vertexData.limit(vertexData.position() + length));
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }

        return slot;
    }

    /** Возвращает slot в пул при выгрузке чанка. / Returns the slot to the pool when the chunk is unloaded. */
    public void freeChunk(long packedPos) {
        Integer slot = chunkToSlot.remove(packedPos);
        if (slot != null) {
            freeSlots.add(slot);
        }
    }

    /** Вызывается в конце кадра. / Called at the end of the frame. */
    public void endFrame() {
        if (staging != null) {
            staging.endFrame();
        }
    }

    public int getVaoId() { return vaoId; }
    public int getVboId() { return vboId; }
    public int getIboId() { return iboId; }
    public int getOccupiedSlots() { return totalSlots - freeSlots.size(); }
    public boolean isInitialized() { return initialized; }

    public void destroy() {
        if (staging != null) {
            staging.destroy();
            staging = null;
        }
        if (vboId != -1) { GL15.glDeleteBuffers(vboId); vboId = -1; }
        if (iboId != -1) { GL15.glDeleteBuffers(iboId); iboId = -1; }
        if (vaoId != -1) { GL30.glDeleteVertexArrays(vaoId); vaoId = -1; }
        chunkToSlot.clear();
        freeSlots.clear();
        initialized = false;
    }
}

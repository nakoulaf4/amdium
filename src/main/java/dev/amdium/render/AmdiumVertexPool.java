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
 * Глобальный vertex pool для всех чанков.
 *
 * Содержит:
 *   1. Один большой VBO — все вершины всех чанков
 *   2. Общий IBO с quad-паттерном 0,1,2,0,2,3 (Minecraft рендерит quads)
 *   3. Persistent-mapped staging buffer для zero-copy uploads
 *
 * Vertex stride = 36 bytes (DefaultVertexFormat.BLOCK в 1.20.1).
 * Каждая секция получает slot; slot * MAX_VERTS_PER_CHUNK = baseVertex.
 */
public class AmdiumVertexPool {

    /**
     * Макс. вершин на чанк (все 4 слоя). Worst case ~8K/слой → 32K/чанк.
     * 512 MB / (32768 × 36) = ~436 чанков (RD 12).
     */
    public static final int MAX_VERTS_PER_CHUNK = 32768;

    /** Vertex stride — vanilla DefaultVertexFormat.BLOCK в 1.20.1 = 36 bytes. */
    public static final int VERTEX_STRIDE = 36;

    /** Размер slot'а в байтах. */
    public static final long SLOT_BYTES = (long) MAX_VERTS_PER_CHUNK * VERTEX_STRIDE;

    /** Quad = 4 verts → 6 indices. */
    private static final int[] QUAD_INDICES = {0, 1, 2, 0, 2, 3};

    private int vboId = -1;
    private int iboId = -1;
    private int vaoId = -1;

    private long poolSizeBytes;
    private int totalSlots;
    private boolean initialized = false;

    private PersistentStagingBuffer staging;

    private final Queue<Integer> freeSlots = new ArrayDeque<>();
    private final Map<Long, Integer> chunkToSlot = new HashMap<>();

    public AmdiumVertexPool() {}

    public void init(boolean usePersistentStaging) {
        int poolMB = dev.amdium.Amdium.effectiveVertexPoolMB;
        this.poolSizeBytes = (long) poolMB * 1024 * 1024;
        this.totalSlots = (int) (poolSizeBytes / SLOT_BYTES);

        // VAO
        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        // VBO (пишем через staging, persistent mapping самого VBO не делаем)
        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        if (GL.getCapabilities().OpenGL44) {
            int flags = GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT;
            GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, poolSizeBytes, flags);
        } else {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, poolSizeBytes, GL15.GL_DYNAMIC_DRAW);
        }

        // Общий IBO с quad-паттерном
        iboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, iboId);
        int indexCount = MAX_VERTS_PER_CHUNK / 4 * 6;
        IntBuffer iboData = MemoryUtil.memAllocInt(indexCount);
        int written = 0;
        while (written < indexCount) {
            for (int idx : QUAD_INDICES) {
                if (written >= indexCount) break;
                iboData.put(idx);
                written++;
            }
        }
        iboData.flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, iboData, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(iboData);

        // Vertex attributes — DefaultVertexFormat.BLOCK (stride = 36)
        //   0: Position (3f)          offset  0
        //   1: Color    (4ub, norm)   offset 12
        //   2: UV0      (2f) atlas    offset 16
        //   3: UV1      (2s) overlay  offset 24
        //   4: UV2      (2s) lightmap offset 28
        //   5: Normal   (3b+pad, norm) offset 32
        setupVertexAttributes();

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        // Staging buffer (max 64 MB)
        if (usePersistentStaging) {
            try {
                long stagingSize = Math.min(poolSizeBytes / 8, 64L * 1024 * 1024);
                staging = new PersistentStagingBuffer(stagingSize, 3);
            } catch (Exception e) {
                Amdium.LOGGER.warn("[Amdium] Persistent staging недоступен ({}), fallback на glBufferSubData",
                        e.getMessage());
                staging = null;
            }
        }

        for (int i = 0; i < totalSlots; i++) {
            freeSlots.add(i);
        }

        initialized = true;
        Amdium.LOGGER.info("[Amdium] Vertex pool: {} MB, {} слотов по {} verts, staging={}",
                poolMB, totalSlots, MAX_VERTS_PER_CHUNK,
                staging != null ? "persistent" : "glBufferSubData");
    }

    private void setupVertexAttributes() {
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, VERTEX_STRIDE, 0L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 4, GL15.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE, 12L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(2, 2, GL15.GL_FLOAT, false, VERTEX_STRIDE, 16L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(2);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(3, 2, GL15.GL_SHORT, false, VERTEX_STRIDE, 24L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(3);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(4, 2, GL15.GL_SHORT, false, VERTEX_STRIDE, 28L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(4);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(5, 4, GL15.GL_BYTE, true, VERTEX_STRIDE, 32L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(5);
    }

    /**
     * Загружает vanilla vertex data в pool.
     * @return slot или -1 если pool переполнен
     */
    public int uploadChunkLayer(long packedPos, int vanillaVboId, ByteBuffer vertexData) {
        int vertexCount = vertexData.remaining() / VERTEX_STRIDE;
        if (vertexCount > MAX_VERTS_PER_CHUNK) {
            Amdium.LOGGER.warn("[Amdium] Чанк {} превышает лимит вершин ({} > {}), fallback на vanilla",
                    packedPos, vertexCount, MAX_VERTS_PER_CHUNK);
            return -1;
        }

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

    public void freeChunk(long packedPos) {
        Integer slot = chunkToSlot.remove(packedPos);
        if (slot != null) {
            freeSlots.add(slot);
        }
    }

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
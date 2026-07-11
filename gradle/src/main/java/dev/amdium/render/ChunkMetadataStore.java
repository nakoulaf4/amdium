package dev.amdium.render;

import dev.amdium.Amdium;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Хранилище метаданных чанков. Связывает upload-path (когда vanilla
 * заливает вершины) с draw-path (когда MDI нужно знать параметры команды).
 *
 * Storage of chunk metadata. Links the upload-path (when vanilla
 * uploads vertices) with the draw-path (when MDI needs command parameters).
 */
public final class ChunkMetadataStore {

    /** 4 слоя в vanilla Minecraft 1.20.1. / 4 layers in vanilla Minecraft 1.20.1. */
    public static final int LAYER_COUNT = 4;
    public static final int LAYER_SOLID = 0;
    public static final int LAYER_CUTOUT_MIPPED = 1;
    public static final int LAYER_CUTOUT = 2;
    public static final int LAYER_TRANSLUCENT = 3;

    public static final class ChunkMeta {
        public final long packedPos;
        public int slot;
        public final LayerData[] layers = new LayerData[LAYER_COUNT];

        public float aabbMinX, aabbMinY, aabbMinZ;
        public float aabbMaxX, aabbMaxY, aabbMaxZ;
        public float originX, originY, originZ;

        public boolean ready;

        public ChunkMeta(long packedPos) {
            this.packedPos = packedPos;
        }
    }

    public static final class LayerData {
        public int vertexCount;       // verts этого слоя / verts of this layer
        public int vertexOffsetInSlot; // смещение в вершинах от начала slot / offset in vertices from the slot start
        public int vanillaVboId;
        public boolean uploaded;
    }

    /** vboId → (packedPos + layerIndex) — для resolve в VertexBufferMixin.
     *  vboId → (packedPos + layerIndex) — for resolve in VertexBufferMixin. */
    public static final class VboLookup {
        public final long packedPos;
        public final int layerIndex;
        public VboLookup(long packedPos, int layerIndex) {
            this.packedPos = packedPos;
            this.layerIndex = layerIndex;
        }
    }

    private static final Long2ObjectOpenHashMap<ChunkMeta> BY_POS = new Long2ObjectOpenHashMap<>(2048);
    private static final Int2ObjectOpenHashMap<VboLookup> VBO_TO_INFO = new Int2ObjectOpenHashMap<>(8192);

    // Новая мапа для связи инстанса VertexBuffer с его метаданными чанка
    // New map to associate a VertexBuffer instance with its chunk metadata
    private static final java.util.Map<com.mojang.blaze3d.vertex.VertexBuffer, VboLookup> REFS_TO_INFO = new java.util.HashMap<>(8192);

    /** Связывает конкретный инстанс VertexBuffer с чанком и слоем.
     *  Links a specific VertexBuffer instance with a chunk and layer. */
    public static void linkBufferInstance(com.mojang.blaze3d.vertex.VertexBuffer buffer, long packedPos, int layerIndex) {
        REFS_TO_INFO.put(buffer, new VboLookup(packedPos, layerIndex));
    }

    /** Находит метаданные чанка по инстансу VertexBuffer.
     *  Finds chunk metadata by VertexBuffer instance. */
    public static VboLookup findByBufferInstance(com.mojang.blaze3d.vertex.VertexBuffer buffer) {
        return REFS_TO_INFO.get(buffer);
    }

    /** Регистрирует секцию с известным origin. / Registers a section with a known origin. */
    public static ChunkMeta register(long packedPos, float originX, float originY, float originZ) {
        ChunkMeta meta = BY_POS.get(packedPos);
        if (meta == null) {
            meta = new ChunkMeta(packedPos);
            BY_POS.put(packedPos, meta);
        }
        meta.originX = originX;
        meta.originY = originY;
        meta.originZ = originZ;
        meta.aabbMinX = originX;
        meta.aabbMinY = originY;
        meta.aabbMinZ = originZ;
        meta.aabbMaxX = originX + 16.0f;
        meta.aabbMaxY = originY + 16.0f;
        meta.aabbMaxZ = originZ + 16.0f;
        return meta;
    }

    /**
     * Загружает данные одного слоя в метаданные.
     * Loads one layer's data into the metadata.
     * Вычисляет vertexOffsetInSlot как сумму vertexCount предыдущих слоёв.
     * Computes vertexOffsetInSlot as the sum of vertexCount of previous layers.
     */
    public static void setLayerData(long packedPos, int layerIndex, int vanillaVboId,
                                    int vertexCount, int slot) {
        ChunkMeta meta = BY_POS.get(packedPos);
        if (meta == null) {
            Amdium.LOGGER.warn("[Amdium] setLayerData для незарегистрированного чанка {}", packedPos);
            return;
        }

        LayerData ld = meta.layers[layerIndex];
        if (ld == null) {
            ld = new LayerData();
            meta.layers[layerIndex] = ld;
        }

        // Если повторная загрузка — обновляем offset'ы для ВСЕХ слоёв
        // On re-upload — update offsets for ALL layers
        ld.vertexCount = vertexCount;
        ld.vanillaVboId = vanillaVboId;
        ld.uploaded = true;
        meta.slot = slot;

        // Пересчёт vertexOffsetInSlot для всех слоёв
        // Recompute vertexOffsetInSlot for all layers
        int cumulative = 0;
        for (int i = 0; i < LAYER_COUNT; i++) {
            LayerData l = meta.layers[i];
            if (l != null && l.uploaded) {
                l.vertexOffsetInSlot = cumulative;
                cumulative += l.vertexCount;
            }
        }

        // VboId → lookup / VboId → поиск
        VBO_TO_INFO.put(vanillaVboId, new VboLookup(packedPos, layerIndex));

        // Готов если хотя бы один слой загружен
        // Ready if at least one layer is uploaded
        meta.ready = true;
    }

    /** Находит layer для конкретного vanilla VBO ID.
     *  Finds the layer for a specific vanilla VBO ID. */
    public static VboLookup findByVboId(int vboId) {
        return VBO_TO_INFO.get(vboId);
    }

    public static ChunkMeta get(long packedPos) {
        return BY_POS.get(packedPos);
    }

    public static void remove(long packedPos) {
        ChunkMeta meta = BY_POS.remove(packedPos);
        if (meta != null) {
            for (LayerData ld : meta.layers) {
                if (ld != null && ld.vanillaVboId != 0) {
                    VBO_TO_INFO.remove(ld.vanillaVboId);
                }
            }
        }
    }

    /** Возвращает список всех готовых чанков. / Returns a list of all ready chunks. */
    public static List<ChunkMeta> snapshot() {
        List<ChunkMeta> out = new ArrayList<>(BY_POS.size());
        for (ChunkMeta meta : BY_POS.values()) {
            if (meta.ready) out.add(meta);
        }
        return out;
    }

    public static int size() { return BY_POS.size(); }

    public static void clear() {
        BY_POS.clear();
        VBO_TO_INFO.clear();
        // Очищаем ссылки на буферы при смене мира
        // Clear buffer references on world change
        REFS_TO_INFO.clear();
    }
}

package dev.amdium.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Хранилище per-section метаданных для interop-режима v2.2.
 * / Per-section metadata storage for interop mode v2.2.
 *
 * Проблема: Embedium собирает MultiDrawBatch из per-section (section, facing)
 * пар. Каждая запись имеет {count, firstIndex, baseVertex}, но НЕ имеет AABB
 * или chunk origin — это «голые» draw-команды.
 *
 * Чтобы делать per-command GPU culling, Amdium'у нужен per-command AABB.
 * Эта мапа даёт обратный lookup: (baseVertex → chunkPos + AABB).
 *
 * Заполняется через mixin в Embedium's RenderSection при upload'е геометрии.
 * Запрашивается в EmbediumInterop при перехвате multiDrawElementsBaseVertex:
 * для каждой записи в MultiDrawBatch ищем chunk по baseVertex.
 *
 * / Embedium builds MultiDrawBatch from per-section (section, facing) pairs.
 * Each entry has {count, firstIndex, baseVertex} but NO AABB or chunk origin.
 *
 * To do per-command GPU culling, Amdium needs per-command AABB.
 * This map provides reverse lookup: (baseVertex → chunkPos + AABB).
 *
 * Populated via a mixin into Embedium's RenderSection at geometry upload time.
 * Queried in EmbediumInterop when intercepting multiDrawElementsBaseVertex:
 * for each entry in MultiDrawBatch we look up the chunk by baseVertex.
 */
public final class PerCommandMetadata {

    public static final class SectionInfo {
        public final long packedPos;        // SectionPos.asLong()
        public final float originX, originY, originZ;
        public final float aabbMinX, aabbMinY, aabbMinZ;
        public final float aabbMaxX, aabbMaxY, aabbMaxZ;

        public SectionInfo(long packedPos, float originX, float originY, float originZ) {
            this.packedPos = packedPos;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            // AABB чанк-секции 16×16×16, начинающаяся с origin.
            // / Chunk-section AABB 16×16×16 starting at origin.
            this.aabbMinX = originX;
            this.aabbMinY = originY;
            this.aabbMinZ = originZ;
            this.aabbMaxX = originX + 16.0f;
            this.aabbMaxY = originY + 16.0f;
            this.aabbMaxZ = originZ + 16.0f;
        }
    }

    // region origin + baseVertex → SectionInfo.
    // baseVertex is only unique inside an Embeddium region VBO, so a global
    // baseVertex map aliases sections from different regions and causes
    // incorrect culling.
    private static final Long2ObjectOpenHashMap<SectionInfo> BY_REGION_AND_BASE_VERTEX =
            new Long2ObjectOpenHashMap<>(8192);

    /** Регистрирует (или обновляет) секцию.
     *  Registers (or updates) a section. */
    public static void register(int baseVertex, long packedPos,
                                 float originX, float originY, float originZ) {
        BY_REGION_AND_BASE_VERTEX.put(key(regionOrigin(originX), regionOriginY(originY), regionOrigin(originZ), baseVertex),
                new SectionInfo(packedPos, originX, originY, originZ));
    }

    /** Находит секцию по region origin + baseVertex (из MultiDrawBatch). */
    public static SectionInfo findByBaseVertex(int regionOriginX, int regionOriginY, int regionOriginZ, int baseVertex) {
        return BY_REGION_AND_BASE_VERTEX.get(key(regionOriginX, regionOriginY, regionOriginZ, baseVertex));
    }

    /** Удаляет секцию (при выгрузке чанка). */
    public static void remove(int baseVertex, float originX, float originY, float originZ) {
        BY_REGION_AND_BASE_VERTEX.remove(key(regionOrigin(originX), regionOriginY(originY), regionOrigin(originZ), baseVertex));
    }

    /** Полная очистка (при смене мира). */
    public static void clear() {
        BY_REGION_AND_BASE_VERTEX.clear();
    }

    public static int size() {
        return BY_REGION_AND_BASE_VERTEX.size();
    }

    private static int regionOrigin(float sectionOrigin) {
        return Math.floorDiv((int) sectionOrigin, 128) * 128;
    }

    private static int regionOriginY(float sectionOriginY) {
        return Math.floorDiv((int) sectionOriginY, 64) * 64;
    }

    private static long key(int regionOriginX, int regionOriginY, int regionOriginZ, int baseVertex) {
        long h = Integer.toUnsignedLong(baseVertex);
        h = (h * 0x9E3779B97F4A7C15L) ^ Integer.toUnsignedLong(regionOriginX);
        h = (h * 0x9E3779B97F4A7C15L) ^ Integer.toUnsignedLong(regionOriginY);
        h = (h * 0x9E3779B97F4A7C15L) ^ Integer.toUnsignedLong(regionOriginZ);
        return h;
    }
}

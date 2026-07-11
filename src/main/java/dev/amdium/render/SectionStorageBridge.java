package dev.amdium.render;

import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;

import java.util.IdentityHashMap;

public class SectionStorageBridge {
    private static final IdentityHashMap<SectionRenderDataStorage, RenderRegion> parentRegions = new IdentityHashMap<>();

    public static void setParentRegion(SectionRenderDataStorage storage, RenderRegion region) {
        if (region != null) {
            parentRegions.put(storage, region);
        }
    }

    public static RenderRegion getParentRegion(SectionRenderDataStorage storage) {
        return parentRegions.get(storage);
    }

    public static void clearParentRegions() {
        parentRegions.clear();
    }
}
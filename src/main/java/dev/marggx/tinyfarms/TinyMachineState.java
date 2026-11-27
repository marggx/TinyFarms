package dev.marggx.tinyfarms;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Transient per-machine runtime state. It lives and dies with the machine's block entity. */
public final class TinyMachineState implements Component<ChunkStore> {
    private static ComponentType<ChunkStore, TinyMachineState> componentType;

    int tier = -1;
    @Nullable Ref<EntityStore> displayRef;
    @Nullable String displayKey;
    int displayGeneration;
    float displayScale;

    public static ComponentType<ChunkStore, TinyMachineState> getComponentType() {
        return componentType;
    }

    static void setComponentType(@Nonnull ComponentType<ChunkStore, TinyMachineState> type) {
        componentType = type;
    }

    public int getTier() {
        return tier;
    }

    public void setTier(int tier) {
        this.tier = tier;
    }

    @Nonnull
    @Override
    public TinyMachineState clone() {
        var copy = new TinyMachineState();
        copy.tier = tier;
        return copy;
    }
}

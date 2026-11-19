package dev.marggx.tinyfarms;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import dev.marggx.tinyfarms.interaction.OpenTinyMachineInteraction;
import dev.marggx.tinyfarms.system.TinyMachineLifecycleSystem;
import dev.marggx.tinyfarms.system.TinyMachineTickSystem;

import javax.annotation.Nonnull;

public class TinyFarmsPlugin extends JavaPlugin {
    private static TinyFarmsPlugin INSTANCE;

    private ComponentType<ChunkStore, ProcessingBenchBlock> tinyMachineComponentType;

    public TinyFarmsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        INSTANCE = this;
    }

    public static TinyFarmsPlugin get() {
        return INSTANCE;
    }

    @Nonnull
    public ComponentType<ChunkStore, ProcessingBenchBlock> getTinyMachineComponentType() {
        return tinyMachineComponentType;
    }

    @Override
    protected void setup() {
        tinyMachineComponentType = getChunkStoreRegistry().registerComponent(ProcessingBenchBlock.class, "TinyMachineBlock", ProcessingBenchBlock.CODEC);
        var benchBlockComponentType = BenchBlock.getComponentType();
        getChunkStoreRegistry().registerSystem(new TinyMachineTickSystem(tinyMachineComponentType, benchBlockComponentType));
        getChunkStoreRegistry().registerSystem(new TinyMachineLifecycleSystem(tinyMachineComponentType, benchBlockComponentType));
        getCodecRegistry(Interaction.CODEC).register("OpenTinyMachine", OpenTinyMachineInteraction.class, OpenTinyMachineInteraction.CODEC);
    }

    @Override
    protected void start() {}
}

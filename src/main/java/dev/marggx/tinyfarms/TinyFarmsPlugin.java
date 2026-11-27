package dev.marggx.tinyfarms;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import dev.marggx.tinyfarms.interaction.OpenTinyMachineInteraction;
import dev.marggx.tinyfarms.process.TinyFarmProcess;
import dev.marggx.tinyfarms.process.TinyProcesses;
import dev.marggx.tinyfarms.system.TinyMachineLifecycleSystem;
import dev.marggx.tinyfarms.system.TinyMachineTickSystem;

import javax.annotation.Nonnull;

public final class TinyFarmsPlugin extends JavaPlugin {
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
        TinyMachineState.setComponentType(getChunkStoreRegistry().registerComponent(TinyMachineState.class, TinyMachineState::new));
        getAssetRegistry().register(HytaleAssetStore.builder(TinyFarmProcess.class, new DefaultAssetMap<>())
            .setPath("TinyFarms/Processes")
            .setCodec(TinyFarmProcess.CODEC)
            .setKeyFunction(TinyFarmProcess::getId)
            .loadsAfter(Item.class, BlockType.class, ItemDropList.class, CraftingRecipe.class)
            .build());
        getEventRegistry().register(LoadAssetEvent.PRIORITY_LOAD_LATE, LoadAssetEvent.class, event -> {
            var errors = TinyProcesses.validate();
            if (!errors.isEmpty()) {
                errors.forEach(error -> getLogger().atSevere().log("TinyFarm process: %s", error));
                event.failed(true, "TinyFarms has " + errors.size() + " invalid process definition(s), see log");
            }
        });
        getChunkStoreRegistry().registerSystem(new TinyMachineTickSystem(tinyMachineComponentType, BenchBlock.getComponentType()));
        getChunkStoreRegistry().registerSystem(new TinyMachineLifecycleSystem(tinyMachineComponentType, BenchBlock.getComponentType()));
        getCodecRegistry(Interaction.CODEC).register("OpenTinyMachine", OpenTinyMachineInteraction.class, OpenTinyMachineInteraction.CODEC);
    }
}

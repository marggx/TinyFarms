package dev.marggx.tinyfarms.system;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.marggx.tinyfarms.TinyMachineDisplays;
import dev.marggx.tinyfarms.TinyMachines;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Lifecycle for the durability-fueled machine block.
 *
 * <p>Mirrors {@code BenchSystems.ProcessingBenchLifecycle}, except the saved
 * {@code lastTickGameTime} is suppressed around {@code setupSlots} so the offline
 * batch-processing catch-up (which would consume the sapling and tool items via
 * {@code advanceProcessing}) never runs. The offline elapsed time is instead handled
 * by the tick system using the durability model.
 */
public class TinyMachineLifecycleSystem extends RefSystem<ChunkStore> {
    private final ComponentType<ChunkStore, ProcessingBenchBlock> componentType;
    private final ComponentType<ChunkStore, BenchBlock> benchBlockComponentType;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockStateInfoComponentType = BlockModule.BlockStateInfo.getComponentType();

    public TinyMachineLifecycleSystem(@Nonnull ComponentType<ChunkStore, ProcessingBenchBlock> componentType,
                                      @Nonnull ComponentType<ChunkStore, BenchBlock> benchBlockComponentType) {
        this.componentType = componentType;
        this.benchBlockComponentType = benchBlockComponentType;
    }

    @Override
    public com.hypixel.hytale.component.query.Query<ChunkStore> getQuery() {
        return componentType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        var machine = commandBuffer.getComponent(ref, componentType);
        var benchBlock = commandBuffer.getComponent(ref, benchBlockComponentType);
        var blockStateInfo = commandBuffer.getComponent(ref, blockStateInfoComponentType);
        if (machine == null || benchBlock == null || blockStateInfo == null) return;

        var sectionRef = blockStateInfo.getSectionRef();
        if (!sectionRef.isValid()) return;

        var section = commandBuffer.getComponent(sectionRef, ChunkSection.getComponentType());
        if (section == null) return;
        var blockSection = commandBuffer.getComponent(sectionRef, BlockSection.getComponentType());
        if (blockSection == null) return;

        int blockIndex = blockStateInfo.getIndex();
        int blockX = ChunkUtil.worldCoordFromLocalCoord(section.getX(), ChunkUtil.xFromIndex(blockIndex));
        int blockY = ChunkUtil.worldCoordFromLocalCoord(section.getY(), ChunkUtil.yFromIndex(blockIndex));
        int blockZ = ChunkUtil.worldCoordFromLocalCoord(section.getZ(), ChunkUtil.zFromIndex(blockIndex));

        var blockId = blockSection.get(ChunkUtil.xFromIndex(blockIndex), ChunkUtil.yFromIndex(blockIndex), ChunkUtil.zFromIndex(blockIndex));
        var blockType = BlockType.getAssetMap().getAsset(blockId);
        if (blockType == null) return;
        var rotationIndex = blockSection.getRotationIndex(ChunkUtil.xFromIndex(blockIndex), ChunkUtil.yFromIndex(blockIndex), ChunkUtil.zFromIndex(blockIndex));

        if (!machine.initializeBenchConfig(blockType)) return;

        var world = commandBuffer.getExternalData().getWorld();

        @Nullable var savedLastTick = machine.getLastTickGameTime();
        machine.setLastTickGameTime(null);
        try {
            machine.setupSlots(world, benchBlock, blockStateInfo, blockX, blockY, blockZ, blockType, rotationIndex);
        } finally {
            machine.setLastTickGameTime(savedLastTick);
        }

        TinyMachines.applyFuelFilter(machine);
        TinyMachines.trackTier(new Vector3i(blockX, blockY, blockZ), benchBlock.getTierLevel());
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        if (reason == RemoveReason.UNLOAD) {
            var blockStateInfo = commandBuffer.getComponent(ref, blockStateInfoComponentType);
            if (blockStateInfo != null) blockStateInfo.markNeedsSaving();
            return;
        }

        var benchBlock = commandBuffer.getComponent(ref, benchBlockComponentType);
        if (benchBlock != null) WindowManager.closeAndRemoveAll(benchBlock.getWindows());

        var machine = commandBuffer.getComponent(ref, componentType);
        if (machine == null) return;

        var blockStateInfo = commandBuffer.getComponent(ref, blockStateInfoComponentType);
        if (blockStateInfo == null) return;

        var sectionRef = blockStateInfo.getSectionRef();
        if (!sectionRef.isValid()) return;

        var section = commandBuffer.getComponent(sectionRef, ChunkSection.getComponentType());
        if (section == null) return;

        int blockIndex = blockStateInfo.getIndex();
        int blockX = ChunkUtil.worldCoordFromLocalCoord(section.getX(), ChunkUtil.xFromIndex(blockIndex));
        int blockY = ChunkUtil.worldCoordFromLocalCoord(section.getY(), ChunkUtil.yFromIndex(blockIndex));
        int blockZ = ChunkUtil.worldCoordFromLocalCoord(section.getZ(), ChunkUtil.zFromIndex(blockIndex));

        // Stop showing the input preview when the machine is gone (chunk reload re-spawns it)
        TinyMachineDisplays.despawn(commandBuffer.getExternalData().getWorld(), blockX, blockY, blockZ);

        var combinedItemContainer = machine.getItemContainer();
        if (combinedItemContainer != null) {
            final List<ItemStack> itemStacks = combinedItemContainer.dropAllItemStacks();

            var world = commandBuffer.getExternalData().getWorld();
            final var entityStore = world.getEntityStore().getStore();
            final var dropPosition = new Vector3d(blockX + 0.5, blockY, blockZ + 0.5);
            final Holder<EntityStore>[] itemEntityHolders = ItemComponent.generateItemDrops(entityStore, itemStacks, dropPosition, Rotation3f.IDENTITY);
            if (itemEntityHolders.length > 0) {
                world.execute(() -> entityStore.addEntities(itemEntityHolders, AddReason.SPAWN));
            }
        }
    }
}
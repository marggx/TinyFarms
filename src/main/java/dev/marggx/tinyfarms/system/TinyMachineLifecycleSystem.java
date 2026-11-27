package dev.marggx.tinyfarms.system;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
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
import dev.marggx.tinyfarms.TinyMachineState;
import dev.marggx.tinyfarms.TinyMachines;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Mirrors {@code BenchSystems.ProcessingBenchLifecycle}, but sets the slots up through
 * {@link TinyMachines#setupSlots} so the vanilla offline catch-up never consumes items.
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
    public Query<ChunkStore> getQuery() {
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
        var blockSection = commandBuffer.getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null || blockSection == null) return;

        int blockIndex = blockStateInfo.getIndex();
        int localX = ChunkUtil.xFromIndex(blockIndex);
        int localY = ChunkUtil.yFromIndex(blockIndex);
        int localZ = ChunkUtil.zFromIndex(blockIndex);

        var blockType = BlockType.getAssetMap().getAsset(blockSection.get(localX, localY, localZ));
        if (blockType == null || !machine.initializeBenchConfig(blockType)) return;

        TinyMachines.setupSlots(machine, commandBuffer.getExternalData().getWorld(), benchBlock, blockStateInfo,
                ChunkUtil.worldCoordFromLocalCoord(section.getX(), localX),
                ChunkUtil.worldCoordFromLocalCoord(section.getY(), localY),
                ChunkUtil.worldCoordFromLocalCoord(section.getZ(), localZ),
                blockType, blockSection.getRotationIndex(localX, localY, localZ));

        commandBuffer.ensureAndGetComponent(ref, TinyMachineState.getComponentType()).setTier(benchBlock.getTierLevel());
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        var world = commandBuffer.getExternalData().getWorld();
        var state = commandBuffer.getComponent(ref, TinyMachineState.getComponentType());
        if (state != null) TinyMachineDisplays.despawn(world, state);

        var blockStateInfo = commandBuffer.getComponent(ref, blockStateInfoComponentType);
        if (reason == RemoveReason.UNLOAD) {
            if (blockStateInfo != null) blockStateInfo.markNeedsSaving();
            return;
        }

        var benchBlock = commandBuffer.getComponent(ref, benchBlockComponentType);
        if (benchBlock != null) WindowManager.closeAndRemoveAll(benchBlock.getWindows());

        var machine = commandBuffer.getComponent(ref, componentType);
        var combinedItemContainer = machine != null ? machine.getItemContainer() : null;
        if (combinedItemContainer == null || blockStateInfo == null) return;

        var sectionRef = blockStateInfo.getSectionRef();
        if (!sectionRef.isValid()) return;
        var section = commandBuffer.getComponent(sectionRef, ChunkSection.getComponentType());
        if (section == null) return;

        int blockIndex = blockStateInfo.getIndex();
        int blockX = ChunkUtil.worldCoordFromLocalCoord(section.getX(), ChunkUtil.xFromIndex(blockIndex));
        int blockY = ChunkUtil.worldCoordFromLocalCoord(section.getY(), ChunkUtil.yFromIndex(blockIndex));
        int blockZ = ChunkUtil.worldCoordFromLocalCoord(section.getZ(), ChunkUtil.zFromIndex(blockIndex));

        final List<ItemStack> itemStacks = combinedItemContainer.dropAllItemStacks();
        final var entityStore = world.getEntityStore().getStore();
        final var dropPosition = new Vector3d(blockX + 0.5, blockY, blockZ + 0.5);
        final Holder<EntityStore>[] itemEntityHolders = ItemComponent.generateItemDrops(entityStore, itemStacks, dropPosition, Rotation3f.IDENTITY);
        if (itemEntityHolders.length > 0) {
            world.execute(() -> entityStore.addEntities(itemEntityHolders, AddReason.SPAWN));
        }
    }
}

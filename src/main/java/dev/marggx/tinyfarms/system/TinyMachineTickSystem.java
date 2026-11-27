package dev.marggx.tinyfarms.system;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.ProcessingBench;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.inventory.container.TestRemoveItemSlotResult;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.marggx.tinyfarms.TinyMachineDisplays;
import dev.marggx.tinyfarms.TinyMachineState;
import dev.marggx.tinyfarms.TinyMachines;
import dev.marggx.tinyfarms.process.TinyFarmProcess;
import dev.marggx.tinyfarms.process.TinyProcesses;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors {@code BenchSystems.ProcessingBenchTick} so the vanilla processing bench window and
 * client behave normally, but a damageable tool in the fuel slot pays durability per cycle
 * instead of burning fuel, and the input only has to be present.
 */
public class TinyMachineTickSystem extends EntityTickingSystem<ChunkStore> {
    private final ComponentType<ChunkStore, ProcessingBenchBlock> componentType;
    private final ComponentType<ChunkStore, BenchBlock> benchBlockComponentType;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockStateInfoComponentType = BlockModule.BlockStateInfo.getComponentType();

    public TinyMachineTickSystem(@Nonnull ComponentType<ChunkStore, ProcessingBenchBlock> componentType,
                                 @Nonnull ComponentType<ChunkStore, BenchBlock> benchBlockComponentType) {
        this.componentType = componentType;
        this.benchBlockComponentType = benchBlockComponentType;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return componentType;
    }

    private record Ctx(ProcessingBenchBlock machine, BenchBlock benchBlock, BlockModule.BlockStateInfo blockStateInfo,
                       ProcessingBench bench, BlockType blockType, ChunkStore chunkStore, Ref<ChunkStore> sectionRef,
                       Store<EntityStore> entityStore, int x, int y, int z, int rotationIndex, Instant gameTime,
                       Map<UUID, BenchWindow> windows, String state) {

        boolean inState(String name) {
            return name.equals(state);
        }

        void setState(String name) {
            machine.setBlockInteractionState(name, blockType, chunkStore, sectionRef, x, y, z);
        }

        void play(int soundEventIndex) {
            machine.playSound(soundEventIndex, entityStore, blockType, rotationIndex, x, y, z);
        }

        /** Leaves a running or completed state with the matching sound. */
        void interrupt(boolean endSoundWhenCompleted) {
            if (inState(ProcessingBenchBlock.PROCESSING)) {
                setState(StateData.NULL_STATE_ID);
                play(bench.getFailedSoundEventIndex());
            } else if (inState(ProcessingBenchBlock.PROCESS_COMPLETED)) {
                setState(StateData.NULL_STATE_ID);
                play(endSoundWhenCompleted ? bench.getEndSoundEventIndex() : bench.getFailedSoundEventIndex());
            }
        }

        void halt(boolean resetProgress) {
            if (resetProgress) machine.setInputProgress(0);
            machine.setActive(false, benchBlock, blockStateInfo);
            machine.setLastTickGameTime(gameTime);
        }
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        var machine = archetypeChunk.getComponent(index, componentType);
        var benchBlock = archetypeChunk.getComponent(index, benchBlockComponentType);
        var blockStateInfo = archetypeChunk.getComponent(index, blockStateInfoComponentType);
        if (machine == null || benchBlock == null || blockStateInfo == null) return;

        var sectionRef = blockStateInfo.getSectionRef();
        if (!sectionRef.isValid()) return;
        var section = store.getComponent(sectionRef, ChunkSection.getComponentType());
        var blockSection = store.getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null || blockSection == null) return;

        int blockIndex = blockStateInfo.getIndex();
        int localX = ChunkUtil.xFromIndex(blockIndex);
        int localY = ChunkUtil.yFromIndex(blockIndex);
        int localZ = ChunkUtil.zFromIndex(blockIndex);
        var blockType = BlockType.getAssetMap().getAsset(blockSection.get(localX, localY, localZ));
        if (blockType == null) return;

        // BlockOperations.setBlock creates BlockPhysics through the store, which throws mid-tick
        if (blockType.hasSupport() && store.getComponent(sectionRef, BlockPhysics.getComponentType()) == null) {
            commandBuffer.ensureComponent(sectionRef, BlockPhysics.getComponentType());
            return;
        }

        var machineState = archetypeChunk.getComponent(index, TinyMachineState.getComponentType());
        if (machineState == null) {
            commandBuffer.ensureAndGetComponent(archetypeChunk.getReferenceTo(index), TinyMachineState.getComponentType());
            return;
        }

        int blockX = ChunkUtil.worldCoordFromLocalCoord(section.getX(), localX);
        int blockY = ChunkUtil.worldCoordFromLocalCoord(section.getY(), localY);
        int blockZ = ChunkUtil.worldCoordFromLocalCoord(section.getZ(), localZ);
        int rotationIndex = blockSection.getRotationIndex(localX, localY, localZ);

        var chunkStore = store.getExternalData();
        var world = chunkStore.getWorld();
        var entityStore = world.getEntityStore().getStore();

        // Vanilla upgrades only re-setup the vanilla component type; keep our machine in sync
        int tierLevel = benchBlock.getTierLevel();
        if (machineState.getTier() != tierLevel) {
            TinyMachines.setupSlots(machine, world, benchBlock, blockStateInfo, blockX, blockY, blockZ, blockType, rotationIndex);
            machineState.setTier(tierLevel);
        }

        var processingBench = machine.getProcessingBench();
        if (processingBench == null) return;

        final Instant currentGameTime = entityStore.getResource(WorldTimeResource.getResourceType()).getGameTime();
        final var lastTick = machine.getLastTickGameTime();
        final float effectiveDt;
        if (lastTick == null || currentGameTime == null || currentGameTime.equals(lastTick)) {
            effectiveDt = 0;
        } else {
            float gameElapsedSeconds = Math.max(0, currentGameTime.toEpochMilli() - lastTick.toEpochMilli()) / 1000f;
            effectiveDt = (float) (gameElapsedSeconds / WorldTimeResource.getSecondsPerTick(world));
        }

        var ctx = new Ctx(machine, benchBlock, blockStateInfo, processingBench, blockType, chunkStore, sectionRef, entityStore,
                blockX, blockY, blockZ, rotationIndex, currentGameTime, benchBlock.getWindows(), blockType.getCurrentInteractionState());

        machine.getProcessingSlots().clear();
        machine.checkForRecipeUpdate(benchBlock);
        var process = TinyProcesses.of(machine.getRecipe());
        var recipe = process != null ? machine.getRecipe() : null;

        float recipeTime = recipe != null ? machine.getRecipeTimeSeconds(tierLevel) : 0f;
        float progress01 = recipeTime > 0 ? machine.getInputProgress() / recipeTime : 0f;
        TinyMachineDisplays.update(world, machineState, blockX, blockY, blockZ,
                machine.getInputContainer().getItemStack((short) 0), process, progress01);

        double toolCost = process != null ? process.durabilityCost(tierLevel) : 0;
        boolean hasFuelSlots = processingBench.getFuel() != null;
        short fuelSlot = hasFuelSlots ? TinyMachines.findFuelToolSlot(machine, toolCost) : -1;
        boolean canProcess = false;

        if (recipe != null) {
            if (!machine.getOutputContainer().canAddItemStacks(process.worstCase(), false, false)) {
                ctx.interrupt(true);
                ctx.halt(false);
                return;
            }

            List<TestRemoveItemSlotResult> result = machine.getInputContainer()
                    .getSlotMaterialsToRemove(process.inputMaterials(recipe), ProcessingBenchBlock.EXACT_RESOURCE_AMOUNTS, true);
            if (result.isEmpty()) {
                ctx.interrupt(true);
                machine.clearCurrentRecipe();
                ctx.halt(true);
                return;
            }
            for (TestRemoveItemSlotResult item : result) {
                machine.getProcessingSlots().addAll(item.getPickedSlots());
            }

            var fuelStack = fuelSlot >= 0 ? machine.getFuelContainer().getItemStack(fuelSlot) : null;
            if (fuelStack == null || !process.acceptsTool(fuelStack.getItemId())) {
                ctx.interrupt(false);
                ctx.halt(true);
                return;
            }

            machine.sendProcessingSlots(ctx.windows());
            canProcess = true;
        } else if (!hasFuelSlots) {
            ctx.interrupt(true);
            machine.setLastTickGameTime(currentGameTime);
            return;
        } else {
            boolean allowNoInputProcessing = processingBench.shouldAllowNoInputProcessing();
            if (!allowNoInputProcessing || ctx.inState(ProcessingBenchBlock.PROCESS_COMPLETED)) {
                ctx.interrupt(true);
                machine.sendProgress(0, ctx.windows());
                ctx.halt(false);
                return;
            }
            machine.sendProgress(0, ctx.windows());
        }

        int completions = 0;
        if (canProcess && fuelSlot >= 0) {
            machine.setActive(true, benchBlock, blockStateInfo);
            completions = processCycles(machine, tierLevel, recipe, process, effectiveDt, fuelSlot);
        } else if (machine.isActive()) {
            machine.setActive(false, benchBlock, blockStateInfo);
        }

        machine.getProcessingFuelSlots().clear();
        if (hasFuelSlots) {
            fuelSlot = TinyMachines.findFuelToolSlot(machine, toolCost);
            if (fuelSlot < 0) {
                ctx.interrupt(false);
                ctx.halt(false);
                return;
            }
            machine.getProcessingFuelSlots().add(fuelSlot);
            machine.sendProcessingFuelSlots(ctx.windows());
            TinyMachines.refreshFuelDisplay(machine, fuelSlot, ctx.windows());
        }

        if (completions > 0) {
            ctx.setState(ProcessingBenchBlock.PROCESS_COMPLETED);
            ctx.play(machine.getBench().getCompletedSoundEventIndex());
        } else {
            if (!ctx.inState(ProcessingBenchBlock.PROCESSING)) ctx.setState(ProcessingBenchBlock.PROCESSING);
            if (canProcess) {
                float cycleTime = machine.getRecipeTimeSeconds(tierLevel);
                machine.sendProgress(cycleTime > 0 ? machine.getInputProgress() / cycleTime : 0, ctx.windows());
            }
        }

        machine.setLastTickGameTime(currentGameTime);
    }

    private int processCycles(@Nonnull ProcessingBenchBlock machine, int tierLevel, @Nonnull CraftingRecipe recipe,
                              @Nonnull TinyFarmProcess process, float dt, short fuelSlot) {
        if (dt <= 0) return 0;
        var outputStacks = process.worstCase();
        double cost = process.durabilityCost(tierLevel);
        float cycleTime = machine.getRecipeTimeSeconds(tierLevel);

        int completions = 0;
        if (cycleTime <= 0) {
            while (canCycle(machine, recipe, process, fuelSlot, cost, outputStacks)) {
                completeOneCycle(machine, recipe, process, fuelSlot, cost);
                completions++;
            }
            return completions;
        }

        float progress = machine.getInputProgress() + dt;
        while (progress >= cycleTime && canCycle(machine, recipe, process, fuelSlot, cost, outputStacks)) {
            completeOneCycle(machine, recipe, process, fuelSlot, cost);
            progress -= cycleTime;
            completions++;
        }
        machine.setInputProgress(Math.min(progress, cycleTime));
        return completions;
    }

    private boolean canCycle(@Nonnull ProcessingBenchBlock machine, @Nonnull CraftingRecipe recipe, @Nonnull TinyFarmProcess process,
                             short fuelSlot, double cost, @Nonnull List<ItemStack> outputStacks) {
        if (!TinyMachines.isUsableTool(machine.getFuelContainer().getItemStack(fuelSlot), cost)) return false;
        if (!machine.getOutputContainer().canAddItemStacks(outputStacks, false, false)) return false;
        return !process.isConsumeInput() || !machine.getInputContainer()
                .getSlotMaterialsToRemove(process.inputMaterials(recipe), ProcessingBenchBlock.EXACT_RESOURCE_AMOUNTS, true).isEmpty();
    }

    private void completeOneCycle(@Nonnull ProcessingBenchBlock machine, @Nonnull CraftingRecipe recipe, @Nonnull TinyFarmProcess process, short fuelSlot, double cost) {
        if (process.isConsumeInput()) {
            machine.getInputContainer().removeMaterials(process.inputMaterials(recipe), true, ProcessingBenchBlock.EXACT_RESOURCE_AMOUNTS, true);
        }
        machine.getOutputContainer().addItemStacks(process.roll(), false, false, false);
        var fuelContainer = machine.getFuelContainer();
        var stack = fuelContainer.getItemStack(fuelSlot);
        if (stack != null) {
            fuelContainer.replaceItemStackInSlot(fuelSlot, stack, stack.withIncreasedDurability(-cost));
        }
    }
}

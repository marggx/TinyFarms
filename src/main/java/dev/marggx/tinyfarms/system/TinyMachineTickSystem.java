package dev.marggx.tinyfarms.system;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.ProcessingBench;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.TestRemoveItemSlotResult;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import dev.marggx.tinyfarms.TinyFarmsRules;
import dev.marggx.tinyfarms.TinyMachineDisplays;
import dev.marggx.tinyfarms.TinyMachines;

import javax.annotation.Nonnull;
import org.joml.Vector3i;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the durability-fueled Tiny Farm machine.
 *
 * <p>Mirrors the built-in {@code BenchSystems.ProcessingBenchTick} layout so the vanilla
 * Processing bench window/client behave normally, but replaces the fuel and input logic with:
 * <ul>
 *   <li>Fuel = a damageable tool whose durability is burned per completed cycle. The tool item
 *       itself is never consumed. When it breaks, the machine stops.</li>
 *   <li>The recipe input (e.g. a sapling) only has to be present - it is never consumed.</li>
 *   <li>Cycle time comes from the registered {@link CraftingRecipe} for the bench.</li>
 * </ul>
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

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        var machine = archetypeChunk.getComponent(index, componentType);
        var benchBlock = archetypeChunk.getComponent(index, benchBlockComponentType);
        var blockStateInfo = archetypeChunk.getComponent(index, blockStateInfoComponentType);
        if (machine == null || benchBlock == null || blockStateInfo == null) return;

        var sectionRef = blockStateInfo.getSectionRef();
        if (!sectionRef.isValid()) return;

        var section = store.getComponent(sectionRef, ChunkSection.getComponentType());
        if (section == null) return;
        var blockSection = store.getComponent(sectionRef, BlockSection.getComponentType());
        if (blockSection == null) return;

        int blockIndex = blockStateInfo.getIndex();
        int localX = ChunkUtil.xFromIndex(blockIndex);
        int localY = ChunkUtil.yFromIndex(blockIndex);
        int localZ = ChunkUtil.zFromIndex(blockIndex);
        int blockX = ChunkUtil.worldCoordFromLocalCoord(section.getX(), localX);
        int blockY = ChunkUtil.worldCoordFromLocalCoord(section.getY(), localY);
        int blockZ = ChunkUtil.worldCoordFromLocalCoord(section.getZ(), localZ);

        var blockId = blockSection.get(localX, localY, localZ);
        var blockType = BlockType.getAssetMap().getAsset(blockId);
        if (blockType == null) return;
        int rotationIndex = blockSection.getRotationIndex(localX, localY, localZ);

        var chunkStore = store.getExternalData();
        var world = chunkStore.getWorld();
        var entityStore = world.getEntityStore().getStore();

        // Vanilla upgrades only re-setup the vanilla component type; keep our machine in sync
        var machinePos = new Vector3i(blockX, blockY, blockZ);
        if (TinyMachines.knownTier(machinePos) != benchBlock.getTierLevel()) {
            machine.setupSlots(world, benchBlock, blockStateInfo, blockX, blockY, blockZ, blockType, rotationIndex);
            TinyMachines.applyFuelFilter(machine);
            TinyMachines.trackTier(machinePos, benchBlock.getTierLevel());
        }

        var processingBench = machine.getProcessingBench();
        if (processingBench == null) return;

        // 3D display: input item grows with progress (kept up to date on every tick path)
        float progress01 = 0f;
        if (machine.getRecipe() != null) {
            float recipeTime = machine.getRecipeTimeSeconds(benchBlock.getTierLevel());
            if (recipeTime > 0) progress01 = machine.getInputProgress() / recipeTime;
        }
        TinyMachineDisplays.update(world, blockX, blockY, blockZ,
                machine.getInputContainer().getItemStack((short) 0), progress01);

        final Instant currentGameTime = entityStore.getResource(WorldTimeResource.getResourceType()).getGameTime();

        final float effectiveDt;
        if (machine.getLastTickGameTime() == null || currentGameTime == null || currentGameTime.equals(machine.getLastTickGameTime())) {
            effectiveDt = 0;
        } else {
            float gameElapsedSeconds = Math.max(0, currentGameTime.toEpochMilli() - machine.getLastTickGameTime().toEpochMilli()) / 1000f;
            effectiveDt = (float) (gameElapsedSeconds / WorldTimeResource.getSecondsPerTick(world));
        }

        String currentState = blockType.getCurrentInteractionState();
        Map<UUID, BenchWindow> windows = benchBlock.getWindows();

        machine.getProcessingSlots().clear();
        machine.checkForRecipeUpdate(benchBlock);

        boolean hasFuelSlots = processingBench.getFuel() != null;
        boolean canProcess = false;
        var recipe = machine.getRecipe();
        short fuelSlot = hasFuelSlots ? TinyMachines.findFuelToolSlot(machine) : -1;

        if (recipe != null) {
            var outputItemStacks = CraftingManager.getOutputItemStacks(recipe);
            if (!machine.getOutputContainer().canAddItemStacks(outputItemStacks, false, false)) {
                if (ProcessingBenchBlock.PROCESSING.equals(currentState)) {
                    machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                    machine.playSound(processingBench.getFailedSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
                } else if (ProcessingBenchBlock.PROCESS_COMPLETED.equals(currentState)) {
                    machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                    machine.playSound(processingBench.getEndSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
                }
                machine.setActive(false, benchBlock, blockStateInfo);
                machine.setLastTickGameTime(currentGameTime);
                return;
            }

            var inputMaterials = CraftingManager.getInputMaterials(recipe);
            List<TestRemoveItemSlotResult> result = machine.getInputContainer().getSlotMaterialsToRemove(inputMaterials, ProcessingBenchBlock.EXACT_RESOURCE_AMOUNTS, true);

            if (result.isEmpty()) {
                if (ProcessingBenchBlock.PROCESSING.equals(currentState)) {
                    machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                    machine.playSound(processingBench.getFailedSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
                } else if (ProcessingBenchBlock.PROCESS_COMPLETED.equals(currentState)) {
                    machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                    machine.playSound(processingBench.getEndSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
                }
                machine.setInputProgress(0);
                machine.setActive(false, benchBlock, blockStateInfo);
                machine.clearCurrentRecipe();
                machine.setLastTickGameTime(currentGameTime);
                return;
            }

            for (TestRemoveItemSlotResult item : result) {
                machine.getProcessingSlots().addAll(item.getPickedSlots());
            }

            // Tool gating: the inserted fuel tool must unlock this recipe's inputs
            var fuelStack = fuelSlot >= 0 ? machine.getFuelContainer().getItemStack(fuelSlot) : null;
            if (!TinyFarmsRules.recipeAllowed(recipe, fuelStack)) {
                if (ProcessingBenchBlock.PROCESSING.equals(currentState) || ProcessingBenchBlock.PROCESS_COMPLETED.equals(currentState)) {
                    machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                    machine.playSound(processingBench.getFailedSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
                }
                machine.setInputProgress(0);
                machine.setActive(false, benchBlock, blockStateInfo);
                machine.setLastTickGameTime(currentGameTime);
                return;
            }

            machine.sendProcessingSlots(windows);
            canProcess = true;
        } else if (!hasFuelSlots) {
            if (ProcessingBenchBlock.PROCESSING.equals(currentState)) {
                machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                machine.playSound(processingBench.getFailedSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
            } else if (ProcessingBenchBlock.PROCESS_COMPLETED.equals(currentState)) {
                machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                machine.playSound(processingBench.getEndSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
            }
            machine.setLastTickGameTime(currentGameTime);
            return;
        } else {
            boolean allowNoInputProcessing = processingBench.shouldAllowNoInputProcessing();

            if (!allowNoInputProcessing && ProcessingBenchBlock.PROCESSING.equals(currentState)) {
                machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                machine.playSound(processingBench.getFailedSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
            } else if (ProcessingBenchBlock.PROCESS_COMPLETED.equals(currentState)) {
                machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                machine.playSound(processingBench.getEndSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
                machine.setActive(false, benchBlock, blockStateInfo);
                machine.sendProgress(0, windows);
                machine.setLastTickGameTime(currentGameTime);
                return;
            }

            machine.sendProgress(0, windows);

            if (!allowNoInputProcessing) {
                machine.setActive(false, benchBlock, blockStateInfo);
                machine.setLastTickGameTime(currentGameTime);
                return;
            }
        }

        int completions = 0;
        if (canProcess && fuelSlot >= 0) {
            machine.setActive(true, benchBlock, blockStateInfo);
            completions = processCycles(machine, benchBlock, recipe, effectiveDt, fuelSlot);
        } else if (machine.isActive()) {
            machine.setActive(false, benchBlock, blockStateInfo);
        }

        machine.getProcessingFuelSlots().clear();
        if (hasFuelSlots) {
            fuelSlot = TinyMachines.findFuelToolSlot(machine);
            if (fuelSlot >= 0) {
                machine.getProcessingFuelSlots().add(fuelSlot);
                machine.sendProcessingFuelSlots(windows);
            } else {
                if (ProcessingBenchBlock.PROCESSING.equals(currentState) || ProcessingBenchBlock.PROCESS_COMPLETED.equals(currentState)) {
                    machine.setBlockInteractionState(StateData.NULL_STATE_ID, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
                    machine.playSound(processingBench.getFailedSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
                }
                machine.setActive(false, benchBlock, blockStateInfo);
                machine.setLastTickGameTime(currentGameTime);
                return;
            }
            TinyMachines.refreshFuelDisplay(machine, windows);
        }

        if (completions > 0) {
            machine.setBlockInteractionState(ProcessingBenchBlock.PROCESS_COMPLETED, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
            machine.playSound(machine.getBench().getCompletedSoundEventIndex(), entityStore, blockType, rotationIndex, blockX, blockY, blockZ);
        } else {
            if (!ProcessingBenchBlock.PROCESSING.equals(currentState)) {
                machine.setBlockInteractionState(ProcessingBenchBlock.PROCESSING, blockType, chunkStore, sectionRef, blockX, blockY, blockZ);
            }
            if (canProcess) {
                int tierLevel = benchBlock.getTierLevel();
                float recipeTime = machine.getRecipeTimeSeconds(tierLevel);
                if (machine.getRecipe() != null && recipeTime > 0) {
                    machine.sendProgress(machine.getInputProgress() / recipeTime, windows);
                } else {
                    machine.sendProgress(0, windows);
                }
            }
        }

        machine.setLastTickGameTime(currentGameTime);
    }

    private int processCycles(@Nonnull ProcessingBenchBlock machine, @Nonnull BenchBlock benchBlock, @javax.annotation.Nullable CraftingRecipe recipe, float dt, short fuelSlot) {
        if (recipe == null || dt <= 0) return 0;
        var outputStacks = CraftingManager.getOutputItemStacks(recipe);
        int tierLevel = benchBlock.getTierLevel();
        float cycleTime = machine.getRecipeTimeSeconds(tierLevel);

        int completions = 0;
        if (cycleTime <= 0) {
            while (hasFuel(machine, fuelSlot) && machine.getOutputContainer().canAddItemStacks(outputStacks, false, false)) {
                completeOneCycle(machine, outputStacks, fuelSlot, tierLevel);
                completions++;
            }
            return completions;
        }

        float progress = machine.getInputProgress() + dt;
        while (progress >= cycleTime && hasFuel(machine, fuelSlot) && machine.getOutputContainer().canAddItemStacks(outputStacks, false, false)) {
            completeOneCycle(machine, outputStacks, fuelSlot, tierLevel);
            progress -= cycleTime;
            completions++;
        }
        machine.setInputProgress(Math.max(0, progress));
        return completions;
    }

    private void completeOneCycle(@Nonnull ProcessingBenchBlock machine, @Nonnull List<ItemStack> outputStacks, short fuelSlot, int tierLevel) {
        machine.getOutputContainer().addItemStacks(outputStacks, false, false, false);
        var fuelContainer = machine.getFuelContainer();
        var stack = fuelContainer.getItemStack(fuelSlot);
        if (stack != null) {
            double cost = TinyFarmsRules.durabilityPerCycle(tierLevel);
            if (stack.getDurability() - cost >= 1.0) {
                fuelContainer.replaceItemStackInSlot(fuelSlot, stack, stack.withIncreasedDurability(-cost));
            }
        }
    }

    private static boolean hasFuel(@Nonnull ProcessingBenchBlock machine, short fuelSlot) {
        return TinyMachines.isUsableTool(machine.getFuelContainer().getItemStack(fuelSlot));
    }
}
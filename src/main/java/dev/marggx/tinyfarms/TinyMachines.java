package dev.marggx.tinyfarms;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.builtin.crafting.window.ProcessingBenchWindow;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.marggx.tinyfarms.process.TinyProcesses;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

public final class TinyMachines {
    private TinyMachines() {}

    /**
     * Runs {@code setupSlots} without the saved {@code lastTickGameTime}: vanilla would otherwise
     * batch-process the offline time and consume the input and tool items. The tick system
     * handles elapsed time itself. Re-applies the fuel filter, which the rebuilt containers lose.
     */
    public static void setupSlots(@Nonnull ProcessingBenchBlock machine, @Nonnull World world, @Nonnull BenchBlock benchBlock,
                                  @Nonnull BlockModule.BlockStateInfo blockStateInfo, int x, int y, int z,
                                  @Nonnull BlockType blockType, int rotationIndex) {
        var savedLastTick = machine.getLastTickGameTime();
        machine.setLastTickGameTime(null);
        try {
            machine.setupSlots(world, benchBlock, blockStateInfo, x, y, z, blockType, rotationIndex);
        } finally {
            machine.setLastTickGameTime(savedLastTick);
        }
        applyFuelFilter(machine);
    }

    private static void applyFuelFilter(@Nonnull ProcessingBenchBlock machine) {
        var fuelContainer = machine.getFuelContainer();
        var processingBench = machine.getProcessingBench();
        if (fuelContainer == null || processingBench == null || processingBench.getFuel() == null) return;
        for (short slot = 0; slot < processingBench.getFuel().length; slot++) {
            fuelContainer.setSlotFilter(FilterActionType.ADD, slot, (actionType, container, slotIndex, stack) -> fuelAllowed(stack));
        }
    }

    private static boolean fuelAllowed(@Nullable ItemStack stack) {
        return stack == null || (stack.getMaxDurability() > 0 && TinyProcesses.isProcessTool(stack));
    }

    /** A durability tool that can pay {@code cost} and still keep some, so it never breaks. */
    public static boolean isUsableTool(@Nullable ItemStack stack, double cost) {
        return stack != null && stack.getMaxDurability() > 0 && !stack.isBroken() && stack.getDurability() > cost;
    }

    /** @return the fuel slot holding a usable tool, or -1 if none. */
    public static short findFuelToolSlot(@Nonnull ProcessingBenchBlock machine, double cost) {
        var fuelContainer = machine.getFuelContainer();
        if (fuelContainer == null) return -1;
        for (short slot = 0; slot < fuelContainer.getCapacity(); slot++) {
            if (isUsableTool(fuelContainer.getItemStack(slot), cost)) return slot;
        }
        return -1;
    }

    /** Pushes the durability of the tool in {@code fuelSlot} into the fuel bar, or clears it when the slot is -1. */
    public static void refreshFuelDisplay(@Nonnull ProcessingBenchBlock machine, short fuelSlot, @Nonnull Map<UUID, BenchWindow> windows) {
        if (windows.isEmpty()) return;
        var stack = fuelSlot < 0 ? null : machine.getFuelContainer().getItemStack(fuelSlot);
        int max = stack == null ? 0 : (int) stack.getMaxDurability();
        float fill = stack == null || max <= 0 ? 0f : (float) (stack.getDurability() / max);
        windows.forEach((uuid, window) -> {
            var processingWindow = (ProcessingBenchWindow) window;
            processingWindow.setMaxFuel(max);
            processingWindow.setFuelTime(fill);
        });
    }
}

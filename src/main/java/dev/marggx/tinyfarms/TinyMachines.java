package dev.marggx.tinyfarms;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.builtin.crafting.window.ProcessingBenchWindow;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared helpers for the durability-fueled Tiny Farm machine.
 */
public final class TinyMachines {
    /** Last tier level we ran {@code setupSlots} with, per machine position. */
    private static final Map<Vector3i, Integer> KNOWN_TIERS = new ConcurrentHashMap<>();

    private TinyMachines() {}

    public static int knownTier(@Nonnull Vector3i pos) {
        return KNOWN_TIERS.getOrDefault(pos, -1);
    }

    public static void trackTier(@Nonnull Vector3i pos, int tierLevel) {
        KNOWN_TIERS.put(pos, tierLevel);
    }

    /**
     * Fuel slots take tools only. Must be re-applied after every {@code setupSlots},
     * which rebuilds the containers.
     */
    public static void applyFuelFilter(@Nonnull ProcessingBenchBlock machine) {
        var fuelContainer = machine.getFuelContainer();
        var processingBench = machine.getProcessingBench();
        if (fuelContainer == null || processingBench == null || processingBench.getFuel() == null) return;
        for (short slot = 0; slot < processingBench.getFuel().length; slot++) {
            fuelContainer.setSlotFilter(FilterActionType.ADD, slot,
                    (actionType, container, slotIndex, stack) -> TinyFarmsRules.fuelAllowed(stack));
        }
    }

    /** Tools either carry a Tool spec (hatchets, pickaxes...) or are durability tools by id prefix (sickles...). */
    public static boolean isToolItem(@Nullable ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        var item = stack.getItem();
        return item.getTool() != null || item.getId().startsWith("Tool_");
    }

    public static boolean isUsableTool(@Nullable ItemStack stack) {
        if (!isToolItem(stack)) return false;
        if (stack.getMaxDurability() <= 0 || stack.isBroken()) return false;
        // never consume the last point of durability
        return stack.getDurability() >= TinyFarmsRules.durabilityPerCycle(0) + 1.0;
    }

    /**
     * @return the fuel slot index holding a usable tool, or -1 if none.
     */
    public static short findFuelToolSlot(@Nullable ProcessingBenchBlock machine) {
        if (machine == null) return -1;
        var fuelContainer = machine.getFuelContainer();
        if (fuelContainer == null) return -1;
        for (short slot = 0; slot < fuelContainer.getCapacity(); slot++) {
            if (isUsableTool(fuelContainer.getItemStack(slot))) return slot;
        }
        return -1;
    }

    /**
     * Pushes the current fuel item's durability into the processing bench fuel bar.
     * If no usable tool is present the bar is cleared.
     */
    public static void refreshFuelDisplay(@Nullable ProcessingBenchBlock machine, @Nonnull Map<UUID, BenchWindow> windows) {
        if (windows.isEmpty()) return;
        short slot = findFuelToolSlot(machine);
        if (slot < 0) {
            windows.forEach((uuid, window) -> {
                var processingWindow = (ProcessingBenchWindow) window;
                processingWindow.setMaxFuel(0);
                processingWindow.setFuelTime(0);
            });
            return;
        }
        var stack = machine.getFuelContainer().getItemStack(slot);
        if (stack == null) return;
        int max = (int) stack.getMaxDurability();
        double remaining = stack.getDurability();
        windows.forEach((uuid, window) -> {
            var processingWindow = (ProcessingBenchWindow) window;
            processingWindow.setMaxFuel(max);
            processingWindow.setFuelTime(max > 0 ? (float) (remaining / max) : 0f);
        });
    }
}
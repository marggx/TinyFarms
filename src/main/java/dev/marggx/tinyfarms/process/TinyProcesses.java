package dev.marggx.tinyfarms.process;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Lookups and load-time validation for {@link TinyFarmProcess}. */
public final class TinyProcesses {
    public static final String BENCH_ID = "TinyFarm";

    private TinyProcesses() {}

    @Nullable
    public static TinyFarmProcess of(@Nullable CraftingRecipe recipe) {
        return recipe == null || recipe.getId() == null ? null : TinyFarmProcess.getAssetMap().getAsset(recipe.getId());
    }

    /** Whether any process accepts this item as its tool. */
    public static boolean isProcessTool(@Nullable ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        var id = stack.getItem().getId();
        for (var process : TinyFarmProcess.getAssetMap().getAssetMap().values()) {
            if (process.acceptsTool(id)) return true;
        }
        return false;
    }

    /** The block entity key a display of this process renders, or null for an item display. */
    @Nullable
    public static String displayBlockKey(@Nonnull TinyProcessDisplay display) {
        if (display.getType() != TinyProcessDisplay.Type.Block) return null;
        var blockType = BlockType.getAssetMap().getAsset(display.getBlock());
        if (blockType == null) return null;
        return display.getState() == null ? display.getBlock() : blockType.getBlockKeyForState(display.getState());
    }

    /** Every problem between processes, recipes and the game's assets; empty when everything is consistent. */
    @Nonnull
    public static List<String> validate() {
        var errors = new ArrayList<String>();
        var recipes = CraftingPlugin.getBenchRecipes(BenchType.Processing, BENCH_ID);
        var processes = TinyFarmProcess.getAssetMap().getAssetMap();

        var recipeIds = new HashSet<String>();
        for (var recipe : recipes) {
            recipeIds.add(recipe.getId());
            var process = processes.get(recipe.getId());
            if (process == null) {
                errors.add("recipe " + recipe.getId() + " has no TinyFarmProcess");
                continue;
            }
            var dropItems = new HashSet<String>();
            for (var drop : process.getAllDrops()) {
                if (drop == null || drop.getItemId() == null) continue;
                dropItems.add(drop.getItemId());
            }
            for (var output : CraftingManager.getOutputItemStacks(recipe)) {
                if (output.getItem() != null && !dropItems.contains(output.getItem().getId())) {
                    errors.add(process.getId() + ": recipe output " + output.getItem().getId() + " is not in the Drops");
                }
            }
        }

        for (var process : processes.values()) {
            var id = process.getId();
            if (!recipeIds.contains(id)) errors.add("process " + id + " has no crafting recipe on bench " + BENCH_ID);
            for (var drop : process.getAllDrops()) {
                if (drop == null || drop.getItemId() == null || Item.getAssetMap().getAsset(drop.getItemId()) == null) {
                    errors.add(id + ": drop item " + (drop == null ? null : drop.getItemId()) + " does not exist");
                }
            }
            var display = process.getDisplay();
            if (display.getType() == TinyProcessDisplay.Type.Item) {
                if (Item.getAssetMap().getAsset(display.getItem()) == null) {
                    errors.add(id + ": display item " + display.getItem() + " does not exist");
                }
            } else {
                var blockType = BlockType.getAssetMap().getAsset(display.getBlock());
                if (blockType == null) {
                    errors.add(id + ": display block " + display.getBlock() + " does not exist");
                } else if (display.getState() != null && blockType.getBlockKeyForState(display.getState()) == null) {
                    errors.add(id + ": display block " + display.getBlock() + " has no state " + display.getState());
                }
            }
        }
        return errors;
    }
}

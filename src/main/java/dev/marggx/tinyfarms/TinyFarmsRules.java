package dev.marggx.tinyfarms;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Rules for what a Tiny Farm machine accepts as fuel and which tool unlocks which recipe.
 *
 * <p>Matching is by item-id prefix — the only family granularity the item data provides
 * (tools carry no resource types, tags or sub-categories). An empty {@link #TOOL_INPUT_RULES}
 * disables gating; recipes whose inputs match no rule stay open to any usable tool.
 */
public final class TinyFarmsRules {
    /** Fuel item-id prefix -> recipe input prefixes it unlocks. */
    private record Rule(String fuelPrefix, List<String> inputPrefixes) {}

    private static final List<Rule> TOOL_INPUT_RULES = List.of(
            new Rule("Tool_Hatchet_", List.of("Plant_Sapling_"))
    );

    private TinyFarmsRules() {}

    /** Slot filter for the fuel container: it must hold real tools (broken or not). */
    public static boolean fuelAllowed(@Nullable ItemStack stack) {
        if (stack == null) return true;
        var item = stack.getItem();
        return item != null && item.getTool() != null;
    }

    /**
     * Tool durability burned per completed cycle, by bench tier (0-based; 1 = Iron ... 4 = Adamantite).
     */
    public static double durabilityPerCycle(int tierLevel) {
        var costs = new double[]{1.0, 0.9, 0.8, 0.7};
        return costs[Math.max(0, Math.min(costs.length - 1, tierLevel))];
    }

    /** Whether {@code fuelTool} may drive {@code recipe}. Unmatched recipes are open to any tool. */
    public static boolean recipeAllowed(@Nullable CraftingRecipe recipe, @Nullable ItemStack fuelTool) {
        if (recipe == null || fuelTool == null) return false;
        if (TOOL_INPUT_RULES.isEmpty() || fuelTool.getItem() == null) return TOOL_INPUT_RULES.isEmpty();
        var fuelId = fuelTool.getItem().getId();
        Rule matched = null;
        for (var rule : TOOL_INPUT_RULES) {
            for (var material : CraftingManager.getInputMaterials(recipe)) {
                var inputId = material.getItemId() != null ? material.getItemId() : material.getResourceTypeId();
                if (inputId != null && rule.inputPrefixes().stream().anyMatch(inputId::startsWith)) {
                    matched = rule;
                    break;
                }
            }
            if (matched != null) break;
        }
        return matched == null || (fuelId != null && fuelId.startsWith(matched.fuelPrefix()));
    }
}

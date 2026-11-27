package dev.marggx.tinyfarms.process;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One Tiny Farm process. The id equals the id of the process' crafting recipe, which owns the
 * input, time, bench and tier requirement; this asset owns everything else.
 */
public class TinyFarmProcess implements JsonAssetWithMap<String, DefaultAssetMap<String, TinyFarmProcess>> {
    public static final AssetBuilderCodec<String, TinyFarmProcess> CODEC = AssetBuilderCodec.builder(
        TinyFarmProcess.class, TinyFarmProcess::new, Codec.STRING,
        (process, id) -> process.id = id, TinyFarmProcess::getId,
        (process, data) -> process.data = data, process -> process.data
    )
        .append(new KeyedCodec<>("Tool", Codec.STRING_ARRAY, true), (p, v) -> p.tool = v, p -> p.tool)
            .addValidator(Validators.nonNull())
            .addValidator(Validators.nonEmptyArray())
            .add()
        .append(new KeyedCodec<>("DurabilityCost", Codec.DOUBLE, true), (p, v) -> p.durabilityCost = v, p -> p.durabilityCost)
            .addValidator(Validators.greaterThan(0.0))
            .add()
        .append(new KeyedCodec<>("TierDiscount", Codec.DOUBLE), (p, v) -> p.tierDiscount = v, p -> p.tierDiscount)
            .addValidator(Validators.range(0.0, 1.0))
            .add()
        .append(new KeyedCodec<>("MaxDiscountTiers", Codec.INTEGER), (p, v) -> p.maxDiscountTiers = v, p -> p.maxDiscountTiers)
            .addValidator(Validators.min(0))
            .add()
        .append(new KeyedCodec<>("ConsumeInput", Codec.BOOLEAN), (p, v) -> p.consumeInput = v, p -> p.consumeInput).add()
        .append(new KeyedCodec<>("Drops", ItemDropContainer.CODEC, true), (p, v) -> p.drops = v, p -> p.drops)
            .addValidator(Validators.nonNull())
            .add()
        .append(new KeyedCodec<>("Display", TinyProcessDisplay.CODEC, true), (p, v) -> p.display = v, p -> p.display)
            .addValidator(Validators.nonNull())
            .add()
        .validator((p, results) -> {
            if (p.drops != null && p.drops.getAllDrops(new ArrayList<>()).isEmpty()) results.fail("Drops must drop something");
            if (p.tierDiscount * p.maxDiscountTiers >= 1.0) results.fail("TierDiscount * MaxDiscountTiers must stay below 1");
            if (p.tool != null) {
                for (var prefix : p.tool) {
                    if (prefix == null || prefix.isBlank()) results.fail("Tool entries must not be blank");
                }
            }
        })
        .build();

    private record InputMaterials(CraftingRecipe recipe, List<MaterialQuantity> materials) {}

    private static volatile DefaultAssetMap<String, TinyFarmProcess> assetMap;

    private AssetExtraInfo.Data data;
    private String id;
    private String[] tool;
    private double durabilityCost;
    private double tierDiscount = 0.1;
    private int maxDiscountTiers = 3;
    private boolean consumeInput;
    private ItemDropContainer drops;
    private TinyProcessDisplay display;
    private volatile List<ItemStack> worstCase;
    private volatile InputMaterials inputMaterials;

    public static DefaultAssetMap<String, TinyFarmProcess> getAssetMap() {
        var map = assetMap;
        if (map == null) assetMap = map = AssetRegistry.getAssetStore(TinyFarmProcess.class).getAssetMap();
        return map;
    }

    @Override
    public String getId() {
        return id;
    }

    public boolean acceptsTool(@Nullable String itemId) {
        if (itemId == null) return false;
        for (var prefix : tool) {
            if (itemId.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Durability burned per cycle at a bench tier level (1-based): the base, minus {@code TierDiscount} of it per further level, up to {@code MaxDiscountTiers} levels. */
    public double durabilityCost(int tierLevel) {
        int steps = Math.max(0, Math.min(maxDiscountTiers, tierLevel - 1));
        return durabilityCost * (1.0 - tierDiscount * steps);
    }

    public boolean isConsumeInput() {
        return consumeInput;
    }

    @Nonnull
    public ItemDropContainer getDrops() {
        return drops;
    }

    @Nonnull
    public TinyProcessDisplay getDisplay() {
        return display;
    }

    /** Every item the drops can produce. */
    @Nonnull
    public List<ItemDrop> getAllDrops() {
        return drops.getAllDrops(new ArrayList<>());
    }

    /** One roll of the drops. */
    @Nonnull
    public List<ItemStack> roll() {
        var random = ThreadLocalRandom.current();
        var configured = new ArrayList<ItemDrop>();
        drops.populateDrops(configured, random::nextDouble, id);
        var stacks = new ArrayList<ItemStack>(configured.size());
        for (var drop : configured) {
            if (drop == null || drop.getItemId() == null) continue;
            int amount = drop.getRandomQuantity(random);
            if (amount > 0) stacks.add(new ItemStack(drop.getItemId(), amount, drop.getMetadata()));
        }
        return stacks;
    }

    /** Maximum output of one cycle, used to check that the output container has room. */
    @Nonnull
    public List<ItemStack> worstCase() {
        var cached = worstCase;
        if (cached != null) return cached;
        var stacks = new ArrayList<ItemStack>();
        for (var drop : getAllDrops()) {
            if (drop != null && drop.getItemId() != null && drop.getQuantityMax() > 0) {
                stacks.add(new ItemStack(drop.getItemId(), drop.getQuantityMax(), drop.getMetadata()));
            }
        }
        return worstCase = List.copyOf(stacks);
    }

    /** The recipe's input materials, resolved once per recipe instance. */
    @Nonnull
    public List<MaterialQuantity> inputMaterials(@Nonnull CraftingRecipe recipe) {
        var cached = inputMaterials;
        if (cached != null && cached.recipe() == recipe) return cached.materials();
        var materials = CraftingManager.getInputMaterials(recipe);
        inputMaterials = new InputMaterials(recipe, materials);
        return materials;
    }
}

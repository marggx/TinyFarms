package dev.marggx.tinyfarms;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.marggx.tinyfarms.process.TinyFarmProcess;
import dev.marggx.tinyfarms.process.TinyProcessDisplay;
import dev.marggx.tinyfarms.process.TinyProcesses;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Spawns a static, non-pickable display of the machine's input inside the (transparent) block
 * and scales it with the bench progress, as configured by the process' display settings.
 *
 * <p>The display entity's UUID is derived from the machine position, so a display left over from
 * before a restart is replaced instead of duplicated.
 */
public final class TinyMachineDisplays {
    private static final float SCALE_EPSILON = 0.001f;
    private static final TinyProcessDisplay FALLBACK = new TinyProcessDisplay();

    private TinyMachineDisplays() {}

    private static UUID displayUuid(@Nonnull World world, int x, int y, int z) {
        return UUID.nameUUIDFromBytes(("tinyfarm-display:" + world.getName() + ":" + x + "," + y + "," + z).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Syncs the display of the machine at {@code x,y,z} to {@code inputStack} and the process'
     * display settings, scaled by {@code progress01}. Without a process the input item itself is
     * shown with default settings. Cheap enough to call every tick.
     */
    public static void update(@Nonnull World world, @Nonnull TinyMachineState state, int x, int y, int z,
                              @Nullable ItemStack inputStack, @Nullable TinyFarmProcess process, float progress01) {
        var item = inputStack != null ? inputStack.getItem() : null;
        if (item == null) {
            if (state.displayKey != null) despawn(world, state);
            return;
        }

        var settings = process != null ? process.getDisplay() : FALLBACK;
        var displayKey = process != null ? process.getId() : item.getId();
        var ref = state.displayRef;

        if (!displayKey.equals(state.displayKey) || (ref != null && !ref.isValid())) {
            despawn(world, state);
            spawn(world, state, x, y, z, settings, item.getId(), displayKey, progress01);
            return;
        }
        if (ref == null) return;

        float scale = scaleFor(settings, progress01);
        if (Math.abs(scale - state.displayScale) <= SCALE_EPSILON) return;
        var scaleComponent = ref.getStore().getComponent(ref, EntityScaleComponent.getComponentType());
        if (scaleComponent == null) return;
        scaleComponent.setScale(scale);
        state.displayScale = scale;
    }

    public static void despawn(@Nonnull World world, @Nonnull TinyMachineState state) {
        state.displayGeneration++;
        var ref = state.displayRef;
        state.displayRef = null;
        state.displayKey = null;
        if (ref == null) return;
        world.execute(() -> {
            if (ref.isValid()) ref.getStore().removeEntity(ref, RemoveReason.REMOVE);
        });
    }

    private static float scaleFor(@Nonnull TinyProcessDisplay settings, float progress01) {
        if (!settings.isGrowWithProgress()) return settings.getScaleMax();
        return settings.getScaleMin() + Math.max(0f, Math.min(1f, progress01)) * (settings.getScaleMax() - settings.getScaleMin());
    }

    private static void spawn(@Nonnull World world, @Nonnull TinyMachineState state, int x, int y, int z,
                              @Nonnull TinyProcessDisplay settings, @Nonnull String inputItemId,
                              @Nonnull String displayKey, float progress01) {
        var entityStore = world.getEntityStore();
        var uuid = displayUuid(world, x, y, z);
        var holder = EntityStore.REGISTRY.newHolder();

        var offset = settings.getOffset();
        var rotation = settings.getRotation();
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(
                new Vector3d(x + 0.5 + offset[0], y + offset[1], z + 0.5 + offset[2]),
                new Rotation3f((float) Math.toRadians(rotation[1]), (float) Math.toRadians(rotation[0]), (float) Math.toRadians(rotation[2]))));

        var blockKey = TinyProcesses.displayBlockKey(settings);
        if (blockKey != null) {
            // A pure block entity: an item component would make the client draw the item's own model instead
            holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(blockKey));
        } else {
            // Mirrors NPC EntitySpawnPage#spawnItem: an animation-overridden stack renders statically instead of as a bobbing drop
            var itemId = settings.getItem() != null ? settings.getItem() : inputItemId;
            var displayStack = new ItemStack(itemId, 1);
            displayStack.setOverrideDroppedItemAnimation(true);
            holder.addComponent(ItemComponent.getComponentType(), new ItemComponent(displayStack));
            var item = Item.getAssetMap().getAsset(itemId);
            if (item != null && item.hasBlockType()) {
                holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(itemId));
            }
        }
        float scale = scaleFor(settings, progress01);
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(uuid));
        holder.ensureComponent(Intangible.getComponentType());
        holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        holder.addComponent(PropComponent.getComponentType(), PropComponent.get());

        state.displayKey = displayKey;
        state.displayScale = scale;
        int generation = ++state.displayGeneration;
        world.execute(() -> {
            if (state.displayGeneration != generation) return;
            var store = entityStore.getStore();
            var leftover = entityStore.getRefFromUUID(uuid);
            if (leftover != null && leftover.isValid()) store.removeEntity(leftover, RemoveReason.REMOVE);
            state.displayRef = store.addEntity(holder, AddReason.SPAWN);
        });
    }
}

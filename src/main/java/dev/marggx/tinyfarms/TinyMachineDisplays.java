package dev.marggx.tinyfarms;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
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
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns a static, non-pickable copy of the machine's input item inside the (transparent)
 * block and scales it with the bench progress.
 *
 * <p>The display entity's UUID is derived from the machine position, so it survives restarts:
 * the first tick after loading adopts the existing entity instead of spawning a duplicate,
 * and destroying the machine removes it even when the tracker map was lost (crash).
 */
public final class TinyMachineDisplays {
    public static final float MIN_SCALE = 0.35f;
    public static final float MAX_SCALE = 1.0f;

    private static final float Y_OFFSET = 0.1f;
    private static final float SCALE_EPSILON = 0.001f;

    private record Key(int x, int y, int z) {}

    /** {@code ref == null} while the spawn task is still queued on the world thread. */
    private record Entry(@Nullable Ref<EntityStore> ref, String itemId) {}

    private static final Map<Key, Entry> DISPLAYS = new ConcurrentHashMap<>();

    private TinyMachineDisplays() {}

    private static UUID displayUuid(int x, int y, int z) {
        return UUID.nameUUIDFromBytes(("tinyfarm-display:" + x + "," + y + "," + z).getBytes());
    }

    /**
     * Syncs the display entity for the machine at {@code x,y,z} to {@code inputStack} and
     * scales it by {@code progress01}. Safe to call every tick.
     */
    public static void update(@Nonnull World world, int x, int y, int z, @Nullable ItemStack inputStack, float progress01) {
        var itemId = inputStack != null && inputStack.getItem() != null ? inputStack.getItem().getId() : null;
        var key = new Key(x, y, z);
        var entry = DISPLAYS.get(key);

        if (itemId == null) {
            if (entry != null) despawn(world, key, entry);
            return;
        }

        var entityStore = world.getEntityStore();
        var store = entityStore.getStore();
        boolean needsSpawn = entry == null
                || !entry.itemId().equals(itemId)
                || (entry.ref() != null && !entry.ref().isValid());
        if (needsSpawn) {
            if (entry != null) despawn(world, key, entry);
            // adopt a surviving display from before a restart instead of duplicating it
            var existingRef = entityStore.getRefFromUUID(displayUuid(x, y, z));
            if (existingRef != null && existingRef.isValid()) {
                store.putComponent(existingRef, ItemComponent.getComponentType(), new ItemComponent(inputStack));
                entry = new Entry(existingRef, itemId);
                DISPLAYS.put(key, entry);
            } else {
                entry = spawn(world, key, inputStack, itemId);
            }
        }
        if (entry.ref() == null) return; // spawn still queued

        var scaleComponent = store.getComponent(entry.ref(), EntityScaleComponent.getComponentType());
        if (scaleComponent == null) return;
        float scale = MIN_SCALE + Math.max(0f, Math.min(1f, progress01)) * (MAX_SCALE - MIN_SCALE);
        if (Math.abs(scale - scaleComponent.getScale()) > SCALE_EPSILON) scaleComponent.setScale(scale);
    }

    /** Removes the display entity of the machine at {@code x,y,z}, if any. */
    public static void despawn(@Nonnull World world, int x, int y, int z) {
        var key = new Key(x, y, z);
        var entry = DISPLAYS.remove(key);
        if (entry != null) despawn(world, key, entry);
    }

    private static void despawn(@Nonnull World world, Key key, Entry entry) {
        DISPLAYS.remove(key, entry);
        if (entry.ref() == null) return;
        world.execute(() -> {
            var entityStore = world.getEntityStore().getStore();
            if (entry.ref().isValid()) entityStore.removeEntity(entry.ref(), RemoveReason.REMOVE);
        });
    }

    private static @Nonnull Entry spawn(@Nonnull World world, Key key, @Nonnull ItemStack inputStack, @Nonnull String itemId) {
        var entityStore = world.getEntityStore().getStore();
        var holder = EntityStore.REGISTRY.newHolder();

        // Mirrors NPC EntitySpawnPage#spawnItem: a fresh, animation-overridden stack renders
        // statically like an entity-tool placement instead of a bobbing drop.
        var item = Item.getAssetMap().getAsset(itemId);
        var displayStack = new ItemStack(itemId, 1);
        displayStack.setOverrideDroppedItemAnimation(true);

        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(
                new Vector3d(key.x() + 0.5, key.y() + Y_OFFSET, key.z() + 0.5), Rotation3f.IDENTITY));
        holder.addComponent(ItemComponent.getComponentType(), new ItemComponent(displayStack));
        if (item != null && item.hasBlockType()) {
            // Block-type items render their block model (e.g. the sapling cross)
            holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(itemId));
        }
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(MIN_SCALE));
        holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(displayUuid(key.x(), key.y(), key.z())));
        holder.ensureComponent(Intangible.getComponentType());
        holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        holder.addComponent(PropComponent.getComponentType(), PropComponent.get());

        var entry = new Entry(null, itemId);
        DISPLAYS.put(key, entry);
        // addEntity runs on the world thread and returns the ref for later scale updates
        world.execute(() -> DISPLAYS.put(key, new Entry(entityStore.addEntity(holder, AddReason.SPAWN), itemId)));
        return entry;
    }
}

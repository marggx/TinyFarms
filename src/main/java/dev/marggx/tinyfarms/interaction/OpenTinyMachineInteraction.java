package dev.marggx.tinyfarms.interaction;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.window.ProcessingBenchWindow;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.marggx.tinyfarms.TinyFarmsPlugin;
import dev.marggx.tinyfarms.TinyMachines;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock.PROCESSING;
import static com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock.PROCESS_COMPLETED;

public final class OpenTinyMachineInteraction extends SimpleBlockInteraction {

    @Nonnull
    public static final BuilderCodec<OpenTinyMachineInteraction> CODEC = BuilderCodec.builder(OpenTinyMachineInteraction.class, OpenTinyMachineInteraction::new, SimpleBlockInteraction.CODEC)
        .documentation("Opens the Tiny Farm machine processing bench page.")
        .build();

    @Override
    protected void interactWithBlock(@Nonnull World world, @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InteractionType type, @Nonnull InteractionContext context, @Nullable ItemStack itemInHand, @Nonnull Vector3i pos, @Nonnull CooldownHandler cooldownHandler) {
        final var ref = context.getEntity();
        final var store = ref.getStore();

        final var playerComponent = commandBuffer.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) return;

        final var chunkStore = world.getChunkStore();
        final var sectionRef = chunkStore.getChunkSectionReferenceAtBlock(pos.x, pos.y, pos.z);
        if (sectionRef == null || !sectionRef.isValid()) return;
        final var chunkStoreStore = chunkStore.getStore();
        final var blockEntityRef = BlockModule.getBlockEntity(chunkStoreStore, sectionRef, pos.x, pos.y, pos.z);
        if (blockEntityRef == null) return;

        final var benchState = chunkStoreStore.getComponent(blockEntityRef, TinyFarmsPlugin.get().getTinyMachineComponentType());
        if (benchState == null) {
            final var playerRefComponent = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
            if (playerRefComponent != null) {
                playerRefComponent.sendMessage(Message.translation("server.interactions.invalidBlockState")
                    .param("interaction", getClass().getSimpleName())
                    .param("blockState", "null"));
            }
            return;
        }

        final var benchBlock = chunkStoreStore.getComponent(blockEntityRef, BenchBlock.getComponentType());
        if (benchBlock == null) return;

        final var blockStateInfo = chunkStoreStore.getComponent(blockEntityRef, BlockModule.BlockStateInfo.getComponentType());
        if (blockStateInfo == null) return;

        final var blockSection = chunkStoreStore.getComponent(sectionRef, BlockSection.getComponentType());
        if (blockSection == null) return;
        final var blockType = BlockType.getAssetMap().getAsset(blockSection.get(pos.x, pos.y, pos.z));
        if (blockType == null || blockType.getBench() == null) return;

        final var uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) return;
        final var uuid = uuidComponent.getUuid();
        final int rotationIndex = blockSection.getRotationIndex(pos.x, pos.y, pos.z);
        final var window = new ProcessingBenchWindow(benchState, benchBlock, blockStateInfo, pos.x, pos.y, pos.z, rotationIndex, blockType);

        final var windows = benchBlock.getWindows();
        if (windows.putIfAbsent(uuid, window) == null) {
            TinyMachines.refreshFuelDisplay(benchState, TinyMachines.findFuelToolSlot(benchState, 0), windows);

            if (playerComponent.getPageManager().setPageWithWindows(ref, store, Page.Bench, true, window)) {
                window.registerCloseEvent(event -> {
                    windows.remove(uuid, window);
                    final var currentSectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(pos.x, pos.y, pos.z);
                    if (currentSectionRef == null || !currentSectionRef.isValid()) return;
                    final var currentBlockSection = currentSectionRef.getStore().getComponent(currentSectionRef, BlockSection.getComponentType());
                    if (currentBlockSection == null) return;
                    final var currentBlockType = BlockType.getAssetMap().getAsset(currentBlockSection.get(pos.x, pos.y, pos.z));
                    if (currentBlockType == null || currentBlockType == BlockType.EMPTY || currentBlockType == BlockType.UNKNOWN) return;

                    final String interactionState = currentBlockType.getCurrentInteractionState();

                    if (windows.isEmpty() && !PROCESSING.equals(interactionState) && !PROCESS_COMPLETED.equals(interactionState)) {
                        BlockOperations.setBlockInteractionState(chunkStore, currentSectionRef, pos.x, pos.y, pos.z,
                            BenchBlock.getBaseBlockType(currentBlockType), benchBlock.getTierStateName(), false);
                    }

                    final int soundEventIndex = blockType.getBench().getLocalCloseSoundEventIndex();
                    if (soundEventIndex == SoundEvent.EMPTY_ID) return;

                    SoundUtil.playSoundEvent2d(ref, soundEventIndex, SoundCategory.UI, commandBuffer);
                });

                final int soundEventIndex = blockType.getBench().getLocalOpenSoundEventIndex();
                if (soundEventIndex == SoundEvent.EMPTY_ID) return;

                SoundUtil.playSoundEvent2d(ref, soundEventIndex, SoundCategory.UI, commandBuffer);
            } else {
                windows.remove(uuid, window);
            }
        }
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nullable ItemStack itemInHand, @Nonnull World world, @Nonnull Vector3i targetBlock) {}
}

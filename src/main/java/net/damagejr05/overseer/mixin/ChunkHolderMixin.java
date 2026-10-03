package net.damagejr05.overseer.mixin;

import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.damagejr05.overseer.core.net.PacketRedirection;
import net.damagejr05.overseer.core.net.RemoteChunkTracker;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;
import java.util.List;

/**
 * Keeps a streamed chunk up to date after it has been sent.
 *
 * <p>{@link RemoteChunkTracker} sends each chunk once. Everything that changes afterwards -- a
 * block placed, a door opened, the light from a torch -- goes out through here in vanilla, to
 * whoever is watching the chunk, and somebody looking at it through a portal is not on that list.
 * Without this the far side of a doorway is a photograph of the moment it was first seen.
 *
 * <p>The packets are built again rather than intercepted on their way out, because vanilla does
 * not build them at all when nobody is watching the chunk -- which, for a memory dimension with
 * no one in it, is always.
 *
 * @author Flex
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    @Shadow @Final private HeightLimitView world;
    @Shadow @Final private ShortSet[] blockUpdatesBySection;
    @Shadow @Final private BitSet blockLightUpdateBits;
    @Shadow @Final private BitSet skyLightUpdateBits;
    @Shadow @Final private LightingProvider lightingProvider;
    @Shadow private boolean pendingBlockUpdates;

    @Shadow public abstract boolean hasPendingUpdates();

    // HEAD, because vanilla clears every one of these fields on its way through.
    @Inject(method = "flushUpdates", at = @At("HEAD"))
    private void overseer$streamToPortalViewers(WorldChunk chunk, CallbackInfo ci) {
        if (!this.hasPendingUpdates() || !(chunk.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        RegistryKey<World> dimension = serverWorld.getRegistryKey();
        List<ServerPlayerEntity> viewers = RemoteChunkTracker.viewers(serverWorld.getServer(), dimension, chunk.getPos());

        if (viewers.isEmpty()) {
            return;
        }

        if (!this.skyLightUpdateBits.isEmpty() || !this.blockLightUpdateBits.isEmpty()) {
            overseer$send(viewers, dimension, new LightUpdateS2CPacket(
                    chunk.getPos(), this.lightingProvider, this.skyLightUpdateBits, this.blockLightUpdateBits
            ));
        }

        if (!this.pendingBlockUpdates) {
            return;
        }

        for (int i = 0; i < this.blockUpdatesBySection.length; i++) {
            ShortSet changed = this.blockUpdatesBySection[i];
            if (changed == null) {
                continue;
            }

            ChunkSectionPos section = ChunkSectionPos.from(chunk.getPos(), this.world.sectionIndexToCoord(i));

            if (changed.size() == 1) {
                BlockPos pos = section.unpackBlockPos(changed.iterator().nextShort());
                BlockState state = serverWorld.getBlockState(pos);

                overseer$send(viewers, dimension, new BlockUpdateS2CPacket(pos, state));
                overseer$sendBlockEntity(viewers, serverWorld, pos, state);
            } else {
                ChunkDeltaUpdateS2CPacket delta = new ChunkDeltaUpdateS2CPacket(section, changed, chunk.getSection(i));

                overseer$send(viewers, dimension, delta);
                delta.visitUpdates((pos, state) -> overseer$sendBlockEntity(viewers, serverWorld, pos, state));
            }
        }
    }

    private static void overseer$sendBlockEntity(List<ServerPlayerEntity> viewers, ServerWorld world,
                                                 BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        Packet<?> packet = blockEntity == null ? null : blockEntity.toUpdatePacket();

        if (packet != null) {
            overseer$send(viewers, world.getRegistryKey(), packet);
        }
    }

    private static void overseer$send(List<ServerPlayerEntity> viewers, RegistryKey<World> dimension, Packet<?> packet) {
        Packet<?> wrapped = PacketRedirection.wrap(dimension, packet);

        for (ServerPlayerEntity viewer : viewers) {
            viewer.networkHandler.sendPacket(wrapped);
        }
    }
}

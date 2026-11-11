package net.damagejr05.overseer.core.client;

import net.damagejr05.overseer.core.Overseer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.chunk.BlockBufferAllocatorStorage;
import net.minecraft.client.render.chunk.BlockBufferBuilderPool;
import net.minecraft.client.util.BufferAllocator;

/**
 * The buffer storage behind one world renderer built by {@link SecondaryWorldManager}, and the
 * means of giving it back.
 *
 * <p>A {@link BufferBuilderStorage} is mostly memory the garbage collector cannot see: a set of
 * terrain meshing buffers for every worker thread, a little over nine megabytes each, plus one
 * buffer per entity render layer. On an eight-thread machine that is some eighty megabytes
 * allocated straight from the operating system.
 *
 * <p>Vanilla makes one of these at startup and keeps it until the process exits, so nothing in it
 * was ever written to be released -- the storage has no {@code close}, and closing the renderer
 * that uses it does not touch it. A secondary world is different: it is dropped whenever the
 * player walks away from a doorway and built again when they come back. Without this class every
 * one of those round trips left the whole allocation behind, and an evening spent near a doorway
 * ended with the game holding gigabytes it could never use or return.
 *
 * <p>Two things are done about it. A closed renderer's storage is kept as a spare and handed to
 * the next renderer built, so walking up to a doorway, away and back does not allocate all of it
 * again each time. And a spare nobody has wanted for a while is released, so the memory is not
 * held for the rest of the session by a doorway visited once.
 *
 * <p>Neither happens straight away. Sections are meshed on worker threads, each holding one
 * buffer set from the pool for as long as it runs, and a job that was already under way when its
 * renderer was closed finishes in its own time. Handing its set to another renderer would have
 * two jobs writing one buffer, and freeing it would be a write into freed memory -- a hard crash
 * of the whole process, not an exception. So a storage is neither reused nor freed until every
 * set has been handed back.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
final class RendererBuffers {
    /**
     * Ticks a closed renderer's buffers are kept as a spare before being released: half a
     * minute, on top of the ten seconds an empty world is itself kept for.
     */
    private static final int SPARE_TICKS = 600;

    /**
     * Ticks after which a buffer set that still has not come back never will. A meshing job that
     * threw does not return its set; by then the game is on its way to a crash report anyway.
     */
    private static final int GIVE_UP_TICKS = 1200;

    private final BufferBuilderStorage storage;

    /** How many buffer sets the pool was built with, which is how many must return to it. */
    private final int poolSize;

    private int retiredTicks = 0;

    RendererBuffers(BufferBuilderStorage storage) {
        this.storage = storage;
        this.poolSize = storage.getBlockBufferBuildersPool().getAvailableBuilderCount();
    }

    BufferBuilderStorage storage() {
        return storage;
    }

    /**
     * Whether every buffer set is back in the pool, which is when the storage may be given to
     * another renderer or released.
     *
     * <p>The count is volatile and written last by whichever thread returns a set, so reading it
     * is also what makes that thread's changes to the pool visible to this one. Once it is back
     * to full on a stopped builder, nothing of the old renderer's will touch the pool again.
     */
    boolean idle() {
        return storage.getBlockBufferBuildersPool().getAvailableBuilderCount() >= poolSize;
    }

    /** Taken as a spare for a new renderer: it is in use again, and no longer waiting to go. */
    void reuse() {
        retiredTicks = 0;
    }

    /**
     * Called once a tick while the storage is a spare.
     *
     * @return true once the memory has been released and this can be forgotten
     */
    boolean tickRetired() {
        if (++retiredTicks < SPARE_TICKS) {
            return false;
        }

        boolean allReturned = idle();

        if (!allReturned && retiredTicks < GIVE_UP_TICKS) {
            return false;
        }

        try {
            release(allReturned);
        } catch (Throwable e) {
            Overseer.LOGGER.error("Failed to release a secondary world renderer's buffers", e);
        }

        return true;
    }

    private void release(boolean allReturned) {
        if (allReturned) {
            BlockBufferBuilderPool pool = storage.getBlockBufferBuildersPool();
            BlockBufferAllocatorStorage set;

            while ((set = pool.acquire()) != null) {
                set.close();
            }
        } else {
            // A set is still out with a worker. The pool cannot be drained safely while that is
            // so, and what is left in it is abandoned rather than risked.
            Overseer.LOGGER.warn("A secondary world renderer never got all of its meshing buffers back; leaving the pool allocated");
        }

        // None of the rest is ever used off the render thread. Several allocators appear in more
        // than one of these; closing one twice is harmless.
        storage.getBlockBufferBuilders().close();
        close(storage.getEntityVertexConsumers());
        close(storage.getEffectVertexConsumers());
        close(storage.getOutlineVertexConsumers().plainDrawer);

        Overseer.LOGGER.debug("Released a secondary world renderer's buffers ({} meshing sets)", poolSize);
    }

    private static void close(VertexConsumerProvider.Immediate consumers) {
        consumers.allocator.close();
        consumers.layerBuffers.values().forEach(BufferAllocator::close);
    }
}

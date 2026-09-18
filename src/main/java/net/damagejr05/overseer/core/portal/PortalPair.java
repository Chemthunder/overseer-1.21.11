package net.damagejr05.overseer.core.portal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.Nullable;

/**
 * A linked doorway with one end in the overworld and one in the memory dimension.
 *
 * <p>There are two of these. The <em>fixed</em> pair is permanent; the <em>dynamic</em> pair
 * expires. Both memory-side ends sit at known spots near the origin, so the memory dimension acts
 * as a corridor between them: step in through one doorway, walk across whatever has been built
 * there, and step out through the other.
 *
 * @author Flex
 */
public record PortalPair(PortalAnchor outer, PortalAnchor inner) {
    public static final Codec<PortalPair> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PortalAnchor.CODEC.fieldOf("Outer").forGetter(PortalPair::outer),
            PortalAnchor.CODEC.fieldOf("Inner").forGetter(PortalPair::inner)
    ).apply(instance, PortalPair::new));

    /** Walking into the overworld end lands you at the memory end. */
    public PortalTransform inward() {
        return new PortalTransform(outer, inner);
    }

    /** Walking into the memory end lands you back at the overworld end. */
    public PortalTransform outward() {
        return new PortalTransform(inner, outer);
    }

    /**
     * The transform for whichever end sits in {@code dimension}, or null if neither does.
     */
    public @Nullable PortalTransform from(net.minecraft.registry.RegistryKey<net.minecraft.world.World> dimension) {
        if (outer.dimension() == dimension) {
            return inward();
        }

        if (inner.dimension() == dimension) {
            return outward();
        }

        return null;
    }

    /** The end that lives in {@code dimension}, or null. */
    public @Nullable PortalAnchor anchorIn(net.minecraft.registry.RegistryKey<net.minecraft.world.World> dimension) {
        if (outer.dimension() == dimension) {
            return outer;
        }

        if (inner.dimension() == dimension) {
            return inner;
        }

        return null;
    }
}

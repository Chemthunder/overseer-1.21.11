package net.damagejr05.overseer.core.index;

import net.damagejr05.overseer.core.Overseer;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;

import java.util.function.Function;

/**
 * Blocks added by the mod.
 *
 * @author Flex
 */
public class ModBlocks {
    /**
     * The floor of the memory dimension. Reads as void until you stand on it.
     */
    public static final Block VOID_MATTER = register(
            "void_matter",
            Block::new,
            AbstractBlock.Settings.create()
                    .mapColor(MapColor.BLACK)
                    .strength(1.5F, 6.0F)
                    .requiresTool()
                    .sounds(BlockSoundGroup.DEEPSLATE)
    );

    private static Block register(String name, Function<AbstractBlock.Settings, Block> factory, AbstractBlock.Settings settings) {
        RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, Overseer.id(name));
        Block block = factory.apply(settings.registryKey(blockKey));
        Registry.register(Registries.BLOCK, blockKey, block);

        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, Overseer.id(name));
        Registry.register(Registries.ITEM, itemKey, new BlockItem(
                block,
                new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey()
        ));

        return block;
    }

    public static void init() {
    }
}

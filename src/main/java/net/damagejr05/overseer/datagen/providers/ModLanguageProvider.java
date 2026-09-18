package net.damagejr05.overseer.datagen.providers;

import net.damagejr05.overseer.core.index.ModBlocks;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.registry.RegistryWrapper;

import java.util.concurrent.CompletableFuture;

/**
 * @author Chemthunder
 */
public class ModLanguageProvider extends FabricLanguageProvider {
    public ModLanguageProvider(FabricDataOutput dataOutput, CompletableFuture<RegistryWrapper.WrapperLookup> registryLookup) {
        super(dataOutput, registryLookup);
    }

    public void generateTranslations(RegistryWrapper.WrapperLookup wrapperLookup, TranslationBuilder translationBuilder) {
        translationBuilder.add("broadcast.ban.death", "%s was removed from divinity's eyes");
        translationBuilder.add("broadcast.ban.disconnect", "%s left the game");

        translationBuilder.add(ModBlocks.VOID_MATTER, "Void Matter");
    }
}

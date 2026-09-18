package net.damagejr05.overseer.datagen;

import net.damagejr05.overseer.datagen.providers.ModBlockLootTableProvider;
import net.damagejr05.overseer.datagen.providers.ModLanguageProvider;
import net.damagejr05.overseer.datagen.providers.ModModelProvider;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public class OverseerDataGenerator implements DataGeneratorEntrypoint {
	public void onInitializeDataGenerator(FabricDataGenerator fdg) {
        var pack = fdg.createPack();

        pack.addProvider(ModLanguageProvider::new);
        pack.addProvider(ModModelProvider::new);
        pack.addProvider(ModBlockLootTableProvider::new);
	}
}

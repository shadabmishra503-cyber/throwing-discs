package com.throwingdiscs;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

public class ThrowingDiscsMod implements ModInitializer {
	public static final String MOD_ID = "tdisc";

	private static ResourceKey<LootTable> table(String namespace, String path) {
		return ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(namespace, path));
	}

	private static final ResourceKey<LootTable> VANILLA_RARE = table("minecraft", "chests/trial_chambers/reward_rare");
	private static final ResourceKey<LootTable> VANILLA_OMINOUS_RARE = table("minecraft", "chests/trial_chambers/reward_ominous_rare");
	private static final ResourceKey<LootTable> DISC_RARE = table(MOD_ID, "chests/trial_chambers/reward_rare_disc");
	private static final ResourceKey<LootTable> DISC_OMINOUS_RARE = table(MOD_ID, "chests/trial_chambers/reward_ominous_rare_disc");

	@Override
	public void onInitialize() {
		ModEntities.register();
		ModItems.register();

		// The Bedrock add-on replaced the two trial chamber "rare" reward tables. On Java we add one extra
		// pool instead, so none of the vanilla rewards are lost. The pool just points at our own small
		// loot tables (data/tdisc/loot_table/...), which hold the odds and the enchanting.
		LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
			if (key.equals(VANILLA_RARE)) {
				builder.withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F)).add(NestedLootTable.lootTableReference(DISC_RARE)));
			} else if (key.equals(VANILLA_OMINOUS_RARE)) {
				builder.withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F)).add(NestedLootTable.lootTableReference(DISC_OMINOUS_RARE)));
			}
		});
	}
}

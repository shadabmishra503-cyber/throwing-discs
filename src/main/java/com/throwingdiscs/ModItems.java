package com.throwingdiscs;

import java.util.EnumMap;
import java.util.Map;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public final class ModItems {
	public static final Map<DiscTier, Item> DISCS = new EnumMap<>(DiscTier.class);

	private ModItems() {}

	public static void register() {
		for (DiscTier tier : DiscTier.values()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(ThrowingDiscsMod.MOD_ID, tier.itemName));
			Item.Properties props = new Item.Properties()
					.setId(key)
					.durability(tier.durability) // also makes it a stack of 1
					.enchantable(tier.enchantability)
					.repairable(tier.repairItem); // repair ingot = +25 %, like the Bedrock version
			DISCS.put(tier, Registry.register(BuiltInRegistries.ITEM, key, new ThrowingDiscItem(props, tier)));
		}

		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.COMBAT).register(entries -> {
			for (DiscTier tier : DiscTier.values()) {
				entries.accept(DISCS.get(tier));
			}
		});
	}
}

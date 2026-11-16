package com.throwingdiscs;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The five disc tiers. Numbers are copied from the Bedrock add-on
 * (damage / speed / sound pitch from main.js, durability / enchantability from the item files).
 */
public enum DiscTier {
	COPPER("copper_throwing_disc", 4.0, 1.30, 1.00F, 190, 13, Items.COPPER_INGOT),
	IRON("iron_throwing_disc", 5.0, 1.30, 1.00F, 250, 14, Items.IRON_INGOT),
	GOLDEN("golden_throwing_disc", 3.5, 1.55, 1.15F, 30, 22, Items.GOLD_INGOT),
	DIAMOND("diamond_throwing_disc", 6.0, 1.35, 1.10F, 1560, 10, Items.DIAMOND),
	NETHERITE("netherite_throwing_disc", 7.0, 1.35, 0.85F, 2030, 15, Items.NETHERITE_INGOT);

	public final String itemName;
	/** Base damage of one hit. */
	public final double damage;
	/** Blocks per tick on the way out. */
	public final double speed;
	public final float pitch;
	public final int durability;
	public final int enchantability;
	public final Item repairItem;

	DiscTier(String itemName, double damage, double speed, float pitch, int durability, int enchantability, Item repairItem) {
		this.itemName = itemName;
		this.damage = damage;
		this.speed = speed;
		this.pitch = pitch;
		this.durability = durability;
		this.enchantability = enchantability;
		this.repairItem = repairItem;
	}
}

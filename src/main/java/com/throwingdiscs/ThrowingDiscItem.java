package com.throwingdiscs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ThrowingDiscItem extends Item {
	public final DiscTier tier;

	public ThrowingDiscItem(Properties properties, DiscTier tier) {
		super(properties);
		this.tier = tier;
	}

	/**
	 * Right-click throws the disc from whichever hand holds it. Java already has a real off-hand,
	 * so the Bedrock add-on's crouch + click workaround is not needed here.
	 */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
			if (!ThrowingDiscEntity.tryThrow(serverLevel, serverPlayer, hand, stack, tier)) {
				return InteractionResult.FAIL;
			}
		}
		return InteractionResult.SUCCESS;
	}
}

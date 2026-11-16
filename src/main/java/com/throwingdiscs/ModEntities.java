package com.throwingdiscs;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final ResourceKey<EntityType<?>> THROWING_DISC_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(ThrowingDiscsMod.MOD_ID, "throwing_disc"));

	public static final EntityType<ThrowingDiscEntity> THROWING_DISC = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			THROWING_DISC_KEY,
			EntityType.Builder.<ThrowingDiscEntity>of(ThrowingDiscEntity::new, MobCategory.MISC)
					.sized(0.3F, 0.15F)
					.fireImmune()
					.clientTrackingRange(10)
					.updateInterval(1) // the disc is moved every tick, so sync every tick
					.build(THROWING_DISC_KEY));

	private ModEntities() {}

	/** Touching this class registers the entity type; call from the mod initializer. */
	public static void register() {}
}

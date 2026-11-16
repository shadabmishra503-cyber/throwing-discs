package com.throwingdiscs.client;

import com.throwingdiscs.ModEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;

public class ThrowingDiscsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// The flying disc is drawn with the disc's own item model (same texture as the inventory icon).
		EntityRendererRegistry.register(ModEntities.THROWING_DISC, ThrownItemRenderer::new);
	}
}

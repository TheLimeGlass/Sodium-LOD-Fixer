package me.limeglass.sodiumfixer;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SodiumLODFixer implements ModInitializer {
	public static final String MOD_ID = "sodium-lod-fixer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Sodium LOD fixer initialized");
	}
}

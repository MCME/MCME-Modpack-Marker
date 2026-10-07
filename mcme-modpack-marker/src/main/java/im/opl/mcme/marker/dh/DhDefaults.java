package im.opl.mcme.marker.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Distant Horizons' settings MCME needs, set through its API - which
 * overrides the player's own while this mod is installed, and changes nothing
 * in their config file.
 *
 * <p>Biome blending off (McmeConfig.dhBiomeBlendingOff): DH blends a LOD's
 * water colour over the biomes around it, and where it can't tell one of them
 * it gives up and tints none - leaving rivers, which always have their banks
 * within reach, the water texture's untinted grey, while open water keeps its
 * colour. Blending changes nothing to see at a LOD's distance, and DH loads
 * faster without it.
 *
 * <p>Through reflection, so that this mod needs nothing of DH to build or run;
 * DH's configs are there only once it has started, so tried each tick until then.
 */
public final class DhDefaults {
	private static boolean done;

	private DhDefaults() {
	}

	public static void register() {
		if (!FabricLoader.getInstance().isModLoaded("distanthorizons")) return;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!done) done = apply();
		});
	}

	/** Sets DH's biome blending as the settings say; true once done, or once it can't be. */
	public static boolean apply() {
		if (!FabricLoader.getInstance().isModLoaded("distanthorizons")) return true;
		try {
			Object configs = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
			if (configs == null) return false;
			Object graphics = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig").getMethod("graphics").invoke(configs);
			Object blending = Class.forName("com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig").getMethod("getBiomeBlending").invoke(graphics);
			Class<?> value = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
			if (McmeConfig.get().dhBiomeBlendingOff) {
				Object set = value.getMethod("setValue", Object.class).invoke(blending, 0);
				MCMEModpackMarker.LOGGER.info("Distant Horizons' biome blending set off by MCME: {}", Boolean.TRUE.equals(set) ? "done" : "refused");
			} else {
				value.getMethod("clearValue").invoke(blending);
				MCMEModpackMarker.LOGGER.info("Distant Horizons' biome blending left to its own setting");
			}
		} catch (Exception e) {
			MCMEModpackMarker.LOGGER.warn("Couldn't set Distant Horizons' biome blending", e);
		}
		return true;
	}
}

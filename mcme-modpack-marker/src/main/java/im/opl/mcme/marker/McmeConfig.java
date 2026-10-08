package im.opl.mcme.marker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's settings for what this mod does, in
 * config/mcme-modpack-marker.json - everything on unless switched off, from
 * the settings screen (SettingsScreen: Mod Menu, or /mcme) or the file.
 */
public final class McmeConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mcme-modpack-marker.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static McmeConfig current;

	/** Download new versions of this mod by itself, put in place when the game closes. */
	public boolean autoUpdate = true;
	/** Shader packs MCME has a recipe for get the fire eye and lava drawn into them (an edited copy). */
	public boolean shaderPackRecipes = true;
	/** Every shader pack's terrain shows the resource packs' water, lava and tar. */
	public boolean shaderPackTerrain = true;
	/** The fire eye drawn over a shader pack's picture, where no recipe draws it. */
	public boolean shaderPackEye = true;
	/** Distant Horizons' LODs drawn with the resource packs' shaders: the fire eye and lava far off. */
	public boolean dhResourcePackShaders = true;
	/** Water, lava and the eye animate by real time, not the world's clock, which jumps when the game stutters. */
	public boolean realTimeClock = true;
	/** Distant Horizons' biome blending off, which leaves rivers grey in its LODs. */
	public boolean dhBiomeBlendingOff = true;
	/** Distant Horizons' LODs as the blocks look: custom models (Special Model Loader's) by their own faces, each slab and mushroom state by its own model, leaf slabs on doors and trapdoors not left out. */
	public boolean dhExactColors = true;
	/** For finding faults, in the file only: every shader-pack program the mod edits written to .minecraft/mcme/iris/. */
	public boolean debugShaderDumps = false;

	public static McmeConfig get() {
		if (current == null) current = load();
		return current;
	}

	private static McmeConfig load() {
		try {
			if (Files.isRegularFile(FILE)) {
				McmeConfig config = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), McmeConfig.class);
				if (config != null) return config;
			}
		} catch (Exception e) {
			MCMEModpackMarker.LOGGER.warn("Couldn't read {}; using the defaults", FILE, e);
		}
		McmeConfig config = new McmeConfig();
		config.save();
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			MCMEModpackMarker.LOGGER.warn("Couldn't save {}", FILE, e);
		}
	}
}

package im.opl.mcme.marker.shaderpacks;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Hands Iris an edited copy of the shader pack it loads, if one of
 * ShaderPackPatcher's recipes takes it: drawing RP-Mordor's fire eye, and its
 * lava, from the shaders the resource packs have (minecraft:shaders/include/).
 * The pack itself is left alone and keeps its name, and so its settings; the
 * copy is kept in .minecraft/mcme/shaderpacks/, and made again only when the
 * pack, those shaders or this mod change. Any trouble, and Iris gets the pack
 * as it is.
 */
public final class PatchedShaderPacks {
	/** Changed with anything here or in the patcher that changes a copy. */
	private static final String VERSION = "2";
	private static final String FINGERPRINT = ".mcme-fingerprint";
	private static final String DONE = ".mcme-recipe";
	private static final String[] PATCHER_PARTS = {"ShaderPackPatcher.class", "/mcme/patch/fire_eye_draw.glsl", "/mcme/patch/fire_eye_face.glsl",
		"/mcme/patch/lava_pack.glsl", "/mcme/patch/lod.glsl", "/mcme/patch/pass.fsh", "/mcme/patch/pass.vsh"};

	private PatchedShaderPacks() {
	}

	/**
	 * What a recipe did to the shader pack Iris loaded last - the eye, and the
	 * lava where it put it - which MCME's edits for any pack
	 * ({@code im.opl.mcme.marker.iris}) then leave alone. NONE if no recipe took it.
	 */
	public record Recipe(String name, boolean eye, Set<ShaderPackPatcher.LavaWhere> lava) {
		public static final Recipe NONE = new Recipe(null, false, EnumSet.noneOf(ShaderPackPatcher.LavaWhere.class));

		public boolean lavaIn(ShaderPackPatcher.LavaWhere where) {
			return lava.contains(where);
		}
	}

	private static volatile Recipe current = Recipe.NONE;

	/** What a recipe did to the shader pack Iris loaded last. */
	public static Recipe current() {
		return current;
	}

	/** Iris is loading a shader pack: none of a recipe's edits until substitute() says otherwise. */
	public static void reset() {
		current = Recipe.NONE;
	}

	/** What Iris should load for the pack named name, whose shaders/ folder is shaders. */
	public static Path substitute(Path shaders, String name) {
		current = Recipe.NONE;
		if (!McmeConfig.get().shaderPackRecipes) return shaders;
		try {
			if (Files.exists(shaders.resolveSibling(ShaderPackPatcher.MARKER)) || Files.exists(shaders.resolve(ShaderPackPatcher.MARKER))) {
				// already edited, by the installer or the old patch script: the eye
				// at least, its lava unknown (a second go at it does no harm)
				current = new Recipe("an earlier edit", true, EnumSet.noneOf(ShaderPackPatcher.LavaWhere.class));
				MCMEModpackMarker.LOGGER.info("Shader pack {} was edited for MCME already, so is loaded as it is; pick the pack it was made from to have MCME edit it now", name);
				return shaders;
			}
			ShaderPackPatcher.Eye eye = ShaderPackPatcher.Eye.from(PatchedShaderPacks::include);
			if (eye == null) return shaders;    // no resource pack with the eye

			String fingerprint = fingerprint(shaders, eye);
			Path copy = FabricLoader.getInstance().getGameDir().resolve("mcme/shaderpacks").resolve(safe(name));
			Path copied = copy.resolve("shaders");
			Path stamp = copy.resolve(FINGERPRINT);
			Path done = copy.resolve(DONE);
			if (Files.isRegularFile(stamp) && Files.readString(stamp).equals(fingerprint) && Files.isRegularFile(done)) {
				current = read(done);
				return copied;
			}
			delete(copy);
			copyTree(shaders, copied);
			ShaderPackPatcher.Patched patched;
			try {
				patched = ShaderPackPatcher.patch(copy, eye);
			} catch (ShaderPackPatcher.NotSupported e) {
				delete(copy);
				MCMEModpackMarker.LOGGER.info("Shader pack {} has no MCME recipe, so gets MCME's edits for any pack: {}", name, e.refusals);
				return shaders;
			}
			Recipe recipe = new Recipe(patched.recipe(), true, patched.lava());
			Files.writeString(done, recipe.name() + "\n" + recipe.lava().stream().map(Enum::name).collect(Collectors.joining(",")) + "\n");
			Files.writeString(stamp, fingerprint);
			current = recipe;
			MCMEModpackMarker.LOGGER.info("Shader pack {} edited by MCME ({}), in {}", name, patched.describe(), copy);
			return copied;
		} catch (Exception e) {
			current = Recipe.NONE;
			MCMEModpackMarker.LOGGER.warn("Couldn't edit shader pack {} for MCME; loading it as it is", name, e);
			return shaders;
		}
	}

	// what a recipe did, as substitute() wrote it
	private static Recipe read(Path done) throws IOException {
		List<String> lines = Files.readAllLines(done);
		Set<ShaderPackPatcher.LavaWhere> lava = EnumSet.noneOf(ShaderPackPatcher.LavaWhere.class);
		if (lines.size() > 1 && !lines.get(1).isBlank()) {
			for (String where : lines.get(1).split(",")) lava.add(ShaderPackPatcher.LavaWhere.valueOf(where.strip()));
		}
		return new Recipe(lines.isEmpty() ? "?" : lines.get(0), true, lava);
	}

	// the resource packs' minecraft:shaders/include/name, or null
	private static String include(String name) {
		try {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager()
				.getResource(Identifier.fromNamespaceAndPath(Identifier.DEFAULT_NAMESPACE, "shaders/include/" + name));
			if (resource.isEmpty()) return null;
			try (InputStream in = resource.get().open()) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			return null;
		}
	}

	// of everything the copy is made from: the pack's files, the includes and this mod
	private static String fingerprint(Path shaders, ShaderPackPatcher.Eye eye) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(("mcme " + VERSION + " " + MCMEModpackMarker.version() + "\n").getBytes(StandardCharsets.UTF_8));
		// the recipes themselves, so a build that changes them makes the copy again
		for (String part : PATCHER_PARTS) {
			try (InputStream in = PatchedShaderPacks.class.getResourceAsStream(part)) {
				if (in != null) digest.update(in.readAllBytes());
			}
		}
		for (Map.Entry<String, String> include : eye.includes.entrySet()) {
			digest.update((include.getKey() + "\n").getBytes(StandardCharsets.UTF_8));
			digest.update(include.getValue().getBytes(StandardCharsets.UTF_8));
		}
		for (Path file : files(shaders)) {
			digest.update((shaders.relativize(file).toString().replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static List<Path> files(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			List<Path> files = new ArrayList<>(walk.filter(Files::isRegularFile).toList());
			files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));
			return files;
		}
	}

	// works from a zip's file system into the real one too
	private static void copyTree(Path from, Path to) throws IOException {
		for (Path file : files(from)) {
			Path target = to.resolve(from.relativize(file).toString().replace('\\', '/'));
			Files.createDirectories(target.getParent());
			Files.copy(file, target);
		}
	}

	private static void delete(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
		}
	}

	private static String safe(String name) {
		String safe = name.replaceAll("[^A-Za-z0-9._ +-]", "_");
		return safe.isBlank() ? "pack" : safe;
	}
}

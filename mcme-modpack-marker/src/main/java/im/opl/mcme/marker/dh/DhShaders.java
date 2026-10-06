package im.opl.mcme.marker.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Distant Horizons' OpenGL renderer, which it switches to whenever Iris is
 * installed, reads its shaders from its own jar only, so a resource pack can't
 * change how its LODs look - while its Blaze3D renderer reads them through the
 * game's resources. This gives the OpenGL renderer the same: a shader a resource
 * pack has at the same path is used instead of DH's own, with
 * {@code #moj_import <namespace:file>} lines filled in from the packs'
 * {@code shaders/include/} as the game does.
 *
 * <p>And as DH's OpenGL terrain shader isn't told where the camera is, nor the
 * time, it is given both: {@code uMcmeCameraBlock} (ivec3) and
 * {@code uMcmeCameraFrac} (vec3), the camera's block and where in it, and
 * {@code uMcmeTime} (float), seconds into the day as vanilla's
 * {@code GameTime * 1200.0}. A shader that doesn't declare them is untouched.
 */
public final class DhShaders {
	private static final Pattern IMPORT = Pattern.compile("^\\s*#moj_import\\s*<(?:([a-z0-9_.-]+):)?([^>]+)>\\s*$");
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	private DhShaders() {
	}

	/**
	 * The resource packs' version of the shader DH loads from {@code path}
	 * (such as {@code assets/distanthorizons/shaders/terrain/gl/vert.vert}), or
	 * null to let DH load its own.
	 */
	public static String load(String path) {
		try {
			if (!path.startsWith("assets/")) return null;
			String rest = path.substring("assets/".length());
			int slash = rest.indexOf('/');
			if (slash <= 0) return null;
			Identifier id = Identifier.tryBuild(rest.substring(0, slash), rest.substring(slash + 1));
			Minecraft minecraft = Minecraft.getInstance();
			if (id == null || minecraft == null || minecraft.getResourceManager() == null) return null;
			Optional<Resource> resource = minecraft.getResourceManager().getResource(id);
			if (resource.isEmpty()) return null;
			String source = expand(read(resource.get()), new HashSet<>(), 0);
			if (LOGGED.add(path)) {
				MCMEModpackMarker.LOGGER.info("Distant Horizons shader {} from {}", path, resource.get().sourcePackId());
			}
			return source;
		} catch (Exception e) {
			MCMEModpackMarker.LOGGER.warn("Couldn't load Distant Horizons shader {} from the resource packs, using its own", path, e);
			return null;
		}
	}

	/** Gives the bound DH program the camera's position and the time, if it asks for them. */
	public static void setUniforms() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null) return;
		int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		if (program == 0) return;
		// looked up each time: cheap, and DH's programs come and go
		int block = GL20.glGetUniformLocation(program, "uMcmeCameraBlock");
		int frac = GL20.glGetUniformLocation(program, "uMcmeCameraFrac");
		int time = GL20.glGetUniformLocation(program, "uMcmeTime");
		if (block < 0 && frac < 0 && time < 0) return;
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		int x = Mth.floor(camera.x), y = Mth.floor(camera.y), z = Mth.floor(camera.z);
		if (block >= 0) GL20.glUniform3i(block, x, y, z);
		if (frac >= 0) GL20.glUniform3f(frac, (float) (camera.x - x), (float) (camera.y - y), (float) (camera.z - z));
		if (time >= 0) GL20.glUniform1f(time, daySeconds());
	}

	/** Seconds into the day, as vanilla's shaders' {@code GameTime * 1200.0}: the clock MCME's effects run by. */
	public static float daySeconds() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) return 0.0f;
		return (minecraft.level.getGameTime() % 24000L + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20.0f;
	}

	/**
	 * The resource packs' {@code minecraft:shaders/include/<file>}, its
	 * {@code #moj_import} lines filled in, or null if none has it.
	 */
	public static String include(String file) {
		try {
			Identifier id = Identifier.fromNamespaceAndPath(Identifier.DEFAULT_NAMESPACE, "shaders/include/" + file);
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
			if (resource.isEmpty()) return null;
			Set<Identifier> seen = new HashSet<>();
			seen.add(id);
			return expand(read(resource.get()), seen, 0);
		} catch (IOException e) {
			MCMEModpackMarker.LOGGER.warn("Couldn't read shader include {} from the resource packs", file, e);
			return null;
		}
	}

	private static String read(Resource resource) throws IOException {
		StringBuilder text = new StringBuilder();
		try (BufferedReader reader = resource.openAsReader()) {
			String line;
			while ((line = reader.readLine()) != null) text.append(line).append('\n');
		}
		return text.toString();
	}

	// #moj_import lines filled in, as the game's shader loader does: each file
	// once, without its own #version line
	private static String expand(String source, Set<Identifier> seen, int depth) throws IOException {
		if (depth > 16) throw new IOException("#moj_import nested too deep");
		StringBuilder out = new StringBuilder();
		for (String line : source.split("\n", -1)) {
			Matcher m = IMPORT.matcher(line);
			if (!m.matches()) {
				out.append(line).append('\n');
				continue;
			}
			String namespace = m.group(1) == null ? Identifier.DEFAULT_NAMESPACE : m.group(1);
			Identifier id = Identifier.fromNamespaceAndPath(namespace, "shaders/include/" + m.group(2));
			if (!seen.add(id)) continue;
			Resource included = Minecraft.getInstance().getResourceManager().getResource(id)
				.orElseThrow(() -> new IOException("#moj_import of " + id + ", which no pack has"));
			out.append(expand(read(included).replaceAll("(?m)^\\s*#version\\b.*$", ""), seen, depth + 1));
		}
		return out.toString();
	}
}

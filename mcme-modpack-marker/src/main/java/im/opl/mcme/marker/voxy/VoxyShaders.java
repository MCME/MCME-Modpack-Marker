package im.opl.mcme.marker.voxy;

import im.opl.mcme.marker.MCMEModpackMarker;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Voxy's LOD terrain, drawn without a shader pack, with a resource pack's
 * part: Voxy reads its shaders from its own jar, so a pack can't replace
 * them. When a pack has minecraft:shaders/include/mcme_voxy_terrain.glsl,
 * Voxy's terrain shaders (lod/gl46/quads3.vert, quads.frag) are patched as
 * they load to call it, its imports filled in from the resource packs. With a shader pack (PATCHED_SHADER) Voxy appends the shader
 * pack's code to the same quads.frag: lava's look goes to it as the colour
 * it samples, for it to light as its own lava.
 *
 * Lava is marked by the top bit of its models' customId (LAVA_BIT): Voxy's
 * for a shader pack's block ids, which may give lava none of its own, and
 * unused without one. Patched quads.frag takes the bit off before a shader
 * pack sees the id.
 *
 * The pack's file gets, in quads.frag:
 * - mcmeLodPoint: the fragment's place, relative to Voxy's baseSectionPos * 32
 *   (cameraSubPos is the camera's);
 * - mcmeLodId: its model's customId, with MCME_VOXY_LAVA_BIT for lava - flat,
 *   so the same over every fragment of a quad;
 * - lightSampler: the game's lightmap, with MCME's clock (mcme_clock.glsl);
 * and defines mcmeVoxyBegin(), called first in main() - before anything that
 * breaks derivatives -, mcmeVoxyTerrain(inout vec4 colour), called on Voxy's
 * colour, and mcmeVoxyShaderPack(inout vec4 colour), called on the colour a
 * shader pack gets.
 */
public final class VoxyShaders {
	/** Lava's mark in its models' customId. */
	public static final int LAVA_BIT = 0x80000000;
	// whether Voxy's quads.frag takes LAVA_BIT off before a shader pack sees customId
	private static volatile boolean marking;
	// whether the quads3.vert Voxy last loaded hands quads.frag mcmeLodPoint and mcmeLodId
	private static volatile boolean vertexPatched;
	private static final Identifier HOOK = Identifier.withDefaultNamespace("shaders/include/mcme_voxy_terrain.glsl");
	// a pack's imports, as vanilla's 26.2 (#moj_import) and 26.3 (#include) shaders write them
	private static final Pattern IMPORT = Pattern.compile("^\\s*#(?:moj_import|include)\\s*<([a-z0-9_.-]+):([^>]+)>", Pattern.MULTILINE);

	private VoxyShaders() {
	}

	/**
	 * Voxy's customIds (a shader pack's block ids, or none), with LAVA_BIT on
	 * lava's while Voxy's quads.frag takes it off again: looked up as Voxy
	 * uploads each model, after its shaders are made.
	 */
	public static Object2IntMap<BlockState> markLava(Object2IntMap<BlockState> ids) {
		Object2IntOpenHashMap<BlockState> marked = new Object2IntOpenHashMap<>() {
			@Override
			public boolean containsKey(Object state) {
				return super.containsKey(state) || marking && isLava(state);
			}

			@Override
			public int getInt(Object state) {
				return marking && isLava(state) ? super.getInt(state) | LAVA_BIT : super.getInt(state);
			}
		};
		if (ids != null) marked.putAll(ids);
		return marked;
	}

	private static boolean isLava(Object state) {
		return state instanceof BlockState block && block.is(Blocks.LAVA);
	}

	/**
	 * The resource packs' part, its imports filled in, as vanilla fills them;
	 * null if a pack doesn't have it, or any of it can't be read. All read
	 * before quads.frag is patched to use it: Voxy crashes the game on a
	 * shader it can't load.
	 */
	private static String packPart() {
		return Minecraft.getInstance().getResourceManager().getResource(HOOK).isEmpty() ? null : expand(HOOK, 0);
	}

	private static String expand(Identifier file, int depth) {
		if (depth > 16) {
			MCMEModpackMarker.LOGGER.warn("MCME found {} imported too deep for Voxy (a loop?)", file);
			return null;
		}
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
		if (resource.isEmpty()) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't find {} for Voxy", file);
			return null;
		}
		String source;
		try (InputStream in = resource.get().open()) {
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't read {} for Voxy", file, e);
			return null;
		}
		// <minecraft:x.glsl> is minecraft:shaders/include/x.glsl
		Matcher imports = IMPORT.matcher(source);
		StringBuilder out = new StringBuilder();
		while (imports.find()) {
			String imported = expand(Identifier.fromNamespaceAndPath(imports.group(1), "shaders/include/" + imports.group(2)), depth + 1);
			if (imported == null) return null;
			imports.appendReplacement(out, Matcher.quoteReplacement(imported));
		}
		return imports.appendTail(out).toString();
	}

	/**
	 * One of Voxy's own shaders, patched: quads.frag always takes LAVA_BIT off
	 * customId; both get the resource packs' part if they have one. Voxy loads
	 * quads3.vert first: quads.frag reads what it hands on only if it was
	 * patched, or the two wouldn't link.
	 */
	public static String patch(Identifier id, String source) {
		if (source == null || !id.getNamespace().equals("voxy")) return source;
		boolean vertex = id.getPath().equals("lod/gl46/quads3.vert");
		if (!vertex && !id.getPath().equals("lod/gl46/quads.frag")) return source;
		// unmarked until patched: should patching fail, lava's ids stay the shader pack's
		if (vertex) vertexPatched = false;
		else marking = false;
		if (vertex && Minecraft.getInstance().getResourceManager().getResource(HOOK).isEmpty()) return source;
		String part = vertex || !vertexPatched ? null : packPart();
		String patched = vertex ? vertex(source) : fragment(source, part);
		if (vertex) vertexPatched = patched != null;
		else marking = patched != null;
		if (patched == null) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't find its places in Voxy's {}, so leaves it as it is", id);
			return source;
		}
		if (vertex || part != null) MCMEModpackMarker.LOGGER.info("MCME patched Voxy's {} with the resource packs' mcme_voxy_terrain.glsl", id);
		return patched;
	}

	// what quads.frag gets: the fragment's place, and its model's customId - read once a vertex, not once a fragment
	private static String vertex(String source) {
		return insert(source,
			"layout(location = 0) out flat uvec4 interData;\n",
			"layout(location = 10) out vec3 mcmeLodPoint;     // MCME: for the resource packs' part of quads.frag\n"
				+ "layout(location = 11) out flat uint mcmeLodId;\n",
			"    interData = quad.attributeData;\n",
			"    mcmeLodPoint = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * (vec2((cornerId>>1)&1u, cornerId&1u) * quad.lodScale), 0));\n"
				+ "    mcmeLodId = modelData[extractStateId(quadData[uint(gl_VertexID)>>2])].customId;\n");
	}

	private static String fragment(String source, String part) {
		String emit = "tint, model.customId));\n", main = "\nvoid main() {\n";
		String patched = insert(source,
			"#import <voxy:lod/gl46/bindings.glsl>\n",
			"// MCME: lava's mark (VoxyShaders), and the resource packs' part (minecraft:shaders/include/mcme_voxy_terrain.glsl)\n"
				+ "#define MCME_VOXY_LAVA_BIT " + Integer.toUnsignedString(LAVA_BIT) + "u\n"
				+ (part != null ? "#define MCME_VOXY\n#define LIGHTING_SAMPLER_BINDING 1\n"
					+ "layout(location = 10) in vec3 mcmeLodPoint;\nlayout(location = 11) flat in uint mcmeLodId;\n" : ""),
			"    colour = computeColour(texPos, colour);\n",
			"    #ifdef MCME_VOXY\n    mcmeVoxyTerrain(colour);\n    #endif\n");
		if (patched == null || !once(patched, emit) || !once(patched, main)) return null;
		// a shader pack's: lava's look as the colour it samples, and customId without the mark
		int line = patched.lastIndexOf('\n', patched.indexOf(emit)) + 1;
		patched = patched.substring(0, line) + "    #ifdef MCME_VOXY\n    mcmeVoxyShaderPack(colour);\n    #endif\n"
			+ patched.substring(line).replace(emit, "tint, model.customId & ~MCME_VOXY_LAVA_BIT));\n");
		if (part == null) return patched;
		return patched.replace(main, "\n// MCME: minecraft:shaders/include/mcme_voxy_terrain.glsl\n" + part + "\n"
			+ main + "    mcmeVoxyBegin();\n");
	}

	// source with each text after its place (each found once), or null
	private static String insert(String source, String... placesAndTexts) {
		for (int i = 0; i < placesAndTexts.length; i += 2) {
			String place = placesAndTexts[i];
			if (!once(source, place)) return null;
			source = source.replace(place, place + placesAndTexts[i + 1]);
		}
		return source;
	}

	private static boolean once(String source, String text) {
		int at = source.indexOf(text);
		return at >= 0 && at == source.lastIndexOf(text);
	}
}

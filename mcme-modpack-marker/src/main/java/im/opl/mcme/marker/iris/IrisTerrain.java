package im.opl.mcme.marker.iris;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.dh.DhShaders;
import im.opl.mcme.marker.shaderpacks.PatchedShaderPacks;
import im.opl.mcme.marker.shaderpacks.ShaderPackPatcher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

/**
 * Edits the shaders Iris makes of any shader pack's terrain - Sodium's, which
 * draws it - as they are about to be compiled: the GLSL Iris has finished,
 * the same whatever the pack, rather than the pack's own files.
 *
 * <ul>
 * <li>The fire eye block's faces are dropped, in the shadow pass too:
 * {@link EyeOverlay} draws the eye over the finished scene instead.</li>
 * <li>Every lookup of the block atlas in the fragment shader is wrapped, so
 * that where it lands on the water's texture, or RP-Mordor's lava's or tar's,
 * the pack gets that fluid's colour as the resource packs draw it, and lights
 * and shades it as it would the texture (/mcme/iris/terrain_fluids.glsl).</li>
 * </ul>
 *
 * The edited program is linked once to try it: if it fails, Iris gets the
 * program as it was, and the reason is logged. Each edited program is also
 * written to .minecraft/mcme/iris/, to see what was done.
 */
public final class IrisTerrain {
	// Iris's names for Sodium's terrain programs (its ShaderKeys, lower case)
	private static final Set<String> TERRAIN = Set.of("sodium_terrain_solid", "sodium_terrain_cutout", "sodium_terrain_translucent");
	private static final String SHADOW_TERRAIN = "shadow_sodium_terrain_";

	// the resource packs' includes each fluid needs, in order; the water's every pack has
	private static final String[] WATER = {"fluid.glsl", "water_config.glsl", "water.glsl"};
	private static final String[] LAVA = {"lava_config.glsl", "lava.glsl"};
	private static final String[] TAR = {"mordor_fluid.glsl", "tar_config.glsl", "tar.glsl"};

	// the block atlas's names - Iris's, gtexture, and tex, which it leaves as
	// it is - and lookups of it, as Iris prints them: texture2D and the rest
	// are texture and the rest by then; functions, and their sampler2D parameters
	private static final List<String> ATLAS_NAMES = List.of("gtexture", "tex");
	private static final Pattern FUNCTION = Pattern.compile("(?m)^\\w+\\s+(\\w+)\\s*\\(([^()]*)\\)\\s*\\{");
	private static final Pattern SAMPLER_PARAMETER = Pattern.compile("\\bsampler2D\\s+(\\w+)");
	// Iris's DH vertex shaders: the colour the pack is given, and lava's
	// material (its StandardMacros' DH_BLOCK_LAVA); the flat colour for it,
	// lava.glsl's lavaShade(0.65) - its melt, between the crust's rafts
	private static final Pattern LOD_COLOUR = Pattern.compile("_vert_color\\s*=\\s*iris_color\\s*;");
	private static final int DH_BLOCK_LAVA = 6;
	private static final String LOD_LAVA = "vec3(0.9, 0.3, 0.03)";
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
	private static final Pattern ATLAS = Pattern.compile("\\buniform\\s+sampler2D\\s+(gtexture|tex)\\s*;");
	private static final String PROTOTYPES = ""
		+ "vec4 mcmeTexture(sampler2D atlas, vec2 uv);\n"
		+ "vec4 mcmeTexture(sampler2D atlas, vec2 uv, float bias);\n"
		+ "vec4 mcmeTextureLod(sampler2D atlas, vec2 uv, float lod);\n"
		+ "vec4 mcmeTextureGrad(sampler2D atlas, vec2 uv, vec2 dx, vec2 dy);\n";
	private static final String[][] FLUID_UNIFORMS = {{"float", "viewWidth"}, {"float", "viewHeight"},
		{"mat4", "gbufferProjectionInverse"}, {"mat4", "gbufferModelViewInverse"},
		{"ivec3", "cameraPositionInt"}, {"vec3", "cameraPositionFract"}, {"float", "frameTimeCounter"}};

	// what the resource packs' includes define at the top level, each renamed mcme_...
	private static final Pattern DEFINED = Pattern.compile("(?m)^(?:#define\\s+(\\w+)|struct\\s+(\\w+)|const\\s+\\w+\\s+(\\w+)"
		+ "|(?!return\\b|const\\b|struct\\b|uniform\\b|in\\b|out\\b|layout\\b|precision\\b)\\w+\\s+(\\w+)\\s*[(=;\\[])");

	private IrisTerrain() {
	}

	/**
	 * The shaders of Iris's program name, stages - keyed by its
	 * PatchShaderType - edited as above, or stages as they are.
	 */
	public static <K> Map<K, String> patch(String name, Map<K, String> stages) {
		if (name == null || stages == null) return stages;
		boolean terrain = TERRAIN.contains(name);
		if (!terrain && !name.startsWith(SHADOW_TERRAIN)) return stages;
		// what a recipe did to the pack is left to it
		PatchedShaderPacks.Recipe recipe = PatchedShaderPacks.current();
		try {
			Map<K, String> patched = new LinkedHashMap<>(stages);
			List<String> done = new ArrayList<>();
			K vertex = key(stages, "VERTEX"), fragment = key(stages, "FRAGMENT");
			if (vertex != null && !recipe.eye()) {
				String edited = dropEyeFaces(stages.get(vertex));
				if (edited != null) {
					patched.put(vertex, edited);
					done.add("the fire eye block dropped");
				}
			}
			if (terrain && fragment != null) {
				Fluids fluids = Fluids.load(DhShaders::include, !recipe.lavaIn(ShaderPackPatcher.LavaWhere.TERRAIN));
				String edited = fluids == null ? null : fluids.addTo(stages.get(fragment));
				if (edited != null) {
					patched.put(fragment, edited);
					done.add(fluids.what);
				}
			}
			return tried(name, stages, patched, done);
		} catch (RuntimeException e) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't edit the shader pack's {}, so left it as it was", name, e);
			return stages;
		}
	}

	/**
	 * The shaders of Iris's program name for Distant Horizons' LODs, with
	 * their lava coloured as molten - flat: DH tells the pack only each
	 * vertex's colour, which the pack then lights and makes glow as its lava -
	 * unless a recipe draws RP-Mordor's lava there, or the resource packs
	 * have none.
	 */
	public static <K> Map<K, String> patchLod(String name, Map<K, String> stages) {
		if (name == null || stages == null || !name.startsWith("dh_")) return stages;
		if (PatchedShaderPacks.current().lavaIn(ShaderPackPatcher.LavaWhere.DISTANT_HORIZONS)) return stages;
		try {
			K vertex = key(stages, "VERTEX");
			if (vertex == null || DhShaders.include("lava.glsl") == null) return stages;
			Matcher colour = LOD_COLOUR.matcher(stages.get(vertex));
			if (!colour.find()) return stages;
			Map<K, String> patched = new LinkedHashMap<>(stages);
			patched.put(vertex, colour.replaceFirst(Matcher.quoteReplacement(
				"_vert_color = dhMaterialId == " + DH_BLOCK_LAVA + " ? vec4(" + LOD_LAVA + ", iris_color.a) : iris_color;")));
			return tried(name, stages, patched, List.of("its LODs' lava coloured molten"));
		} catch (RuntimeException e) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't edit the shader pack's {}, so left it as it was", name, e);
			return stages;
		}
	}

	// patched if it compiles and links, else stages as they were, and why logged
	private static <K> Map<K, String> tried(String name, Map<K, String> stages, Map<K, String> patched, List<String> done) {
		if (done.isEmpty()) return stages;
		String error = linkError(patched);
		dump(name, patched, error);
		if (error != null) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't add {} to the shader pack's {}, so left it as it was: {}",
				String.join(" and ", done), name, error);
			return stages;
		}
		MCMEModpackMarker.LOGGER.info("MCME added to the shader pack's {}: {}", name, String.join(", ", done));
		return patched;
	}

	private static <K> K key(Map<K, String> stages, String stage) {
		for (Map.Entry<K, String> entry : stages.entrySet()) {
			if (String.valueOf(entry.getKey()).equals(stage) && entry.getValue() != null) return entry.getKey();
		}
		return null;
	}

	// ---------------------------------------------------------------- the eye

	/** text, Sodium's terrain vertex shader as Iris made it, dropping the eye's faces; null if it can't. */
	static String dropEyeFaces(String text) {
		if (!text.contains("_vert_tex_diffuse_coord_bias")) return null;
		Matcher main = MAIN.matcher(text);
		if (!main.find()) return null;
		Matcher atlas = ATLAS.matcher(text);
		String sampler = atlas.find() ? atlas.group(1) : null;
		String declare = sampler == null ? "uniform sampler2D gtexture;\n" : "";
		text = text.substring(0, main.start()) + "void mcmePackMain()" + text.substring(main.end());
		return text + "\n" + declare + resource("/mcme/iris/terrain_eye_face.glsl").replace("{atlas}", sampler == null ? "gtexture" : sampler);
	}

	// ---------------------------------------------------------------- the fluids

	/** The fluids' code, from the resource packs' includes, renamed. */
	static final class Fluids {
		final String code;
		final String what;

		private Fluids(String code, String what) {
			this.code = code;
			this.what = what;
		}

		/** From the includes include gives (null for one it hasn't); null without the water's. */
		static Fluids load(Function<String, String> include) {
			return load(include, true);
		}

		/** The same, with the lava only if lava. */
		static Fluids load(Function<String, String> include, boolean lava) {
			StringBuilder library = new StringBuilder();
			if (!append(library, WATER, include)) return null;
			String defines = "";
			String what = "the water";
			if (lava && append(library, LAVA, include)) {
				defines += "#define MCME_LAVA\n";
				what += ", lava";
			}
			if (append(library, TAR, include)) {
				defines += "#define MCME_TAR\n";
				what += ", tar";
			}
			String code = rename(library.toString(), library + resource("/mcme/iris/terrain_fluids.glsl"));
			return new Fluids(defines + code, what);
		}

		// the includes' texts added to library, if the resource packs have them all
		private static boolean append(StringBuilder library, String[] files, Function<String, String> include) {
			StringBuilder texts = new StringBuilder();
			for (String file : files) {
				String text = include.apply(file);
				if (text == null) return false;
				texts.append("// ").append(file).append('\n').append(text).append('\n');
			}
			library.append(texts);
			return true;
		}

		/** text, a terrain fragment shader as Iris made it, with the fluids; null if it has nothing to add them to. */
		String addTo(String text) {
			String wrapped = wrapLookups(text);
			if (wrapped.equals(text)) return null;
			Matcher main = MAIN.matcher(wrapped);
			if (!main.find()) return null;
			text = wrapped.substring(0, main.start()) + "void mcmePackMain()" + wrapped.substring(main.end());
			int head = afterDirectives(text);
			StringBuilder uniforms = new StringBuilder();
			for (String[] uniform : FLUID_UNIFORMS) {
				if (!Pattern.compile("\\buniform\\b[^;]*\\b" + uniform[1] + "\\b").matcher(text).find()) {
					uniforms.append("uniform ").append(uniform[0]).append(' ').append(uniform[1]).append(";\n");
				}
			}
			return text.substring(0, head) + PROTOTYPES + text.substring(head) + "\n" + uniforms + code;
		}
	}

	/**
	 * text with its lookups of the block atlas wrapped: by Iris's names for
	 * it, and inside a function it is passed to, by that function's sampler2D
	 * parameters - Bliss looks it up through one (and its normal and specular
	 * maps through the same, which the wrapping leaves as they are: they carry
	 * no fluid's code).
	 */
	static String wrapLookups(String text) {
		StringBuilder out = new StringBuilder();
		int done = 0;
		Matcher function = FUNCTION.matcher(text);
		int from = 0;
		while (function.find(from)) {
			from = function.end();
			List<String> samplers = new ArrayList<>(ATLAS_NAMES);
			Matcher parameter = SAMPLER_PARAMETER.matcher(function.group(2));
			while (parameter.find()) samplers.add(parameter.group(1));
			if (samplers.size() == ATLAS_NAMES.size()) continue;
			if (!Pattern.compile("\\b" + function.group(1) + "\\s*\\([^;{]*\\b(?:gtexture|tex)\\b").matcher(text).find()) continue;
			int open = function.end() - 1;
			int close = closingBrace(text, open);
			if (close < 0) continue;
			out.append(wrap(text.substring(done, open), ATLAS_NAMES));
			out.append(wrap(text.substring(open, close), samplers));
			done = close;
			from = close;
		}
		return out.append(wrap(text.substring(done), ATLAS_NAMES)).toString();
	}

	// text with lookups of the samplers named wrapped: texture(s, ...) to mcmeTexture(s, ...)
	private static String wrap(String text, List<String> samplers) {
		Pattern lookup = Pattern.compile("\\b(texture|textureLod|textureGrad)(\\s*\\(\\s*(?:" + String.join("|", samplers) + ")\\s*,)");
		return lookup.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
			"mcme" + Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1) + m.group(2)));
	}

	// the index just past the brace closing the one at open, or -1 (Iris prints no comments)
	private static int closingBrace(String text, int open) {
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '{') depth++;
			else if (c == '}' && --depth == 0) return i + 1;
		}
		return -1;
	}

	// where the lines at the top that are directives - #version, #extension - end
	private static int afterDirectives(String text) {
		int at = 0;
		while (at < text.length()) {
			int end = text.indexOf('\n', at);
			end = end < 0 ? text.length() : end + 1;
			String line = text.substring(at, end).strip();
			if (!line.isEmpty() && !line.startsWith("#")) break;
			at = end;
		}
		return at;
	}

	// text with every name library defines at the top level renamed mcme_<name>
	static String rename(String library, String text) {
		Set<String> names = new LinkedHashSet<>();
		Matcher m = DEFINED.matcher(library);
		while (m.find()) {
			for (int g = 1; g <= m.groupCount(); g++) {
				if (m.group(g) != null) names.add(m.group(g));
			}
		}
		if (names.isEmpty()) return text;
		return Pattern.compile("\\b(" + String.join("|", names) + ")\\b").matcher(text).replaceAll("mcme_$1");
	}

	// ---------------------------------------------------------------- trying it

	/** Why stages don't compile and link, or null if they do. */
	private static <K> String linkError(Map<K, String> stages) {
		int program = GL20.glCreateProgram();
		List<Integer> shaders = new ArrayList<>();
		try {
			for (Map.Entry<K, String> stage : stages.entrySet()) {
				if (stage.getValue() == null) continue;
				int type = glType(String.valueOf(stage.getKey()));
				if (type == 0) return "a stage MCME doesn't know, " + stage.getKey();
				int shader = GL20.glCreateShader(type);
				shaders.add(shader);
				GL20.glShaderSource(shader, stage.getValue());
				GL20.glCompileShader(shader);
				if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
					return stage.getKey() + ": " + GL20.glGetShaderInfoLog(shader, 8192).strip();
				}
				GL20.glAttachShader(program, shader);
			}
			GL20.glLinkProgram(program);
			if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
				return "linking: " + GL20.glGetProgramInfoLog(program, 8192).strip();
			}
			return null;
		} finally {
			for (int shader : shaders) GL20.glDeleteShader(shader);
			GL20.glDeleteProgram(program);
		}
	}

	private static int glType(String stage) {
		return switch (stage) {
			case "VERTEX" -> GL20.GL_VERTEX_SHADER;
			case "FRAGMENT" -> GL20.GL_FRAGMENT_SHADER;
			case "GEOMETRY" -> GL32.GL_GEOMETRY_SHADER;
			case "TESS_CONTROL" -> GL40.GL_TESS_CONTROL_SHADER;
			case "TESS_EVAL" -> GL40.GL_TESS_EVALUATION_SHADER;
			default -> 0;
		};
	}

	private static <K> void dump(String name, Map<K, String> stages, String error) {
		try {
			Path folder = FabricLoader.getInstance().getGameDir().resolve("mcme/iris");
			Files.createDirectories(folder);
			for (Map.Entry<K, String> stage : stages.entrySet()) {
				if (stage.getValue() == null) continue;
				Files.writeString(folder.resolve(name + "." + String.valueOf(stage.getKey()).toLowerCase() + ".glsl"), stage.getValue());
			}
			Path errorFile = folder.resolve(name + ".error.txt");
			if (error != null) Files.writeString(errorFile, error);
			else Files.deleteIfExists(errorFile);
		} catch (IOException e) {
			// only for looking at
		}
	}

	static String resource(String name) {
		try (InputStream in = IrisTerrain.class.getResourceAsStream(name)) {
			if (in == null) throw new IllegalStateException("MCME's mod is missing " + name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Couldn't read " + name + " from MCME's mod", e);
		}
	}
}

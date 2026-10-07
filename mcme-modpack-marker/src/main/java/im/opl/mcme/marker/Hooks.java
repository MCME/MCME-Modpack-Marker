package im.opl.mcme.marker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Every class and member of Iris and Distant Horizons this mod hooks or
 * reaches by reflection (docs/architecture.md). Their hooks are optional
 * (require = 0) and catch their own errors, so when an update renames one,
 * its feature just stops - silently. This checks them all once the game has
 * started, logs what is missing and which features that turns off, and
 * /mcme hooks lists it.
 */
public final class Hooks {
	private record Hook(String mod, String feature, String className, String... members) {
	}

	private static final String DH = "com.seibel.distanthorizons.";
	private static final String IRIS = "net.irisshaders.iris.";

	private static final List<Hook> HOOKS = List.of(
		new Hook("iris", "edited shader packs", IRIS + "Iris", "loadShaderpack", "loadExternalShaderpack"),
		new Hook("iris", "edited shader packs", IRIS + "shaderpack.ShaderPack", "<init>"),
		new Hook("iris", "fluids in any shader pack", IRIS + "pipeline.programs.ShaderCreator", "create", "createShadow"),
		new Hook("iris", "lava in shader packs' LODs", IRIS + "compat.dh.IrisLodRenderProgram", "createProgram"),
		new Hook("iris", "the eye over shader packs", IRIS + "pipeline.IrisRenderingPipeline", "finalizeLevelRendering", "getDHCompat"),
		new Hook("iris", "the eye over shader packs", IRIS + "uniforms.CapturedRenderingState", "INSTANCE", "getGbufferProjection", "getGbufferModelView"),
		new Hook("iris", "the eye over shader packs", IRIS + "compat.dh.DHCompat", "hasRenderingEnabled", "getProjection", "getDepthTex"),

		new Hook("distanthorizons", "resource-pack DH shaders", DH + "common.render.openGl.glObject.shader.GlShader", "loadFile"),
		new Hook("distanthorizons", "resource-pack DH shaders", DH + "common.render.openGl.terrain.GlDhTerrainShaderProgram", "fillUniformData"),
		new Hook("distanthorizons", "grey rivers fix", DH + "api.DhApi$Delayed", "configs", "renderProxy"),
		new Hook("distanthorizons", "grey rivers fix", DH + "api.interfaces.config.client.IDhApiGraphicsConfig", "getBiomeBlending"),
		new Hook("distanthorizons", "LOD colours", DH + "common.wrappers.block.QuadWrapper", "getQuadsForDirection"),
		new Hook("distanthorizons", "LOD colours", DH + "common.wrappers.block.ClientBlockStateColorCache",
			"getQuadsForDirection", "resolveColors", "blockState", "baseColor", "needPostTinting", "tintIndex", "isColorResolved", "clearCachedTints"),
		new Hook("distanthorizons", "LOD colours", DH + "common.wrappers.block.BlockStateWrapper",
			"calculateOpacity", "isAir", "WRAPPER_BY_BLOCK_STATE", "opacity", "isLiquid"),
		new Hook("distanthorizons", "unseen blocks left out far off", DH + "common.wrappers.block.BlockStateWrapper",
			"getRendererIgnoredBlocks", "fromBlockState", "rendererIgnoredBlocks", "rendererIgnoredCaveBlocks"),
		new Hook("distanthorizons", "LODs after a pack switch", DH + "common.render.openGl.GlDhTerrainRenderer", "INSTANCE", "terrainShaderProgram"),
		new Hook("distanthorizons", "LODs after a pack switch", DH + "common.render.openGl.util.GlAbstractShaderRenderer", "<init>", "init", "free"),
		new Hook("distanthorizons", "LODs after a pack switch", DH + "common.render.openGl.generic.GlGenericObjectRenderer",
			"<init>", "init", "instancedShaderProgram", "directShaderProgram"),
		new Hook("distanthorizons", "LODs after a pack switch", DH + "common.render.openGl.generic.GlGenericObjectShaderProgram", "<init>", "free"),
		new Hook("distanthorizons", "LODs after a pack switch", DH + "common.wrappers.world.ClientLevelWrapper",
			"LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL", "clearBlockColorCache"),
		new Hook("distanthorizons", "LODs after a pack switch", DH + "common.wrappers.block.ClientBlockStateTextureCache", "clearCache"),
		new Hook("distanthorizons", "LODs after a pack switch", DH + "core.dataObjects.render.textures.BlockTextureRegistry", "INSTANCE", "clear"),
		new Hook("distanthorizons", "LODs after a pack switch", DH + "api.interfaces.render.IDhApiRenderProxy", "clearRenderDataCache"));

	private static List<String> missing = List.of();
	private static boolean checked;

	private Hooks() {
	}

	/** Checks every hook into the installed mods, and logs the missing ones. */
	public static synchronized void check() {
		List<String> found = new ArrayList<>();
		Set<String> off = new java.util.TreeSet<>();
		for (Hook hook : HOOKS) {
			if (!FabricLoader.getInstance().isModLoaded(hook.mod)) continue;
			for (String member : absent(hook)) {
				found.add(hook.className.substring(hook.className.lastIndexOf('.') + 1) + (member.isEmpty() ? "" : "." + member));
				off.add(hook.feature);
			}
		}
		missing = found;
		checked = true;
		if (!found.isEmpty()) {
			MCMEModpackMarker.LOGGER.warn("MCME's hooks into {} that weren't found - an update changed them - so these are off: {}. Missing: {}",
				versions(), String.join(", ", off), String.join(", ", found));
		}
	}

	/** What /mcme hooks says. */
	public static List<String> report() {
		if (!checked) check();
		List<String> lines = new ArrayList<>();
		lines.add("MCME hooks, with " + versions() + ":");
		if (missing.isEmpty()) {
			lines.add("all " + HOOKS.stream().filter(h -> FabricLoader.getInstance().isModLoaded(h.mod)).count() + " found");
		} else {
			lines.add(missing.size() + " missing: " + String.join(", ", missing));
		}
		return lines;
	}

	private static String versions() {
		List<String> mods = new ArrayList<>();
		for (String id : new String[]{"iris", "distanthorizons"}) {
			FabricLoader.getInstance().getModContainer(id).ifPresent(mod ->
				mods.add(mod.getMetadata().getName() + " " + mod.getMetadata().getVersion().getFriendlyString()));
		}
		return mods.isEmpty() ? "neither Iris nor Distant Horizons" : String.join(" and ", mods);
	}

	// the members of hook that its class lacks: "" alone for the class itself
	private static List<String> absent(Hook hook) {
		Class<?> type;
		try {
			type = Class.forName(hook.className, false, Hooks.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			return List.of("");
		}
		Set<String> names = new HashSet<>();
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				for (Method m : c.getDeclaredMethods()) names.add(m.getName());
				for (Field f : c.getDeclaredFields()) names.add(f.getName());
				for (Constructor<?> ignored : c.getDeclaredConstructors()) names.add("<init>");
			} catch (LinkageError e) {
				return List.of("");
			}
		}
		for (Class<?> i : type.getInterfaces()) {
			for (Method m : i.getMethods()) names.add(m.getName());
		}
		List<String> lacking = new ArrayList<>();
		for (String member : hook.members) {
			if (!names.contains(member)) lacking.add(member);
		}
		return lacking;
	}
}

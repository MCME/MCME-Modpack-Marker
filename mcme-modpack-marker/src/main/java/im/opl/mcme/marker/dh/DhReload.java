package im.opl.mcme.marker.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.toasts.SystemToast;

/**
 * Distant Horizons after the resource packs change - a server sending another
 * pack, the player switching: DH builds its shaders once and keeps what it
 * learnt of each block (colour, texture, opacity) for good, so without this
 * its LODs keep the old packs' look until a restart. Here, after each
 * resource reload, once DH has been drawing:
 *
 * <ul>
 * <li>its OpenGL shader programs are dropped, to be built again from the
 * packs as they now are (DhShaders) - the terrain's, every post-processing
 * pass's, the generic objects' (clouds);</li>
 * <li>what it knows of blocks is forgotten - their colours, face textures and
 * the tiles made of them - and each block's opacity worked out again;</li>
 * <li>its LODs are rebuilt (DhApi renderProxy.clearRenderDataCache).</li>
 * </ul>
 *
 * <p>The same without the shaders after a setting that changes how DH
 * colours its LODs (afterColorSetting): its biome blending, the mod's true
 * colours. DH bakes the colours into the LODs it builds, so without a rebuild
 * a change shows only on LODs built after it.
 *
 * All through reflection on DH's own classes (docs/distant-horizons.md): if
 * any of it is missing, the player is told to restart instead.
 */
public final class DhReload {
	private static final String GL = "com.seibel.distanthorizons.common.render.openGl.";
	private static final String WRAPPERS = "com.seibel.distanthorizons.common.wrappers.";

	// DH's post-processing passes and generic object renderers, as DH makes them (mixin/dh/DhRendererTrackingMixin)
	private static final Set<Object> SHADER_RENDERERS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	private static final Set<Object> GENERIC_RENDERERS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private DhReload() {
	}

	public static void trackShaderRenderer(Object renderer) {
		SHADER_RENDERERS.add(renderer);
	}

	public static void trackGenericRenderer(Object renderer) {
		GENERIC_RENDERERS.add(renderer);
	}

	/** After a resource reload, on the render thread: DH brought up to the packs as they now are. */
	public static void afterReload() {
		if (!DhShaders.anyLoaded()) return;     // DH hasn't drawn anything yet: nothing to redo
		try {
			shaders();
			blocks();
			rebuildLods();
			MCMEModpackMarker.LOGGER.info("Distant Horizons rebuilt for the resource packs as they now are (MCME)");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't bring Distant Horizons up to the new resource packs; it will after a restart", e);
			Minecraft minecraft = Minecraft.getInstance();
			SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.literal("Far terrain needs a restart"),
				Component.literal("Distant Horizons uses this resource pack's look after a restart"));
		}
	}

	/** After a setting that changes DH's colours, on the render thread: its LODs coloured again. */
	public static void afterColorSetting() {
		if (!DhShaders.anyLoaded()) return;     // DH hasn't drawn anything yet: it will colour them as set
		try {
			blocks();
			rebuildLods();
			MCMEModpackMarker.LOGGER.info("Distant Horizons' LODs rebuilt for the new colour settings (MCME)");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't rebuild Distant Horizons' LODs; the new colour settings show after a restart", e);
			Minecraft minecraft = Minecraft.getInstance();
			SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.literal("Far terrain needs a restart"),
				Component.literal("Distant Horizons uses the new colour settings after a restart"));
		}
	}

	// its LODs built again from what it stored of the world, coloured anew
	private static void rebuildLods() throws ReflectiveOperationException {
		Object proxy = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("renderProxy").get(null);
		if (proxy != null) Class.forName("com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy").getMethod("clearRenderDataCache").invoke(proxy);
	}

	// every DH shader program dropped, to be built again when next drawn
	private static void shaders() throws ReflectiveOperationException {
		// the terrain's: made when first asked for, if there is none
		Class<?> terrainRenderer = Class.forName(GL + "GlDhTerrainRenderer");
		Object terrain = terrainRenderer.getField("INSTANCE").get(null);
		Field program = field(terrainRenderer, "terrainShaderProgram");
		Object old = program.get(terrain);
		if (old != null) {
			Class.forName(GL + "glObject.shader.GlShaderProgram").getMethod("free").invoke(old);
			program.set(terrain, null);
		}
		// the post-processing passes': made again by render(), once not init
		Class<?> shaderRenderer = Class.forName(GL + "util.GlAbstractShaderRenderer");
		Field init = field(shaderRenderer, "init");
		Method free = shaderRenderer.getMethod("free");
		synchronized (SHADER_RENDERERS) {
			for (Object renderer : SHADER_RENDERERS) {
				if (!init.getBoolean(renderer)) continue;
				free.invoke(renderer);
				init.setBoolean(renderer, false);
			}
		}
		// the generic objects' (clouds): made in init() with its buffers, so made again here
		Class<?> genericRenderer = Class.forName(GL + "generic.GlGenericObjectRenderer");
		Class<?> genericProgram = Class.forName(GL + "generic.GlGenericObjectShaderProgram");
		Field genericInit = field(genericRenderer, "init");
		synchronized (GENERIC_RENDERERS) {
			for (Object renderer : GENERIC_RENDERERS) {
				if (!genericInit.getBoolean(renderer)) continue;
				for (String name : new String[]{"instancedShaderProgram", "directShaderProgram"}) {
					Field f = field(genericRenderer, name);
					Object current = f.get(renderer);
					if (current == null || current.getClass() != genericProgram) continue;  // another mod's: left be
					genericProgram.getMethod("free").invoke(current);
					f.set(renderer, genericProgram.getConstructor(boolean.class).newInstance(name.startsWith("instanced")));
				}
			}
		}
	}

	// what DH knows of blocks forgotten, and their opacity worked out again
	private static void blocks() throws ReflectiveOperationException {
		Class<?> levelWrapper = Class.forName(WRAPPERS + "world.ClientLevelWrapper");
		Map<?, ?> levels = (Map<?, ?>) field(levelWrapper, "LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL").get(null);
		for (Object ref : levels.values().toArray()) {
			Object level = ((WeakReference<?>) ref).get();
			if (level != null) levelWrapper.getMethod("clearBlockColorCache").invoke(level);
		}
		Class.forName(WRAPPERS + "block.ClientBlockStateColorCache").getMethod("clearCachedTints").invoke(null);
		// the blocks it leaves out, made again (with those no one can see: DhIgnoredBlocksMixin)
		Class<?> blockWrapper = Class.forName(WRAPPERS + "block.BlockStateWrapper");
		field(blockWrapper, "rendererIgnoredBlocks").set(null, null);
		field(blockWrapper, "rendererIgnoredCaveBlocks").set(null, null);
		Class.forName(WRAPPERS + "block.ClientBlockStateTextureCache").getMethod("clearCache").invoke(null);
		Class<?> tiles = Class.forName("com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry");
		tiles.getMethod("clear").invoke(tiles.getField("INSTANCE").get(null));

		Class<?> wrapper = Class.forName(WRAPPERS + "block.BlockStateWrapper");
		Map<?, ?> wrappers = (Map<?, ?>) wrapper.getField("WRAPPER_BY_BLOCK_STATE").get(null);
		LodColors.unseen();     // the unseen states for these packs, before their opacity (DhBlockOpacityMixin)
		Method opacityOf = wrapper.getDeclaredMethod("calculateOpacity", net.minecraft.world.level.block.state.BlockState.class, boolean.class, boolean.class);
		Method isAir = wrapper.getDeclaredMethod("isAir", net.minecraft.world.level.block.state.BlockState.class);
		opacityOf.setAccessible(true);
		isAir.setAccessible(true);
		Field opacity = field(wrapper, "opacity");
		Field liquid = field(wrapper, "isLiquid");
		for (Map.Entry<?, ?> entry : wrappers.entrySet()) {
			Object state = entry.getKey();
			opacity.setInt(entry.getValue(), (int) opacityOf.invoke(null, state, isAir.invoke(null, state), liquid.getBoolean(entry.getValue())));
		}
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}
}

package im.opl.mcme.marker.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.BitSet;

/**
 * Which of a LOD's blocks are water, for DH's cave culling to leave be
 * (mixin/dh/DhCaveWaterMixin). Below its height (60 by default) DH takes a
 * block with no sky light under an opaque one for a cave, and draws the
 * opaque one down over it (FullDataToRenderDataTransformer
 * .setRenderColumnView). Water loses a level of sky light with each block it's
 * deep, so a tall fall is dark inside: Rauros' falls, at Y -25 to -4, showed
 * the cliff above drawn down over whole columns of water. Cave culling itself
 * stays, as it also fills the dark pockets under canopies, which DH lights as
 * if leaves let no light through.
 *
 * <p>A block is water by DH's material (12, EDhApiBlockMaterial.WATER), which
 * waterlogged blocks have too.
 */
public final class CaveWater {
	private static final String DH = "com.seibel.distanthorizons.";
	private static final int WATER = 12;
	// the data source being turned into LODs on this thread, and which of its block ids are water
	private static final ThreadLocal<Object> SOURCE = new ThreadLocal<>();
	private static final ThreadLocal<Ids> IDS = new ThreadLocal<>();
	private static MethodHandle mapping, wrapper, material;
	private static volatile boolean ready;
	private static boolean failed, logged;

	private record Ids(Object mapping, BitSet known, BitSet water) {
	}

	private CaveWater() {
	}

	/** The full data source DH is about to draw on this thread. */
	public static void source(Object source) {
		SOURCE.set(source);
	}

	/** Whether DH's cave culling may cover the block with this id: not water. */
	public static boolean cullable(boolean enabled, int id) {
		Object source = SOURCE.get();
		if (!enabled || failed || source == null) return enabled;
		try {
			if (!ready) init();
			Object map = mapping.invoke(source);
			Ids ids = IDS.get();
			if (ids == null || ids.mapping != map) {
				ids = new Ids(map, new BitSet(), new BitSet());
				IDS.set(ids);
			}
			if (!ids.known.get(id)) {
				ids.known.set(id);
				Object found;
				try {
					found = wrapper.invoke(map, id);
				} catch (IndexOutOfBoundsException e) {
					found = null;
				}
				if (found != null && (byte) material.invoke(found) == WATER) ids.water.set(id);
			}
			if (!ids.water.get(id)) return true;
			if (!logged) {
				logged = true;
				MCMEModpackMarker.LOGGER.info("MCME keeps Distant Horizons' cave culling off its water");
			}
			return false;
		} catch (Throwable e) {
			failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't keep Distant Horizons' cave culling off its water", e);
			return enabled;
		}
	}

	private static synchronized void init() throws ReflectiveOperationException {
		if (ready) return;
		MethodHandles.Lookup lookup = MethodHandles.publicLookup();
		Class<?> source = Class.forName(DH + "core.dataObjects.fullData.sources.FullDataSourceV2");
		Class<?> map = Class.forName(DH + "core.dataObjects.fullData.FullDataPointIdMap");
		Class<?> wrapperClass = Class.forName(DH + "core.wrapperInterfaces.block.IBlockStateWrapper");
		mapping = lookup.unreflectGetter(source.getField("mapping"));
		wrapper = lookup.findVirtual(map, "getBlockStateWrapper", MethodType.methodType(wrapperClass, int.class));
		material = lookup.findVirtual(wrapperClass, "getMaterialId", MethodType.methodType(byte.class));
		ready = true;
	}
}

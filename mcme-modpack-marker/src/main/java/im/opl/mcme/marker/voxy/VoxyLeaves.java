package im.opl.mcme.marker.voxy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MCME's custom leaves in Voxy's LODs. Their .obj models are leafy cards
 * reaching well past their block, which Voxy bakes clipped to it, one 16x16
 * picture per side: what's left covers part of each side, sunk into the
 * block. Voxy draws leaves solid, as full blocks, and leaves out the faces
 * they touch - the ground under them - so the gaps around the sunken leaves
 * showed through the world. Each side of such leaves is made a full face, on
 * the block's side: the leaves where baked, their side's average colour where
 * not, like fast leaves.
 */
public final class VoxyLeaves {
	// the depth word's top 24 bits are the depth into the block (0 on its side, all set where nothing was drawn); its low byte marks a pixel drawn
	private static final int DRAWN = 1;
	private static final Map<BlockState, Boolean> FILLED = new ConcurrentHashMap<>();
	// Voxy's ColourDepthTextureData, reached by reflection: all set at once, as any thread may bake
	private record Sides(Constructor<?> make, Method colour, Method depth, Method width, Method height) {
	}

	private static volatile Sides access;

	private VoxyLeaves() {
	}

	/** Whether the state's last bake was of leaves drawn through Fabric's renderer API, to be filled. */
	public static void baked(BlockState state, boolean fill) {
		FILLED.put(state, fill);
	}

	public static boolean fills(BlockState state) {
		return FILLED.getOrDefault(state, false);
	}

	/** The state's baked sides (Voxy's ColourDepthTextureData[]), made full faces. */
	public static void fill(Object[] sides) throws ReflectiveOperationException {
		Sides a = access;
		if (a == null) {
			Class<?> type = sides.getClass().getComponentType();
			access = a = new Sides(type.getConstructor(int[].class, int[].class, int.class, int.class),
				type.getMethod("colour"), type.getMethod("depth"), type.getMethod("width"), type.getMethod("height"));
		}
		int[][] colours = new int[sides.length][], depths = new int[sides.length][];
		// each side's leaves' colour, summed (sum's last: how many pixels), then all sides'
		long[][] sums = new long[sides.length + 1][4];
		for (int i = 0; i < sides.length; i++) {
			colours[i] = (int[]) a.colour.invoke(sides[i]);
			depths[i] = (int[]) a.depth.invoke(sides[i]);
			for (int p = 0; p < colours[i].length; p++) {
				if (!isLeaf(colours[i][p], depths[i][p])) continue;
				add(sums[i], colours[i][p]);
				add(sums[sides.length], colours[i][p]);
			}
		}
		if (sums[sides.length][3] == 0) return;     // nothing baked at all: left as it is
		int all = mean(sums[sides.length]);
		for (int i = 0; i < sides.length; i++) {
			int fallback = sums[i][3] == 0 ? all : mean(sums[i]);
			int[] full = new int[colours[i].length], flush = new int[depths[i].length];
			for (int p = 0; p < full.length; p++) {
				full[p] = isLeaf(colours[i][p], depths[i][p]) ? colours[i][p] | 0xFF000000 : fallback;
				flush[p] = DRAWN;
			}
			sides[i] = a.make.newInstance(full, flush, a.width.invoke(sides[i]), a.height.invoke(sides[i]));
		}
	}

	private static boolean isLeaf(int colour, int depth) {
		return (depth & 0xFF) != 0 && (colour >>> 24) > 1;
	}

	// sum: the channels below alpha, then how many pixels
	private static void add(long[] sum, int pixel) {
		sum[0] += pixel & 0xFF;
		sum[1] += (pixel >>> 8) & 0xFF;
		sum[2] += (pixel >>> 16) & 0xFF;
		sum[3]++;
	}

	private static int mean(long[] sum) {
		return 0xFF000000 | (int) (sum[2] / sum[3]) << 16 | (int) (sum[1] / sum[3]) << 8 | (int) (sum[0] / sum[3]);
	}
}

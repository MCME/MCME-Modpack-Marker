package im.opl.mcme.marker.dh;

import im.opl.mcme.marker.mixin.SpriteContentsAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Distant Horizons' LOD colours, as the blocks look. DH colours a block by one
 * face's texture, every pixel of it: for a model with no full-cube faces -
 * an .obj, MCME's custom leaves and plants - that's whichever face comes
 * first, and the whole texture, used or not. Here such a model's colour is
 * the average of all its faces, each over the part of its texture it shows,
 * by its size and how much of it isn't see-through.
 */
public final class LodColors {
	/** rgb (no alpha); tinted and tintIndex as the faces mostly are. */
	public record Color(int rgb, boolean tinted, int tintIndex) {
	}

	private static final Direction[] SIDES = Direction.values();

	// the states unseen() last found, for DH threads to look up without the textures
	private static volatile java.util.Set<BlockState> unseenStates = java.util.Set.of();

	private LodColors() {
	}

	/** null where DH's own is right: a model with full-cube faces, or nothing to go by. */
	public static synchronized Color of(BlockState state) {
		List<BakedQuad>[] faces = ModelQuads.all(state);
		if (faces == null) return null;
		for (Direction side : SIDES) {
			if (!faces[side.ordinal()].isEmpty()) return null;
		}
		List<BakedQuad> quads = faces[SIDES.length];
		double r = 0, g = 0, b = 0, weight = 0, area = 0, tintedArea = 0;
		int tintIndex = -1;
		for (BakedQuad quad : quads) {
			double size = area(quad);
			double[] c = average(quad);
			if (size <= 0 || c == null) continue;
			double w = size * c[3];
			r += c[0] * w;
			g += c[1] * w;
			b += c[2] * w;
			weight += w;
			area += size;
			if (quad.materialInfo().isTinted()) {
				tintedArea += size;
				if (tintIndex < 0) tintIndex = quad.materialInfo().tintIndex();
			}
		}
		if (weight <= 0) return null;
		boolean tinted = tintedArea * 2 > area;
		return new Color(srgb(r / weight) << 16 | srgb(g / weight) << 8 | srgb(b / weight), tinted, tinted ? tintIndex : -1);
	}

	/**
	 * Whether the state's model is mostly leaves - by area, of textures named
	 * so: MCME's leaf slabs, which sit on doors and trapdoors. DH takes such
	 * blocks for see-through, as skylight passes them, and leaves them out of
	 * its LODs.
	 */
	public static boolean isLeafy(BlockState state) {
		List<BakedQuad>[] faces = ModelQuads.all(state);
		if (faces == null) return false;    // models not loaded yet: ask again later
		double leaves = 0, all = 0;
		for (List<BakedQuad> quads : faces) {
			for (BakedQuad quad : quads) {
				double size = area(quad);
				String name = quad.materialInfo().sprite().contents().name().getPath();
				all += size;
				if (name.contains("leaves")) leaves += size;
			}
		}
		return all > 0 && leaves * 2 > all;
	}

	/**
	 * Whether the state's model is there but can't be seen: every face's part
	 * of its texture fully see-through - such as MCME's shadow block, tinted
	 * glass with a blank texture, which darkens canopies by blocking skylight.
	 * DH colours a block by its texture's pixels, colour and all, so it draws
	 * such a block black (mixin/dh/DhIgnoredBlocksMixin).
	 */
	public static synchronized boolean isInvisible(BlockState state) {
		if (!state.getFluidState().isEmpty()) return false;     // its water is seen
		List<BakedQuad>[] faces = ModelQuads.all(state);
		if (faces == null) return false;    // models not loaded yet: ask again later
		boolean any = false;
		for (List<BakedQuad> quads : faces) {
			for (BakedQuad quad : quads) {
				double[] c = average(quad);
				if (c == null || c[3] > 1.0 / 255.0) return false;
				any = true;
			}
		}
		return any;
	}

	/**
	 * Every block state no one sees far off, for DH to leave out of its LODs;
	 * null while models aren't loaded:
	 * - those no one can see at all (isInvisible). Only states whose particle
	 *   texture is see-through are looked at closely: no one gives a block
	 *   that's seen a blank particle;
	 * - those whose face DH colours them by (lodFace) has a blank texture named
	 *   *_lod: a pack's way of keeping a block out of LODs, as RP-Mordor's fire
	 *   eye on shroomlight does (fire_eye_lod). DH drew it as a box no one sees,
	 *   which still hid the faces behind it. Only so named: plants give some
	 *   faces a blank texture (block/invisible) and are seen all the same.
	 * Also null while a resource reload has freed the textures it reads.
	 */
	public static List<BlockState> unseen() {
		try {
			List<BlockState> unseen = findUnseen();
			if (unseen != null) unseenStates = java.util.Set.copyOf(unseen);
			return unseen;
		} catch (IllegalStateException e) {
			return null;     // "Image is not allocated": the old packs' textures, freed mid-reload
		}
	}

	/** Whether unseen() last found the state unseen: safe on any thread, at any time. */
	public static boolean isUnseen(BlockState state) {
		return unseenStates.contains(state);
	}

	private static List<BlockState> findUnseen() {
		var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		if (models == null) return null;
		java.util.Map<TextureAtlasSprite, Boolean> blank = new java.util.HashMap<>();
		List<BlockState> unseen = new ArrayList<>();
		for (BlockState state : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
			if (state.isAir() || !state.getFluidState().isEmpty()) continue;
			BlockStateModel model = models.get(state);
			TextureAtlasSprite face = lodFace(model, state);
			if (face != null && face.contents().name().getPath().endsWith("_lod") && blank.computeIfAbsent(face, LodColors::isBlank)) {
				unseen.add(state);
				continue;
			}
			TextureAtlasSprite particle = model.particleMaterial().sprite();
			if (!blank.computeIfAbsent(particle, LodColors::isBlank)) continue;
			if (isInvisible(state)) unseen.add(state);
		}
		return unseen;
	}

	// the order DH looks for a face to colour a block by (ClientBlockStateColorCache.COLOR_RESOLUTION_DIRECTION_ORDER)
	private static final Direction[] DH_FACE_ORDER = {Direction.UP, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.DOWN};

	/**
	 * The texture DH colours a block by: its model's first face culled on a
	 * side, in DH's order - a pillar's not on top - or else its first unculled
	 * one. Only the model's own faces: null for one drawn through Fabric's
	 * renderer, or with none.
	 */
	private static TextureAtlasSprite lodFace(BlockStateModel model, BlockState state) {
		List<BlockStateModelPart> parts = new ArrayList<>();
		synchronized (ModelQuads.class) {
			model.collectParts(RandomSource.create(42L), parts);
		}
		boolean pillar = state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock;
		for (Direction side : DH_FACE_ORDER) {
			if (pillar && side == Direction.UP) continue;
			for (BlockStateModelPart part : parts) {
				List<BakedQuad> quads = part.getQuads(side);
				if (!quads.isEmpty()) return quads.get(0).materialInfo().sprite();
			}
		}
		for (BlockStateModelPart part : parts) {
			List<BakedQuad> quads = part.getQuads(null);
			if (!quads.isEmpty()) return quads.get(0).materialInfo().sprite();
		}
		return null;
	}

	// whether every pixel of a sprite is fully see-through, or all but (alpha 1)
	private static boolean isBlank(TextureAtlasSprite sprite) {
		var image = ((SpriteContentsAccessor) sprite.contents()).mcme$originalImage();
		if (image == null) return false;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getPixel(x, y) >>> 24) > 1) return false;
			}
		}
		return true;
	}

	/** What DH has to go by for a state, and what it and this make of it: for /mcme dh. */
	public static List<String> describe(BlockState state) {
		List<String> lines = new ArrayList<>();
		lines.add(state.toString());
		lines.add("occludes " + state.canOcclude() + ", skylight passes " + state.propagatesSkylightDown()
			+ " -> DH's own opacity " + (state.canOcclude() || !state.propagatesSkylightDown() ? 16 : 0) + "; leaves to MCME: " + isLeafy(state)
			+ ", unseen to MCME: " + isInvisible(state) + ", kept out by a blank *_lod face: " + lodFaceBlank(state));
		try {
			Object wrappers = Class.forName("com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper").getField("WRAPPER_BY_BLOCK_STATE").get(null);
			Object wrapper = ((java.util.Map<?, ?>) wrappers).get(state);
			if (wrapper == null) {
				lines.add("DH: no wrapper for it yet");
			} else {
				StringBuilder dh = new StringBuilder("DH:");
				for (String field : new String[]{"opacity", "isSolid", "renderTexture", "alwaysRasterizeTexture", "useBottomTextureForSides", "blockMaterialId"}) {
					var f = wrapper.getClass().getDeclaredField(field);
					f.setAccessible(true);
					dh.append(' ').append(field).append('=').append(f.get(wrapper));
				}
				lines.add(dh.toString());
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			lines.add("DH: " + e);
		}
		BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		lines.add("particle " + model.particleMaterial().sprite().contents().name());
		List<BlockStateModelPart> parts = new ArrayList<>();
		synchronized (ModelQuads.class) {
			model.collectParts(RandomSource.create(42L), parts);
		}
		int own = 0;
		for (BlockStateModelPart part : parts) {
			own += part.getQuads(null).size();
			for (Direction side : SIDES) own += part.getQuads(side).size();
		}
		lines.add(own + " faces of the model's own" + (own == 0 ? "; drawn through Fabric's renderer:" : ":"));
		for (Direction side : new Direction[]{null, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			java.util.Map<String, Integer> sprites = new java.util.TreeMap<>();
			List<BakedQuad> quads = ModelQuads.get(state, side);
			for (BakedQuad quad : quads) {
				sprites.merge(quad.materialInfo().sprite().contents().name() + (quad.materialInfo().isTinted() ? " (tinted)" : ""), 1, Integer::sum);
			}
			if (!quads.isEmpty()) lines.add((side == null ? "unculled" : side.getName()) + ": " + quads.size() + " faces " + sprites);
		}
		Color color = of(state);
		lines.add("MCME colour: " + (color == null ? "DH's own (full-cube faces)" : String.format("#%06X tinted=%s", color.rgb(), color.tinted())));
		return lines;
	}

	private static boolean lodFaceBlank(BlockState state) {
		TextureAtlasSprite face = lodFace(Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state), state);
		return face != null && face.contents().name().getPath().endsWith("_lod") && isBlank(face);
	}

	private static double area(BakedQuad quad) {
		Vector3fc p0 = quad.position0();
		Vector3f a = new Vector3f(quad.position1()).sub(p0), b = new Vector3f(quad.position2()).sub(p0), c = new Vector3f(quad.position3()).sub(p0);
		return (new Vector3f(a).cross(b).length() + new Vector3f(b).cross(c).length()) / 2.0;
	}

	/** The quad's part of its sprite: linear r, g, b by alpha, and how opaque it is on the whole (0 to 1). */
	private static double[] average(BakedQuad quad) {
		TextureAtlasSprite sprite = quad.materialInfo().sprite();
		var image = ((SpriteContentsAccessor) sprite.contents()).mcme$originalImage();
		if (image == null) return null;
		int w = sprite.contents().width(), h = sprite.contents().height();
		float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
		if (du == 0 || dv == 0) return null;
		double[] xs = new double[4], ys = new double[4];
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			long uv = quad.packedUV(i);
			xs[i] = (UVPair.unpackU(uv) - sprite.getU0()) / du * w;
			ys[i] = (UVPair.unpackV(uv) - sprite.getV0()) / dv * h;
			minX = Math.min(minX, xs[i]);
			maxX = Math.max(maxX, xs[i]);
			minY = Math.min(minY, ys[i]);
			maxY = Math.max(maxY, ys[i]);
		}
		double r = 0, g = 0, b = 0, alpha = 0;
		int count = 0;
		int x0 = clamp((int) Math.floor(minX), w), x1 = clamp((int) Math.ceil(maxX), w);
		int y0 = clamp((int) Math.floor(minY), h), y1 = clamp((int) Math.ceil(maxY), h);
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				double cx = x + 0.5, cy = y + 0.5;
				if (!inside(xs, ys, 0, 1, 2, cx, cy) && !inside(xs, ys, 0, 2, 3, cx, cy)) continue;
				int argb = image.getPixel(x, y);
				double a = (argb >>> 24) / 255.0;
				r += linear(argb >> 16 & 0xFF) * a;
				g += linear(argb >> 8 & 0xFF) * a;
				b += linear(argb & 0xFF) * a;
				alpha += a;
				count++;
			}
		}
		if (count == 0) {
			// smaller than a pixel: the one under its middle
			int x = clamp((int) ((minX + maxX) / 2), w - 1), y = clamp((int) ((minY + maxY) / 2), h - 1);
			int argb = image.getPixel(x, y);
			double a = (argb >>> 24) / 255.0;
			r = linear(argb >> 16 & 0xFF) * a;
			g = linear(argb >> 8 & 0xFF) * a;
			b = linear(argb & 0xFF) * a;
			alpha = a;
			count = 1;
		}
		if (alpha <= 0) return new double[]{0, 0, 0, 0};
		return new double[]{r / alpha, g / alpha, b / alpha, alpha / count};
	}

	private static boolean inside(double[] xs, double[] ys, int i, int j, int k, double x, double y) {
		double d1 = (x - xs[j]) * (ys[i] - ys[j]) - (xs[i] - xs[j]) * (y - ys[j]);
		double d2 = (x - xs[k]) * (ys[j] - ys[k]) - (xs[j] - xs[k]) * (y - ys[k]);
		double d3 = (x - xs[i]) * (ys[k] - ys[i]) - (xs[k] - xs[i]) * (y - ys[i]);
		boolean negative = d1 < 0 || d2 < 0 || d3 < 0, positive = d1 > 0 || d2 > 0 || d3 > 0;
		return !(negative && positive);
	}

	private static int clamp(int v, int max) {
		return Math.max(0, Math.min(max, v));
	}

	private static double linear(int c) {
		return Math.pow(c / 255.0, 2.2);
	}

	private static int srgb(double c) {
		return (int) Math.round(Math.pow(Math.max(0, Math.min(1, c)), 1 / 2.2) * 255);
	}
}

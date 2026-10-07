package im.opl.mcme.marker.dh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block state's faces, as Distant Horizons and LodColors read them: the
 * model's own (BlockStateModelPart.getQuads), and where it has none, the ones
 * it draws through Fabric's renderer API instead. Special Model Loader's .obj
 * models - all MCME's custom leaves, on leaf, door and trapdoor states - are
 * drawn so: DH, finding no faces, took their particle texture for their
 * colour, which on a multipart blockstate is its first entry's (a door's
 * sandstone), and their opacity for nothing.
 */
public final class ModelQuads {
	private static final Direction[] SIDES = Direction.values();
	private static final int UNCULLED = SIDES.length;
	// by state, with the model they came from: a resource reload bakes new
	// models, and so drops what was known of the old ones
	private record Known(BlockStateModel model, List<BakedQuad>[] faces) {
	}

	private static final Map<BlockState, Known> FACES = new ConcurrentHashMap<>();

	private ModelQuads() {
	}

	/** The faces culled by side, or with side null those never culled. */
	public static List<BakedQuad> get(BlockState state, Direction side) {
		List<BakedQuad>[] faces = all(state);
		return faces == null ? List.of() : faces[side == null ? UNCULLED : side.ordinal()];
	}

	/** Every face of the state's model, by side (Direction.ordinal(), then the unculled ones); null before models are loaded. */
	@SuppressWarnings("unchecked")
	public static List<BakedQuad>[] all(BlockState state) {
		Minecraft client = Minecraft.getInstance();
		BlockStateModel model = client.getModelManager().getBlockStateModelSet().get(state);
		if (model == null) return null;
		Known known = FACES.get(state);
		if (known != null && known.model == model) return known.faces;
		List<BakedQuad>[] faces = new List[UNCULLED + 1];
		for (int i = 0; i <= UNCULLED; i++) faces[i] = new ArrayList<>();
		List<BlockStateModelPart> parts = new ArrayList<>();
		synchronized (ModelQuads.class) {
			model.collectParts(RandomSource.create(42L), parts);
		}
		boolean any = false;
		for (BlockStateModelPart part : parts) {
			for (Direction side : SIDES) any |= faces[side.ordinal()].addAll(part.getQuads(side));
			any |= faces[UNCULLED].addAll(part.getQuads(null));
		}
		if (!any) emitted(model, state, faces);
		FACES.put(state, new Known(model, faces));
		return faces;
	}

	/**
	 * Whether state looks like other: every texture its faces use, other's
	 * use too. Distant Horizons colours a slab as its double slab and a huge
	 * mushroom block as its default state, for their full faces - the same
	 * look in vanilla, but MCME gives each state a model of its own.
	 */
	public static boolean looksLike(BlockState state, BlockState other) {
		java.util.Set<Object> theirs = new java.util.HashSet<>();
		List<BakedQuad>[] faces = all(other), mine = all(state);
		if (faces == null || mine == null) return true;
		for (List<BakedQuad> quads : faces) {
			for (BakedQuad quad : quads) theirs.add(quad.materialInfo().sprite().contents().name());
		}
		for (List<BakedQuad> quads : mine) {
			for (BakedQuad quad : quads) {
				if (!theirs.contains(quad.materialInfo().sprite().contents().name())) return false;
			}
		}
		return true;
	}

	// the faces the model draws through Fabric's renderer API, as plain quads
	private static void emitted(BlockStateModel model, BlockState state, List<BakedQuad>[] faces) {
		SpriteFinder sprites = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).spriteFinder();
		MutableMesh mesh = Renderer.get().mutableMesh();
		synchronized (ModelQuads.class) {
			// an empty world: this runs on DH's threads, and the faces mustn't depend on where the block is
			model.emitQuads(mesh.emitter(), net.minecraft.client.renderer.block.BlockAndTintGetter.EMPTY, BlockPos.ZERO, state, RandomSource.create(42L), side -> false);
		}
		mesh.forEach(quad -> {
			Direction cull = quad.cullFace();
			faces[cull == null ? UNCULLED : cull.ordinal()].add(quad.toBakedQuad(sprites.find(quad)));
		});
	}
}

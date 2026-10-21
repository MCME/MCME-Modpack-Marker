package im.opl.mcme.marker.mixin.voxy;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.dh.LodColors;
import im.opl.mcme.marker.dh.ModelQuads;
import im.opl.mcme.marker.voxy.VoxyLeaves;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Voxy bakes each block state's LOD look from its model's parts
 * (collectParts, then getQuads) - the model's own faces only. Special Model
 * Loader's .obj models, all MCME's custom leaves, have none there: they draw
 * through Fabric's renderer API. Voxy so baked them empty, and leaves, which
 * it draws solid, black. They get the faces they draw (ModelQuads) as a part,
 * and leaves among them full sides (VoxyLeaves, VoxyModelFactoryMixin).
 * A block no one sees (LodColors.unseen), such as the shadow block, gets none:
 * VoxyModelFactoryMixin keeps it from being drawn solid.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery", remap = false)
public abstract class VoxyModelBakeryMixin {
	private static boolean mcme$failed;

	@Redirect(method = "bakeBlockModel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;collectParts(Lnet/minecraft/util/RandomSource;Ljava/util/List;)V"), require = 0)
	private void mcme$drawnParts(BlockStateModel model, RandomSource random, List<BlockStateModelPart> parts, BlockState state) {
		model.collectParts(random, parts);
		if (mcme$failed) return;
		try {
			VoxyLeaves.baked(state, false);
			if (LodColors.isUnseenNow(state)) {
				parts.clear();
				return;
			}
			BlockStateModelPart drawn = ModelQuads.drawnPart(state, model, parts);
			if (drawn == null) return;
			parts.clear();
			parts.add(drawn);
			VoxyLeaves.baked(state, state.is(BlockTags.LEAVES));
		} catch (RuntimeException | LinkageError e) {
			mcme$failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't give Voxy the faces of {}, so leaves them to it", state, e);
		}
	}
}

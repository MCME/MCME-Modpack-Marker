package im.opl.mcme.marker.mixin.voxy;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.dh.LodColors;
import im.opl.mcme.marker.voxy.VoxyLeaves;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Voxy draws leaves solid, whatever their texture, and a solid face is drawn
 * where anything was baked, see-through or not. MCME's shadow block, a leaves
 * state with a blank texture on every face, so came out a black box. A block
 * no one sees (LodColors.unseen) is baked with no faces (VoxyModelBakeryMixin)
 * and goes in the cutout layer, where an empty face is no face at all.
 * Leaves drawn through Fabric's renderer API get full sides (VoxyLeaves).
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory", remap = false)
public abstract class VoxyModelFactoryMixin {
	private static boolean mcme$failed;

	@ModifyArgs(method = "processModelResult", at = @At(value = "INVOKE",
		target = "Lme/cortex/voxy/client/core/model/ModelFactory;processTextureBakeResult(ILnet/minecraft/world/level/block/state/BlockState;[Lme/cortex/voxy/client/core/model/ColourDepthTextureData;ZZLnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lme/cortex/voxy/client/core/model/ModelFactory$ModelBakeResultUpload;"),
		require = 0)
	private void mcme$unseenCutout(Args args) {
		if (mcme$failed) return;
		BlockState state = args.get(1);
		try {
			if (LodColors.isUnseenNow(state)) args.set(5, ChunkSectionLayer.CUTOUT);
			else if (VoxyLeaves.fills(state)) VoxyLeaves.fill(args.get(2));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			mcme$failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't give Voxy its look of {}, so leaves it to it", state, e);
		}
	}
}

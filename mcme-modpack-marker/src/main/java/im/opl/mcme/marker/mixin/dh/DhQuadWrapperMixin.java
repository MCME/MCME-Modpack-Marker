package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import im.opl.mcme.marker.dh.ModelQuads;
import java.util.List;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons reads a block's faces, for its LODs' colour and textures,
 * through QuadWrapper.getQuadsForDirection - the model's own faces only. A
 * model drawn through Fabric's renderer API, as Special Model Loader's .obj
 * models are, has none there: it gets the ones it draws (ModelQuads).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.block.QuadWrapper", remap = false)
public abstract class DhQuadWrapperMixin {
	private static boolean mcme$failed;

	@Inject(method = "getQuadsForDirection", at = @At("RETURN"), cancellable = true, require = 0)
	private static void mcme$drawnFaces(BlockState state, @Coerce Object direction, CallbackInfoReturnable<List<BakedQuad>> cir) {
		List<BakedQuad> own = cir.getReturnValue();
		if (mcme$failed || (own != null && !own.isEmpty()) || !McmeConfig.get().dhExactColors) return;
		try {
			Direction side = direction == null ? null : Direction.valueOf(((Enum<?>) direction).name());
			List<BakedQuad> drawn = ModelQuads.get(state, side);
			if (!drawn.isEmpty()) cir.setReturnValue(drawn);
		} catch (RuntimeException | LinkageError e) {
			mcme$failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't give Distant Horizons the faces of {}, so leaves them to it", state, e);
		}
	}
}

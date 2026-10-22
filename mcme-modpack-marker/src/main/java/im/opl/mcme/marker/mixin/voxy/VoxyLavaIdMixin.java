package im.opl.mcme.marker.mixin.voxy;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.voxy.VoxyShaders;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lava's models marked for the resource packs' part of Voxy's terrain
 * (VoxyShaders): a bit of their customId, on its own without a shader pack
 * and on the pack's block ids with one (IrisVoxyRenderPipeline.
 * setupExtraModelBakeryData).
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory", remap = false)
public abstract class VoxyLavaIdMixin {
	@Shadow
	private Object2IntMap<BlockState> customBlockStateIdMapping;

	@Inject(method = "<init>", at = @At("TAIL"), require = 0)
	private void mcme$lavaIds(CallbackInfo ci) {
		try {
			if (customBlockStateIdMapping == null) customBlockStateIdMapping = VoxyShaders.markLava(null);
		} catch (RuntimeException | LinkageError e) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't mark lava for Voxy", e);
		}
	}

	@ModifyVariable(method = "setCustomBlockStateMapping", at = @At("HEAD"), argsOnly = true, require = 0)
	private Object2IntMap<BlockState> mcme$shaderPackLavaIds(Object2IntMap<BlockState> ids) {
		try {
			return VoxyShaders.markLava(ids);
		} catch (RuntimeException | LinkageError e) {
			MCMEModpackMarker.LOGGER.warn("MCME couldn't mark lava for Voxy", e);
			return ids;
		}
	}
}

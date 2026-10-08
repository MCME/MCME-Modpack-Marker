package im.opl.mcme.marker.mixin.iris;

import im.opl.mcme.marker.iris.EyeOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Once a shader pack has finished the world - its composites and final pass
 * done - MCME draws the fire eye over it (see {@link EyeOverlay}).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisRenderingPipelineMixin {
	@Inject(method = "finalizeLevelRendering", at = @At("TAIL"), require = 0)
	private void mcme$fireEye(CallbackInfo ci) {
		EyeOverlay.draw(this);
	}
}

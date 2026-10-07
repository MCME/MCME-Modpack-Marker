package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.dh.DhReload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes each of Distant Horizons' post-processing passes as DH makes it, so
 * that their shaders can be made again after a resource reload (DhReload).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer", remap = false)
public abstract class DhRendererTrackingMixin {
	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void mcme$track(CallbackInfo ci) {
		DhReload.trackShaderRenderer(this);
	}
}

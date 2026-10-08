package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.dh.DhFog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** As DhFogParamsMixin, for Distant Horizons' Blaze3D renderer - the one it uses without Iris. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhFogRenderer", remap = false)
public abstract class DhBlazeFogParamsMixin {
	@Inject(method = "render", at = @At("HEAD"), require = 0)
	private void mcme$fog(@Coerce Object renderParams, @Coerce Object fog, CallbackInfo ci) {
		DhFog.capture(fog);
	}
}

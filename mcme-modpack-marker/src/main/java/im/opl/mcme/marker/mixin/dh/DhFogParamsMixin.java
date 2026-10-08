package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.dh.DhFog;
import im.opl.mcme.marker.dh.DhShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The settings Distant Horizons' OpenGL renderer draws its fog with, each
 * frame - its config's, as its API's events leave them - for the fire eye on
 * the sky (DhFog). And its fog program, once it has set its own uniforms, is
 * given the camera's position and the time, as its terrain program is
 * (DhShaders.setUniforms): RP-Mordor's fog shader fogs the eye by its own
 * distance with them.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.postProcessing.fog.GlDhFogShader", remap = false)
public abstract class DhFogParamsMixin {
	@Inject(method = "prepUniformObjects", at = @At("HEAD"), require = 0)
	private void mcme$fog(@Coerce Object matrix, @Coerce Object fog, CallbackInfo ci) {
		DhFog.capture(fog);
	}

	@Inject(method = "onApplyUniforms", at = @At("TAIL"), require = 0)
	private void mcme$cameraAndTime(@Coerce Object renderParams, CallbackInfo ci) {
		DhShaders.setUniforms();
	}
}

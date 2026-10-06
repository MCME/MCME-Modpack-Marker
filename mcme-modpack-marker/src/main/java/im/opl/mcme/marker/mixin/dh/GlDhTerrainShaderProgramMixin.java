package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.dh.DhShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons' OpenGL terrain program, once it has bound itself and set
 * its own uniforms each frame, is given the camera's position and the time too
 * (see {@link DhShaders#setUniforms}).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram", remap = false)
public abstract class GlDhTerrainShaderProgramMixin {
	@Inject(method = "fillUniformData", at = @At("TAIL"), require = 0)
	private void mcme$cameraAndTime(CallbackInfo ci) {
		DhShaders.setUniforms();
	}
}

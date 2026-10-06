package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.dh.DhShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons' OpenGL renderer loads every shader through
 * {@code GlShader.loadFile(path, absoluteFilePath)}, from its own jar: a
 * resource pack's version of it is taken first (see {@link DhShaders#load}).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader", remap = false)
public abstract class GlShaderMixin {
	@Inject(method = "loadFile", at = @At("HEAD"), cancellable = true, require = 0)
	private static void mcme$loadFromResourcePacks(String path, boolean absoluteFilePath, CallbackInfoReturnable<String> cir) {
		if (absoluteFilePath) return;
		String source = DhShaders.load(path);
		if (source != null) cir.setReturnValue(source);
	}
}

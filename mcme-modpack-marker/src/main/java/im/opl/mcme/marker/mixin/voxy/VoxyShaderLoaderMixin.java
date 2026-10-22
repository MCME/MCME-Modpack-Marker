package im.opl.mcme.marker.mixin.voxy;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.voxy.VoxyShaders;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy reads its shaders from its own jar: the resource packs' part is
 * patched in as they load (VoxyShaders).
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader$ShaderLoadingParser", remap = false)
public abstract class VoxyShaderLoaderMixin {
	private static boolean mcme$failed;

	@Inject(method = "loadShaderAsset", at = @At("RETURN"), cancellable = true, require = 0)
	private static void mcme$patch(Identifier id, CallbackInfoReturnable<String> cir) {
		if (mcme$failed) return;
		try {
			cir.setReturnValue(VoxyShaders.patch(id, cir.getReturnValue()));
		} catch (RuntimeException | LinkageError e) {
			mcme$failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't patch Voxy's {}", id, e);
		}
	}
}

package im.opl.mcme.marker.mixin.iris;

import com.llamalad7.mixinextras.sugar.Local;
import im.opl.mcme.marker.shaderpacks.PatchedShaderPacks;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where Iris loads the shader pack the player picked - its shaders/ folder,
 * named name - it is handed MCME's edited copy instead, if one of the
 * patcher's recipes takes it (see {@link PatchedShaderPacks}). A pack none
 * takes, and Iris's own, get MCME's edits for any pack instead
 * ({@code im.opl.mcme.marker.iris}).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisMixin {
	@Inject(method = "loadShaderpack", at = @At("HEAD"), require = 0)
	private static void mcme$noRecipeYet(CallbackInfo ci) {
		PatchedShaderPacks.reset();
	}

	@ModifyArg(method = "loadExternalShaderpack",
		at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V"),
		index = 0, require = 0)
	private static Path mcme$editedCopy(Path shaders, @Local(argsOnly = true) String name) {
		return PatchedShaderPacks.substitute(shaders, name);
	}
}

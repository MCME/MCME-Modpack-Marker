package im.opl.mcme.marker.mixin.iris;

import com.llamalad7.mixinextras.sugar.Local;
import im.opl.mcme.marker.iris.IrisTerrain;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The shaders Iris has made of a shader pack's program for Distant Horizons'
 * LODs, as it is about to compile them - name is the program's, such as
 * dh_terrain - get MCME's edits for them (see {@link IrisTerrain#patchLod}).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.IrisLodRenderProgram", remap = false)
public abstract class IrisLodRenderProgramMixin {
	@ModifyVariable(method = "createProgram", at = @At("STORE"), ordinal = 0, require = 0)
	private static Map<Object, String> mcme$lod(Map<Object, String> transformed, @Local(argsOnly = true) String name) {
		return IrisTerrain.patchLod(name, transformed);
	}
}

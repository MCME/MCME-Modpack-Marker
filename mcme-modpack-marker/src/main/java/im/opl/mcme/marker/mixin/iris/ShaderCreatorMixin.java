package im.opl.mcme.marker.mixin.iris;

import com.llamalad7.mixinextras.sugar.Local;
import im.opl.mcme.marker.iris.IrisTerrain;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The shaders Iris has made of a shader pack's program, as it is about to
 * compile them - name is the program's, such as sodium_terrain_solid - get
 * MCME's edits for terrain (see {@link IrisTerrain}).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class ShaderCreatorMixin {
	@ModifyVariable(method = {"create", "createShadow"}, at = @At("STORE"), ordinal = 0, require = 0)
	private static Map<Object, String> mcme$terrain(Map<Object, String> transformed, @Local(argsOnly = true) String name) {
		return IrisTerrain.patch(name, transformed);
	}
}

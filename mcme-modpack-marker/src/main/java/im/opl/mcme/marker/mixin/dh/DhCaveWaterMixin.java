package im.opl.mcme.marker.mixin.dh;

import com.llamalad7.mixinextras.sugar.Local;
import im.opl.mcme.marker.dh.CaveWater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons' cave culling kept off water, so it doesn't draw the cliff
 * above a deep fall down over it (dh/CaveWater).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer", remap = false)
public abstract class DhCaveWaterMixin {
	@Inject(method = "transformFullDataToRenderSource", at = @At("HEAD"), require = 0)
	private static void mcme$source(@Coerce Object source, @Coerce Object level, CallbackInfoReturnable<Object> cir) {
		CaveWater.source(source);
	}

	@ModifyVariable(method = "setRenderColumnView", at = @At("LOAD"), name = "caveCullingEnabled", require = 0)
	private static boolean mcme$notOnWater(boolean enabled, @Local(name = "id") int id) {
		return CaveWater.cullable(enabled, id);
	}
}

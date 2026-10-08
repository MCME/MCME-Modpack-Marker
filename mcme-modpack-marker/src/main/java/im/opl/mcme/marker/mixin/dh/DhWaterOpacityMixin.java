package im.opl.mcme.marker.mixin.dh;

import com.llamalad7.mixinextras.sugar.Local;
import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Distant Horizons draws water as see-through as its texture (two thirds to
 * three quarters opaque). Far off a waterfall a block thick then shows the
 * dark cliff behind it, and looks dark: few pixels, and none of the light and
 * foam it has close up. Its tops too: far off a fall is mostly the tops of
 * its flowing water's steps, and see-through they showed the pool and cliff
 * behind in streaks. So far off all of water's faces are WATER_OPACITY
 * (LodQuadBuilder.addQuadUp, addQuadAdj, addQuadDown), as close up MCME's
 * water seen from afar is all but opaque (its murk, water.glsl). Water is
 * DH's material 12 (EDhApiBlockMaterial.WATER), which it hands each quad as
 * irisBlockMaterialId.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder", remap = false)
public abstract class DhWaterOpacityMixin {
	private static final int WATER = 12;
	private static final int WATER_OPACITY = 245;
	private static final boolean[] mcme$logged = new boolean[3];

	@ModifyVariable(method = "addQuadUp", at = @At("HEAD"), argsOnly = true, name = "color", require = 0)
	private int mcme$opaqueTop(int color, @Local(argsOnly = true, name = "irisBlockMaterialId") byte material) {
		return mcme$opaque(color, material, 2, "tops");
	}

	@ModifyVariable(method = "addQuadAdj", at = @At("HEAD"), argsOnly = true, name = "color", require = 0)
	private int mcme$opaqueSide(int color, @Local(argsOnly = true, name = "irisBlockMaterialId") byte material) {
		return mcme$opaque(color, material, 0, "sides");
	}

	@ModifyVariable(method = "addQuadDown", at = @At("HEAD"), argsOnly = true, name = "color", require = 0)
	private int mcme$opaqueUnderside(int color, @Local(argsOnly = true, name = "irisBlockMaterialId") byte material) {
		return mcme$opaque(color, material, 1, "undersides");
	}

	private static int mcme$opaque(int color, byte material, int face, String what) {
		if (material != WATER || !McmeConfig.get().dhExactColors || color >>> 24 >= WATER_OPACITY) return color;
		if (!mcme$logged[face]) {
			mcme$logged[face] = true;
			MCMEModpackMarker.LOGGER.info("MCME draws Distant Horizons' water {} opaque", what);
		}
		return WATER_OPACITY << 24 | color & 0xFFFFFF;
	}
}

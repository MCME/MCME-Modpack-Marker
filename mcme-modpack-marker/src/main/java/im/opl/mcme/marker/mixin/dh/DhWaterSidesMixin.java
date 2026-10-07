package im.opl.mcme.marker.mixin.dh;

import com.llamalad7.mixinextras.sugar.Local;
import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Distant Horizons draws a see-through block's side only where it gets the
 * full sky's light (15): ColumnBox.tryAddVerticalFaceWithSkyLightToBuilder
 * returns early for inputTransparent and any other light, so that water
 * doesn't show its sides in caves. A waterfall's sides, against a cliff or
 * under an overhang, get a little less, so DH left them out: the falls were
 * holes far off, the cliff showing through. Water's sides are drawn here
 * wherever an opaque block's would be - still not against solid ground,
 * which DH leaves out before this.
 *
 * <p>That rule was also all that hid the sides between two water columns:
 * DH lights such a side by the water's bottom (makeAdjVerticalQuad), which
 * never gets the full 15. Drawn, they walled lakes and rivers in. So the
 * stretch of a see-through water side that faces other see-through water is
 * left out, as close up: it gets DH's "covered" light, -1, like a stretch
 * against solid ground. See-through on both sides, as DH counts every block
 * holding water as water (BlockStateWrapper.isLiquid): a waterlogged stair,
 * slab or wall too, which it draws solid in the block's own colour, and
 * whose sides against the water must stay.
 *
 * <p>Water is DH's material 12 (EDhApiBlockMaterial.WATER), handed to the
 * methods as irisBlockMaterialId. A render data point keeps it in its top
 * four bits, and its opacity in the four under them, 15 opaque
 * (RenderDataPointUtil.getBlockMaterialId, getAlpha).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnBox", remap = false)
public abstract class DhWaterSidesMixin {
	private static final int WATER = 12;
	private static boolean mcme$logged;
	private static boolean mcme$loggedInWater;

	@ModifyVariable(method = "tryAddVerticalFaceWithSkyLightToBuilder", at = @At("HEAD"), argsOnly = true, name = "inputTransparent", require = 0)
	private static boolean mcme$waterSides(boolean transparent, @Local(argsOnly = true, name = "irisBlockMaterialId") byte material,
		@Local(argsOnly = true, name = "lastSkyLight") byte light) {
		if (!transparent || material != WATER || !McmeConfig.get().dhExactColors) return transparent;
		if (!mcme$logged && light >= 0 && light != 15) {
			mcme$logged = true;
			MCMEModpackMarker.LOGGER.info("MCME draws a side of Distant Horizons' water DH would leave out (sky light {})", light);
		}
		return false;
	}

	@ModifyVariable(method = "makeAdjVerticalQuad", at = @At("LOAD"), name = "lightToApply", require = 0)
	private static byte mcme$waterInWater(byte light, @Local(argsOnly = true, name = "irisBlockMaterialId") byte material,
		@Local(name = "inputTransparent") boolean seeThrough, @Local(name = "adjPoint") long adjPoint) {
		if (material != WATER || !seeThrough || (adjPoint >>> 60 & 15) != WATER || (adjPoint >>> 56 & 15) == 15
			|| !McmeConfig.get().dhExactColors) return light;
		if (!mcme$loggedInWater) {
			mcme$loggedInWater = true;
			MCMEModpackMarker.LOGGER.info("MCME leaves out the sides between Distant Horizons' water columns");
		}
		return -1;
	}
}

package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import im.opl.mcme.marker.dh.LodColors;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons gives a block that doesn't occlude and lets skylight
 * through an opacity of 0 (BlockStateWrapper.calculateOpacity): it leaves it
 * out of its LODs. That's doors and trapdoors - which MCME's leaf slabs sit
 * on - so trees lose those leaves far off, and show the trunks and ground
 * behind them. A state whose model is mostly leaves is as opaque as a leaf
 * block here (LodColors.isLeafy).
 *
 * <p>And a block no one can see (LodColors.isUnseen) - MCME's shadow
 * block, under canopy leaves - has an opacity of 0. DH leaves it out of its
 * LODs (DhIgnoredBlocksMixin), but one it takes for opaque it leaves out by
 * lighting what's next to it with the block's own light
 * (FullDataToRenderDataTransformer): 0 inside an opaque block, so the leaf
 * faces it was placed against were drawn black. See-through, it's skipped
 * like air, and they take the light around them.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper", remap = false)
public abstract class DhBlockOpacityMixin {
	private static boolean mcme$failed;

	@Inject(method = "calculateOpacity", at = @At("RETURN"), cancellable = true, require = 0)
	private static void mcme$leavesOpaque(BlockState state, boolean air, boolean liquid, CallbackInfoReturnable<Integer> cir) {
		if (mcme$failed || air || liquid || !McmeConfig.get().dhExactColors) return;
		try {
			if (cir.getReturnValueI() == 0 && LodColors.isLeafy(state)) cir.setReturnValue(16);
			else if (cir.getReturnValueI() > 0 && LodColors.isUnseen(state)) cir.setReturnValue(0);
		} catch (RuntimeException | LinkageError e) {
			mcme$failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't tell whether {} is leaves or unseen for Distant Horizons, so leaves its opacity alone", state, e);
		}
	}
}

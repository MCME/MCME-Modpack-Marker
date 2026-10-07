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
		} catch (RuntimeException | LinkageError e) {
			mcme$failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't tell whether {} is leaves for Distant Horizons, so leaves its opacity alone", state, e);
		}
	}
}

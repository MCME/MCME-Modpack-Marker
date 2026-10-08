package im.opl.mcme.marker;

import net.minecraft.client.Minecraft;

/**
 * The time vanilla's shaders are given (Globals' GameTime), and with it MCME's
 * effects - the water's waves, the lava, the eye: real time, as this client
 * counts it, not the world's. The world's clock is the server's and runs
 * true, but the client's copy of it jumps whenever the client stalls -
 * joining, loading terrain - and everything moving by it skips. (Smoothing it
 * was tried: the water stood still.) Without this mod the shaders use the
 * world's clock and only skip on stalls. It is given as the world's is - a day's fraction,
 * 1200 seconds - so all of MCME's patterns, which come round whole times a
 * day, run on seamlessly as it starts over.
 */
public final class ShaderClock {
	private static final long START = System.nanoTime();

	private ShaderClock() {
	}

	/** Seconds into the day, as vanilla's shaders' {@code GameTime * 1200.0} - the world's, if the settings say so. */
	public static float daySeconds() {
		if (!McmeConfig.get().realTimeClock) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.level == null) return 0.0f;
			return (minecraft.level.getGameTime() % 24000L + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20.0f;
		}
		return (float) (((System.nanoTime() - START) / 1.0e9) % 1200.0);
	}

	/** The day's fraction, as vanilla's GameTime. */
	public static float dayFraction() {
		return daySeconds() / 1200.0f;
	}
}

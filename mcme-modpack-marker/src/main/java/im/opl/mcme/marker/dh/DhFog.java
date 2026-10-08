package im.opl.mcme.marker.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.phys.Vec3;

/**
 * Distant Horizons' fog, for RP-Mordor's fire eye painted on the sky past its
 * block's reach (sky.fsh): DH fogs only the LODs, by their own depth, and
 * leaves the sky alone - so the eye showed clear in front of fogged
 * mountains. Here DH's fog is worked out at the eye, as its fog shader
 * (fog/gl/fog.frag) would at a LOD there, from the settings it draws its fog
 * with (mixin/dh/DhFogParamsMixin), and handed to vanilla's shaders in the
 * Fog block.
 *
 * <p>The way in: with vanilla's fog off, DH sets FogRenderDistanceStart to
 * 4.206942e14, past anything drawn, so no render distance fog comes of it
 * whatever its end (vanilla's own end, as DH lets that through). End is set
 * to Start * (2 + the eye's fog), 0 to 1: still no fog, and a shader reads
 * the eye's fog as End / Start - 2 (RP-Mordor's far_terrain.glsl,
 * farTerrainFog). Without this mod End is far under Start, and it reads 0.
 *
 * <p>Through reflection, as the rest of the mod's DH code: nothing of DH is
 * needed to build or run.
 */
public final class DhFog {
	private static final Pattern EYE = Pattern.compile("#define FIRE_EYE_BLOCK ivec3\\((-?\\d+), *(-?\\d+), *(-?\\d+)\\)");
	// DH's fog settings, as it last drew its fog with them; stale after a second, as when its fog is off
	private static volatile Params params;
	private static volatile long paramsAt;
	// the eye's centre, from the packs' fire_eye_config.glsl: null for none, unknown until looked for
	private static volatile Vec3 eye;
	private static volatile boolean eyeKnown;
	private static boolean failed, captured;
	private static float loggedThickness = -1.0f;
	private static long loggedAt;

	private DhFog() {
	}

	/** DhApiFogRenderParam's values, and the falloff and mix enums' ids, as DH's fog shader is given them. */
	private record Params(int farFalloff, float farStart, float farEnd, float farMin, float farMax, float farDensity,
		int heightFalloff, String mixMode, int mixValue, float heightBase, float heightStart, float heightEnd,
		boolean basedOnCamera, boolean appliesUp, boolean appliesDown) {
	}

	/** DH is about to draw its fog with these settings (a DhApiFogRenderParam). */
	public static void capture(Object fog) {
		if (failed || fog == null) return;
		try {
			Class<?> c = fog.getClass();
			Object mix = get(c, fog, "getHeightFogMixingMode"), direction = get(c, fog, "getHeightFogDirection");
			params = new Params(value(get(c, fog, "getFarFogFalloff")), (float) get(c, fog, "getFarFogStartPercent"), (float) get(c, fog, "getFarFogEndPercent"),
				(float) get(c, fog, "getFarFogMinThickness"), (float) get(c, fog, "getFarFogMaxThickness"), (float) get(c, fog, "getFarFogDensity"),
				value(get(c, fog, "getHeightFogFalloff")), ((Enum<?>) mix).name(), value(mix), (float) get(c, fog, "getHeightFogBaseHeight"),
				(float) get(c, fog, "getHeightFogStartPercent"), (float) get(c, fog, "getHeightFogEndPercent"),
				flag(direction, "basedOnCamera"), flag(direction, "fogAppliesUp"), flag(direction, "fogAppliesDown"));
			paramsAt = System.nanoTime();
			if (!captured) {
				captured = true;
				MCMEModpackMarker.LOGGER.info("MCME reads Distant Horizons' fog for the fire eye ({}, {} to {})", params.mixMode, params.farStart, params.farEnd);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't read Distant Horizons' fog, so leaves the fire eye on the sky unfogged", e);
		}
	}

	/** The resource packs changed: look for the eye again. */
	public static void forgetEye() {
		eyeKnown = false;
	}

	/** Vanilla's fog for this frame is about to be sent: puts the eye's fog in it, if DH's is on and a pack has the eye. */
	public static void encode(FogData fog) {
		if (failed || fog.renderDistanceStart < 1.0e8f) return;
		Params p = params;
		if (p == null || System.nanoTime() - paramsAt > 1_000_000_000L) return;
		Vec3 centre = eye();
		Minecraft minecraft = Minecraft.getInstance();
		if (centre == null || minecraft.level == null || minecraft.gameRenderer == null) return;
		try {
			float thickness = thickness(p, centre.subtract(minecraft.gameRenderer.mainCamera().position()),
				minecraft.gameRenderer.mainCamera().position().y, renderDistance(), minecraft.level.getHeight());
			fog.renderDistanceEnd = fog.renderDistanceStart * (2.0f + thickness);
			// in the log as it changes, at most every 10 seconds
			long now = System.nanoTime();
			if (Math.abs(thickness - loggedThickness) > 0.1f && now - loggedAt > 10_000_000_000L) {
				loggedThickness = thickness;
				loggedAt = now;
				MCMEModpackMarker.LOGGER.info("MCME fogs the fire eye as Distant Horizons fogs its LODs: {} at {} blocks (LODs to {})",
					String.format("%.2f", thickness), Math.round(centre.distanceTo(minecraft.gameRenderer.mainCamera().position())), renderDistance() * 16);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't work out Distant Horizons' fog at the fire eye, so leaves it unfogged", e);
		}
	}

	// DH's fog shader at a LOD at view (from the camera), as fog.frag works it out
	private static float thickness(Params p, Vec3 view, double cameraY, int chunkRadius, int levelHeight) {
		double scale = 1.0 / (chunkRadius * 16.0);
		boolean spherical = p.mixMode.equals("SPHERICAL");
		boolean heightOn = !spherical && !p.mixMode.equals("CYLINDRICAL");
		double distance = (spherical ? view.length() : Math.sqrt(view.x * view.x + view.z * view.z)) * scale;
		double far = falloff(p.farFalloff, distance, p.farStart, p.farEnd - p.farStart, p.farMin, p.farMax - p.farMin, p.farDensity);
		double height = 0.0;
		if (heightOn) {
			double y = view.y;
			if (!p.basedOnCamera) y -= p.heightBase - cameraY;
			double depth = p.appliesDown && p.appliesUp ? Math.abs(y) : p.appliesDown ? -y : p.appliesUp ? y : 0.0;
			// DH gives its height fog the far fog's min, range and density
			height = falloff(p.heightFalloff, depth / levelHeight, p.heightStart, p.heightEnd - p.heightStart, p.farMin, p.farMax - p.farMin, p.farDensity);
		}
		double mixed = switch (p.mixValue) {
			case 2 -> Math.max(far, height);
			case 3 -> far + height;
			case 4 -> far * height;
			case 5 -> 1.0 - (1.0 - far) * (1.0 - height);
			case 6 -> far + Math.max(far, height);
			case 7 -> far + far * height;
			case 8 -> far + 1.0 - (1.0 - far) * (1.0 - height);
			case 9 -> far * 0.5 + height * 0.5;
			default -> far;
		};
		return (float) Math.max(0.0, Math.min(1.0, mixed));
	}

	// fog.frag's linearFog, exponentialFog and exponentialSquaredFog, by EDhApiFogFalloff's value
	private static double falloff(int type, double x, double start, double length, double min, double range, double density) {
		if (type == 0) return min + range * Math.max(0.0, Math.min(1.0, (x - start) / length));
		x = Math.max((x - start) / length, 0.0) * density;
		return min + range - range / Math.exp(type == 1 ? x : x * x);
	}

	private static Vec3 eye() {
		if (eyeKnown) return eye;
		String config = DhShaders.include("fire_eye_config.glsl");
		Matcher m = config == null ? null : EYE.matcher(config);
		eye = m != null && m.find() ? new Vec3(Integer.parseInt(m.group(1)) + 0.5, Integer.parseInt(m.group(2)) + 0.5, Integer.parseInt(m.group(3)) + 0.5) : null;
		eyeKnown = true;
		return eye;
	}

	// DH's LOD render distance, in chunks: the one its fog is scaled by
	private static int renderDistance() throws ReflectiveOperationException {
		Object configs = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
		Object graphics = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig").getMethod("graphics").invoke(configs);
		Object distance = Class.forName("com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig").getMethod("chunkRenderDistance").invoke(graphics);
		return (Integer) Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue").getMethod("getValue").invoke(distance);
	}

	private static Object get(Class<?> c, Object o, String getter) throws ReflectiveOperationException {
		Method m = c.getMethod(getter);
		return m.invoke(o);
	}

	private static int value(Object e) throws ReflectiveOperationException {
		return e.getClass().getField("value").getInt(e);
	}

	private static boolean flag(Object e, String field) throws ReflectiveOperationException {
		return e.getClass().getField(field).getBoolean(e);
	}
}

package im.opl.mcme.marker.iris;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * What MCME's mod reads of Iris, by reflection - so that it builds, and runs,
 * without Iris: the matrices it renders the world with, and Distant Horizons'
 * depth and projection as it hands them to shader packs. Each is null, or 0,
 * if Iris hasn't it (any more).
 */
final class IrisAccess {
	private static boolean looked;
	private static Object captured;
	private static Method projection, modelView;
	private static Method dhRendering, dhProjection;

	private IrisAccess() {
	}

	private static void look() {
		if (looked) return;
		looked = true;
		try {
			Class<?> state = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
			Field instance = state.getField("INSTANCE");
			captured = instance.get(null);
			projection = state.getMethod("getGbufferProjection");
			modelView = state.getMethod("getGbufferModelView");
		} catch (ReflectiveOperationException | LinkageError e) {
			captured = null;
		}
		try {
			Class<?> dh = Class.forName("net.irisshaders.iris.compat.dh.DHCompat");
			dhRendering = dh.getMethod("hasRenderingEnabled");
			dhProjection = dh.getMethod("getProjection");
		} catch (ReflectiveOperationException | LinkageError e) {
			dhRendering = null;
		}
	}

	/** The projection the world was drawn with this frame - its view bobbing included. */
	static Matrix4fc projection() {
		return matrix(projection);
	}

	/** The world's model-view this frame: the camera's turn. */
	static Matrix4fc modelView() {
		return matrix(modelView);
	}

	private static Matrix4fc matrix(Method getter) {
		look();
		if (captured == null) return null;
		try {
			return (Matrix4fc) getter.invoke(captured);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}

	/** Distant Horizons' depth texture under pipeline (an IrisRenderingPipeline), or 0 if it draws none. */
	static int dhDepthTexture(Object pipeline) {
		look();
		if (dhRendering == null) return 0;
		try {
			if (!(Boolean) dhRendering.invoke(null)) return 0;
			Object compat = pipeline.getClass().getMethod("getDHCompat").invoke(pipeline);
			if (compat == null) return 0;
			return (Integer) compat.getClass().getMethod("getDepthTex").invoke(compat);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return 0;
		}
	}

	/** The projection Distant Horizons' LODs were drawn with, or null. */
	static Matrix4f dhProjection() {
		look();
		if (dhProjection == null) return null;
		try {
			return (Matrix4f) dhProjection.invoke(null);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}
}

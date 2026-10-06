package im.opl.mcme.marker.iris;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.dh.DhShaders;
import im.opl.mcme.marker.shaderpacks.PatchedShaderPacks;
import im.opl.mcme.marker.shaderpacks.ShaderPackPatcher;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Draws RP-Mordor's fire eye over whatever a shader pack made of the scene,
 * after its last pass, with the resource packs' own fire_eye.glsl - as the
 * game draws it without shaders - hidden by whatever the depth says is in
 * front, the game's and Distant Horizons'. Any shader pack, with nothing
 * edited in it; the eye block's own faces it drops ({@link IrisTerrain}).
 * Only in the overworld, and only if the resource packs have the eye.
 */
public final class EyeOverlay {
	private static Object pipelineSeen;
	private static boolean failed;
	private static int program;
	private static int vertexArray;
	private static int framebuffer;
	private static int framebufferColor = -1;
	private static ShaderPackPatcher.Eye eye;
	private static final Map<String, Integer> UNIFORMS = new HashMap<>();

	private EyeOverlay() {
	}

	/** Draws the eye over the finished frame of pipeline, Iris's IrisRenderingPipeline. */
	public static void draw(Object pipeline) {
		try {
			if (pipeline != pipelineSeen) {
				// a new pipeline: a shader pack, or the resource packs, (re)loaded
				pipelineSeen = pipeline;
				failed = false;
				build();
			}
			// a recipe draws it in the pack itself
			if (failed || program == 0 || PatchedShaderPacks.current().eye()) return;
			drawEye(pipeline);
		} catch (Throwable e) {
			failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't draw the fire eye over the shader pack; it won't be, until shaders are reloaded", e);
		}
	}

	private static void build() throws IOException {
		if (program != 0) GL20.glDeleteProgram(program);
		program = 0;
		UNIFORMS.clear();
		eye = ShaderPackPatcher.Eye.from(DhShaders::include);
		if (eye == null) return;    // no resource pack with the eye
		String fragment = IrisTerrain.resource("/mcme/iris/eye.fsh")
			.replace("{includes}", eye.includes.get("far_terrain.glsl") + "\n" + eye.includes.get("fire_eye_config.glsl"))
			.replace("{fire_eye}", eye.includes.get("fire_eye.glsl"));
		int vertexShader = compile(GL20.GL_VERTEX_SHADER, IrisTerrain.resource("/mcme/iris/eye.vsh"));
		int fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, fragment);
		int linked = GL20.glCreateProgram();
		GL20.glAttachShader(linked, vertexShader);
		GL20.glAttachShader(linked, fragmentShader);
		GL20.glLinkProgram(linked);
		GL20.glDeleteShader(vertexShader);
		GL20.glDeleteShader(fragmentShader);
		if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
			String log = GL20.glGetProgramInfoLog(linked, 8192);
			GL20.glDeleteProgram(linked);
			throw new IllegalStateException("the fire eye's shader doesn't link: " + log);
		}
		program = linked;
		for (String name : new String[]{"mcmeSceneDepth", "mcmeLodDepth", "mcmeHasLod", "mcmeProjectionInverse",
			"mcmeLodProjectionInverse", "mcmeModelViewInverse", "mcmeEyeCentre", "mcmeTime", "mcmeFar", "mcmeViewSize"}) {
			UNIFORMS.put(name, GL20.glGetUniformLocation(program, name));
		}
		if (vertexArray == 0) vertexArray = GlStateManager._glGenVertexArrays();
		if (framebuffer == 0) framebuffer = GlStateManager.glGenFramebuffers();
		MCMEModpackMarker.LOGGER.info("MCME draws the fire eye over the shader pack");
	}

	private static int compile(int type, String source) {
		int shader = GL20.glCreateShader(type);
		GL20.glShaderSource(shader, source);
		GL20.glCompileShader(shader);
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			String log = GL20.glGetShaderInfoLog(shader, 8192);
			GL20.glDeleteShader(shader);
			throw new IllegalStateException("the fire eye's shader doesn't compile: " + log);
		}
		return shader;
	}

	private static void drawEye(Object pipeline) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || !minecraft.level.dimension().equals(Level.OVERWORLD)) return;
		Matrix4fc projection = IrisAccess.projection(), modelView = IrisAccess.modelView();
		if (projection == null || modelView == null) return;
		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		if (!(target.getColorTexture() instanceof GlTexture color) || !(target.getDepthTexture() instanceof GlTexture depth)) return;
		int lodDepth = IrisAccess.dhDepthTexture(pipeline);
		Matrix4f lodProjection = lodDepth > 0 ? IrisAccess.dhProjection() : null;
		boolean lod = lodProjection != null;
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();

		int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
		if (framebufferColor != color.glId()) {
			GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.glId(), 0);
			framebufferColor = color.glId();
		}
		GlStateManager._viewport(0, 0, target.width, target.height);
		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);
		GlStateManager._disableCull();
		GlStateManager._disableScissorTest();
		GlStateManager._enableBlend(0);
		GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
		GlStateManager._blendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
		GlStateManager._glUseProgram(program);

		GL20.glUniform1i(UNIFORMS.get("mcmeSceneDepth"), 0);
		GL20.glUniform1i(UNIFORMS.get("mcmeLodDepth"), 1);
		GL20.glUniform1i(UNIFORMS.get("mcmeHasLod"), lod ? 1 : 0);
		matrix("mcmeProjectionInverse", new Matrix4f(projection).invert());
		matrix("mcmeLodProjectionInverse", lod ? lodProjection.invert() : new Matrix4f());
		matrix("mcmeModelViewInverse", new Matrix4f(modelView).invert());
		GL20.glUniform3f(UNIFORMS.get("mcmeEyeCentre"), (float) (eye.x + 0.5 - camera.x), (float) (eye.y + 0.5 - camera.y), (float) (eye.z + 0.5 - camera.z));
		GL20.glUniform1f(UNIFORMS.get("mcmeTime"), DhShaders.daySeconds());
		GL20.glUniform1f(UNIFORMS.get("mcmeFar"), minecraft.options.getEffectiveRenderDistance() * 16.0f);
		GL20.glUniform2f(UNIFORMS.get("mcmeViewSize"), target.width, target.height);

		GlStateManager._activeTexture(GL13.GL_TEXTURE1);
		GlStateManager._bindTexture(lod ? lodDepth : depth.glId());
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._bindTexture(depth.glId());
		GlStateManager._glBindVertexArray(vertexArray);
		GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, 3);

		GlStateManager._glBindVertexArray(0);
		GlStateManager._glUseProgram(0);
		GlStateManager._disableBlend(0);
		GlStateManager._depthMask(true);
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
	}

	private static void matrix(String name, Matrix4f matrix) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer buffer = stack.mallocFloat(16);
			matrix.get(buffer);
			GL20.glUniformMatrix4fv(UNIFORMS.get(name), false, buffer);
		}
	}
}

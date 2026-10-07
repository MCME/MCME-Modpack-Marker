package im.opl.mcme.marker.shaderpacks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static im.opl.mcme.marker.shaderpacks.ShaderPackPatcher.*;

/**
 * Bliss: the eye in dimensions/composite3.fsh, faded by the clouds Bliss
 * draws in front of it (measured in composite2), and lava in its terrain
 * and Distant Horizons'.
 * One of ShaderPackPatcher's recipes; see there for how they work.
 */
final class BlissRecipe {
    private BlissRecipe() {
    }

    static final String[][] BLISS_UNIFORMS = {{"ivec3", "cameraPositionInt"}, {"vec3", "cameraPositionFract"}, {"float", "far"},
            {"mat4", "gbufferModelViewInverse"}, {"mat4", "gbufferProjectionInverse"}, {"float", "frameTimeCounter"}, {"sampler2D", "colortex4"}};

    /**
     * Bliss (Chocapic13 edit), 2.1 and its development builds: faces dropped in
     * dimensions/all_translucent.vsh; the eye drawn in composite3, after fog,
     * clouds and the translucents, with Bliss's own view position and distance
     * (Distant Horizons' included: swappedDepth) and its exposure.
     */
    static Map<Path, String> bliss(Path shaders, Eye eye) throws IOException, Unsupported {
        Path dims = shaders.resolve("dimensions");
        Path vsh = dims.resolve("all_translucent.vsh"), comp = dims.resolve("composite3.fsh");
        if (!(isFile(vsh) && isFile(comp) && isFile(shaders.resolve("world0/gbuffers_water.vsh")))) {
            throw new Unsupported("not Bliss");
        }
        String text = read(comp);
        // its view position: viewPos in development builds, fragpos in 2.1
        String view = null;
        for (String v : new String[]{"viewPos", "fragpos"}) {
            if (search("vec3 " + v + " = toScreenSpace_DH\\(", text)) {
                view = v;
                break;
            }
        }
        if (view == null || !search("float swappedDepth = ", text) || !search("float linearDistance = ", text)) {
            throw new Unsupported("Bliss's composite3 has changed");
        }
        Path particles = dims.resolve("all_particles.vsh");
        if (!isFile(particles)) {
            throw new Unsupported("Bliss's particles have moved");
        }
        Map<Path, String> edits = new LinkedHashMap<>();
        boolean clouds = blissClouds(shaders, eye, edits);
        String name = comp.getFileName().toString();
        text = insert(text, "void main\\(\\) \\{", name, "// MCME: the fire eye, at its block (MCME mod)\n"
                + "#ifdef OVERWORLD_SHADER\n"
                + drawDefines(eye, true) + "#include \"/lib/mcme/fire_eye_draw.glsl\"\n"
                + (clouds ? "uniform sampler2D mcmeEyeCloudSampler;\n" : "") + "#endif\n"
                + "\n", true);
        text = declareUniforms(shaders, comp, text, text.indexOf("// MCME: the fire eye, at its block"), BLISS_UNIFORMS, edits);
        edits.put(vsh, dropEyeFaces(shaders, vsh, edits));
        edits.put(particles, dropFarParticles(shaders, particles, edits));
        if (eye.lava) {
            blissLava(shaders, edits);
        }
        text = insert(text, "gl_FragData\\[0\\](\\.r)? = (vec4\\()?bloomyFogMult", name, "\n"
                + "  // MCME: the fire eye, after fog and clouds, so it shows at any distance,\n"
                + "  // Distant Horizons' too, and nothing cuts its glow off"
                + (clouds ? " - faded by the clouds between it and the camera, which composite2 measures" : "") + "\n"
                + "  #ifdef OVERWORLD_SHADER\n"
                + "    " + (clouds ? "mcmeFireVisibility = texelFetch(mcmeEyeCloudSampler, ivec2(0), 0).r;" : "") + "\n"
                + "    color.rgb = mcmeDrawFireEye(color.rgb, " + view + ", swappedDepth >= 1.0 ? 1.0e9 : linearDistance,\n"
                + "                                1.0 / max(texelFetch(colortex4, ivec2(10, 37), 0).r, 1.0e-4));\n"
                + "  #endif\n"
                + "\n", true);
        edits.put(comp, text);
        return edits;
    }

    // How much light gets through Bliss's clouds between the camera and the
    // eye, for composite3 to fade the eye by: Bliss's own clouds, from its own
    // cloud function, on the line to the eye, marched only as far as the eye -
    // so the eye fades only behind clouds Bliss draws in front of it. Once a
    // frame, by one pixel, into a 1x1 image.

    /**
     * Bliss's lava: its colour, where Bliss samples the block's, from
     * lava.glsl - in dimensions/all_solid.fsh, for the world's terrain, and
     * DH_solid.fsh, for Distant Horizons' (still, as LODs don't say which way
     * it flows). Bliss lights it, and makes it glow, as it does its own.
     */
    static void blissLava(Path shaders, Map<Path, String> edits) throws IOException {
        Path dims = shaders.resolve("dimensions");
        lavaPart("Bliss's terrain", LavaWhere.TERRAIN, edits, e -> blissTerrainLava(shaders, dims.resolve("all_solid.fsh"), e));
        Path dh = dims.resolve("DH_solid.fsh");
        if (isFile(dh)) lavaPart("Bliss's Distant Horizons terrain", LavaWhere.DISTANT_HORIZONS, edits, e -> blissDhLava(shaders, dh, e));
    }

    static void blissTerrainLava(Path shaders, Path solid, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, solid);
        String name = solid.getFileName().toString();
        if (!search("flat varying float blockID;", text) || !search("vec3 fragpos = ", text)) {
            throw new Unsupported("all_solid has changed");
        }
        text = includeLava(shaders, solid, text, edits);
        text = insert(text, "vec4 Albedo = texture2D_POMSwitch\\(texture, adjustedTexCoord\\.xy[^;\r\n]*;\r?\n", name, ""
                + "\t" + LAVA_COLOUR + "\n"
                + "\t#if defined WORLD && !defined ENTITIES && !defined HAND && !defined BLOCKENTITIES\n"
                + "\t\tFluidFrame mcmeLavaHere = mcmeLavaFrame(mat3(gbufferModelViewInverse) * fragpos + gbufferModelViewInverse[3].xyz, adjustedTexCoord.xy);\n"
                + "\t\tif (abs(blockID - BLOCK_LAVA) < 0.5) Albedo.rgb = mcmeLava(texture, adjustedTexCoord.xy, mcmeLavaHere, mcmeLavaTime(frameTimeCounter), Albedo.rgb);\n"
                + "\t#endif\n");
        // the emission: Bliss since 2026 keeps it in specularData until it
        // packs it into SPECULAR_DATA (a layout(location = 1) out, which a
        // gl_FragData[1] next to it would collide with); before, gl_FragData[1]
        String emission = search("vec4 specularData = ", text) ? "specularData.a" : "gl_FragData[1].a";
        text = insert(text, "\t\t#if SSS_TYPE == 0\r?\n", name, ""
                + "\t\t" + LAVA_GLOW_MARK + "\n"
                + "\t\t#if defined WORLD && !defined ENTITIES && !defined HAND && !defined BLOCKENTITIES\n"
                + "\t\t\tif (abs(blockID - BLOCK_LAVA) < 0.5) " + emission + " = min(" + emission + " * LAVA_GLOW, 1.0);\n"
                + "\t\t#endif\n\n", true);
        edits.put(solid, text);
    }

    /** Distant Horizons' LODs: still lava, as they don't say which way it flows. */
    static void blissDhLava(Path shaders, Path dh, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, dh);
        String name = dh.getFileName().toString();
        if (!search("varying vec4 localPos;", text) || !search("flat varying int dh_material_id;", text)) {
            throw new Unsupported("DH_solid has changed");
        }
        Matcher main = findOne(text, "void main\\(\\) \\{\r?\n", name);
        text = includeLavaAt(shaders, dh, text, main.start(), edits);
        text = insert(text, "void main\\(\\) \\{\r?\n", name, ""
                + "    " + LAVA_FRAME + "\n"
                + "    FluidFrame mcmeLavaHere = mcmeLavaFrame(localPos.xyz, vec2(0.0));\n");
        text = insert(text, "[ \t]*#ifdef WhiteWorld\r?\n", name, ""
                + "    " + LAVA_COLOUR + "\n"
                + "    if (dh_material_id == DH_BLOCK_LAVA) Albedo.rgb = lavaColor(LAVA_STILL, mcmeLavaHere, mcmeLavaTime(frameTimeCounter));\n\n", true);
        text = insert(text, "\t#if SSS_TYPE == 0\r?\n", name, ""
                + "\t" + LAVA_GLOW_MARK + "\n"
                + "\tif (dh_material_id == DH_BLOCK_LAVA) gl_FragData[2].a = min(gl_FragData[2].a * LAVA_GLOW, 1.0);\n\n", true);
        edits.put(dh, text);
    }

    static String blissCloudsGlsl(String defines) {
        return "\n"
                + "// MCME: the clouds between the camera and the fire eye (MCME mod)\n"
                + "#ifdef OVERWORLD_SHADER\n"
                + "layout(r16f) uniform image2D mcmeEyeCloud;\n"
                + defines + "#endif\n"
                + "\n";
    }

    static final String BLISS_CLOUDS_MAIN = "\n"
            + "\t\t// MCME: the clouds between the camera and the fire eye, once a frame:\n"
            + "\t\t// Bliss's own, as far as the eye\n"
            + "\t\tif (all(lessThan(gl_FragCoord.xy, vec2(1.0)))) {\n"
            + "\t\t\tvec3 mcmeEye = vec3(MCME_EYE_BLOCK - cameraPositionInt) + 0.5 - cameraPositionFract;\n"
            + "\t\t\tfloat mcmeCloudDistance = cloudPlaneDistance;\n"
            + "\t\t\tvec4 mcmeClouds = GetVolumetricClouds((gbufferModelView * vec4(mcmeEye, 1.0)).xyz, vec2(0.5), WsunVec,\n"
            + "\t\t\t                                      directLightColor, indirectLightColor, mcmeCloudDistance, phaseLevels, backScatterPhase);\n"
            + "\t\t\timageStore(mcmeEyeCloud, ivec2(0), vec4(mcmeClouds.a));\n"
            + "\t\t}\n";

    // The same for Bliss's development builds, whose clouds stop at the
    // distance of the view position they are given (within its render
    // distance, Distant Horizons' included): the eye's
    static final String BLISS_DEV_CLOUDS_MAIN = "\n"
            + "\t\t// MCME: the clouds between the camera and the fire eye, once a frame:\n"
            + "\t\t// Bliss's own, as far as the eye\n"
            + "\t\tif (all(lessThan(gl_FragCoord.xy, vec2(1.0)))) {\n"
            + "\t\t\tvec3 mcmeEye = vec3(MCME_EYE_BLOCK - cameraPositionInt) + 0.5 - cameraPositionFract;\n"
            + "\t\t\tvec4 mcmeClouds = GetVolumetricClouds((gbufferModelView * vec4(mcmeEye, 1.0)).xyz, vec2(0.5), WsunVec,\n"
            + "\t\t\t                                      directLightColor, indirectLightColor);\n"
            + "\t\t\timageStore(mcmeEyeCloud, ivec2(0), vec4(mcmeClouds.a));\n"
            + "\t\t}\n";

    /**
     * Adds to edits those measuring the clouds in front of the eye - none where
     * Bliss's clouds aren't the ones this knows (2.1's). Whether it did.
     */
    static boolean blissClouds(Path shaders, Eye eye, Map<Path, String> edits) throws IOException, Unsupported {
        Path comp2 = shaders.resolve("dimensions/composite2.fsh");
        if (!isFile(comp2)) {
            return false;
        }
        String text = current(edits, comp2);
        String full = expand(shaders, text, comp2.getParent(), new HashSet<Path>());
        String anchor = "vec4 VolumetricClouds = GetVolumetricClouds\\(viewPos0, BN, WsunVec, directLightColor, indirectLightColor, "
                + "cloudPlaneDistance, phaseLevels, backScatterPhase\\);";
        String measure = BLISS_CLOUDS_MAIN;
        if (!search(anchor, text)) {
            anchor = "vec4 VolumetricClouds = GetVolumetricClouds\\(viewPos0, BN, WsunVec, directLightColor, indirectLightColor\\);";
            measure = BLISS_DEV_CLOUDS_MAIN;
        }
        if (!search(anchor, text) || !full.contains("vec4 GetVolumetricClouds(")) {
            return false;
        }
        String name = comp2.getFileName().toString();
        text = "#extension GL_ARB_shader_image_load_store : enable\n" + text;
        text = insert(text, "void main\\(\\) \\{", name,
                blissCloudsGlsl("#define MCME_EYE_BLOCK ivec3(" + eye.x + ", " + eye.y + ", " + eye.z + ")\n"), true);
        text = declareUniforms(shaders, comp2, text, text.indexOf("// MCME: the clouds between the camera and the fire eye ("),
                new String[][]{{"ivec3", "cameraPositionInt"}, {"vec3", "cameraPositionFract"}, {"mat4", "gbufferModelView"}}, edits);
        text = insert(text, anchor, name, measure);
        Path props = shaders.resolve("shaders.properties");
        String nl = nl(read(props));
        edits.put(comp2, text);
        edits.put(props, rstrip(current(edits, props)) + nl + nl
                + "# MCME: the clouds between the camera and the fire eye (MCME mod)" + nl
                + "image.mcmeEyeCloud = mcmeEyeCloudSampler RED R16F HALF_FLOAT false false 1 1" + nl);
        return true;
    }
}

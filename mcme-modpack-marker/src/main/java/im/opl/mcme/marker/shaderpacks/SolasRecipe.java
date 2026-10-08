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
 * Solas: the eye in programs/deferred1.glsl, and lava in its terrain,
 * Distant Horizons' and Voxy's.
 * One of ShaderPackPatcher's recipes; see there for how they work.
 */
final class SolasRecipe {
    private SolasRecipe() {
    }

    // ---- Solas: the eye in programs/deferred1.glsl, after its fog over the
    // terrain, Distant Horizons' and Voxy's, and its clouds, which it shows
    // through - in the gamma-space colours it has there - and the lava in its
    // terrain, Distant Horizons' and Voxy's

    static void solasEye(Path shaders, Eye eye, Map<Path, String> edits) throws IOException, Unsupported {
        Path deferred = shaders.resolve("programs/deferred1.glsl");
        if (!isFile(deferred)) throw new Unsupported("Solas's deferred1 has moved");
        String name = deferred.getFileName().toString();
        edits.put(deferred, section(current(edits, deferred), "#ifdef FSH", "#ifdef VSH", name, frag -> {
            frag = eyeGlobals(shaders, deferred, frag, findOne(frag, "void main\\(\\) \\{", name).start(), eye, false, true,
                    "defined OVERWORLD", edits);
            return insert(frag, "[ \\t]*/\\* DRAWBUFFERS:045 \\*/\\r?\\n", name, ""
                    + "\t// MCME: the fire eye - after the fog, so it shows at any distance, and the\n"
                    + "\t// clouds, which it shows through, as it does without shaders (MCME's mod)\n"
                    + "\t#ifdef OVERWORLD\n"
                    + "\tcolor = mcmeDrawFireEye(color, viewPos.xyz,\n"
                    + "\t                        mcmeLodDistance(texCoord, ivec2(gl_FragCoord.xy), z0 < 1.0 ? length(viewPos.xyz) : 1.0e9), 1.0);\n"
                    + "\t#endif\n\n", true);
        }));
    }

    static void solasLava(Path shaders, Map<Path, String> edits) throws IOException, Unsupported {
        String isLava = lavaTest(shaders, "mat");
        if (isLava == null) {
            LAVA_LEFT_OUT.add("Solas: its block.properties gives lava no ID");
            return;
        }
        Path programs = shaders.resolve("programs");
        lavaPart("Solas's terrain", LavaWhere.TERRAIN, edits, e -> solasTerrainLava(shaders, programs.resolve("gbuffers_terrain.glsl"), isLava, e));
        Path dh = programs.resolve("dh_terrain.glsl");
        if (isFile(dh)) lavaPart("Solas's Distant Horizons terrain", LavaWhere.DISTANT_HORIZONS, edits, e -> solasDhLava(shaders, dh, isLava, e));
        Path voxy = programs.resolve("voxyOpaque.glsl"), voxyJson = shaders.resolve("voxy.json");
        if (isFile(voxy) && isFile(voxyJson)) lavaPart("Solas's Voxy terrain", LavaWhere.VOXY, edits, e -> solasVoxyLava(voxy, voxyJson, isLava, e));
    }

    static void solasTerrainLava(Path shaders, Path terrain, String isLava, Map<Path, String> edits) throws IOException, Unsupported {
        String name = terrain.getFileName().toString();
        edits.put(terrain, section(current(edits, terrain), "#ifdef FSH", "#ifdef VSH", name, frag -> {
            frag = includeLavaScreen(shaders, terrain, frag, findOne(frag, "void main\\(\\) \\{\\r?\\n", name).start(), edits);
            frag = insert(frag, "[ \\t]*vec4 albedo = albedoTexture;\\r?\\n", name, ""
                    + "\t" + LAVA_FRAME + "\n"
                    + "\tFluidFrame mcmeLavaHere = mcmeLavaFrame(mcmeScreenRel(gbufferProjectionInverse), texCoord);\n");
            return insert(frag, "[ \\t]*gbuffersLighting\\(color, albedo, ", name, ""
                    + "\t" + LAVA_COLOUR + ", and " + LAVA_GLOW_MARK.substring(LAVA_GLOW_MARK.indexOf("glows")) + "\n"
                    + "\tif " + isLava + " {\n"
                    + "\t\talbedo.rgb = mcmeLava(gtexture, texCoord, mcmeLavaHere, mcmeLavaTime(frameTimeCounter), albedo.rgb);\n"
                    + "\t\temission *= LAVA_GLOW;\n"
                    + "\t}\n\n", true);
        }));
    }

    /** Distant Horizons' LODs: still lava, its vertices telling it apart (mat), which they don't. */
    static void solasDhLava(Path shaders, Path dh, String isLava, Map<Path, String> edits) throws IOException, Unsupported {
        String name = dh.getFileName().toString();
        String text = section(current(edits, dh), "#ifdef VSH", null, name, vert -> insert(vert,
                "[ \\t]*if \\(dhMaterialId == DH_BLOCK_LEAVES\\)\\{", name,
                "\tif (dhMaterialId == DH_BLOCK_LAVA) mat = " + isLava.replaceAll("^\\(mat == (\\d+).*$", "$1") + "; // MCME: which is RP-Mordor's lava\n", true));
        edits.put(dh, section(text, "#ifdef FSH", "#ifdef VSH", name, frag -> {
            frag = includeLavaScreen(shaders, dh, frag, findOne(frag, "void main\\(\\) \\{\\r?\\n", name).start(), edits);
            frag = insert(frag, "[ \\t]*vec4 albedo = albedoTexture \\* color;\\r?\\n", name, ""
                    + "\t" + LAVA_FRAME + "\n"
                    + "\tFluidFrame mcmeLavaHere = mcmeLavaFrame(mcmeScreenRel(dhProjectionInverse), vec2(0.0));\n");
            return insert(frag, "[ \\t]*gbuffersLighting\\(color, albedo, ", name, ""
                    + "\t" + LAVA_COLOUR + ", and it glows (MCME's mod)\n"
                    + "\tif " + isLava + " {\n"
                    + "\t\talbedo.rgb = lavaColor(LAVA_STILL, mcmeLavaHere, mcmeLavaTime(frameTimeCounter));\n"
                    + "\t\temission = 0.5 * LAVA_GLOW;\n"
                    + "\t}\n\n", true);
        }));
    }

    /** Voxy's LODs: still lava, without derivatives, which Voxy's fragments haven't. */
    static void solasVoxyLava(Path voxy, Path voxyJson, String isLava, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, voxy);
        String name = voxy.getFileName().toString();
        Matcher emit = findOne(text, "void voxy_emitFragment\\(", name);
        // (Voxy declares the uniforms its JSON lists itself)
        text = text.substring(0, emit.start()) + (LAVA_MARK + "\n#include \"/lib/mcme/lava_pack.glsl\"\n\n").replace("\n", nl(text))
                + text.substring(emit.start());
        text = insert(text, "[ \\t]*gbuffersLighting\\(voxyColor, albedo, ", name, ""
                + "    " + LAVA_COLOUR + ", and " + LAVA_GLOW_MARK.substring(LAVA_GLOW_MARK.indexOf("glows")) + "\n"
                + "    if " + isLava + " {\n"
                + "        albedo.rgb = lavaColor(LAVA_STILL, mcmeLavaFrameFlat(worldPos, mat3(vxModelViewInv) * normal, length(viewPos) * 1.4 / viewHeight),\n"
                + "                               mcmeLavaTime(frameTimeCounter));\n"
                + "        emission *= LAVA_GLOW;\n"
                + "    }\n\n", true);
        edits.put(voxy, text);
        edits.put(voxyJson, voxyUniforms(current(edits, voxyJson), "Solas's voxy.json",
                "cameraPositionInt", "cameraPositionFract", "frameTimeCounter", "viewHeight", "vxModelViewInv"));
    }

    /**
     * Solas: faces dropped in programs/gbuffers_water.glsl's vertex shader; the
     * eye drawn in a pass of its own, composite4 - after its water fog,
     * volumetric fog and refraction (composite to composite3), before its bloom
     * (composite13) - over colortex0, its linear scene. Its overworld programs
     * are its root's, which its other dimensions fall back to, so the pass is
     * switched off in those.
     */
    static Map<Path, String> solas(Path shaders, Eye eye) throws IOException, Unsupported {
        Path programs = shaders.resolve("programs");
        Path water = programs.resolve("gbuffers_water.glsl"), particles = programs.resolve("gbuffers_textured.glsl");
        Path tonemap = programs.resolve("composite14.glsl"), bloom = programs.resolve("composite13.glsl");
        for (Path p : new Path[]{water, particles, tonemap, bloom, shaders.resolve("composite3.fsh")}) {
            if (!isFile(p)) {
                throw new Unsupported("not Solas");
            }
        }
        String marker = "#ifdef VSH";
        Path comp3 = programs.resolve("composite3.glsl");
        if (!read(water).contains(marker) || !read(particles).contains(marker)
                || !read(tonemap).contains("Uncharted2Tonemap(color * TONEMAP_BRIGHTNESS)")
                || !read(bloom).contains("computeBloom") || !isFile(comp3) || !read(comp3).contains("DRAWBUFFERS:0")) {
            throw new Unsupported("Solas's passes have changed");
        }
        Map<Path, String> edits = new LinkedHashMap<>();
        solasEye(shaders, eye, edits);
        edits.put(water, dropEyeFaces(shaders, water, edits, marker));
        edits.put(particles, dropFarParticles(shaders, particles, edits, marker, null));
        if (eye.lava) {
            solasLava(shaders, edits);
        }
        return edits;
    }
}

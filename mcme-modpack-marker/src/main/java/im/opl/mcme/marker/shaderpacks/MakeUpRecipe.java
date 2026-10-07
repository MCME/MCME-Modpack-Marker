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
 * MakeUp: the eye in common/deferred_fragment.glsl, and lava in its
 * terrain and Distant Horizons'.
 * One of ShaderPackPatcher's recipes; see there for how they work.
 */
final class MakeUpRecipe {
    private MakeUpRecipe() {
    }

    /**
     * MakeUp (and edits): faces dropped in common/water_blocks_vertex.glsl; the
     * eye drawn in composite (its first, after the forward-rendered scene and
     * its fog) before it takes its bloom source, in its gamma-space colours and
     * exposure. It has no free pass, so this goes into its own.
     */
    static Map<Path, String> makeup(Path shaders, Eye eye) throws IOException, Unsupported {
        Path vertex = shaders.resolve("common/water_blocks_vertex.glsl"), comp = shaders.resolve("common/composite_fragment.glsl");
        if (!(isFile(vertex) && isFile(comp))) {
            throw new Unsupported("not MakeUp");
        }
        Map<Path, String> edits = new LinkedHashMap<>();
        Path particles = shaders.resolve("common/solid_blocks_vertex.glsl");
        Path textured = shaders.resolve("world0/gbuffers_textured.vsh");
        if (!isFile(particles) || !isFile(textured) || !read(textured).contains("GBUFFER_TEXTURED")) {
            throw new Unsupported("MakeUp's particles have moved");
        }
        makeupEye(shaders, eye, edits);
        edits.put(vertex, dropEyeFaces(shaders, vertex, edits));
        edits.put(particles, dropFarParticles(shaders, particles, edits, null, "GBUFFER_TEXTURED"));
        if (eye.lava) {
            makeupLava(shaders, edits);
        }
        return edits;
    }

    // ---- MakeUp: the eye in common/deferred_fragment.glsl, where it puts its
    // clouds over the sky - into the sky after them, so it shows through them as
    // it does without shaders, by the exposure composite stored for the frame
    // before (gaux3) - and the
    // lava in its terrain and Distant Horizons'. Its Voxy terrain knows lava
    // only along with other glowing blocks, so it keeps its own.

    static void makeupEye(Path shaders, Eye eye, Map<Path, String> edits) throws IOException, Unsupported {
        Path deferred = shaders.resolve("common/deferred_fragment.glsl");
        if (!isFile(deferred)) throw new Unsupported("MakeUp's deferred has moved");
        String text = current(edits, deferred);
        String name = deferred.getFileName().toString();
        if (!search("uniform sampler2D gaux3;", text)) throw new Unsupported("MakeUp's deferred has changed");
        text = eyeGlobals(shaders, deferred, text, findOne(text, "void main\\(\\) \\{", name).start(), eye, false, true,
                "!defined THE_END && !defined NETHER", edits);
        text = insert(text, "[ \\t]*#if AO == 1\\r?\\n[ \\t]*// AO distance attenuation", name, ""
                + "    // MCME: the fire eye, into the sky after the clouds, which it shows through (MCME's mod)\n"
                + "    #if !defined THE_END && !defined NETHER\n"
                + "    if (linearDepth > 0.9999) {\n"
                + "        vec4 mcmeView = gbufferProjectionInverse * vec4(vec3(texcoord, depth) * 2.0 - 1.0, 1.0);\n"
                + "        blockColor.rgb = mcmeDrawFireEye(blockColor.rgb, mcmeView.xyz / mcmeView.w,\n"
                + "                                         mcmeLodDistance(texcoord, ivec2(gl_FragCoord.xy), 1.0e9),\n"
                + "                                         1.0 / max(texture2DLod(gaux3, vec2(0.5), 0.0).r, 1.0e-4));\n"
                + "    }\n"
                + "    #endif\n\n", true);
        edits.put(deferred, text);
    }

    static void makeupLava(Path shaders, Map<Path, String> edits) throws IOException {
        Path common = shaders.resolve("common");
        lavaPart("MakeUp's terrain", LavaWhere.TERRAIN, edits, e -> makeupTerrainLava(shaders, common.resolve("solid_blocks_fragment.glsl"), e));
        Path dhVertex = common.resolve("solid_dh_blocks_vertex.glsl"), dh = common.resolve("solid_dh_blocks_fragment.glsl");
        if (isFile(dh) && isFile(dhVertex)) {
            lavaPart("MakeUp's Distant Horizons terrain", LavaWhere.DISTANT_HORIZONS, edits, e -> makeupDhLava(shaders, dhVertex, dh, e));
        }
    }

    static void makeupTerrainLava(Path shaders, Path terrain, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, terrain);
        String name = terrain.getFileName().toString();
        Matcher main = findOne(text, "void main\\(\\) \\{\\r?\\n", name);
        // (shared by every program drawing blocks: the lava only in the terrain's)
        text = text.substring(0, main.start()) + (LAVA_MARK + "\n#ifdef GBUFFER_TERRAIN\n#define MCME_SCREEN_REL\n"
                + "#include \"/lib/mcme/lava_pack.glsl\"\n#endif\n\n").replace("\n", nl(text)) + text.substring(main.start());
        String[][] uniforms = Arrays.copyOf(LAVA_SCREEN_UNIFORMS, LAVA_SCREEN_UNIFORMS.length + 1);
        uniforms[uniforms.length - 1] = new String[]{"mat4", "gbufferProjectionInverse"};
        text = declareUniforms(shaders, terrain, text, text.indexOf(LAVA_MARK), uniforms, edits);
        text = insert(text, "void main\\(\\) \\{\\r?\\n", name, ""
                + "    #ifdef GBUFFER_TERRAIN\n"
                + "    " + LAVA_FRAME + "\n"
                + "    FluidFrame mcmeLavaHere = mcmeLavaFrame(mcmeScreenRel(gbufferProjectionInverse), texcoord);\n"
                + "    #endif\n");
        text = insert(text, "[ \\t]*vec4 blockColor = texture2D\\(tex, texcoord\\) \\* tintColor;\\r?\\n", name, ""
                + "        #ifdef GBUFFER_TERRAIN\n"
                + "        " + LAVA_COLOUR + "\n"
                + "        blockColor.rgb = mcmeLava(tex, texcoord, mcmeLavaHere, mcmeLavaTime(frameTimeCounter), blockColor.rgb);\n"
                + "        #endif\n");
        edits.put(terrain, text);
    }

    /** Distant Horizons' LODs: still lava, its vertices told it apart (dhMaterialId), as its fragments aren't. */
    static void makeupDhLava(Path shaders, Path vertex, Path dh, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, vertex);
        String name = vertex.getFileName().toString();
        text = insert(text, "void main\\(\\) \\{\\r?\\n", name, "    mcmeIsLava = float(dhMaterialId == DH_BLOCK_LAVA); // MCME: which is RP-Mordor's lava\n");
        text = insert(text, "void main\\(\\) \\{", name, "varying float mcmeIsLava; // MCME (MCME's mod)\n\n", true);
        edits.put(vertex, text);

        text = current(edits, dh);
        name = dh.getFileName().toString();
        if (!search("varying vec4 position;", text)) throw new Unsupported(name + " has changed");
        Matcher main = findOne(text, "void main\\(\\) \\{\\r?\\n", name);
        text = includeLavaAt(shaders, dh, text, main.start(), edits);
        text = insert(text, "void main\\(\\) \\{", name, "varying float mcmeIsLava; // MCME (MCME's mod)\n\n", true);
        text = insert(text, "void main\\(\\) \\{\\r?\\n", name, ""
                + "    " + LAVA_FRAME + "\n"
                + "    FluidFrame mcmeLavaHere = mcmeLavaFrame(position.xyz, vec2(0.0));\n");
        text = insert(text, "[ \\t]*vec4 blockColor = tintColor;\\r?\\n", name, ""
                + "    " + LAVA_COLOUR + "\n"
                + "    if (mcmeIsLava > 0.5) blockColor.rgb = lavaColor(LAVA_STILL, mcmeLavaHere, mcmeLavaTime(frameTimeCounter));\n");
        edits.put(dh, text);
    }
}

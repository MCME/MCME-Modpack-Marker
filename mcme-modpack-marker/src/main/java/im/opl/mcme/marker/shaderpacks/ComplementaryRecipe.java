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
 * Complementary (Reimagined, Unbound, Spooklementary): the eye in
 * program/deferred1.glsl, and lava in its terrain, Distant Horizons' and Voxy's.
 * One of ShaderPackPatcher's recipes; see there for how they work.
 */
final class ComplementaryRecipe {
    private ComplementaryRecipe() {
    }

    static void complementaryLava(Path shaders, Map<Path, String> edits) throws IOException, Unsupported {
        String isLava = lavaTest(shaders, "mat");
        if (isLava == null) {
            LAVA_LEFT_OUT.add("Complementary: its block.properties gives lava no ID");
            return;
        }
        Path program = shaders.resolve("program");
        lavaPart("Complementary's terrain", LavaWhere.TERRAIN, edits, e -> complementaryTerrainLava(shaders, program.resolve("gbuffers_terrain.glsl"), isLava, e));
        Path dh = program.resolve("dh_terrain.glsl");
        if (isFile(dh)) lavaPart("Complementary's Distant Horizons terrain", LavaWhere.DISTANT_HORIZONS, edits, e -> complementaryDhLava(shaders, dh, e));
        Path voxy = program.resolve("voxy_opaque.glsl"), voxyJson = program.resolve("voxy.json");
        if (isFile(voxy) && isFile(voxyJson)) lavaPart("Complementary's Voxy terrain", LavaWhere.VOXY, edits, e -> complementaryVoxyLava(voxy, voxyJson, isLava, e));
    }

    /**
     * Complementary's world terrain: the frame at the top of main(), before
     * anything discards; the colour - in Reimagined and Unbound before the
     * material code, which makes lava glow by how bright it is; in its newer
     * code (Spooklementary's), after its own lava's noise and edges, which
     * would paint over it - and the glow LAVA_GLOW times as much.
     */
    static void complementaryTerrainLava(Path shaders, Path terrain, String isLava, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, terrain);
        String name = terrain.getFileName().toString();
        int vertex = text.indexOf("#ifdef VERTEX_SHADER");
        if (vertex < 0 || !search("in vec3 vertexPos;", text)) throw new Unsupported("gbuffers_terrain has changed");
        String frag = text.substring(0, vertex), rest = text.substring(vertex);
        Matcher main = findOne(frag, "void main\\(\\) \\{\\r?\\n", name);
        frag = includeLavaAt(shaders, terrain, frag, main.start(), edits);
        frag = insert(frag, "void main\\(\\) \\{\\r?\\n", name, "    " + LAVA_FRAME + "\n"
                + "    FluidFrame mcmeLavaHere = mcmeLavaFrame(vertexPos, texCoord);\n");
        String colour = "if " + isLava + " color.rgb = mcmeLava(tex, texCoord, mcmeLavaHere, mcmeLavaTime(frameTimeCounter), color.rgb);\n";
        if (search("#include \"/lib/materials/materialHandling/terrainMaterials\\.glsl\"", frag)) {
            frag = insert(frag, "[ \\t]*vec3 screenPos = vec3\\(gl_FragCoord\\.xy / vec2\\(viewWidth, viewHeight\\), gl_FragCoord\\.z\\);\\r?\\n", name,
                    "    " + LAVA_COLOUR + "\n    " + colour + "\n", true);
            frag = insert(frag, "[ \\t]*#include \"/lib/materials/materialHandling/terrainMaterials\\.glsl\"\\r?\\n", name,
                    "    " + LAVA_GLOW_MARK + "\n"
                    + "    if " + isLava + " emission *= LAVA_GLOW;\n");
        } else {
            frag = insert(frag, "[ \\t]*#include \"/lib/materials/specificMaterials/terrain/lavaEdge\\.glsl\"\\r?\\n", name,
                    "            " + LAVA_COLOUR + "\n            " + colour);
            frag = insert(frag, "[ \\t]*emission \\*= LAVA_EMISSION;\\r?\\n", name,
                    "            emission *= LAVA_GLOW; " + LAVA_GLOW_MARK + "\n");
        }
        edits.put(terrain, frag + rest);
    }

    /** Complementary's Distant Horizons terrain: still lava, from lava.glsl, its glow LAVA_GLOW times as much. */
    static void complementaryDhLava(Path shaders, Path dh, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, dh);
        String name = dh.getFileName().toString();
        int vertex = text.indexOf("#ifdef VERTEX_SHADER");
        if (vertex < 0 || !search("in vec3 playerPos;", text)) throw new Unsupported("dh_terrain has changed");
        String frag = text.substring(0, vertex), rest = text.substring(vertex);
        Matcher main = findOne(frag, "void main\\(\\) \\{\r?\n", name);
        frag = includeLavaAt(shaders, dh, frag, main.start(), edits);
        frag = insert(frag, "[ \t]*vec4 color = vec4\\(glColor\\.rgb, 1\\.0\\);\r?\n", name, ""
                + "    " + LAVA_COLOUR + "\n"
                + "    FluidFrame mcmeLavaHere = mcmeLavaFrame(playerPos, vec2(0.0));\n"
                + "    if (mat == DH_BLOCK_LAVA) color.rgb = lavaColor(LAVA_STILL, mcmeLavaHere, mcmeLavaTime(frameTimeCounter));\n");
        frag = insert(frag, "\\} else if \\(mat == DH_BLOCK_LAVA\\) \\{\r?\n[ \t]*emission = [^;\r\n]*;\r?\n", name,
                "        emission *= LAVA_GLOW; " + LAVA_GLOW_MARK + "\n");
        edits.put(dh, frag + rest);
    }

    /** Complementary's Voxy terrain: still lava, without derivatives, which Voxy's fragments haven't. */
    static void complementaryVoxyLava(Path voxy, Path voxyJson, String isLava, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, voxy);
        String name = voxy.getFileName().toString();
        Matcher emit = findOne(text, "void voxy_emitFragment\\(", name);
        // (Voxy declares the uniforms its JSON lists itself)
        text = text.substring(0, emit.start()) + (LAVA_MARK + "\n#include \"/lib/mcme/lava_pack.glsl\"\n\n").replace("\n", nl(text))
                + text.substring(emit.start());
        text = insert(text, "[ \t]*vec3 worldGeoNormal = normalize\\(mat3\\(vxModelViewInv\\) \\* normal\\);\r?\n", name, ""
                + "    " + LAVA_COLOUR + "\n"
                + "    if " + isLava + " color.rgb = lavaColor(LAVA_STILL, mcmeLavaFrameFlat(playerPos, worldGeoNormal, lViewPos * 1.4 / viewHeight),\n"
                + "                                       mcmeLavaTime(frameTimeCounter));\n");
        text = insert(text, "[ \t]*#include \"/lib/materials/materialHandling/terrainMaterials\\.glsl\"\r?\n", name,
                "    " + LAVA_GLOW_MARK + "\n"
                + "    if " + isLava + " emission *= LAVA_GLOW;\n");
        edits.put(voxy, text);
        edits.put(voxyJson, voxyUniforms(current(edits, voxyJson), "Complementary's voxy.json",
                "cameraPositionInt", "cameraPositionFract", "frameTimeCounter", "viewHeight"));
    }

    /**
     * Complementary's eye: drawn in program/deferred1.glsl, where it puts the
     * sky, its fog over the terrain and the distant terrain mods', and then its
     * clouds - just after those, so it shows through them as it does without
     * shaders, and at any distance; the translucents come later, over it. In
     * its linear colours, as its composites have them.
     */
    static void complementaryEye(Path shaders, Eye eye, Map<Path, String> edits) throws IOException, Unsupported {
        Path deferred = shaders.resolve("program/deferred1.glsl");
        if (!isFile(deferred)) throw new Unsupported("Complementary's deferred1 has moved");
        String text = current(edits, deferred);
        String name = deferred.getFileName().toString();
        int fragment = text.indexOf("#ifdef FRAGMENT_SHADER");
        if (fragment < 0 || !search("vec4 viewPos = gbufferProjectionInverse \\* \\(screenPos \\* 2\\.0 - 1\\.0\\);", text)) {
            throw new Unsupported("Complementary's deferred1 has changed");
        }
        Matcher main = re("void main\\(\\) \\{\\r?\\n").matcher(text);
        if (!main.find(fragment)) throw new Unsupported("Complementary's deferred1 has no main()");
        // (the pack's own declarations of what lod.glsl needs, marked before it goes in)
        text = markDeclarations(shaders, deferred, text, main.start(), names(LOD_UNIFORMS), edits);
        main = re("void main\\(\\) \\{\\r?\\n").matcher(text);
        if (!main.find(text.indexOf("#ifdef FRAGMENT_SHADER"))) throw new Unsupported("Complementary's deferred1 has no main()");
        String globals = "// MCME: the fire eye, at its block (MCME's mod)\n"
                + "#ifdef OVERWORLD\n"
                + "MCME_UNIFORMS"
                + resource("/mcme/patch/lod.glsl") + "\n"
                + drawDefines(eye, true) + "#include \"/lib/mcme/fire_eye_draw.glsl\"\n"
                + "#endif\n\n";
        text = text.substring(0, main.start()) + globals.replace("\n", nl(text)) + text.substring(main.start());
        int at = text.indexOf("MCME_UNIFORMS");
        text = text.substring(0, at) + text.substring(at + "MCME_UNIFORMS".length());
        text = declareUniforms(shaders, deferred, text, at, EYE_UNIFORMS, edits);
        text = insert(text, "color = mix\\(color, vec4\\(clouds\\.rgb, 0\\.0\\), clouds\\.a\\);\\r?\\n[ \\t]*\\}\\r?\\n[ \\t]*#endif\\r?\\n", name, "\n"
                + "    // MCME: the fire eye - after the fog, so it shows at any distance, and the\n"
                + "    // clouds, which it shows through, as it does without shaders (MCME's mod)\n"
                + "    #ifdef OVERWORLD\n"
                + "        color.rgb = mcmeDrawFireEye(color.rgb, viewPos.xyz,\n"
                + "                                    mcmeLodDistance(texCoord, ivec2(gl_FragCoord.xy), z0 < 1.0 ? length(viewPos.xyz) : 1.0e9), 1.0);\n"
                + "    #endif\n");
        edits.put(deferred, text);
    }

    static Map<Path, String> complementary(Path shaders, Eye eye) throws IOException, Unsupported {
        Path water = shaders.resolve("program/gbuffers_water.glsl"), comp1 = shaders.resolve("program/composite1.glsl");
        if (!(isFile(water) && isFile(comp1) && isFile(shaders.resolve("program/composite3.glsl")))) {
            throw new Unsupported("not Complementary");
        }
        String marker = "//////////Vertex Shader//////////";
        if (!read(water).contains(marker) || !read(comp1).contains("/* DRAWBUFFERS:0 */")) {
            throw new Unsupported("Complementary's water or composite1 have changed");
        }
        Map<Path, String> edits = new LinkedHashMap<>();
        complementaryEye(shaders, eye, edits);
        Path particles = shaders.resolve("program/gbuffers_textured.glsl");
        if (!isFile(particles) || !read(particles).contains(marker)) {
            throw new Unsupported("Complementary's particles have moved");
        }
        edits.put(water, dropEyeFaces(shaders, water, edits, marker));
        edits.put(particles, dropFarParticles(shaders, particles, edits, marker, null));
        if (eye.lava) {
            complementaryLava(shaders, edits);
        }
        return edits;
    }
}

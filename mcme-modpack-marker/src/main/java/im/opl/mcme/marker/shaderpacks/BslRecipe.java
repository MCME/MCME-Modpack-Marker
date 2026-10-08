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
 * BSL (v10, and edits): the eye in its composite, and lava in its terrain,
 * Distant Horizons' and Voxy's.
 * One of ShaderPackPatcher's recipes; see there for how they work.
 */
final class BslRecipe {
    private BslRecipe() {
    }

    // ---- BSL's lava: in its terrain, Distant Horizons' and Voxy's, its colour
    // from lava.glsl once BSL has its albedo in linear colour (and recoloured
    // it, if set to), and its glow - BSL's emission, from the texture -
    // LAVA_GLOW times as much

    static void bslLava(Path shaders, Map<Path, String> edits) throws IOException {
        Path program = shaders.resolve("program");
        lavaPart("BSL's terrain", LavaWhere.TERRAIN, edits, e -> bslTerrainLava(shaders, program.resolve("gbuffers_terrain.glsl"), e));
        Path dh = program.resolve("dh_terrain.glsl");
        if (isFile(dh)) lavaPart("BSL's Distant Horizons terrain", LavaWhere.DISTANT_HORIZONS, edits, e -> bslDhLava(shaders, dh, e));
        Path voxy = program.resolve("voxy_opaque.glsl"), voxyJson = program.resolve("voxy.json");
        if (isFile(voxy) && isFile(voxyJson)) lavaPart("BSL's Voxy terrain", LavaWhere.VOXY, edits, e -> bslVoxyLava(voxy, voxyJson, e));
    }

    static String bslFragment(String text, String name, FragmentEdit edit) throws IOException, Unsupported {
        int vertex = text.indexOf("//Vertex Shader//");
        if (vertex < 0) throw new Unsupported(name + " has changed");
        return edit.apply(text.substring(0, vertex)) + text.substring(vertex);
    }

    static final String BSL_GLOW = "[ \\t]*emission \\*= GetHardcodedEmission\\(albedo\\.rgb, hsv\\);\\r?\\n";
    static final String BSL_WHITE_WORLD = "[ \\t]*#ifdef WHITE_WORLD\\r?\\n";

    static void bslTerrainLava(Path shaders, Path terrain, Map<Path, String> edits) throws IOException, Unsupported {
        String name = terrain.getFileName().toString();
        edits.put(terrain, bslFragment(current(edits, terrain), name, frag -> {
            Matcher main = findOne(frag, "void main\\(\\) \\{\\r?\\n", name);
            frag = includeLavaScreen(shaders, terrain, frag, main.start(), edits);
            frag = insert(frag, "[ \\t]*vec4 albedo = texture2D\\(texture, texCoord\\) \\* vec4\\(color\\.rgb, 1\\.0\\);\\r?\\n", name, ""
                    + "\t" + LAVA_FRAME + "\n"
                    + "\tFluidFrame mcmeLavaHere = mcmeLavaFrame(mcmeScreenRel(gbufferProjectionInverse), texCoord);\n");
            frag = insert(frag, BSL_GLOW, name, "\t\tif (lava > 0.5) emission *= LAVA_GLOW; " + LAVA_GLOW_MARK + "\n");
            return insert(frag, BSL_WHITE_WORLD, name, ""
                    + "\t\t" + LAVA_COLOUR + "\n"
                    + "\t\tif (lava > 0.5) albedo.rgb = pow(mcmeLava(texture, texCoord, mcmeLavaHere, mcmeLavaTime(frameTimeCounter),\n"
                    + "\t\t                                          pow(albedo.rgb, vec3(1.0 / 2.2))), vec3(2.2));\n\n", true);
        }));
    }

    /** Distant Horizons' LODs: still lava, as they don't say which way it flows. */
    static void bslDhLava(Path shaders, Path dh, Map<Path, String> edits) throws IOException, Unsupported {
        String name = dh.getFileName().toString();
        edits.put(dh, bslFragment(current(edits, dh), name, frag -> {
            Matcher main = findOne(frag, "void main\\(\\) \\{\\r?\\n", name);
            frag = includeLavaScreen(shaders, dh, frag, main.start(), edits);
            frag = insert(frag, "[ \\t]*vec4 albedo = color;\\r?\\n", name, ""
                    + "\t" + LAVA_FRAME + "\n"
                    + "\tFluidFrame mcmeLavaHere = mcmeLavaFrame(mcmeScreenRel(dhProjectionInverse), vec2(0.0));\n");
            frag = insert(frag, BSL_GLOW, name, "\t\tif (lava > 0.5) emission *= LAVA_GLOW; " + LAVA_GLOW_MARK + "\n");
            return insert(frag, BSL_WHITE_WORLD, name, ""
                    + "\t\t" + LAVA_COLOUR + "\n"
                    + "\t\tif (lava > 0.5) albedo.rgb = pow(lavaColor(LAVA_STILL, mcmeLavaHere, mcmeLavaTime(frameTimeCounter)), vec3(2.2));\n\n", true);
        }));
    }

    /** Voxy's LODs: still lava, without derivatives, which Voxy's fragments haven't. */
    static void bslVoxyLava(Path voxy, Path voxyJson, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, voxy);
        String name = voxy.getFileName().toString();
        Matcher emit = findOne(text, "void voxy_emitFragment\\(", name);
        // (Voxy declares the uniforms its JSON lists itself)
        text = text.substring(0, emit.start()) + (LAVA_MARK + "\n#include \"/lib/mcme/lava_pack.glsl\"\n\n").replace("\n", nl(text))
                + text.substring(emit.start());
        text = insert(text, BSL_GLOW, name, "\t\tif (lava > 0.5) emission *= LAVA_GLOW; " + LAVA_GLOW_MARK + "\n");
        text = insert(text, BSL_WHITE_WORLD, name, ""
                + "\t\t" + LAVA_COLOUR + "\n"
                + "\t\tif (lava > 0.5) albedo.rgb = pow(lavaColor(LAVA_STILL, mcmeLavaFrameFlat(worldPos, mat3(vxModelViewInv) * normal,\n"
                + "\t\t                                                                     length(viewPos) * 1.4 / viewHeight),\n"
                + "\t\t                                           mcmeLavaTime(frameTimeCounter)), vec3(2.2));\n\n", true);
        edits.put(voxy, text);
        edits.put(voxyJson, voxyUniforms(current(edits, voxyJson), "BSL's voxy.json",
                "cameraPositionInt", "cameraPositionFract", "frameTimeCounter", "viewHeight"));
    }

    /**
     * BSL's eye: drawn in program/deferred1.glsl, where it puts the sky, its
     * fog over the terrain and the distant terrain mods', and then its clouds -
     * just after those, so it shows through them as it does without shaders,
     * and at any distance (viewPos and z are the mods' terrain's there, where
     * it is nearest); the translucents come later, over it. In its linear
     * colours, by its fixed exposure (exp2(2 + EXPOSURE), in composite5).
     */
    static void bslEye(Path shaders, Eye eye, Map<Path, String> edits) throws IOException, Unsupported {
        Path deferred = shaders.resolve("program/deferred1.glsl");
        if (!isFile(deferred)) throw new Unsupported("BSL's deferred1 has moved");
        String name = deferred.getFileName().toString();
        edits.put(deferred, bslFragment(current(edits, deferred), name, frag -> {
            Matcher main = findOne(frag, "void main\\(\\) \\{\\r?\\n", name);
            frag = frag.substring(0, main.start()) + ("// MCME: the fire eye, at its block (MCME's mod)\n"
                    + "#ifdef OVERWORLD\n"
                    + drawDefines(eye, true) + "#include \"/lib/mcme/fire_eye_draw.glsl\"\n"
                    + "#endif\n\n").replace("\n", nl(frag)) + frag.substring(main.start());
            frag = declareUniforms(shaders, deferred, frag, frag.indexOf("#define MCME_EYE_BLOCK"), EYE_UNIFORMS, edits);
            return insert(frag, "[ \\t]*color\\.rgb = mix\\(color\\.rgb, cloud\\.rgb, cloud\\.a\\);\\r?\\n", name, ""
                    + "\t// MCME: the fire eye - after the fog, so it shows at any distance, and the\n"
                    + "\t// clouds, which it shows through, as it does without shaders (MCME's mod)\n"
                    + "\tcolor.rgb = mcmeDrawFireEye(color.rgb, viewPos.xyz, z < 1.0 ? length(viewPos.xyz) : 1.0e9, 1.0 / exp2(2.0 + EXPOSURE));\n");
        }));
    }

    /** BSL (v10, and edits): faces dropped in program/gbuffers_water.glsl's vertex shader; the eye as bslEye(). */
    static Map<Path, String> bsl(Path shaders, Eye eye) throws IOException, Unsupported {
        Path program = shaders.resolve("program");
        Path water = program.resolve("gbuffers_water.glsl"), comp = program.resolve("composite.glsl"), tonemap = program.resolve("composite5.glsl");
        Path particles = program.resolve("gbuffers_textured.glsl");
        for (Path p : new Path[]{water, comp, tonemap, particles}) {
            if (!isFile(p)) {
                throw new Unsupported("not BSL");
            }
        }
        String marker = "//Vertex Shader//";
        String text = read(comp);
        if (!read(water).contains(marker) || !read(particles).contains(marker) || !read(tonemap).contains("color *= exp2(2.0 + EXPOSURE);")
                || !search("vec4 viewPos = gbufferProjectionInverse \\* \\(screenPos \\* 2\\.0 - 1\\.0\\);", text)
                || !search("color\\.rgb \\*= color\\.rgb;", text)) {
            throw new Unsupported("BSL's composite, exposure or water have changed");
        }
        Map<Path, String> edits = new LinkedHashMap<>();
        bslEye(shaders, eye, edits);
        edits.put(water, dropEyeFaces(shaders, water, edits, marker));
        edits.put(particles, dropFarParticles(shaders, particles, edits, marker, null));
        if (eye.lava) {
            bslLava(shaders, edits);
        }
        return edits;
    }
}

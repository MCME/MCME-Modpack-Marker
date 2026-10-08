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
 * Mellow: the eye in program/deferred1.fsh, and lava in its terrain and
 * Distant Horizons'.
 * One of ShaderPackPatcher's recipes; see there for how they work.
 */
final class MellowRecipe {
    private MellowRecipe() {
    }

    /**
     * Mellow: faces dropped in program/gbuffers_water.vsh; the eye drawn in a
     * pass of its own, composite1 - its composites start at 2, with bloom - over
     * colortex0, its linear scene, with its fixed EXPOSURE.
     */
    static Map<Path, String> mellow(Path shaders, Eye eye) throws IOException, Unsupported {
        Path water = shaders.resolve("program/gbuffers_water.vsh"), fin = shaders.resolve("program/composite8.fsh");
        Path settings = shaders.resolve("lib/settings.glsl");
        if (!(isFile(water) && isFile(fin) && isFile(settings))) {
            throw new Unsupported("not Mellow");
        }
        Path waterFsh = shaders.resolve("program/gbuffers_water.fsh");
        if (!read(fin).contains("Color.rgb *= EXPOSURE;") || !isFile(waterFsh) || !read(waterFsh).contains("DRAWBUFFERS:0")) {
            throw new Unsupported("Mellow's scene buffer or exposure have changed");
        }
        Map<Path, String> edits = new LinkedHashMap<>();
        Path particles = shaders.resolve("program/gbuffers_basic.vsh");
        if (!isFile(particles)) {
            throw new Unsupported("Mellow's particles have moved");
        }
        mellowEye(shaders, eye, edits);
        edits.put(water, dropEyeFaces(shaders, water, edits));
        edits.put(particles, dropFarParticles(shaders, particles, edits));
        if (eye.lava) {
            mellowLava(shaders, edits);
        }
        return edits;
    }

    // ---- Mellow: the eye in program/deferred1.fsh, where it puts its clouds
    // and fog - after both, so it shows through them as it does without
    // shaders, Distant Horizons' terrain included - and the lava in
    // its terrain and Distant Horizons'. Its Voxy terrain knows lava only along
    // with torches and the like, so it keeps its own.

    static void mellowEye(Path shaders, Eye eye, Map<Path, String> edits) throws IOException, Unsupported {
        Path deferred = shaders.resolve("program/deferred1.fsh");
        if (!isFile(deferred)) throw new Unsupported("Mellow's deferred1 has moved");
        String text = current(edits, deferred);
        String name = deferred.getFileName().toString();
        text = eyeGlobals(shaders, deferred, text, findOne(text, "void main\\(\\) \\{", name).start(), eye, true, false,
                "defined DIMENSION_OVERWORLD", edits);
        text = insert(text, "[ \\t]*#ifdef VOXY\\r?\\n[ \\t]*if\\(texture\\(depthtex1, texcoord\\)\\.r >= 1\\) \\{", name, ""
                + "    // MCME: the fire eye, after the clouds and the fog, so it shows through\n"
                + "    // them, as it does without shaders (MCME's mod)\n"
                + "    #ifdef DIMENSION_OVERWORLD\n"
                + "    Color.rgb = mcmeDrawFireEye(Color.rgb, ViewPos, Depth < 1.0 ? length(ViewPos) : 1.0e9, 1.0 / EXPOSURE);\n"
                + "    #endif\n\n", true);
        edits.put(deferred, text);
    }

    static void mellowLava(Path shaders, Map<Path, String> edits) throws IOException {
        Path program = shaders.resolve("program");
        lavaPart("Mellow's terrain", LavaWhere.TERRAIN, edits, e -> mellowTerrainLava(shaders, program.resolve("gbuffers_terrain.fsh"), e));
        Path dhVertex = program.resolve("dh_terrain.vsh"), dh = program.resolve("dh_terrain.fsh");
        if (isFile(dh) && isFile(dhVertex)) {
            lavaPart("Mellow's Distant Horizons terrain", LavaWhere.DISTANT_HORIZONS, edits, e -> mellowDhLava(shaders, dhVertex, dh, e));
        }
    }

    static final String MELLOW_LINEAR = "[ \\t]*Color\\.rgb = \\( Color\\.rgb \\* \\(Color\\.rgb \\* \\(Color\\.rgb \\* 0\\.305306011 \\+ 0\\.682171111\\) \\+ 0\\.012522878\\) \\);\\r?\\n";

    static void mellowTerrainLava(Path shaders, Path terrain, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, terrain);
        String name = terrain.getFileName().toString();
        Matcher main = findOne(text, "void main\\(\\) \\{\\r?\\n", name);
        text = includeLavaAt(shaders, terrain, text, main.start(), edits);
        text = insert(text, "void main\\(\\) \\{\\r?\\n", name, ""
                + "    " + LAVA_FRAME + "\n"
                + "    FluidFrame mcmeLavaHere = mcmeLavaFrame(view_player(ViewPos, false), texcoord);\n");
        text = insert(text, "[ \\t]*Color\\.rgb \\*= glcolor\\.rgb \\* glcolor\\.a;\\r?\\n", name, ""
                + "    " + LAVA_COLOUR + "\n"
                + "    Color.rgb = mcmeLava(gtexture, Texcoord, mcmeLavaHere, mcmeLavaTime(frameTimeCounter), Color.rgb);\n");
        edits.put(terrain, text);
    }

    /** Distant Horizons' LODs: still lava, its vertices telling it apart (material, which they leave 0 for LODs). */
    static void mellowDhLava(Path shaders, Path vertex, Path dh, Map<Path, String> edits) throws IOException, Unsupported {
        String text = current(edits, vertex);
        String name = vertex.getFileName().toString();
        text = insert(text, "[ \\t]*init_generic\\(\\);\\r?\\n", name, ""
                + "    if (dhMaterialId == DH_BLOCK_LAVA) material = float(DH_BLOCK_LAVA); // MCME: which is RP-Mordor's lava\n");
        edits.put(vertex, text);

        text = current(edits, dh);
        name = dh.getFileName().toString();
        Matcher main = findOne(text, "void main\\(\\) \\{\\r?\\n", name);
        text = includeLavaAt(shaders, dh, text, main.start(), edits);
        text = insert(text, "[ \\t]*vec3 PlayerPos = view_player\\(ViewPos, true\\);\\r?\\n", name, ""
                + "    " + LAVA_FRAME + "\n"
                + "    FluidFrame mcmeLavaHere = mcmeLavaFrame(PlayerPos, vec2(0.0));\n");
        text = insert(text, MELLOW_LINEAR, name, ""
                + "    " + LAVA_COLOUR + "\n"
                + "    if (material == float(DH_BLOCK_LAVA)) Color.rgb = lavaColor(LAVA_STILL, mcmeLavaHere, mcmeLavaTime(frameTimeCounter));\n", true);
        edits.put(dh, text);
    }
}

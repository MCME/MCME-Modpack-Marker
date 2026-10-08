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
 * Sildur's: the eye in deferred.fsh, and lava in its terrain.
 * One of ShaderPackPatcher's recipes; see there for how they work.
 */
final class SildursRecipe {
    private SildursRecipe() {
    }

    // ---- Sildur's: the eye in deferred.fsh, where it puts the sky and its
    // clouds - after those and the terrain's fog, so it shows through them as
    // it does without shaders, Distant Horizons' terrain included - by its eye
    // adaptation, which it applies there; and the lava in its terrain (its
    // gbuffers_textured: it has no gbuffers_terrain). Its Distant Horizons
    // and Voxy terrain don't tell lava apart.

    static void sildursEye(Path shaders, Eye eye, Map<Path, String> edits) throws IOException, Unsupported {
        Path deferred = shaders.resolve("deferred.fsh");
        if (!isFile(deferred)) throw new Unsupported("Sildur's deferred has moved");
        String text = current(edits, deferred);
        String name = deferred.getFileName().toString();
        if (!search("albedo\\.rgb = \\(albedo\\.rgb \\* pow\\(eyeAdapt,0\\.88\\)\\);", text)) throw new Unsupported("Sildur's deferred has changed");
        text = eyeGlobals(shaders, deferred, text, findOne(text, "void main\\(\\) \\{", name).start(), eye, true, true, "1", edits);
        text = insert(text, "[ \\t]*albedo\\.rgb = \\(albedo\\.rgb \\* pow\\(eyeAdapt,0\\.88\\)\\);\\r?\\n", name, ""
                + "\t// MCME: the fire eye, after the sky's clouds and the terrain's fog, so it\n"
                + "\t// shows through them, as it does without shaders (MCME's mod)\n"
                + "\t{\n"
                + "\t\tfloat mcmeDistance = mcmeLodDistance(texcoord.xy, ivec2(gl_FragCoord.xy),\n"
                + "\t\t                                     texture2D(depthtex0, texcoord.xy).x < 1.0 ? length(fragpos0) : 1.0e9);\n"
                + "\t\talbedo.rgb = mcmeDrawFireEye(albedo.rgb, fragpos0, mcmeDistance, 1.0 / pow(eyeAdapt, 0.88));\n"
                + "\t}\n\n", true);
        edits.put(deferred, text);
    }

    static void sildursLava(Path shaders, Map<Path, String> edits) throws IOException {
        Path textured = shaders.resolve("gbuffers_textured.fsh");
        lavaPart("Sildur's terrain", LavaWhere.TERRAIN, edits, e -> {
            String text = current(e, textured);
            String name = textured.getFileName().toString();
            if (!search("varying vec3 worldpos;", text)) throw new Unsupported(name + " has changed");
            Matcher main = findOne(text, "void main\\(\\) \\{\\r?\\n", name);
            text = includeLavaAt(shaders, textured, text, main.start(), e);
            text = declareUniforms(shaders, textured, text, text.indexOf(LAVA_MARK), new String[][]{{"vec3", "cameraPosition"}}, e);
            text = insert(text, "[ \\t]*vec4 albedo = texture2D\\(texture, texcoord\\.xy\\)\\*color;\\r?\\n", name, ""
                    + "\t" + LAVA_COLOUR + "\n"
                    + "\tFluidFrame mcmeLavaHere = mcmeLavaFrame(worldpos - cameraPosition, texcoord.xy);\n"
                    + "\talbedo.rgb = mcmeLava(texture, texcoord.xy, mcmeLavaHere, mcmeLavaTime(frameTimeCounter), albedo.rgb);\n");
            e.put(textured, text);
        });
    }

    /**
     * Sildur's Vibrant: faces dropped in gbuffers_water.vsh; the eye drawn at
     * the end of composite1 - after its fog, before TAA - in the linear colours
     * it works in there; final's tonemap is fixed (4.7). Its overworld programs
     * are its root's. Its bloom is taken earlier, in composite, so the eye
     * doesn't bloom. composite1 runs only with one of its effects on, so it is
     * switched on always.
     */
    static Map<Path, String> sildurs(Path shaders, Eye eye) throws IOException, Unsupported {
        Path comp = shaders.resolve("composite1.fsh"), fin = shaders.resolve("final.fsh");
        Path water = shaders.resolve("gbuffers_water.vsh"), particles = shaders.resolve("gbuffers_textured.vsh");
        Path props = shaders.resolve("shaders.properties");
        for (Path p : new Path[]{comp, fin, water, particles, props}) {
            if (!isFile(p)) {
                throw new Unsupported("not Sildur's");
            }
        }
        if (!read(fin).contains("Uncharted2Tonemap(albedo.rgb*4.7)") || !search("vec3 fragpos0 = utilScreenSpace\\(", read(comp))) {
            throw new Unsupported("Sildur's composite1 or final have changed");
        }
        Map<Path, String> edits = new LinkedHashMap<>();
        sildursEye(shaders, eye, edits);
        edits.put(water, dropEyeFaces(shaders, water, edits));
        // (its gbuffers_textured draws its terrain too)
        edits.put(particles, dropFarParticles(shaders, particles, edits, null, null, true));
        if (eye.lava) {
            sildursLava(shaders, edits);
        }
        return edits;
    }
}

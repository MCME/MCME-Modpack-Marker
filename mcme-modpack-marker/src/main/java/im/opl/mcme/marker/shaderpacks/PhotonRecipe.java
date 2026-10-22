package im.opl.mcme.marker.shaderpacks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;

import static im.opl.mcme.marker.shaderpacks.ShaderPackPatcher.*;

/**
 * Photon: the eye in program/c1_blend_layers.fsh, faded by the clouds Photon
 * draws in front of it, and lava in its Distant Horizons terrain. Its world
 * terrain's lava is left to MCME's edits for any pack
 * (im.opl.mcme.marker.iris).
 * One of ShaderPackPatcher's recipes; see there for how they work.
 */
final class PhotonRecipe {
    private PhotonRecipe() {
    }

    /**
     * Photon: faces dropped in program/gbuffers_all_translucent.vsh; the eye
     * drawn at the end of c1, after it blends the translucents, the clouds in
     * front of them and its fog, before its TAA, bloom and colour grading - in
     * its scene's light, linear Rec. 2020, by the exposure c4 keeps in
     * colortex5's first texel (which c14 multiplies the scene by).
     */
    static Map<Path, String> photon(Path shaders, Eye eye) throws IOException, Unsupported {
        Path program = shaders.resolve("program");
        Path blend = program.resolve("c1_blend_layers.fsh"), vsh = program.resolve("gbuffers_all_translucent.vsh");
        Path solid = program.resolve("gbuffers_all_solid.vsh"), grading = program.resolve("c14_color_grading.fsh");
        Path composite = shaders.resolve("world0/composite1.fsh");
        if (!(isFile(blend) && isFile(vsh) && isFile(composite) && read(composite).contains("/program/c1_blend_layers.fsh"))) {
            throw new Unsupported("not Photon");
        }
        String text = read(blend);
        String name = blend.getFileName().toString();
        if (!search("vec4 clouds_and_aurora =\\s*read_clouds_and_aurora\\(refracted_uv, clouds_apparent_distance\\);", text)
                || !search("vec3 back_position_view =", text) || !search("bool is_sky = ", text)
                || !search("layout\\(location = 0\\) out vec3 fragment_color;", text)
                || !search("uniform sampler2D colortex5;", text)) {
            throw new Unsupported("Photon's c1_blend_layers has changed");
        }
        // its exposure, where c14 reads it
        if (!isFile(grading) || !search("float exposure = texelFetch\\(colortex5, ivec2\\(0\\), 0\\)\\.a;", read(grading))) {
            throw new Unsupported("Photon's exposure has moved");
        }
        if (!isFile(solid)) {
            throw new Unsupported("Photon's particles have moved");
        }
        Map<Path, String> edits = new LinkedHashMap<>();
        Matcher main = findOne(text, "void main\\(\\) \\{", name);
        text = eyeGlobals(shaders, blend, text, main.start(), eye, true, false, "defined WORLD_OVERWORLD", edits);
        main = findOne(text, "void main\\(\\) \\{", name);
        int end = endOfBlock(text, text.indexOf('{', main.start())) - 1;
        String draw = "\n"
                + "    // MCME: the fire eye, after fog and clouds, so it shows at any distance,\n"
                + "    // Distant Horizons' too, and nothing cuts its glow off - faded by the\n"
                + "    // clouds in front of it, in linear sRGB (MCME's mod)\n"
                + "#if defined WORLD_OVERWORLD\n"
                + "    float mcmeEyeDistance = length(vec3(MCME_EYE_BLOCK - cameraPositionInt) + 0.5 - cameraPositionFract);\n"
                + "    mcmeFireVisibility = clouds_apparent_distance < mcmeEyeDistance ? clouds_and_aurora.w : 1.0;\n"
                + "    fragment_color = mcmeDrawFireEye(fragment_color * rec2020_to_rec709, back_position_view,\n"
                + "                                     is_sky ? 1.0e9 : length(back_position_view),\n"
                + "                                     1.0 / max(texelFetch(colortex5, ivec2(0), 0).a, 1.0e-4)) * rec709_to_rec2020;\n"
                + "#endif\n";
        text = text.substring(0, end) + draw.replace("\n", nl(text)) + text.substring(end);
        edits.put(blend, text);
        edits.put(vsh, dropEyeFaces(shaders, vsh, edits));
        edits.put(vsh, dropFarParticles(shaders, vsh, edits, null, "PROGRAM_GBUFFERS_PARTICLES_TRANSLUCENT"));
        edits.put(solid, dropFarParticles(shaders, solid, edits, null, "PROGRAM_GBUFFERS_PARTICLES"));
        Path dhVertex = program.resolve("dh_terrain.vsh"), dh = program.resolve("dh_terrain.fsh");
        if (eye.lava && isFile(dh) && isFile(dhVertex)) {
            lavaPart("Photon's Distant Horizons terrain", LavaWhere.DISTANT_HORIZONS, edits, e -> photonDhLava(shaders, dhVertex, dh, e));
        }
        return edits;
    }

    /**
     * Distant Horizons' LODs: still lava, as they don't say which way it
     * flows, by the material mask Photon's vertices give it (39), its colour
     * where Photon packs the block's into its gbuffer. Photon makes it glow
     * by that mask.
     */
    static void photonDhLava(Path shaders, Path vertex, Path dh, Map<Path, String> edits) throws IOException, Unsupported {
        if (!search("case DH_BLOCK_LAVA:\\s*material_mask = 39;", read(vertex))) {
            throw new Unsupported("Photon's dh_terrain.vsh has changed");
        }
        String text = current(edits, dh);
        String name = dh.getFileName().toString();
        if (!search("in vec3 scene_pos;", text) || !search("flat in uint material_mask;", text)) {
            throw new Unsupported(name + " has changed");
        }
        Matcher main = findOne(text, "void main\\(\\) \\{\\r?\\n", name);
        text = includeLavaAt(shaders, dh, text, main.start(), edits);
        text = insert(text, "void main\\(\\) \\{\\r?\\n", name, ""
                + "    " + LAVA_FRAME + "\n"
                + "    FluidFrame mcmeLavaHere = mcmeLavaFrame(scene_pos, vec2(0.0));\n");
        text = insert(text, "[ \\t]*gbuffer_data_0\\.x = pack_unorm_2x8\\(adjusted_color\\.rg\\);\\r?\\n", name, ""
                + "    " + LAVA_COLOUR + "\n"
                + "    if (material_mask == 39u) adjusted_color = clamp01(lavaColor(LAVA_STILL, mcmeLavaHere, mcmeLavaTime(frameTimeCounter)));\n\n", true);
        edits.put(dh, text);
    }
}

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

/**
 * Makes a copy of an Iris shader pack draw RP-Mordor's fire eye, and its lava
 * where a recipe knows the pack's terrain.
 *
 * Moved here from MCME's installer, which edited copies of the packs it
 * installed; the mod edits whichever supported pack Iris loads, into a cache
 * (PatchedShaderPacks), from the shaders the resource packs have.
 *
 * A port of patch_shaderpack.py (MCME/ResourcePackScripts, patchShaderpack/),
 * which says why and how: under a shader pack the resource pack's terrain
 * shaders don't run, so the eye is drawn over the pack's finished scene,
 * after fog and clouds and before bloom, at its block. Recipe for recipe it
 * writes the same files as the script, so a change to one belongs in both.
 *
 * Packs are recognised by their code, not their name: each recipe checks
 * everything it needs before changing anything, and refuses (Unsupported)
 * a pack that isn't its own or whose code has changed.
 *
 * The eye's includes (far_terrain.glsl, fire_eye_config.glsl, fire_eye.glsl)
 * and the lava's (fluid.glsl, lava_config.glsl, lava.glsl) are the resource
 * packs' assets/minecraft/shaders/include files; the eye's block is
 * fire_eye_config.glsl's FIRE_EYE_BLOCK.
 */
public final class ShaderPackPatcher {

    public static final String MARKER = "MCME-PATCH.txt";
    public static final String[] EYE_INCLUDES = {"far_terrain.glsl", "fire_eye_config.glsl", "fire_eye.glsl"};
    public static final String[] LAVA_INCLUDES = {"fluid.glsl", "lava_config.glsl", "lava.glsl"};

    // how bright the eye and its glow are, relative to the resource pack's look
    static final String BRIGHTNESS = "1.5";
    static final String GLOW = "1.0";

    private ShaderPackPatcher() {
    }

    /** Why a recipe can't patch a pack. */
    public static class Unsupported extends Exception {
        public Unsupported(String message) {
            super(message);
        }
    }

    /** No recipe could patch a pack: each one's reason, by recipe. */
    public static class NotSupported extends Exception {
        public final Map<String, String> refusals;

        NotSupported(Map<String, String> refusals) {
            super("not a supported shader pack");
            this.refusals = refusals;
        }
    }

    /**
     * What goes in: the eye's includes' text and the lava's, by name - the
     * lava's only if all of them are there - and the eye's block.
     */
    public static final class Eye {
        public final Map<String, String> includes;
        public final int x, y, z;
        public final boolean lava;

        public Eye(Map<String, String> includes, int x, int y, int z, boolean lava) {
            this.includes = includes;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lava = lava;
        }

        /**
         * The includes as include(name) gives them - null for one it hasn't -
         * or null if the eye's aren't all there.
         */
        public static Eye from(java.util.function.Function<String, String> include) throws IOException {
            Map<String, String> includes = new LinkedHashMap<>();
            for (String name : EYE_INCLUDES) {
                String text = include.apply(name);
                if (text == null) return null;
                includes.put(name, text);
            }
            boolean lava = true;
            Map<String, String> lavaIncludes = new LinkedHashMap<>();
            for (String name : LAVA_INCLUDES) {
                String text = include.apply(name);
                if (text == null) lava = false;
                else lavaIncludes.put(name, text);
            }
            if (lava) includes.putAll(lavaIncludes);
            Matcher m = re("#define FIRE_EYE_BLOCK ivec3\\((-?\\d+), *(-?\\d+), *(-?\\d+)\\)").matcher(includes.get("fire_eye_config.glsl"));
            if (!m.find()) {
                throw new IOException("fire_eye_config.glsl has no FIRE_EYE_BLOCK");
            }
            return new Eye(includes, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), lava);
        }
    }

    // ---------------------------------------------------------------- shaders
    // The script's FACE_GLSL, DRAW_GLSL, PASS_VSH, PASS_FSH and LOD_GLSL, in
    // /mcme/patch/.

    static String drawDefines(Eye eye, boolean linear) {
        return "#define MCME_EYE_BLOCK ivec3(" + eye.x + ", " + eye.y + ", " + eye.z + ")\n"
                + "#define MCME_LINEAR " + (linear ? 1 : 0) + "\n"
                + "#define MCME_BRIGHTNESS " + BRIGHTNESS + "\n"
                + "#define MCME_GLOW " + GLOW + "\n";
    }

    static final String[][] LOD_UNIFORMS = {{"sampler2D", "dhDepthTex0"}, {"mat4", "dhProjectionInverse"},
            {"sampler2D", "vxDepthTexOpaque"}, {"mat4", "vxProjInv"}};

    static List<String> names(String[][] uniforms) {
        List<String> names = new ArrayList<>();
        for (String[] u : uniforms) {
            names.add(u[1]);
        }
        return names;
    }

    // ---------------------------------------------------------------- editing

    /** Patterns as the script's: . and ^ go by \n alone, \s and \b by Unicode. */
    static Pattern re(String regex) {
        return Pattern.compile(regex, Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS);
    }

    static boolean search(String regex, String text) {
        return re(regex).matcher(text).find();
    }

    static String resource(String name) throws IOException {
        try (InputStream in = ShaderPackPatcher.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing resource " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            // as the script has them, whatever a checkout did to their line ends
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    static String read(Path path) throws IOException, Unsupported {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (CharacterCodingException e) {
            throw new Unsupported(path.getFileName() + " isn't UTF-8");
        }
    }

    static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isFile(Path path) {
        return Files.isRegularFile(path);
    }

    static String nl(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    /** Python's str.rstrip(). */
    static String rstrip(String text) {
        int end = text.length();
        while (end > 0 && isPythonSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    static boolean isPythonSpace(char c) {
        return (c >= '\t' && c <= '\r') || (c >= '\u001c' && c <= ' ') || c == '\u0085' || c == ' ' || c == ' '
                || (c >= ' ' && c <= ' ') || c == ' ' || c == ' ' || c == ' ' || c == ' '
                || c == '　';
    }

    static Matcher findOne(String text, String anchor, String what) throws Unsupported {
        Matcher m = re(anchor).matcher(text);
        Matcher first = null;
        int count = 0;
        while (m.find()) {
            if (count++ == 0) {
                first = re(anchor).matcher(text);
                first.find(m.start());
            }
        }
        if (count != 1) {
            throw new Unsupported("expected one '" + anchor + "' in " + what + ", found " + count);
        }
        return first;
    }

    static String insert(String text, String anchor, String what, String addition, boolean before) throws Unsupported {
        Matcher m = findOne(text, anchor, what);
        int i = before ? m.start() : m.end();
        return text.substring(0, i) + addition.replace("\n", nl(text)) + text.substring(i);
    }

    static String insert(String text, String anchor, String what, String addition) throws Unsupported {
        return insert(text, anchor, what, addition, false);
    }

    /** The index just past the brace closing the one at open, skipping comments. */
    static int endOfBlock(String text, int open) throws Unsupported {
        int depth = 0, i = open;
        while (i < text.length()) {
            if (text.startsWith("//", i)) {
                i = text.indexOf('\n', i);
                i = i < 0 ? text.length() : i;
            } else if (text.startsWith("/*", i)) {
                i = text.indexOf("*/", i) + 2;
                if (i < 2) {
                    break;
                }
            } else {
                if (text.charAt(i) == '{') {
                    depth++;
                } else if (text.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
        }
        throw new Unsupported("unbalanced braces");
    }

    static final Pattern INCLUDE = re("#include\\s+\"([^\"]+)\"");

    /** Where an #include of name points (Iris style: /-rooted at shaders, or relative to here); null if nowhere. */
    static Path included(Path shaders, Path here, String name) {
        try {
            return name.startsWith("/") ? shaders.resolve(name.replaceFirst("^/+", "")) : here.resolve(name);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** text with its #includes expanded, every branch kept - for finding declarations. */
    static String expand(Path shaders, String text, Path here, Set<Path> seen) throws IOException, Unsupported {
        Matcher m = INCLUDE.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            last = m.end();
            Path path = included(shaders, here, m.group(1));
            if (path == null) {
                continue;
            }
            path = path.toAbsolutePath().normalize();
            if (seen.contains(path) || !isFile(path)) {
                continue;
            }
            seen.add(path);
            out.append(expand(shaders, read(path), path.getParent(), seen));
        }
        return out.append(text.substring(last)).toString();
    }

    static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /** text with its comments blanked out, everything else where it was. */
    static String blankComments(String text) {
        Matcher m = COMMENT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            for (int i = m.start(); i < m.end(); i++) {
                out.append(text.charAt(i) == '\n' ? '\n' : ' ');
            }
            last = m.end();
        }
        return out.append(text.substring(last)).toString();
    }

    /** Where the lines holding text's own declarations of uniform name end. */
    static List<Integer> declarationEnds(String text, String name) {
        List<Integer> ends = new ArrayList<>();
        Matcher m = re("\\buniform\\b[^;{}]*\\b" + name + "\\b[^;{}]*;").matcher(blankComments(text));
        while (m.find()) {
            int nl = text.indexOf('\n', m.end());
            ends.add(nl < 0 ? text.length() : nl + 1);
        }
        return ends;
    }

    static boolean declares(String text, String name) {
        return !declarationEnds(text, name).isEmpty();
    }

    /** The files text includes, directly or not, each once. */
    static List<Path> includes(Path shaders, String text, Path here) throws IOException, Unsupported {
        List<Path> found = new ArrayList<>();
        walk(shaders, text, here, found);
        return found;
    }

    private static void walk(Path shaders, String text, Path here, List<Path> found) throws IOException, Unsupported {
        Matcher m = INCLUDE.matcher(blankComments(text));
        while (m.find()) {
            Path path = included(shaders, here, m.group(1));
            if (path == null) {
                continue;
            }
            path = path.normalize();
            if (!found.contains(path) && isFile(path)) {
                found.add(path);
                walk(shaders, read(path), path.getParent(), found);
            }
        }
    }

    static String current(Map<Path, String> edits, Path path) throws IOException, Unsupported {
        return edits.containsKey(path) ? edits.get(path) : read(path);
    }

    /**
     * path's text, with #define MCME_DECLARED_name after each of the shader
     * pack's own declarations of the uniforms named that come before at - in
     * text, or in what it includes, whose new text goes into edits - so what is
     * added at at declares each only where the pack's declaration isn't
     * compiled: shader packs declare many uniforms only under some settings.
     * Unsupported if one is declared after at (-1: the end).
     */
    static String markDeclarations(Path shaders, Path path, String text, int at, List<String> names, Map<Path, String> edits)
            throws IOException, Unsupported {
        at = at < 0 ? text.length() : at;
        String before = text.substring(0, at), after = text.substring(at);
        for (String name : names) {
            boolean later = declares(after, name);
            if (!later) {
                for (Path f : includes(shaders, after, path.getParent())) {
                    if (declares(current(edits, f), name)) {
                        later = true;
                        break;
                    }
                }
            }
            if (later) {
                throw new Unsupported(path.getFileName() + " declares " + name + " after where it is needed");
            }
        }
        for (String name : names) {
            for (Path f : includes(shaders, before, path.getParent())) {
                if (declares(current(edits, f), name)) {
                    edits.put(f, marked(current(edits, f), name));
                }
            }
            before = marked(before, name);
        }
        return before + after;
    }

    private static String marked(String text, String name) {
        String define = "#define MCME_DECLARED_" + name + nl(text);
        List<Integer> ends = declarationEnds(text, name);
        Collections.reverse(ends);
        for (int end : ends) {
            if (!text.startsWith(define, end)) {
                text = text.substring(0, end) + define + text.substring(end);
            }
        }
        return text;
    }

    /**
     * path's text with the uniforms - {type, name} - declared at at, each only
     * where the shader pack's own declaration of it isn't compiled (see
     * markDeclarations).
     */
    static String declareUniforms(Path shaders, Path path, String text, int at, String[][] uniforms, Map<Path, String> edits)
            throws IOException, Unsupported {
        String marked = markDeclarations(shaders, path, text, at, names(uniforms), edits);
        at += marked.length() - text.length();
        return marked.substring(0, at) + uniformLines(uniforms).replace("\n", nl(text)) + marked.substring(at);
    }

    static String uniformLines(String[][] uniforms) {
        StringBuilder lines = new StringBuilder();
        for (String[] u : uniforms) {
            lines.append("#ifndef MCME_DECLARED_").append(u[1]).append("\nuniform ").append(u[0]).append(' ').append(u[1])
                    .append(";\n#endif\n");
        }
        return lines.toString();
    }

    /**
     * path's text, its vertex main() - the first after startMarker - renamed
     * name, and wrapper after it: the new main(), calling it, with the uniforms
     * it declares, named names, marked (markDeclarations).
     */
    static String wrapVertexMain(Path shaders, Path path, String name, String wrapper, Map<Path, String> edits,
                                 List<String> names, String startMarker) throws IOException, Unsupported {
        String text = current(edits, path);
        int start = startMarker != null ? text.indexOf(startMarker) : 0;
        Matcher m = re("void\\s+main\\s*\\(\\s*\\)").matcher(text);
        if (start < 0 || !m.find(start)) {
            throw new Unsupported("no vertex main() in " + path.getFileName());
        }
        int brace = text.indexOf('{', m.end());
        if (brace < 0) {
            throw new Unsupported("no vertex main() in " + path.getFileName());
        }
        int end = endOfBlock(text, brace);
        String renamed = text.substring(0, m.start()) + "void " + name + "()" + text.substring(m.end(), end);
        String whole = renamed + text.substring(end);
        String marked = markDeclarations(shaders, path, whole, renamed.length(), names, edits);
        int at = renamed.length() + marked.length() - whole.length();
        return marked.substring(0, at) + wrapper.replace("\n", nl(text)) + marked.substring(at);
    }

    /** path's text, its vertex main() wrapped so the eye block's faces collapse to a point. */
    static String dropEyeFaces(Path shaders, Path path, Map<Path, String> edits, String startMarker)
            throws IOException, Unsupported {
        return wrapVertexMain(shaders, path, "mcmePackMain",
                "\n\n// MCME: the fire eye block's faces are dropped - the eye is drawn over the scene\n"
                        + "#include \"/lib/mcme/fire_eye_face.glsl\"\n"
                        + "void main() {\n"
                        + "    mcmePackMain();\n"
                        + "    if (mcmeFireEyeFace((gl_TextureMatrix[0] * gl_MultiTexCoord0).xy)) gl_Position = vec4(0.0);\n"
                        + "}\n", edits, Collections.singletonList("gtexture"), startMarker);
    }

    static String dropEyeFaces(Path shaders, Path path, Map<Path, String> edits) throws IOException, Unsupported {
        return dropEyeFaces(shaders, path, edits, null);
    }

    /**
     * path's text, its vertex main() - particles' - wrapped so that, while a
     * distant terrain mod draws the world, particles past the server's view
     * distance collapse to a point: they come from chunks loaded but not
     * drawn, and would show through the mod's terrain in front of them. Only
     * in programs defining guard, if given.
     */
    static String dropFarParticles(Path shaders, Path path, Map<Path, String> edits, String startMarker, String guard)
            throws IOException, Unsupported {
        return dropFarParticles(shaders, path, edits, startMarker, guard, false);
    }

    /**
     * The same; if shared, the program draws more than particles - Sildur's
     * gbuffers_textured draws its terrain too, which it has no program of its
     * own for - so it drops them only while Iris says it draws particles.
     */
    static String dropFarParticles(Path shaders, Path path, Map<Path, String> edits, String startMarker, String guard, boolean shared)
            throws IOException, Unsupported {
        String test = (guard != null ? "    #ifdef " + guard + "\n" : "")
                + "    if (" + (shared ? "renderStage == MC_RENDER_STAGE_PARTICLES && " : "")
                + "length((gl_ModelViewMatrix * gl_Vertex).xyz) > SERVER_VIEW_DISTANCE) gl_Position = vec4(0.0);\n"
                + (guard != null ? "    #endif\n" : "");
        return wrapVertexMain(shaders, path, "mcmeParticleMain",
                "\n\n// MCME: with a distant terrain mod, particles past the server's view distance\n"
                        + "// are dropped - they would show through its terrain (lib/mcme/far_terrain.glsl)\n"
                        + "#include \"/lib/mcme/far_terrain.glsl\"\n"
                        + (shared ? "#ifndef MCME_DECLARED_renderStage\nuniform int renderStage;\n#endif\n" : "")
                        + "void main() {\n"
                        + "    mcmeParticleMain();\n"
                        + "#if defined DISTANT_HORIZONS || defined VOXY\n" + test + "#endif\n"
                        + "}\n", edits, shared ? Collections.singletonList("renderStage") : Collections.<String>emptyList(), startMarker);
    }

    static String dropFarParticles(Path shaders, Path path, Map<Path, String> edits) throws IOException, Unsupported {
        return dropFarParticles(shaders, path, edits, null, null);
    }

    /** The files of an overworld compositeN, in folder, drawing the eye over colortex buffer. */
    static Map<Path, String> newPass(Path shaders, int number, int buffer, String scale, boolean linear, Eye eye,
                                     String settings, String folder) throws IOException, Unsupported {
        Path world = shaders.resolve(folder).normalize();
        for (String ext : new String[]{"vsh", "fsh"}) {
            if (Files.exists(world.resolve("composite" + number + "." + ext))) {
                throw new Unsupported("world0/composite" + number + "." + ext + " is taken");
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("settings", settings);
        values.put("buffer", Integer.toString(buffer));
        values.put("lod", resource("/mcme/patch/lod.glsl"));
        values.put("defines", drawDefines(eye, linear));
        values.put("scale", scale);
        Matcher m = Pattern.compile("\\{(settings|buffer|lod|defines|scale)\\}").matcher(resource("/mcme/patch/pass.fsh"));
        StringBuffer fsh = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(fsh, Matcher.quoteReplacement(values.get(m.group(1))));
        }
        m.appendTail(fsh);
        Map<Path, String> files = new LinkedHashMap<>();
        files.put(world.resolve("composite" + number + ".vsh"), resource("/mcme/patch/pass.vsh"));
        files.put(world.resolve("composite" + number + ".fsh"), fsh.toString());
        return files;
    }

    static Map<Path, String> newPass(Path shaders, int number, int buffer, String scale, boolean linear, Eye eye)
            throws IOException, Unsupported {
        return newPass(shaders, number, buffer, scale, linear, eye, "", "world0");
    }

    // ---------------------------------------------------------------- recipes
    // Each checks everything it needs before changing anything, and returns
    // the files it changes; Unsupported if the pack isn't its.

    interface Recipe {
        Map<Path, String> apply(Path shaders, Eye eye) throws IOException, Unsupported;
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
        text = insert(text, "void main\\(\\) \\{", name, "// MCME: the fire eye, at its block (patch_shaderpack.py)\n"
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
    static final String[][] LAVA_UNIFORMS = {{"ivec3", "cameraPositionInt"}, {"vec3", "cameraPositionFract"}, {"float", "frameTimeCounter"}};
    static final String LAVA_MARK = "// MCME: RP-Mordor's lava (MCME's mod)";

    /** The lava's include, and the uniforms it needs, before path's main(). */
    static String includeLava(Path shaders, Path path, String text, Map<Path, String> edits) throws IOException, Unsupported {
        String name = path.getFileName().toString();
        text = insert(text, "void main\\(\\) \\{", name, LAVA_MARK + "\n#include \"/lib/mcme/lava_pack.glsl\"\n\n", true);
        return declareUniforms(shaders, path, text, text.indexOf(LAVA_MARK), LAVA_UNIFORMS, edits);
    }

    /** The lava's include, and the uniforms it needs, at at in path's text. */
    static String includeLavaAt(Path shaders, Path path, String text, int at, Map<Path, String> edits) throws IOException, Unsupported {
        text = text.substring(0, at) + (LAVA_MARK + "\n#include \"/lib/mcme/lava_pack.glsl\"\n\n").replace("\n", nl(text)) + text.substring(at);
        return declareUniforms(shaders, path, text, text.indexOf(LAVA_MARK), LAVA_UNIFORMS, edits);
    }

    static final String[][] LAVA_SCREEN_UNIFORMS = {{"ivec3", "cameraPositionInt"}, {"vec3", "cameraPositionFract"},
            {"float", "frameTimeCounter"}, {"float", "viewWidth"}, {"float", "viewHeight"}, {"mat4", "gbufferModelViewInverse"}};

    /** The same, with lava_pack.glsl's mcmeScreenRel(), for a program with no position at the top of main(). */
    static String includeLavaScreen(Path shaders, Path path, String text, int at, Map<Path, String> edits) throws IOException, Unsupported {
        text = text.substring(0, at) + (LAVA_MARK + "\n#define MCME_SCREEN_REL\n#include \"/lib/mcme/lava_pack.glsl\"\n\n").replace("\n", nl(text))
                + text.substring(at);
        return declareUniforms(shaders, path, text, text.indexOf(LAVA_MARK), LAVA_SCREEN_UNIFORMS, edits);
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

    /** text's fragment shader - BSL keeps both in one file - edited by edit. */
    interface FragmentEdit {
        String apply(String fragment) throws IOException, Unsupported;
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
     * The block IDs a pack's block.properties gives lava - some packs give
     * still and flowing lava, or its levels, IDs of their own - as a test of
     * the GLSL expression id, such as "(mat == 10068 || mat == 10070)"; null
     * if it gives lava none. What its terrain programs know lava by, in
     * mc_Entity - and so Voxy, in customId.
     */
    static String lavaTest(Path shaders, String id) throws IOException, Unsupported {
        Path props = shaders.resolve("block.properties");
        if (!isFile(props)) return null;
        String text = read(props).replaceAll("\\\\[ \\t]*\\r?\\n", " ");
        Matcher m = re("(?m)^[ \\t]*block\\.(\\d+)[ \\t]*=(.*)$").matcher(text);
        List<String> tests = new ArrayList<>();
        while (m.find()) {
            for (String token : m.group(2).trim().split("\\s+")) {
                String block = token.replaceFirst("^minecraft:", "").split(":")[0];
                if (block.equals("lava") || block.equals("flowing_lava")) {
                    String test = id + " == " + m.group(1);
                    if (!tests.contains(test)) tests.add(test);
                    break;
                }
            }
        }
        return tests.isEmpty() ? null : "(" + String.join(" || ", tests) + ")";
    }

    /**
     * A Voxy program's JSON with the uniforms Voxy hands it (its "uniforms"
     * list, which it declares itself) including names.
     */
    static String voxyUniforms(String json, String what, String... names) throws Unsupported {
        Matcher list = re("\"uniforms\"\\s*:\\s*\\[").matcher(json);
        if (!list.find()) throw new Unsupported(what + " lists no uniforms");
        StringBuilder add = new StringBuilder();
        for (String name : names) {
            if (!json.contains("\"" + name + "\"")) add.append(nl(json)).append("        \"").append(name).append("\",");
        }
        return json.substring(0, list.end()) + add + json.substring(list.end());
    }

    static final String LAVA_COLOUR = "// MCME: RP-Mordor's lava, its colour from lava.glsl (MCME's mod)";
    static final String LAVA_GLOW_MARK = "// MCME: RP-Mordor's lava glows LAVA_GLOW times as much (MCME's mod)";
    static final String LAVA_FRAME = "// MCME: where RP-Mordor's lava is, taken before anything branches";

    /**
     * Complementary's lava (Reimagined, Unbound and their edits, such as
     * Spooklementary): its colour from lava.glsl where the pack samples the
     * block's, before its material code - which makes lava glow by how bright
     * it is - and that glow LAVA_GLOW times as much; in program/
     * gbuffers_terrain.glsl, dh_terrain.glsl (Distant Horizons' LODs: still,
     * as they don't say which way it flows) and voxy_opaque.glsl (Voxy's: the
     * same, and without derivatives, which Voxy's fragments haven't).
     */
    /** Part of a pack's lava: some programs' edits, which go in whole or not at all. */
    interface LavaPart {
        void apply(Map<Path, String> edits) throws IOException, Unsupported;
    }

    /** Where a recipe puts the lava: the world's terrain, and the distant terrain mods'. */
    public enum LavaWhere { TERRAIN, DISTANT_HORIZONS, VOXY }

    /** Where the recipe being tried put the lava, and where it left it out, and why: patch() reports them. */
    static final Set<LavaWhere> LAVA_DONE = EnumSet.noneOf(LavaWhere.class);
    static final List<String> LAVA_LEFT_OUT = new ArrayList<>();

    /**
     * part's edits, putting the lava where, added to edits if all of them
     * can be made; else none - the eye, and the rest of the lava, still go in
     * - and why in LAVA_LEFT_OUT.
     */
    static void lavaPart(String what, LavaWhere where, Map<Path, String> edits, LavaPart part) throws IOException {
        Map<Path, String> trial = new LinkedHashMap<>(edits);
        try {
            part.apply(trial);
            edits.putAll(trial);
            LAVA_DONE.add(where);
        } catch (Unsupported e) {
            LAVA_LEFT_OUT.add(what + ": " + e.getMessage());
        }
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
                + "// MCME: the clouds between the camera and the fire eye (patch_shaderpack.py)\n"
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
        text = declareUniforms(shaders, comp2, text, text.indexOf("// MCME: the clouds between the camera and the fire eye (patch"),
                new String[][]{{"ivec3", "cameraPositionInt"}, {"vec3", "cameraPositionFract"}, {"mat4", "gbufferModelView"}}, edits);
        text = insert(text, anchor, name, measure);
        Path props = shaders.resolve("shaders.properties");
        String nl = nl(read(props));
        edits.put(comp2, text);
        edits.put(props, rstrip(current(edits, props)) + nl + nl
                + "# MCME: the clouds between the camera and the fire eye (patch_shaderpack.py)" + nl
                + "image.mcmeEyeCloud = mcmeEyeCloudSampler RED R16F HALF_FLOAT false false 1 1" + nl);
        return true;
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

    /**
     * Complementary - Reimagined, Unbound and edits such as Spooklementary:
     * faces dropped in program/gbuffers_water.glsl's vertex shader; the eye
     * drawn in a pass of its own, composite2 - after composite1's refraction,
     * reflections, volumetric light and fog, before composite3's blur and the
     * bloom - over colortex0, its linear scene.
     */
    static final String[][] EYE_UNIFORMS = {{"ivec3", "cameraPositionInt"}, {"vec3", "cameraPositionFract"}, {"float", "far"},
            {"mat4", "gbufferModelViewInverse"}, {"mat4", "gbufferProjectionInverse"}, {"float", "frameTimeCounter"}};

    /**
     * text, path's, with the eye's globals - lod.glsl if lod, the defines and
     * fire_eye_draw.glsl - before mainAt, under #if guard, the uniforms they
     * need declared where the pack's own aren't compiled.
     */
    static String eyeGlobals(Path shaders, Path path, String text, int mainAt, Eye eye, boolean linear, boolean lod, String guard,
                             Map<Path, String> edits) throws IOException, Unsupported {
        if (lod) {
            // (the pack's own declarations of what lod.glsl needs, marked before it goes in)
            String marked = markDeclarations(shaders, path, text, mainAt, names(LOD_UNIFORMS), edits);
            mainAt += marked.length() - text.length();
            text = marked;
        }
        String globals = "// MCME: the fire eye, at its block (MCME's mod)\n"
                + "#if " + guard + "\n"
                + "MCME_UNIFORMS"
                + (lod ? resource("/mcme/patch/lod.glsl") + "\n" : "")
                + drawDefines(eye, linear) + "#include \"/lib/mcme/fire_eye_draw.glsl\"\n"
                + "#endif\n\n";
        text = text.substring(0, mainAt) + globals.replace("\n", nl(text)) + text.substring(mainAt);
        int at = text.indexOf("MCME_UNIFORMS");
        text = text.substring(0, at) + text.substring(at + "MCME_UNIFORMS".length());
        return declareUniforms(shaders, path, text, at, EYE_UNIFORMS, edits);
    }

    /** text's part from the first of marker on - the fragment shader of a file holding both - edited by edit; whole text if marker is null. */
    static String section(String text, String marker, String until, String name, FragmentEdit edit) throws IOException, Unsupported {
        int start = marker == null ? 0 : text.indexOf(marker);
        int end = until == null ? text.length() : text.indexOf(until, Math.max(start, 0));
        if (start < 0 || end < 0) throw new Unsupported(name + " has changed");
        return text.substring(0, start) + edit.apply(text.substring(start, end)) + text.substring(end);
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

    /** The recipes, in the order they are tried, by name. */
    static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();

    static {
        RECIPES.put("Bliss", ShaderPackPatcher::bliss);
        RECIPES.put("MakeUp", ShaderPackPatcher::makeup);
        RECIPES.put("Mellow", ShaderPackPatcher::mellow);
        RECIPES.put("BSL", ShaderPackPatcher::bsl);
        RECIPES.put("Complementary", ShaderPackPatcher::complementary);
        RECIPES.put("Solas", ShaderPackPatcher::solas);
        RECIPES.put("Sildur's", ShaderPackPatcher::sildurs);
    }

    // ---------------------------------------------------------------- main

    /**
     * Patches the shader pack whose shaders/ folder is in root, in place: adds
     * lib/mcme/ and applies the first recipe that takes it. What it did;
     * NotSupported, with each recipe's refusal, if none does - root is then
     * left with lib/mcme/ in it.
     */
    public static Patched patch(Path root, Eye eye) throws IOException, NotSupported {
        Path shaders = root.resolve("shaders").toAbsolutePath().normalize();
        Path lib = shaders.resolve("lib/mcme");
        Files.createDirectories(lib);
        for (Map.Entry<String, String> include : eye.includes.entrySet()) {
            write(lib.resolve(include.getKey()), include.getValue());
        }
        write(lib.resolve("fire_eye_face.glsl"), resource("/mcme/patch/fire_eye_face.glsl"));
        write(lib.resolve("fire_eye_draw.glsl"), resource("/mcme/patch/fire_eye_draw.glsl"));
        if (eye.lava) {
            write(lib.resolve("lava_pack.glsl"), resource("/mcme/patch/lava_pack.glsl"));
        }

        Map<String, String> refusals = new LinkedHashMap<>();
        for (Map.Entry<String, Recipe> recipe : RECIPES.entrySet()) {
            Map<Path, String> edits;
            LAVA_DONE.clear();
            LAVA_LEFT_OUT.clear();
            try {
                edits = recipe.getValue().apply(shaders, eye);
            } catch (Unsupported e) {
                refusals.put(recipe.getKey(), e.getMessage());
                continue;
            }
            for (Map.Entry<Path, String> edit : edits.entrySet()) {
                write(edit.getKey(), edit.getValue());
            }
            return new Patched(recipe.getKey(), EnumSet.copyOf(LAVA_DONE),
                    new ArrayList<>(LAVA_LEFT_OUT));
        }
        throw new NotSupported(refusals);
    }

    /** What patch() did: the recipe that took the pack, where it put the lava, and where it couldn't, and why. */
    public record Patched(String recipe, Set<LavaWhere> lava, List<String> lavaLeftOut) {
        public String describe() {
            String text = recipe + " recipe" + (lava.isEmpty() ? "" : ", lava in " + lava);
            return lavaLeftOut.isEmpty() ? text : text + " (lava left out of " + String.join("; ", lavaLeftOut) + ")";
        }
    }

    /**
     * For trying it out of the game: patches a shader pack folder (holding
     * shaders/) in place, its includes from the folders given, first first.
     *
     *     java -cp mcme-modpack-marker.jar im.opl.mcme.marker.shaderpacks.ShaderPackPatcher folder includeDir...
     */
    public static void main(String[] args) throws Exception {
        Eye eye = Eye.from(name -> {
            for (int i = 1; i < args.length; i++) {
                Path p = Paths.get(args[i], name);
                if (Files.isRegularFile(p)) {
                    try {
                        return Files.readString(p);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }
            return null;
        });
        if (eye == null) {
            System.out.println("the include folders lack the fire eye's includes");
            System.exit(1);
        }
        try {
            System.out.println("patched " + args[0] + ": " + patch(Paths.get(args[0]), eye).describe());
        } catch (NotSupported e) {
            System.out.println("not a supported shader pack:");
            for (Map.Entry<String, String> refusal : e.refusals.entrySet()) {
                System.out.println("  " + refusal.getKey() + ": " + refusal.getValue());
            }
            System.exit(1);
        }
    }
}

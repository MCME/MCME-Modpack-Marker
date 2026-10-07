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
 * It began as a port of patch_shaderpack.py, a script it has replaced. Under
 * a shader pack the resource pack's terrain shaders don't run, so the eye is
 * drawn over the pack's finished scene, after fog and clouds and before
 * bloom, at its block.
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
            // the Lite zip: the eye only, no lava
            if (isLite(include)) lava = false;
            if (lava) includes.putAll(lavaIncludes);
            Matcher m = re("#define FIRE_EYE_BLOCK ivec3\\((-?\\d+), *(-?\\d+), *(-?\\d+)\\)").matcher(includes.get("fire_eye_config.glsl"));
            if (!m.find()) {
                throw new IOException("fire_eye_config.glsl has no FIRE_EYE_BLOCK");
            }
            return new Eye(includes, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), lava);
        }
    }

    /**
     * Whether the resource packs are a Lite zip - their mcme_lite.glsl defines
     * MCME_LITE (ResourcePackScripts' shader base) - which draws the eye only:
     * no lava, water or tar of the mod's either.
     */
    public static boolean isLite(java.util.function.Function<String, String> include) {
        String lite = include.apply("mcme_lite.glsl");
        return lite != null && re("(?m)^[ \\t]*#define[ \\t]+MCME_LITE\\b").matcher(lite).find();
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

    /** text's fragment shader - BSL keeps both in one file - edited by edit. */
    interface FragmentEdit {
        String apply(String fragment) throws IOException, Unsupported;
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

    /** The recipes, in the order they are tried, by name. */
    static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();

    static {
        RECIPES.put("Bliss", BlissRecipe::bliss);
        RECIPES.put("MakeUp", MakeUpRecipe::makeup);
        RECIPES.put("Mellow", MellowRecipe::mellow);
        RECIPES.put("BSL", BslRecipe::bsl);
        RECIPES.put("Complementary", ComplementaryRecipe::complementary);
        RECIPES.put("Solas", SolasRecipe::solas);
        RECIPES.put("Sildur's", SildursRecipe::sildurs);
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

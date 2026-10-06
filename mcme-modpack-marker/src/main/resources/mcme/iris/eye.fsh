#version 330 core
// MCME: RP-Mordor's fire eye, drawn by MCME's mod over whatever a shader
// pack made of the scene, after its last pass - as the game draws it without
// one, its colours the resource pack's own - hidden by whatever the depth
// says is in front of it (EyeOverlay). Its glow is light laid over what is
// behind it.

// the game's depth and Distant Horizons': reversed, as Iris keeps them
uniform sampler2D mcmeSceneDepth;
uniform sampler2D mcmeLodDepth;
uniform int mcmeHasLod;
uniform mat4 mcmeProjectionInverse;
uniform mat4 mcmeLodProjectionInverse;
uniform mat4 mcmeModelViewInverse;
uniform vec3 mcmeEyeCentre;     // the eye block's centre, from the camera
uniform float mcmeTime;         // seconds into the day, as the game's GameTime * 1200
uniform float mcmeFar;          // the render distance, in blocks
uniform vec2 mcmeViewSize;

out vec4 mcmeColor;

{includes}
#define FIRE_NO_GLOW
int fireLayer = 0;
vec3 fireCentre = vec3(0.0);
float fireTime = 0.0;
vec3 fireRay = vec3(0.0, 0.0, -1.0);
#define Pos fireRay
{fire_eye}
#undef Pos

// How far off what a depth buffer holds at pixel is: huge for the sky.
float mcmeDistance(sampler2D depth, mat4 projectionInverse, ivec2 pixel) {
    float z = 1.0 - texelFetch(depth, pixel, 0).r;
    if (z >= 1.0) return 1.0e9;
    vec2 coord = (vec2(pixel) + 0.5) / mcmeViewSize;
    vec4 p = projectionInverse * vec4(vec3(coord, z) * 2.0 - 1.0, 1.0);
    return length(p.xyz / p.w);
}

// How much of the eye shows here (x), and of its glow (y): averaged over this
// pixel and the eight round it. A shader pack with temporal anti-aliasing
// moves the scene by part of a pixel each frame and blends the frames; drawn
// after that, by one pixel's depth alone, the eye's edges against the
// terrain - and its glow over it - would come and go from frame to frame.
vec2 mcmeShown(float fireDistance) {
    vec2 shown = vec2(0.0);
    ivec2 here = ivec2(gl_FragCoord.xy);
    ivec2 last = ivec2(mcmeViewSize) - 1;
    for (int i = 0; i < 9; i++) {
        ivec2 pixel = clamp(here + ivec2(i % 3 - 1, i / 3 - 1), ivec2(0), last);
        float d = mcmeDistance(mcmeSceneDepth, mcmeProjectionInverse, pixel);
        if (mcmeHasLod != 0) d = min(d, mcmeDistance(mcmeLodDepth, mcmeLodProjectionInverse, pixel));
        shown += vec2(smoothstep(fireDistance - FIRE_RADIUS * 1.2, fireDistance - FIRE_RADIUS * 0.7, d),
                      smoothstep(fireDistance - FIRE_RADIUS, fireDistance + FIRE_RADIUS * FIRE_GLOW_DEPTH, d));
    }
    return shown / 9.0;
}

void main() {
    vec2 coord = gl_FragCoord.xy / mcmeViewSize;
    // relative to where the camera truly is: the game puts its view bobbing
    // into the projection, moving it off the view's origin as the player
    // walks - the point the projection draws towards, which it maps to w = 0.
    // Without it the eye wiggles against the world
    vec4 eyePoint = mcmeProjectionInverse * vec4(0.0, 0.0, 1.0, 0.0);
    vec3 viewOrigin = abs(eyePoint.w) > 1.0e-6 ? eyePoint.xyz / eyePoint.w : vec3(0.0);
    vec4 onRay = mcmeProjectionInverse * vec4(coord * 2.0 - 1.0, 0.0, 1.0);
    vec3 origin = mat3(mcmeModelViewInverse) * viewOrigin + mcmeModelViewInverse[3].xyz;
    fireCentre = mcmeEyeCentre - origin;
    fireTime = mcmeTime;
    fireRay = normalize(mat3(mcmeModelViewInverse) * (onRay.xyz / onRay.w - viewOrigin));

    float fireDistance = length(fireCentre);
    // without a distant terrain mod nothing is drawn where the eye's block
    // can't be, so neither is the eye: it fades out over the last chunk of the
    // render distance - a sphere - and of the server's view distance across
    float range = 1.0;
    if (mcmeHasLod == 0) {
        range = (1.0 - smoothstep(mcmeFar - 16.0, mcmeFar, fireDistance))
              * (1.0 - smoothstep(FIRE_HANDOVER - 16.0, FIRE_HANDOVER, length(fireCentre.xz)));
    }
    if (range <= 0.0) discard;

    // the eye, where the ray passes near enough to meet it, hidden by
    // whatever is nearer than its front
    vec3 from = -fireCentre / FIRE_RADIUS;
    float pass = length(from + fireRay * max(dot(-from, fireRay), 0.0));
    bool eyePart = pass < max(FIRE_EYE_WIDTH, FIRE_CORONA) * 1.05;
    vec4 eye = eyePart ? fireColor() : vec4(0.0);
    // its glow: none in front of the ball, all of it FIRE_GLOW_DEPTH radii behind
    vec3 glow = fireGlowLight(fireRay, fireCentre);
    // (where neither shows, the depth isn't looked at)
    if (eye.a <= 0.0 && max(glow.r, max(glow.g, glow.b)) < 0.5 / 255.0) discard;
    vec2 shown = mcmeShown(fireDistance) * range;
    eye.a *= shown.x;
    glow *= shown.y;
    // (premultiplied: blended as one, one minus source alpha)
    mcmeColor = vec4(eye.rgb * eye.a + glow, eye.a);
}

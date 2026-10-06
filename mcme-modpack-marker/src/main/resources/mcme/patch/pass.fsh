#version 330 compatibility
// MCME: the fire eye, drawn over the finished scene, before bloom
// (patch_shaderpack.py)
{settings}
uniform sampler2D colortex{buffer};
uniform sampler2D depthtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform ivec3 cameraPositionInt;
uniform vec3 cameraPositionFract;
uniform float frameTimeCounter;
uniform float far;

{lod}
{defines}
#include "/lib/mcme/fire_eye_draw.glsl"

in vec2 mcmeTexCoord;

/* RENDERTARGETS: {buffer} */
layout(location = 0) out vec4 mcmeColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 scene = texelFetch(colortex{buffer}, pixel, 0);
    float depth = texelFetch(depthtex0, pixel, 0).r;
    vec4 viewPos = gbufferProjectionInverse * vec4(vec3(mcmeTexCoord, depth) * 2.0 - 1.0, 1.0);
    viewPos /= viewPos.w;
    float sceneDistance = mcmeLodDistance(mcmeTexCoord, pixel, depth < 1.0 ? length(viewPos.xyz) : 1.0e9);
    mcmeColor = vec4(mcmeDrawFireEye(scene.rgb, viewPos.xyz, sceneDistance, {scale}), scene.a);
}

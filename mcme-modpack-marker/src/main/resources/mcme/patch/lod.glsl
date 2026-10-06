// MCME: the distant terrain mods' terrain (patch_shaderpack.py)
#if defined DISTANT_HORIZONS
#ifndef MCME_DECLARED_dhDepthTex0
uniform sampler2D dhDepthTex0;
#endif
#ifndef MCME_DECLARED_dhProjectionInverse
uniform mat4 dhProjectionInverse;
#endif
#elif defined VOXY
#ifndef MCME_DECLARED_vxDepthTexOpaque
uniform sampler2D vxDepthTexOpaque;
#endif
#ifndef MCME_DECLARED_vxProjInv
uniform mat4 vxProjInv;
#endif
#endif

float mcmeLodDistance(vec2 coord, ivec2 pixel, float sceneDistance) {
#if defined DISTANT_HORIZONS || defined VOXY
    #ifdef DISTANT_HORIZONS
        float lodDepth = texelFetch(dhDepthTex0, pixel, 0).r;
        mat4 lodProjectionInverse = dhProjectionInverse;
    #else
        float lodDepth = texelFetch(vxDepthTexOpaque, pixel, 0).r;
        mat4 lodProjectionInverse = vxProjInv;
    #endif
    if (lodDepth < 1.0) {
        vec4 lodPos = lodProjectionInverse * vec4(vec3(coord, lodDepth) * 2.0 - 1.0, 1.0);
        sceneDistance = min(sceneDistance, length(lodPos.xyz / lodPos.w));
    }
#endif
    return sceneDistance;
}

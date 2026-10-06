// MCME: whether a vertex is one of the fire eye block's faces (patch_shaderpack.py)
#ifndef MCME_DECLARED_gtexture
uniform sampler2D gtexture;
#endif

bool mcmeFireEyeFace(vec2 uv) {
    ivec2 at = ivec2(uv * vec2(textureSize(gtexture, 0)));
    ivec4 pointer = ivec4(texelFetch(gtexture, at, 0) * 255.0 + 0.5);
    if (pointer.a != 254) return false;
    ivec2 origin = at - ivec2(pointer.r * 16 + (pointer.g >> 4), (pointer.g & 15) * 256 + pointer.b);
    return all(greaterThanEqual(origin, ivec2(0)))
        && ivec4(texelFetch(gtexture, origin, 0) * 255.0 + 0.5) == ivec4(98, 76, 54, 255)
        && ivec4(texelFetch(gtexture, origin + ivec2(1, 0), 0) * 255.0 + 0.5) == ivec4(13, 57, 93, 255);
}

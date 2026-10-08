// MCME: the fire eye block's faces are dropped from a shader pack's terrain
// - MCME's mod draws the eye over the finished scene (IrisTerrain,
// EyeOverlay). Appended to Iris's finished vertex shader for Sodium's
// terrain; {atlas} is its block atlas.

// Whether atlas's texel at uv is one of the eye's textures: each of their
// texels points to the texture's first two, which hold a fixed code.
bool mcmeFireEyeFace(sampler2D atlas, vec2 uv) {
    ivec2 at = ivec2(uv * vec2(textureSize(atlas, 0)));
    ivec4 pointer = ivec4(texelFetch(atlas, at, 0) * 255.0 + 0.5);
    if (pointer.a != 254) return false;
    ivec2 origin = at - ivec2(pointer.r * 16 + (pointer.g >> 4), (pointer.g & 15) * 256 + pointer.b);
    return all(greaterThanEqual(origin, ivec2(0)))
        && ivec4(texelFetch(atlas, origin, 0) * 255.0 + 0.5) == ivec4(98, 76, 54, 255)
        && ivec4(texelFetch(atlas, origin + ivec2(1, 0), 0) * 255.0 + 0.5) == ivec4(13, 57, 93, 255);
}

void main() {
    mcmePackMain();
    // the corner's texture coordinate, a quarter texel in, as Sodium shrinks
    // it towards the sprite's middle - else a corner reads the next sprite
    vec2 mcmeUv = _vert_tex_diffuse_coord + _vert_tex_diffuse_coord_bias * (0.25 / vec2(textureSize({atlas}, 0)));
    if (mcmeFireEyeFace({atlas}, mcmeUv)) gl_Position = vec4(0.0);
}

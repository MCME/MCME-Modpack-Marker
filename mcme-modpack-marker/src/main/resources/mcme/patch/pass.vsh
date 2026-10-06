#version 330 compatibility
// MCME: the fire eye, drawn over the finished scene (patch_shaderpack.py)
out vec2 mcmeTexCoord;

void main() {
    gl_Position = ftransform();
    mcmeTexCoord = gl_MultiTexCoord0.xy;
}

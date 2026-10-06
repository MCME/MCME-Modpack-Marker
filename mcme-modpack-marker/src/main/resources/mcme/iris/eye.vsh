#version 330 core
// MCME: one triangle over the whole screen, for eye.fsh (EyeOverlay)

void main() {
    vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}

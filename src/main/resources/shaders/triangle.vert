#version 450

// M1: a hardcoded RGB triangle selected by gl_VertexIndex (no vertex buffer yet — that is M2).
// Vulkan NDC: y points DOWN, clip depth is [0,1].

layout(location = 0) out vec3 fragColor;

vec2 positions[3] = vec2[](
    vec2( 0.0, -0.6),   // upper-center
    vec2( 0.6,  0.6),   // lower-right
    vec2(-0.6,  0.6)    // lower-left
);

vec3 colors[3] = vec3[](
    vec3(1.0, 0.0, 0.0),
    vec3(0.0, 1.0, 0.0),
    vec3(0.0, 0.0, 1.0)
);

void main() {
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    fragColor = colors[gl_VertexIndex];
}

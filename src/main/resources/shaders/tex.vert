#version 450

// M5: screen-aligned textured quad (text / marks / image sprites). Positions are already in NDC;
// no MVP — sprites are placed in screen space by the CPU (the DrawerVisitor projects the anchor).

layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUV;

layout(location = 0) out vec2 fragUV;

void main() {
    gl_Position = vec4(inPos, 0.0, 1.0);
    fragUV = inUV;
}

package com.jlmoya.gpu;

/**
 * Minimal column-major 4x4 float matrices for **Vulkan** clip space (y points DOWN, depth in [0,1]).
 * Column-major storage: element (row r, col c) is at index {@code c*4 + r} — the layout GLSL `mat4`
 * push constants / UBOs expect.
 */
public final class Mat4 {

    private Mat4() { }

    public static float[] identity() {
        return new float[] {1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1};
    }

    /** {@code a * b}, both column-major. */
    public static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    /** Right-handed perspective for Vulkan clip space (y-flipped, z in [0,1]). fovY in radians. */
    public static float[] perspective(float fovY, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovY / 2.0));
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = -f;                                  // Vulkan y-down
        m[10] = far / (near - far);
        m[11] = -1f;
        m[14] = -(far * near) / (far - near);
        return m;
    }

    /** Right-handed look-at (view matrix). */
    public static float[] lookAt(float[] eye, float[] center, float[] up) {
        float[] f = norm(sub(center, eye));
        float[] s = norm(cross(f, up));
        float[] u = cross(s, f);
        return new float[] {
            s[0], u[0], -f[0], 0,
            s[1], u[1], -f[1], 0,
            s[2], u[2], -f[2], 0,
            -dot(s, eye), -dot(u, eye), dot(f, eye), 1
        };
    }

    public static float[] rotateY(float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[] {c, 0, -s, 0,  0, 1, 0, 0,  s, 0, c, 0,  0, 0, 0, 1};
    }

    public static float[] rotateX(float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[] {1, 0, 0, 0,  0, c, s, 0,  0, -s, c, 0,  0, 0, 0, 1};
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] norm(float[] v) {
        float l = (float) Math.sqrt(dot(v, v));
        return new float[] {v[0] / l, v[1] / l, v[2] / l};
    }
}

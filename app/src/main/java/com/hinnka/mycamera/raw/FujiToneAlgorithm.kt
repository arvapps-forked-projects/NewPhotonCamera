package com.hinnka.mycamera.raw

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Firmware-derived interpolation; remaining ISP assumptions are documented in docs/fuji-filmsimulation.md. */
internal object FujiToneShader {
    val DEFINITION = RawEngineToneShaderDefinition(
        engineUniforms = """
            uniform highp sampler2D uFujiTables;
            uniform highp sampler3D uFujiCube;
            uniform float uFujiCurveEdges[11];
            uniform mat3 uFujiMatrix;
            uniform mat3 uFujiColorMatrix;
            uniform vec3 uFujiLuminance;
        """.trimIndent(),
        engineFunctions = """
            float fujiBezier(int row, int segment, float t) {
                int p = segment * 3;
                float a = texelFetch(uFujiTables, ivec2(p, row), 0).r;
                float b = texelFetch(uFujiTables, ivec2(p + 1, row), 0).r;
                float c = texelFetch(uFujiTables, ivec2(p + 2, row), 0).r;
                float d = texelFetch(uFujiTables, ivec2(p + 3, row), 0).r;
                float s = 1.0 - t;
                return a*s*s*s + 3.0*b*t*s*s + 3.0*c*t*t*s + d*t*t*t;
            }

            float fujiToneOutput(float luminance) {
                // Fixed geometry inferred from firmware DR relations and independent
                // histogram response tables. No scene-fitted knot/exponent controls.
                const float edges[14] = float[14](0.0, 16.0, 32.0, 64.0, 128.0,
                    256.0, 512.0, 1024.0, 2048.0, 4096.0, 6144.0, 8192.0, 12288.0, 16384.0);
                float x = clamp(luminance * 16384.0, 0.0, 16384.0);
                int k = 0;
                for (int j = 1; j < 13; ++j) { if (x >= edges[j]) k = j; }
                float t = (x - edges[k]) / (edges[k + 1] - edges[k]);
                return (x / 16384.0) * fujiBezier(0, k, t);
            }

            float fujiCurve(float value, int channel) {
                float x = clamp(value * 16384.0, 0.0, 16384.0);
                int k = 0;
                for (int j = 1; j < 10; ++j) { if (x >= uFujiCurveEdges[j]) k = j; }
                float t = (x - uFujiCurveEdges[k]) / (uFujiCurveEdges[k + 1] - uFujiCurveEdges[k]);
                return fujiBezier(channel + 1, k, t);
            }

            vec3 fujiCube(vec3 value) {
                // Original nodes are 0,256,...,3840,4095; the final interval is 255.
                // Strip the 18th padding plane on upload and never interpolate through it.
                vec3 code = clamp(value, 0.0, 1.0) * 4095.0;
                ivec3 p = min(ivec3(floor(code / 256.0)), ivec3(15));
                vec3 span = mix(vec3(256.0), vec3(255.0), step(vec3(15.0), vec3(p)));
                vec3 f = (code - vec3(p) * 256.0) / span;
                vec3 c00 = mix(texelFetch(uFujiCube, p, 0).rgb,
                               texelFetch(uFujiCube, p + ivec3(1,0,0), 0).rgb, f.r);
                vec3 c10 = mix(texelFetch(uFujiCube, p + ivec3(0,1,0), 0).rgb,
                               texelFetch(uFujiCube, p + ivec3(1,1,0), 0).rgb, f.r);
                vec3 c01 = mix(texelFetch(uFujiCube, p + ivec3(0,0,1), 0).rgb,
                               texelFetch(uFujiCube, p + ivec3(1,0,1), 0).rgb, f.r);
                vec3 c11 = mix(texelFetch(uFujiCube, p + ivec3(0,1,1), 0).rgb,
                               texelFetch(uFujiCube, p + ivec3(1,1,1), 0).rgb, f.r);
                return mix(mix(c00, c10, f.g), mix(c01, c11, f.g), f.b);
            }

            vec3 applyEngineTone(vec3 color) {
                // WB, camera calibration and user exposure belong to the shared input.
                float luminance = dot(color, uFujiLuminance);
                float gain = luminance > 0.0 ? fujiToneOutput(luminance) / luminance : 1.0;
                vec3 mixed = uFujiMatrix * (color * gain);
                vec3 encoded = vec3(fujiCurve(mixed.r, 0), fujiCurve(mixed.g, 1), fujiCurve(mixed.b, 2));
                encoded = fujiCube(uFujiColorMatrix * encoded);
                // Return linear sRGB for shared edits and exactly one output encoding.
                return mix(encoded / 12.92, pow((encoded + 0.055) / 1.055, vec3(2.4)),
                           step(vec3(0.04045), encoded));
            }
        """.trimIndent(),
    )
}

internal class FujiToneAlgorithm(quad: RawFullscreenQuad) :
    RawRenderingEngineToneAlgorithm(quad, FujiToneShader.DEFINITION) {
    private val textures = IntArray(2)
    private var uploadedPlan: FujiRenderPlan? = null

    override fun bindEngineResources(program: Int, input: RawEngineTonePass.Input) {
        super.bindEngineResources(program, input)
        val plan = requireNotNull(input.fujiRenderPlan) { "Fuji requires a FilmSimulation render plan" }
        ensureTextures(plan)
        for (index in textures.indices) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + FIRST_TEXTURE_UNIT + index)
            GLES30.glBindTexture(if (index == 0) GLES30.GL_TEXTURE_2D else GLES30.GL_TEXTURE_3D, textures[index])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,
                if (index == 0) "uFujiTables" else "uFujiCube"), FIRST_TEXTURE_UNIT + index)
        }
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(program, "uFujiCurveEdges[0]"), 11, plan.curveEdges, 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, "uFujiMatrix"), 1, false, plan.matrix, 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, "uFujiColorMatrix"), 1, false, plan.colorMatrix, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uFujiLuminance"), 1, plan.luminance, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        RawGlesProgram.logErrors("FujiToneAlgorithm.bindEngineResources")
    }

    override fun releaseEngineResources() {
        GLES30.glDeleteTextures(textures.size, textures, 0)
        textures.fill(0)
        uploadedPlan = null
    }

    private fun ensureTextures(plan: FujiRenderPlan) {
        if (uploadedPlan === plan && textures.all { it != 0 }) return
        releaseEngineResources()
        val limit = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_IMAGE_UNITS, limit, 0)
        check(limit[0] > FIRST_TEXTURE_UNIT + 1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limit, 0)
        check(limit[0] >= 40)
        GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, limit, 0)
        check(limit[0] >= 17)
        val unpack = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_UNPACK_ALIGNMENT, unpack, 0)
        try {
            GLES30.glGenTextures(textures.size, textures, 0)
            check(textures.all { it != 0 }) { "Unable to allocate Fuji textures" }
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            for (index in textures.indices) {
                val target = if (index == 0) GLES30.GL_TEXTURE_2D else GLES30.GL_TEXTURE_3D
                val values = if (index == 0) plan.tables else plan.cube
                val data = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                data.put(values).position(0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + FIRST_TEXTURE_UNIT + index)
                GLES30.glBindTexture(target, textures[index])
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                if (index == 0) {
                    GLES30.glTexImage2D(target, 0, GLES30.GL_R32F, 40, 4, 0, GLES30.GL_RED, GLES30.GL_FLOAT, data)
                } else {
                    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexImage3D(target, 0, GLES30.GL_RGB32F, 17, 17, 17, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, data)
                }
                val error = GLES30.glGetError()
                check(error == GLES30.GL_NO_ERROR) { "Fuji texture upload failed: index=$index error=$error" }
            }
            uploadedPlan = plan
        } catch (error: Throwable) {
            releaseEngineResources()
            throw error
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, unpack[0])
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
    }

    private companion object {
        // Shared HDR uses units 2/4 and PGTM uses 7. GLES 3 guarantees 16 fragment units.
        const val FIRST_TEXTURE_UNIT = 10
    }
}

package com.example.chess

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An indexed mesh stored in GPU buffers. Each vertex is interleaved as
 * position(3) + normal(3) = 6 floats. Indices are unsigned shorts.
 */
class Mesh(interleaved: FloatArray, indices: ShortArray) {

    private val vbo: Int
    private val ibo: Int
    private val count: Int = indices.size

    init {
        val vb: FloatBuffer = ByteBuffer
            .allocateDirect(interleaved.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(interleaved)
        vb.position(0)

        val ib: ShortBuffer = ByteBuffer
            .allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices)
        ib.position(0)

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, interleaved.size * 4, vb, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun draw(aPos: Int, aNormal: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, 0)
        if (aNormal >= 0) {
            GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, 12)
        }

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }
}

/** Accumulates geometry, then computes smooth/flat normals and uploads a [Mesh]. */
class MeshBuilder {
    private val pos = ArrayList<Float>()
    private val tris = ArrayList<Int>()

    fun vertex(x: Float, y: Float, z: Float): Int {
        val idx = pos.size / 3
        pos.add(x); pos.add(y); pos.add(z)
        return idx
    }

    fun triangle(a: Int, b: Int, c: Int) {
        tris.add(a); tris.add(b); tris.add(c)
    }

    /** A flat quad p1->p2->p3->p4 (counter-clockwise seen from the front). */
    fun quad(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float
    ) {
        val a = vertex(x1, y1, z1)
        val b = vertex(x2, y2, z2)
        val c = vertex(x3, y3, z3)
        val d = vertex(x4, y4, z4)
        triangle(a, b, c)
        triangle(a, c, d)
    }

    /** Axis-aligned box with flat-shaded faces. */
    fun box(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) {
        // top (+y) and bottom (-y)
        quad(minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ)
        quad(minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ)
        // front (+z) and back (-z)
        quad(minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ)
        quad(maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ)
        // right (+x) and left (-x)
        quad(maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ)
        quad(minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ)
    }

    /**
     * Surface of revolution. [profile] is a flat list r0,y0,r1,y1,... from the
     * bottom up; it is revolved around the Y axis to make a 3D solid.
     */
    fun lathe(profile: FloatArray, segments: Int) {
        val rings = profile.size / 2
        val base = pos.size / 3
        for (i in 0 until rings) {
            val r = profile[i * 2]
            val y = profile[i * 2 + 1]
            for (j in 0 until segments) {
                val a = (2.0 * Math.PI * j / segments).toFloat()
                pos.add(r * cos(a)); pos.add(y); pos.add(r * sin(a))
            }
        }
        for (i in 0 until rings - 1) {
            for (j in 0 until segments) {
                val jn = (j + 1) % segments
                val v00 = base + i * segments + j
                val v01 = base + i * segments + jn
                val v10 = base + (i + 1) * segments + j
                val v11 = base + (i + 1) * segments + jn
                triangle(v00, v10, v11)
                triangle(v00, v11, v01)
            }
        }
    }

    /**
     * Extrude a closed 2D side-profile (in the Y-Z plane) along the X axis to
     * make a solid slab of total width 2*[halfWidth], centered on x=0. [profile]
     * is a flat list z0,y0,z1,y1,... naming the polygon vertices in order. Used
     * to sculpt the knight's horse head, which a surface of revolution can't make.
     */
    fun extrude(profile: FloatArray, halfWidth: Float) {
        val n = profile.size / 2
        if (n < 3) return
        // Signed area in the z-y plane tells us the polygon's winding, so the
        // generated faces get outward normals no matter how the caller listed it.
        var area = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += profile[i * 2] * profile[j * 2 + 1] - profile[j * 2] * profile[i * 2 + 1]
        }
        val ccw = area > 0f

        val rightBase = pos.size / 3
        for (i in 0 until n) vertex(halfWidth, profile[i * 2 + 1], profile[i * 2])
        val leftBase = pos.size / 3
        for (i in 0 until n) vertex(-halfWidth, profile[i * 2 + 1], profile[i * 2])

        // Side fans: +x face must end up with a +x normal, -x face with -x.
        for (i in 1 until n - 1) {
            if (ccw) {
                triangle(rightBase, rightBase + i + 1, rightBase + i)
                triangle(leftBase, leftBase + i, leftBase + i + 1)
            } else {
                triangle(rightBase, rightBase + i, rightBase + i + 1)
                triangle(leftBase, leftBase + i + 1, leftBase + i)
            }
        }
        // Perimeter walls connecting the two side loops, normals facing outward.
        for (i in 0 until n) {
            val j = (i + 1) % n
            val r0 = rightBase + i; val r1 = rightBase + j
            val l0 = leftBase + i;  val l1 = leftBase + j
            if (ccw) {
                triangle(r0, r1, l1); triangle(r0, l1, l0)
            } else {
                triangle(r0, l1, r1); triangle(r0, l0, l1)
            }
        }
    }

    fun build(): Mesh {
        val nv = pos.size / 3
        val nrm = FloatArray(nv * 3)
        var t = 0
        while (t < tris.size) {
            val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
            val ax = pos[a * 3]; val ay = pos[a * 3 + 1]; val az = pos[a * 3 + 2]
            val bx = pos[b * 3]; val by = pos[b * 3 + 1]; val bz = pos[b * 3 + 2]
            val cx = pos[c * 3]; val cy = pos[c * 3 + 1]; val cz = pos[c * 3 + 2]
            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            for (v in intArrayOf(a, b, c)) {
                nrm[v * 3] += nx; nrm[v * 3 + 1] += ny; nrm[v * 3 + 2] += nz
            }
            t += 3
        }

        val interleaved = FloatArray(nv * 6)
        for (v in 0 until nv) {
            var nx = nrm[v * 3]; var ny = nrm[v * 3 + 1]; var nz = nrm[v * 3 + 2]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-6f) { nx /= len; ny /= len; nz /= len } else { ny = 1f }
            interleaved[v * 6] = pos[v * 3]
            interleaved[v * 6 + 1] = pos[v * 3 + 1]
            interleaved[v * 6 + 2] = pos[v * 3 + 2]
            interleaved[v * 6 + 3] = nx
            interleaved[v * 6 + 4] = ny
            interleaved[v * 6 + 5] = nz
        }

        val idx = ShortArray(tris.size) { tris[it].toShort() }
        return Mesh(interleaved, idx)
    }
}

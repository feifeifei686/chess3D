package com.example.chess

/**
 * Geometry for the 3D scene: chess pieces (as surfaces of revolution),
 * the board, and the table. All in world units; one board square = 1 unit.
 */
object ChessModels {

    const val SEGMENTS = 36

    // Board playing area spans x,z in [-4, 4]; square (col,row) center is
    // at x = col - 3.5, z = row - 3.5. Board top surface is y = 0.
    const val HALF = 4f
    const val BORDER = 0.55f
    const val BASE_BOTTOM = -0.55f

    fun squareCenterX(col: Int): Float = col - 3.5f
    fun squareCenterZ(row: Int): Float = row - 3.5f

    /** Approximate height of each piece, used for shadow sizing and camera framing. */
    fun pieceHeight(type: PieceType): Float = when (type) {
        PieceType.PAWN -> 0.80f
        PieceType.ROOK -> 0.86f
        PieceType.KNIGHT -> 1.06f
        PieceType.BISHOP -> 1.22f
        PieceType.QUEEN -> 1.28f
        PieceType.KING -> 1.45f
    }

    private val PAWN = floatArrayOf(
        0.00f, 0.00f, 0.34f, 0.00f, 0.34f, 0.06f, 0.21f, 0.12f,
        0.15f, 0.20f, 0.14f, 0.30f, 0.22f, 0.36f, 0.13f, 0.42f,
        0.17f, 0.50f, 0.20f, 0.58f, 0.18f, 0.66f, 0.10f, 0.74f,
        0.00f, 0.80f
    )

    private val ROOK = floatArrayOf(
        0.00f, 0.00f, 0.40f, 0.00f, 0.40f, 0.07f, 0.26f, 0.13f,
        0.22f, 0.18f, 0.21f, 0.52f, 0.25f, 0.58f, 0.31f, 0.62f,
        0.31f, 0.70f, 0.33f, 0.74f, 0.33f, 0.86f, 0.22f, 0.86f,
        0.22f, 0.78f, 0.00f, 0.78f
    )

    // The knight is NOT a surface of revolution — it's a turned pedestal with a
    // sculpted horse head extruded on top, so it actually reads as a horse.
    private val KNIGHT_BASE = floatArrayOf(
        0.00f, 0.00f, 0.40f, 0.00f, 0.40f, 0.07f, 0.26f, 0.13f,
        0.22f, 0.18f, 0.21f, 0.34f, 0.27f, 0.40f, 0.22f, 0.44f,
        0.00f, 0.45f
    )

    // Side silhouette of the horse head in the z-y plane, muzzle pointing toward
    // -z. Extruded along x to give it thickness. Walks the outline back-of-neck →
    // mane → poll → brow → nose → muzzle → jaw → throat → front-of-neck.
    private val KNIGHT_HEAD = floatArrayOf(
        0.30f, 0.38f,   // back-bottom of neck
        0.34f, 0.66f,   // back of neck
        0.26f, 0.86f,   // crest / mane
        0.14f, 0.94f,   // poll (behind ears)
        0.02f, 0.95f,   // brow
        -0.08f, 0.90f,  // forehead
        -0.22f, 0.80f,  // nose bridge
        -0.34f, 0.70f,  // nose
        -0.40f, 0.62f,  // muzzle tip
        -0.38f, 0.54f,  // upper lip
        -0.28f, 0.54f,  // under muzzle
        -0.16f, 0.58f,  // jaw
        -0.06f, 0.52f,  // throat (cheek)
        0.06f, 0.44f,   // throat lower
        0.16f, 0.38f    // front-bottom of neck
    )

    // Tall mitre with a sharp peak — reads clearly from overhead vs the pawn's
    // round head. The collar ring stays prominent, giving the classic bishop
    // silhouette: base → narrow stem → wide collar → neck → flared mitre → point.
    private val BISHOP = floatArrayOf(
        0.00f, 0.00f, 0.38f, 0.00f, 0.38f, 0.07f, 0.24f, 0.13f,
        0.16f, 0.24f, 0.14f, 0.44f, 0.13f, 0.54f, 0.23f, 0.60f,
        0.13f, 0.64f, 0.15f, 0.74f, 0.22f, 0.86f, 0.18f, 0.98f,
        0.10f, 1.10f, 0.04f, 1.18f, 0.00f, 1.22f
    )

    private val QUEEN = floatArrayOf(
        0.00f, 0.00f, 0.42f, 0.00f, 0.42f, 0.07f, 0.27f, 0.14f,
        0.17f, 0.28f, 0.15f, 0.56f, 0.14f, 0.66f, 0.30f, 0.76f,
        0.34f, 0.84f, 0.22f, 0.90f, 0.30f, 0.96f, 0.18f, 1.02f,
        0.20f, 1.10f, 0.12f, 1.18f, 0.15f, 1.24f, 0.07f, 1.28f,
        0.00f, 1.26f
    )

    private val KING = floatArrayOf(
        0.00f, 0.00f, 0.44f, 0.00f, 0.44f, 0.07f, 0.28f, 0.14f,
        0.18f, 0.30f, 0.16f, 0.60f, 0.15f, 0.70f, 0.32f, 0.80f,
        0.35f, 0.88f, 0.22f, 0.94f, 0.30f, 1.00f, 0.18f, 1.06f,
        0.18f, 1.16f, 0.13f, 1.20f, 0.00f, 1.18f
    )

    fun buildPiece(type: PieceType): Mesh {
        val b = MeshBuilder()
        when (type) {
            PieceType.PAWN -> b.lathe(PAWN, SEGMENTS)
            PieceType.ROOK -> b.lathe(ROOK, SEGMENTS)
            PieceType.KNIGHT -> {
                b.lathe(KNIGHT_BASE, SEGMENTS)
                b.extrude(KNIGHT_HEAD, 0.15f)
                // Two upright ears flanking the poll.
                b.box(0.035f, 0.92f, 0.11f, 0.105f, 1.06f, 0.20f)
                b.box(-0.105f, 0.92f, 0.11f, -0.035f, 1.06f, 0.20f)
            }
            PieceType.BISHOP -> b.lathe(BISHOP, SEGMENTS)
            PieceType.QUEEN -> b.lathe(QUEEN, SEGMENTS)
            PieceType.KING -> {
                b.lathe(KING, SEGMENTS)
                // A cross on top of the crown.
                b.box(-0.05f, 1.16f, -0.05f, 0.05f, 1.45f, 0.05f)
                b.box(-0.15f, 1.26f, -0.05f, 0.15f, 1.36f, 0.05f)
            }
        }
        return b.build()
    }

    /**
     * A stylized hand reaching down to grip a piece: a forearm rising up out of
     * frame, a palm, three curled fingers on the far (-z) side and an opposed
     * thumb on the near (+z) side. The grip point is the origin, so the renderer
     * can park it right on top of the piece it's carrying. Built from axis-aligned
     * boxes; each finger is two segments to fake a curl.
     */
    fun buildHand(): Mesh {
        val b = MeshBuilder()
        // Forearm and palm.
        b.box(-0.10f, 0.38f, -0.09f, 0.10f, 1.45f, 0.09f)
        b.box(-0.18f, 0.16f, -0.16f, 0.18f, 0.40f, 0.16f)
        // Three fingers on the -z side, each an upper segment plus an inward-curled tip.
        for (xc in floatArrayOf(-0.12f, 0.0f, 0.12f)) {
            b.box(xc - 0.035f, 0.02f, -0.18f, xc + 0.035f, 0.20f, -0.12f)
            b.box(xc - 0.035f, -0.06f, -0.13f, xc + 0.035f, 0.05f, -0.07f)
        }
        // Opposed thumb on the +z side.
        b.box(-0.06f, 0.04f, 0.12f, 0.06f, 0.20f, 0.18f)
        b.box(-0.06f, -0.04f, 0.06f, 0.06f, 0.07f, 0.12f)
        return b.build()
    }

    /** Wooden base box plus a raised frame around the playing surface. */
    fun buildBoardBase(): Mesh {
        val b = MeshBuilder()
        val outer = HALF + BORDER
        // Solid base slab.
        b.box(-outer, BASE_BOTTOM, -outer, outer, -0.05f, outer)
        // Raised frame (four bars) around the squares, slightly above the tiles.
        val top = 0.06f
        b.box(-outer, -0.05f, -outer, outer, top, -HALF)          // far (-z)
        b.box(-outer, -0.05f, HALF, outer, top, outer)            // near (+z)
        b.box(-outer, -0.05f, -HALF, -HALF, top, HALF)            // left (-x)
        b.box(HALF, -0.05f, -HALF, outer, top, HALF)              // right (+x)
        return b.build()
    }

    /** All 32 squares of one colour, merged into a single mesh. */
    fun buildTiles(light: Boolean): Mesh {
        val b = MeshBuilder()
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val isLight = (row + col) % 2 == 0
                if (isLight != light) continue
                val x0 = col - 4f
                val z0 = row - 4f
                // Top face only (y = 0), facing up.
                b.quad(
                    x0, 0f, z0 + 1f,
                    x0 + 1f, 0f, z0 + 1f,
                    x0 + 1f, 0f, z0,
                    x0, 0f, z0
                )
            }
        }
        return b.build()
    }

    /** Large flat table the board sits on. */
    fun buildTable(): Mesh {
        val b = MeshBuilder()
        val s = 40f
        val y = BASE_BOTTOM
        b.quad(-s, y, s, s, y, s, s, y, -s, -s, y, -s)
        return b.build()
    }

    /** Unit quad in the XZ plane spanning [-1,1], used for shadows/highlights. */
    fun buildUnitQuad(): Mesh {
        val b = MeshBuilder()
        b.quad(-1f, 0f, 1f, 1f, 0f, 1f, 1f, 0f, -1f, -1f, 0f, -1f)
        return b.build()
    }
}

package com.example.chess

/**
 * Tapered (PeSTO-style) static evaluation for chess positions.
 *
 * Uses separate opening/middlegame (MG) and endgame (EG) piece-square tables
 * that are blended according to remaining material.  Also evaluates king safety,
 * pawn structure, mobility, and other positional features.
 *
 * Pure functions of a [ChessGame] snapshot — no mutable state.
 */
object Evaluation {

    // ===================================================================
    //  Phase
    // ===================================================================

    /** Maximum phase (all non-pawn/king pieces present): 4×1 + 4×1 + 4×2 + 2×4 = 24 */
    private const val MAX_PHASE = 24

    /** Map piece type to its phase contribution. */
    fun phaseWeight(t: PieceType): Int = when (t) {
        PieceType.PAWN   -> 0
        PieceType.KNIGHT -> 1
        PieceType.BISHOP -> 1
        PieceType.ROOK   -> 2
        PieceType.QUEEN  -> 4
        PieceType.KING   -> 0
    }

    /** Total phase of the board (both sides). */
    fun computePhase(board: Array<Array<Piece?>>): Int {
        var phase = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            phase += phaseWeight(board[r][c]?.type ?: continue)
        }
        return minOf(phase, MAX_PHASE)
    }

    // ===================================================================
    //  Material values
    // ===================================================================

    private const val PAWN_VAL   = 100
    private const val KNIGHT_VAL = 320
    private const val BISHOP_VAL = 330
    private const val ROOK_VAL   = 500
    private const val QUEEN_VAL  = 900
    const val KING_VAL           = 20_000   // only for capture ordering, not eval

    fun materialValue(t: PieceType): Int = when (t) {
        PieceType.PAWN   -> PAWN_VAL
        PieceType.KNIGHT -> KNIGHT_VAL
        PieceType.BISHOP -> BISHOP_VAL
        PieceType.ROOK   -> ROOK_VAL
        PieceType.QUEEN  -> QUEEN_VAL
        PieceType.KING   -> KING_VAL
    }

    // ===================================================================
    //  Main evaluation entry point — score from g.turn's perspective
    // ===================================================================

    /** Full evaluation including mobility (for win-rate display, not search). */
    fun evaluate(g: ChessGame): Int {
        return evaluateInternal(g, includeMobility = true)
    }

    /** Lightweight evaluation without mobility — for the search hot path. */
    fun evaluateFast(g: ChessGame): Int {
        return evaluateInternal(g, includeMobility = false)
    }

    /**
     * Material + PST only — no positional terms (king safety, pawn structure,
     * bishop pair, rook terms, mobility).  Used by lazy-eval and razoring
     * pruning heuristics in ChessAI.  Score from g.turn's perspective.
     */
    fun evaluateMaterialOnly(g: ChessGame): Int {
        val board = g.board
        val phase = computePhase(board)

        var mgScore = 0
        var egScore = 0

        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            val mg = pstMg(p.type, p.color, r, c)
            val eg = pstEg(p.type, p.color, r, c)
            if (p.color == PieceColor.WHITE) {
                mgScore += mg; egScore += eg
            } else {
                mgScore -= mg; egScore -= eg
            }
        }

        val mgPart = mgScore * phase
        val egPart = egScore * (MAX_PHASE - phase)
        val raw = (mgPart + egPart) / MAX_PHASE

        return if (g.turn == PieceColor.WHITE) raw else -raw
    }

    private fun evaluateInternal(g: ChessGame, includeMobility: Boolean): Int {
        val board = g.board
        val phase = computePhase(board)

        // ---- count pieces for king-safety decay & mobility skip ----
        var whiteQueens = 0; var blackQueens = 0
        var whiteMinors = 0; var blackMinors = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            when (p.type) {
                PieceType.QUEEN -> if (p.color == PieceColor.WHITE) whiteQueens++ else blackQueens++
                PieceType.KNIGHT, PieceType.BISHOP ->
                    if (p.color == PieceColor.WHITE) whiteMinors++ else blackMinors++
                else -> {}
            }
        }

        var mgScore = 0
        var egScore = 0

        // ---- material + PST (both sides at once) ----
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            val mg = pstMg(p.type, p.color, r, c)
            val eg = pstEg(p.type, p.color, r, c)
            if (p.color == PieceColor.WHITE) {
                mgScore += mg; egScore += eg
            } else {
                mgScore -= mg; egScore -= eg
            }
        }

        // ---- positional terms evaluated per side ----
        mgScore += kingSafety(board, PieceColor.WHITE, whiteQueens + whiteMinors)
        mgScore -= kingSafety(board, PieceColor.BLACK, blackQueens + blackMinors)

        val (wPawnMg, wPawnEg) = pawnStructure(board, PieceColor.WHITE)
        val (bPawnMg, bPawnEg) = pawnStructure(board, PieceColor.BLACK)
        mgScore += wPawnMg - bPawnMg
        egScore += wPawnEg - bPawnEg

        if (includeMobility) {
            val (wMobMg, wMobEg) = mobility(board, PieceColor.WHITE)
            val (bMobMg, bMobEg) = mobility(board, PieceColor.BLACK)
            mgScore += wMobMg - bMobMg
            egScore += wMobEg - bMobEg
        }

        // ---- bishop pair ----
        mgScore += bishopPair(board, PieceColor.WHITE)
        mgScore -= bishopPair(board, PieceColor.BLACK)
        egScore += bishopPairEndgame(board, PieceColor.WHITE)
        egScore -= bishopPairEndgame(board, PieceColor.BLACK)

        // ---- rook on open / semi-open file ----
        mgScore += rookOpenFile(board, PieceColor.WHITE)
        mgScore -= rookOpenFile(board, PieceColor.BLACK, true)

        // ---- rook on 7th rank ----
        mgScore += rookSeventh(board, PieceColor.WHITE)
        mgScore -= rookSeventh(board, PieceColor.BLACK)

        // ---- tapered blend ----
        val mgPart = mgScore * phase
        val egPart = egScore * (MAX_PHASE - phase)
        val raw = (mgPart + egPart) / MAX_PHASE

        // Return from the perspective of the side to move
        return if (g.turn == PieceColor.WHITE) raw else -raw
    }

    // ===================================================================
    //  Piece-square tables  (PeSTO by Ronald Friederich / Rofchade)
    //
    //  All tables are from White's point of view.
    //  Row 0 = rank 8, Row 7 = rank 1.  The PST already embeds material.
    // ===================================================================

    private fun pstIdx(r: Int, c: Int, color: PieceColor): Int {
        val row = if (color == PieceColor.WHITE) r else 7 - r
        return row * 8 + c
    }

    private fun pstMg(t: PieceType, color: PieceColor, r: Int, c: Int): Int =
        when (t) {
            PieceType.PAWN   -> PAWN_MG[pstIdx(r, c, color)]
            PieceType.KNIGHT -> KNIGHT_MG[pstIdx(r, c, color)]
            PieceType.BISHOP -> BISHOP_MG[pstIdx(r, c, color)]
            PieceType.ROOK   -> ROOK_MG[pstIdx(r, c, color)]
            PieceType.QUEEN  -> QUEEN_MG[pstIdx(r, c, color)]
            PieceType.KING   -> KING_MG[pstIdx(r, c, color)]
        }

    private fun pstEg(t: PieceType, color: PieceColor, r: Int, c: Int): Int =
        when (t) {
            PieceType.PAWN   -> PAWN_EG[pstIdx(r, c, color)]
            PieceType.KNIGHT -> KNIGHT_EG[pstIdx(r, c, color)]
            PieceType.BISHOP -> BISHOP_EG[pstIdx(r, c, color)]
            PieceType.ROOK   -> ROOK_EG[pstIdx(r, c, color)]
            PieceType.QUEEN  -> QUEEN_EG[pstIdx(r, c, color)]
            PieceType.KING   -> KING_EG[pstIdx(r, c, color)]
        }

    // -------- MG tables (opening / middlegame) --------

    private val PAWN_MG = intArrayOf(
          0,   0,   0,   0,   0,   0,   0,   0,
         98, 134,  61,  95,  68, 126,  34, -11,
         -6,   7,  26,  31,  65,  56,  25, -20,
        -14,  13,   6,  21,  23,  12,  17, -23,
        -27,  -2,  -5,  12,  17,   6,  10, -25,
        -26,  -4,  -4, -10,   3,   3,  33, -12,
        -35,  -1, -20, -23, -15,  24,  38, -22,
          0,   0,   0,   0,   0,   0,   0,   0
    )

    private val KNIGHT_MG = intArrayOf(
        -167, -89, -34, -49,  61, -97, -15, -107,
         -73, -41,  72,  36,  23,  62,   7,  -17,
         -47,  60,  37,  65,  84, 129,  73,   44,
          -9,  17,  19,  53,  37,  69,  18,   22,
         -13,   4,  16,  13,  28,  19,  21,   -8,
         -23,  -9,  12,  10,  19,  17,  25,  -16,
         -29, -53, -12,  -3,  -1,  18, -14,  -19,
        -105, -21, -58, -33, -17, -28, -19,  -23
    )

    private val BISHOP_MG = intArrayOf(
        -29,   4, -82, -37, -25, -42,   7,  -8,
        -26,  16, -18, -13,  30,  59,  18, -47,
        -16,  37,  43,  40,  35,  50,  37,  -2,
         -4,   5,  19,  50,  37,  37,   7,  -2,
         -6,  16,  15,  32,  34,  12,  30,  -7,
        -11,  14,  25,  24,  24,  43,  11,  -8,
        -21,   2,   4,  20,  20,   2,   6, -29,
        -37, -33, -17, -21, -16, -29, -27, -31
    )

    private val ROOK_MG = intArrayOf(
         32,  42,  32,  51,  63,   9,  31,  43,
         27,  32,  58,  62,  80,  67,  26,  44,
         -5,  19,  26,  36,  17,  45,  61,  16,
        -24, -11,   7,  26,  24,  35,  -8, -20,
        -36, -26, -12,  -1,   9,  -7,   6, -23,
        -45, -25, -16, -17,   3,   0,  -5, -33,
        -44, -16, -20,  -9,  -1,  11,  -6, -71,
        -19, -13,   1,  17,  16,   7, -37, -26
    )

    private val QUEEN_MG = intArrayOf(
        -28,   0,  29,  12,  59,  44,  43,  45,
        -24, -39,  -5,   1, -16,  57,  28,  54,
        -13, -17,   7,   8,  29,  56,  47,  57,
        -27, -27, -16, -16,  -1,  17,  -2,   1,
         -9, -26,  -9, -10,  -2,  -4,   3,  -3,
        -14,   2, -11,  -2,  -5,   2,  14,   5,
        -35,  -8,  11,   2,   8,  15,  -3,   1,
         -1, -18,  -9,  10, -15, -25, -31, -50
    )

    private val KING_MG = intArrayOf(
        -65,  23,  16, -15, -56, -34,   2,  13,
         29,  -1, -20,  -7,  -8,  -4, -38, -29,
         -9,  24,   2, -16, -20,   6,  22, -22,
        -17, -20, -12, -27, -30, -25, -14, -36,
        -49,  -1, -27, -39, -46, -44, -33, -51,
        -14, -14, -22, -46, -44, -30, -15, -27,
          1,   7,  -8, -64, -43, -16,   9,   8,
        -15, -15, -20, -44, -40, -24,   0,  -9
    )

    // -------- EG tables (endgame) --------

    private val PAWN_EG = intArrayOf(
          0,   0,   0,   0,   0,   0,   0,   0,
        178, 173, 158, 134, 147, 132, 165, 187,
         94, 100,  85,  67,  56,  53,  82,  84,
         32,  24,  13,   5,  -2,   4,  17,  17,
         13,   9,  -3,  -7,  -7,  -8,   3,  -1,
          4,   7,  -6,   1,   0,  -5,  -1,  -8,
         13,   8,   8,  10,  13,   0,   2,  -7,
          0,   0,   0,   0,   0,   0,   0,   0
    )

    private val KNIGHT_EG = intArrayOf(
        -58, -38, -13, -28, -31, -27, -63, -99,
        -25,  -8, -25,  -2,  -9, -25, -24, -52,
        -24, -20,  10,   9,  -1,  -9, -19, -41,
        -17,   3,  22,  22,  22,  11,   8, -18,
        -18,  -6,  16,  25,  16,  17,   4, -18,
        -23,  -3,  -1,  15,  10,  -3, -20, -22,
        -42, -20, -10,  -5,  -2, -20, -23, -44,
        -29, -51, -23, -15, -22, -18, -50, -64
    )

    private val BISHOP_EG = intArrayOf(
        -14, -21, -11,  -8,  -7,  -9, -17, -24,
         -8,  -4,   7, -12,  -3, -13,  -4, -14,
          2,  -8,   0,  -1,  -2,   6,   0,   4,
         -3,   9,  12,   9,  14,  10,   3,   2,
         -6,   3,  13,  10,  14,  12,   4,  -8,
        -10,   5,   4,   9,   9,   5,   8, -12,
        -15,  -6,   1,   4,   2,  -2,  -5, -17,
        -24, -20, -13, -13, -11, -14, -17, -24
    )

    private val ROOK_EG = intArrayOf(
         13,  10,  18,  15,  12,  12,   8,   5,
         11,  13,  13,  11,  -3,   3,   8,   3,
          7,   7,   7,   5,   4,  -3,  -5,  -3,
          4,   3,  13,   1,   2,   1,  -1,   2,
          3,   5,   8,   4,  -5,  -6,  -8, -11,
         -4,   0,  -5,  -1,  -7, -12,  -8, -16,
         -6,  -6,   0,   2,  -9,  -9, -11,  -3,
         -9,   2,   3,  -1,  -5, -13,   4, -20
    )

    private val QUEEN_EG = intArrayOf(
         -9,  22,  22,  27,  27,  19,  10,  20,
        -17,  20,  32,  41,  58,  25,  30,   0,
        -20,   6,   9,  49,  47,  35,  19,   9,
          3,  22,  24,  45,  57,  40,  57,  36,
        -18,  28,  19,  47,  31,  34,  39,  23,
        -16, -27,  15,   6,   9,  17,  10,   5,
        -22, -23, -30, -16, -16, -23, -36, -32,
        -33, -28, -22, -43,  -5, -32, -20, -41
    )

    private val KING_EG = intArrayOf(
        -74, -35, -18, -18, -11,  15,   4, -17,
        -12,  17,  14,  17,  17,  38,  23,  11,
         10,  17,  23,  15,  20,  45,  44,  13,
         -8,  22,  24,  27,  26,  33,  26,   3,
        -18,  -4,  21,  24,  27,  23,   9, -11,
        -19,  -3,  11,  21,  23,  16,   7,  -9,
        -27, -11,   4,  13,  14,   4,  -5, -17,
        -53, -34, -21, -11, -28, -14, -24, -43
    )

    // ===================================================================
    //  King safety  (MG-weighted — disappears as phase approaches endgame)
    // ===================================================================

    private fun kingSafety(
        board: Array<Array<Piece?>>,
        color: PieceColor,
        enemyNonPawn: Int
    ): Int {
        val kp = findKing(board, color) ?: return 0
        val kr = kp.first; val kc = kp.second
        val enemy = color.opposite()
        var score = 0

        // --- pawn shield ---
        // Determine king zone: kingside (f-h), queenside (a-c), center (d-e)
        val kingOnKingside = kc >= 5
        val kingOnQueenside = kc <= 2
        if (kingOnKingside) {
            score += shieldScore(board, color, kr, intArrayOf(5, 6, 7))
        } else if (kingOnQueenside) {
            score += shieldScore(board, color, kr, intArrayOf(0, 1, 2))
        }
        // King still in center — penalty
        if (!kingOnKingside && !kingOnQueenside) {
            score -= 30
        }

        // --- castling bonus ---
        val king = board[kr][kc]
        if (king != null && king.hasMoved) {
            score += 40  // has castled
        }

        // --- enemy attack density near king ---
        var attackers = 0
        for (dr in -2..2) for (dc in -2..2) {
            val rr = kr + dr; val cc = kc + dc
            if (rr !in 0 until 8 || cc !in 0 until 8) continue
            val p = board[rr][cc]
            if (p != null && p.color == enemy && p.type != PieceType.PAWN) {
                // Count only pieces that are "near" the king zone
                attackers++
            }
        }
        score -= attackers * 8

        // --- open / semi-open files near king ---
        for (fc in maxOf(0, kc - 1)..minOf(7, kc + 1)) {
            val open = isFileOpenOrSemi(board, fc, color)
            if (open < 0) score -= 15   // fully open
            else if (open == 0) score -= 6  // semi-open against us
        }

        // --- scale by enemy attacking material ---
        // With queen: 100%; without queen: ~50%; without queen and few minors: ~25%
        val weight = when {
            enemyNonPawn >= 4 -> 100
            enemyNonPawn >= 2 -> 55
            else              -> 25
        }
        return score * weight / 100
    }

    /** Returns negative if file is open (no pawns), 0 if semi-open against color (enemy has pawn, we don't). */
    private fun isFileOpenOrSemi(
        board: Array<Array<Piece?>>, file: Int, color: PieceColor
    ): Int {
        var ourPawn = false; var theirPawn = false
        for (r in 0 until 8) {
            val p = board[r][file] ?: continue
            if (p.type == PieceType.PAWN) {
                if (p.color == color) ourPawn = true else theirPawn = true
            }
        }
        return when {
            !ourPawn && !theirPawn -> -1  // fully open
            !ourPawn && theirPawn  -> 0   // semi-open against us
            else                   -> 1   // defended or semi-open for us
        }
    }

    /** Pawn shield bonus/penalty for the given three files in front of king. */
    private fun shieldScore(
        board: Array<Array<Piece?>>,
        color: PieceColor, kr: Int,
        files: IntArray
    ): Int {
        var score = 0
        val frontRank = if (color == PieceColor.WHITE) kr - 1 else kr + 1
        val frontRank2 = if (color == PieceColor.WHITE) kr - 2 else kr + 2
        for (f in files) {
            if (f !in 0 until 8) continue
            // Check for shield on rank directly in front
            val p1 = if (frontRank in 0 until 8) board[frontRank][f] else null
            if (p1 != null && p1.type == PieceType.PAWN && p1.color == color) {
                score += 10  // solid shield
            } else {
                // Check one rank further
                val p2 = if (frontRank2 in 0 until 8) board[frontRank2][f] else null
                if (p2 != null && p2.type == PieceType.PAWN && p2.color == color) {
                    score += 5  // advanced shield
                } else {
                    score -= 12  // missing shield pawn
                }
            }
        }
        return score
    }

    // ===================================================================
    //  Pawn structure
    // ===================================================================

    /** Returns (mgScore, egScore) for [color]'s pawn structure. */
    private fun pawnStructure(
        board: Array<Array<Piece?>>, color: PieceColor
    ): Pair<Int, Int> {
        var mg = 0
        var eg = 0
        val pawnFiles = IntArray(8)  // how many pawns on each file

        // Collect pawn positions
        val pawns = ArrayList<Pair<Int, Int>>()
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            if (p.type == PieceType.PAWN && p.color == color) {
                pawnFiles[c]++
                pawns.add(Pair(r, c))
            }
        }

        // --- doubled pawns ---
        for (f in 0 until 8) {
            if (pawnFiles[f] > 1) {
                val penalty = (pawnFiles[f] - 1) * 18
                mg -= penalty
                eg -= penalty
            }
        }

        // --- isolated & passed ---
        for ((r, c) in pawns) {
            // Isolated: no friendly pawn on adjacent files
            val isolated = (c == 0 || pawnFiles[c - 1] == 0) &&
                    (c == 7 || pawnFiles[c + 1] == 0)
            if (isolated) {
                mg -= 15
                eg -= 20
            }

            // Passed: no enemy pawn ahead on this or adjacent files
            if (isPassed(board, color, r, c)) {
                val promoRow = if (color == PieceColor.WHITE) 0 else 7
                val dist = kotlin.math.abs(promoRow - r)
                // MG bonus (less aggressive)
                val mgBonus = intArrayOf(0, 200, 120, 80, 50, 30, 15, 5)[dist]
                // EG bonus (more aggressive — passed pawns win endgames)
                val egBonus = intArrayOf(0, 300, 200, 140, 100, 70, 40, 15)[dist]
                mg += mgBonus
                eg += egBonus

                // Connected passed pawns
                if (hasAdjacentPassed(board, color, r, c)) {
                    mg += 30
                    eg += 40
                }
            }
        }

        return Pair(mg, eg)
    }

    private fun isPassed(
        board: Array<Array<Piece?>>, color: PieceColor, r: Int, c: Int
    ): Boolean {
        val enemy = color.opposite()
        val dir = if (color == PieceColor.WHITE) -1 else 1
        var rr = r + dir
        while (rr in 0 until 8) {
            for (dc in -1..1) {
                val cc = c + dc
                if (cc in 0 until 8) {
                    val p = board[rr][cc]
                    if (p != null && p.type == PieceType.PAWN && p.color == enemy) return false
                }
            }
            rr += dir
        }
        return true
    }

    private fun hasAdjacentPassed(
        board: Array<Array<Piece?>>, color: PieceColor, r: Int, c: Int
    ): Boolean {
        for (dc in intArrayOf(-1, 1)) {
            val cc = c + dc
            if (cc in 0 until 8) {
                val p = board[r][cc]
                if (p != null && p.type == PieceType.PAWN && p.color == color &&
                    isPassed(board, color, r, cc)
                ) return true
            }
        }
        return false
    }

    // ===================================================================
    //  Mobility  (count pseudo-legal moves for each non-pawn, non-king piece)
    // ===================================================================

    /** Returns (mgScore, egScore) for [color]'s mobility. */
    private fun mobility(
        board: Array<Array<Piece?>>, color: PieceColor
    ): Pair<Int, Int> {
        var mg = 0
        var eg = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            if (p.color != color) continue
            if (p.type == PieceType.PAWN || p.type == PieceType.KING) continue

            val moves = pseudoLegalMovesFor(board, r, c, p)
            // Safe moves: not to a square attacked by enemy pawn
            val safeCount = moves.count { (tr, tc) ->
                !isAttackedByEnemyPawn(board, tr, tc, color)
            }

            val mgPerMove: Int
            val egPerMove: Int
            when (p.type) {
                PieceType.KNIGHT -> { mgPerMove = 4; egPerMove = 2 }
                PieceType.BISHOP -> { mgPerMove = 3; egPerMove = 2 }
                PieceType.ROOK   -> { mgPerMove = 2; egPerMove = 1 }
                PieceType.QUEEN  -> { mgPerMove = 1; egPerMove = 1 }
                else             -> { mgPerMove = 0; egPerMove = 0 }
            }
            val m = minOf(safeCount, 12)  // cap to avoid over-valuing
            mg += m * mgPerMove
            eg += m * egPerMove

            // Penalty for trapped piece (very low mobility)
            if (safeCount <= 1) {
                mg -= 12
                eg -= 10
            }

            // Center control bonus
            for ((tr, tc) in moves) {
                if (tr in 3..4 && tc in 3..4) {
                    mg += 1
                }
            }
        }
        return Pair(mg, eg)
    }

    // ===================================================================
    //  Bishop pair
    // ===================================================================

    private fun bishopPair(
        board: Array<Array<Piece?>>, color: PieceColor
    ): Int {
        var count = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            if (p.type == PieceType.BISHOP && p.color == color) count++
        }
        if (count < 2) return 0
        // MG: +25, scaled by phase so it's ~30cp in pure MG and ~0 in pure EG
        return 25
    }

    // EG bishop pair bonus is handled separately since bishops are stronger in open endgames
    private fun bishopPairEndgame(
        board: Array<Array<Piece?>>, color: PieceColor
    ): Int {
        var count = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            if (p.type == PieceType.BISHOP && p.color == color) count++
        }
        return if (count >= 2) 45 else 0
    }

    // ===================================================================
    //  Rook on open / semi-open file
    // ===================================================================

    private fun rookOpenFile(
        board: Array<Array<Piece?>>, color: PieceColor, forBlack: Boolean = false
    ): Int {
        var score = 0
        val side = if (forBlack) PieceColor.BLACK else color
        // For the symmetry hack: when called for Black, we negate the return.
        for (c in 0 until 8) {
            var ourPawn = false; var theirPawn = false
            for (r in 0 until 8) {
                val p = board[r][c] ?: continue
                if (p.type == PieceType.PAWN) {
                    if (p.color == side) ourPawn = true else theirPawn = true
                }
            }
            val fullyOpen = !ourPawn && !theirPawn
            val semiOpen = !theirPawn && ourPawn  // semi-open for US (enemy has no pawn)
            for (r in 0 until 8) {
                val p = board[r][c] ?: continue
                if (p.type == PieceType.ROOK && p.color == side) {
                    when {
                        fullyOpen -> score += 18
                        semiOpen  -> score += 10
                    }
                }
            }
        }
        return if (forBlack) -score else score
    }

    // ===================================================================
    //  Rook on 7th rank
    // ===================================================================

    private fun rookSeventh(
        board: Array<Array<Piece?>>, color: PieceColor
    ): Int {
        var score = 0
        val seventhRank = if (color == PieceColor.WHITE) 1 else 6
        for (c in 0 until 8) {
            val p = board[seventhRank][c] ?: continue
            if (p.type == PieceType.ROOK && p.color == color) {
                // Bonus only if enemy king is still on the back rank
                val enemy = color.opposite()
                val backRank = if (color == PieceColor.WHITE) 0 else 7
                val enemyKing = findKing(board, enemy)
                if (enemyKing != null && enemyKing.first == backRank) {
                    score += 22
                }
            }
        }
        return score
    }

    // ===================================================================
    //  Helpers
    // ===================================================================

    private fun findKing(
        board: Array<Array<Piece?>>, color: PieceColor
    ): Pair<Int, Int>? {
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c]
            if (p != null && p.type == PieceType.KING && p.color == color) return Pair(r, c)
        }
        return null
    }

    /** Pseudo-legal destination squares (not full legality check) for one piece. */
    private fun pseudoLegalMovesFor(
        board: Array<Array<Piece?>>, r: Int, c: Int, p: Piece
    ): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        when (p.type) {
            PieceType.KNIGHT -> {
                val kn = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2,
                    1 to -2, 1 to 2, 2 to -1, 2 to 1)
                for ((dr, dc) in kn) {
                    val tr = r + dr; val tc = c + dc
                    if (tr in 0 until 8 && tc in 0 until 8) {
                        val target = board[tr][tc]
                        if (target == null || target.color != p.color) result.add(Pair(tr, tc))
                    }
                }
            }
            PieceType.BISHOP -> slideDests(board, r, c, p, DIRS_DIAG, result)
            PieceType.ROOK   -> slideDests(board, r, c, p, DIRS_ORTH, result)
            PieceType.QUEEN  -> {
                slideDests(board, r, c, p, DIRS_DIAG, result)
                slideDests(board, r, c, p, DIRS_ORTH, result)
            }
            else -> {} // pawns and kings not counted for mobility
        }
        return result
    }

    private val DIRS_DIAG = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
    private val DIRS_ORTH = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    private fun slideDests(
        board: Array<Array<Piece?>>, r: Int, c: Int, p: Piece,
        dirs: Array<Pair<Int, Int>>, out: MutableList<Pair<Int, Int>>
    ) {
        for ((dr, dc) in dirs) {
            var rr = r + dr; var cc = c + dc
            while (rr in 0 until 8 && cc in 0 until 8) {
                val target = board[rr][cc]
                if (target != null) {
                    if (target.color != p.color) out.add(Pair(rr, cc))
                    break
                }
                out.add(Pair(rr, cc))
                rr += dr; cc += dc
            }
        }
    }

    /** Quick check: is (r,c) attacked by an enemy pawn? */
    private fun isAttackedByEnemyPawn(
        board: Array<Array<Piece?>>, r: Int, c: Int, ourColor: PieceColor
    ): Boolean {
        val enemy = ourColor.opposite()
        val pawnRow = if (enemy == PieceColor.WHITE) r + 1 else r - 1
        for (dc in intArrayOf(-1, 1)) {
            val cc = c + dc
            if (pawnRow in 0 until 8 && cc in 0 until 8) {
                val p = board[pawnRow][cc]
                if (p != null && p.color == enemy && p.type == PieceType.PAWN) return true
            }
        }
        return false
    }
}

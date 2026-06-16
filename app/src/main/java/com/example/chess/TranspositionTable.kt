package com.example.chess

import kotlin.random.Random

/**
 * Zobrist-hash-based transposition table for chess search.
 *
 * Stores position evaluations keyed by a 64-bit hash so that the same
 * position reached via different move orders is evaluated only once.
 *
 * Capacity: 262,144 entries (~16 MB).  Always-replace strategy.
 */
class TranspositionTable(sizeMb: Int = 16) {

    // ---- entry flags ----
    companion object {
        const val EXACT: Byte = 0
        const val LOWER_BOUND: Byte = 1
        const val UPPER_BOUND: Byte = 2
    }

    data class TTEntry(
        val hash: Long,
        val depth: Int,
        val flag: Byte,
        val score: Int,
        val bestMove: Move?
    )

    // Map sizeMb to a power-of-2 entry count: 16 MB → 2^18 entries
    private val capacity: Int = 1 shl (17 + sizeMb.coerceIn(1, 64) / 16)
    private val mask: Long = (capacity - 1).toLong()
    private val entries = arrayOfNulls<TTEntry>(capacity)

    /** Probe the table. Returns the stored score if usable, null otherwise. */
    fun probe(hash: Long, depth: Int, alpha: Int, beta: Int): Int? {
        val idx = (hash and mask).toInt()
        val entry = entries[idx] ?: return null
        if (entry.hash != hash) return null
        if (entry.depth < depth) return null

        return when (entry.flag) {
            EXACT        -> entry.score
            LOWER_BOUND  -> if (entry.score >= beta) entry.score else null
            UPPER_BOUND  -> if (entry.score <= alpha) entry.score else null
            else         -> null
        }
    }

    /** Retrieve the best move stored for this hash (for move ordering). */
    fun bestMove(hash: Long): Move? {
        val idx = (hash and mask).toInt()
        val entry = entries[idx] ?: return null
        return if (entry.hash == hash) entry.bestMove else null
    }

    /** Store a search result in the table. */
    fun store(hash: Long, depth: Int, flag: Byte, score: Int, bestMove: Move?) {
        val idx = (hash and mask).toInt()
        val existing = entries[idx]
        // Always replace if empty, same hash, or new entry has >= depth
        if (existing == null || existing.hash == hash || depth >= existing.depth) {
            entries[idx] = TTEntry(hash, depth, flag, score, bestMove)
        }
    }

    /** Clear all entries. Call between moves. */
    fun clear() {
        entries.fill(null)
    }

    /** Number of non-null entries (for debugging). */
    fun usedCount(): Int = entries.count { it != null }
}

// ===================================================================
//  Zobrist key manager  (companion-style top-level object)
// ===================================================================

/**
 * Generates and holds the random Zobrist keys used for incremental
 * position hashing.  Keys are seeded deterministically so that the
 * same position always produces the same hash across runs.
 */
object Zobrist {
    // pieceKeys[type.ordinal][color.ordinal][square]
    val pieceKeys: Array<Array<LongArray>> = Array(6) { Array(2) { LongArray(64) } }
    val sideKey: Long
    val castlingKeys: LongArray = LongArray(4)   // WK WQ BK BQ
    val enPassantKeys: LongArray = LongArray(8)   // one per file

    init {
        val rng = Random(0x5AFEBABE_DEAD_BEEF) // deterministic seed (within Long range)
        for (t in 0 until 6) for (c in 0 until 2) for (s in 0 until 64) {
            pieceKeys[t][c][s] = rng.nextLong()
        }
        sideKey = rng.nextLong()
        for (i in 0 until 4) castlingKeys[i] = rng.nextLong()
        for (i in 0 until 8) enPassantKeys[i] = rng.nextLong()
    }

    /** Compute the full Zobrist hash of a position from scratch (O(64)). */
    fun computeHash(g: ChessGame): Long {
        var hash = 0L
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = g.board[r][c] ?: continue
            hash = hash xor pieceKeys[p.type.ordinal][p.color.ordinal][r * 8 + c]
        }
        if (g.turn == PieceColor.BLACK) hash = hash xor sideKey

        // Castling rights
        hash = xorCastling(hash, g)

        // En passant
        val ep = g.enPassant
        if (ep != null) hash = hash xor enPassantKeys[ep.second]
        return hash
    }

    /** XOR in castling right keys based on hasMoved flags. */
    private fun xorCastling(hash: Long, g: ChessGame): Long {
        var h = hash
        val wK = g.board[7][4]
        val bK = g.board[0][4]
        // White kingside
        if (wK != null && wK.type == PieceType.KING && !wK.hasMoved) {
            val wr = g.board[7][7]
            if (wr != null && wr.type == PieceType.ROOK && !wr.hasMoved) h = h xor castlingKeys[0]
        }
        // White queenside
        if (wK != null && wK.type == PieceType.KING && !wK.hasMoved) {
            val wr = g.board[7][0]
            if (wr != null && wr.type == PieceType.ROOK && !wr.hasMoved) h = h xor castlingKeys[1]
        }
        // Black kingside
        if (bK != null && bK.type == PieceType.KING && !bK.hasMoved) {
            val br = g.board[0][7]
            if (br != null && br.type == PieceType.ROOK && !br.hasMoved) h = h xor castlingKeys[2]
        }
        // Black queenside
        if (bK != null && bK.type == PieceType.KING && !bK.hasMoved) {
            val br = g.board[0][0]
            if (br != null && br.type == PieceType.ROOK && !br.hasMoved) h = h xor castlingKeys[3]
        }
        return h
    }

    /**
     * Incrementally update [hash] for [move] applied to [g].
     * Caller is responsible for applying the move to the board AFTER this call
     * (i.e., pass the pre-move state and we compute the post-move hash).
     *
     * This is called BEFORE makeMove so we see the original board.
     */
    fun applyMoveToHash(g: ChessGame, move: Move, oldHash: Long): Long {
        var hash = oldHash

        val piece = g.board[move.fromR][move.fromC] ?: return hash

        // Remove piece from source
        hash = hash xor pieceKeys[piece.type.ordinal][piece.color.ordinal][move.fromR * 8 + move.fromC]

        // Remove captured piece from destination (if any)
        val captured = g.board[move.toR][move.toC]
        if (captured != null) {
            hash = hash xor pieceKeys[captured.type.ordinal][captured.color.ordinal][move.toR * 8 + move.toC]
        }

        // En passant capture: remove the pawn that was passed
        if (move.isEnPassant) {
            val epCapturedRow = move.fromR
            val epCapturedCol = move.toC
            val epPiece = g.board[epCapturedRow][epCapturedCol]
            if (epPiece != null) {
                hash = hash xor pieceKeys[epPiece.type.ordinal][epPiece.color.ordinal][epCapturedRow * 8 + epCapturedCol]
            }
        }

        // Place piece on destination (with possible promotion)
        val finalType = move.promotion ?: piece.type
        hash = hash xor pieceKeys[finalType.ordinal][piece.color.ordinal][move.toR * 8 + move.toC]

        // Handle castling: move the rook
        if (move.isCastle) {
            if (move.toC > move.fromC) { // kingside
                val rook = g.board[move.fromR][7]!!
                hash = hash xor pieceKeys[rook.type.ordinal][rook.color.ordinal][move.fromR * 8 + 7]
                hash = hash xor pieceKeys[rook.type.ordinal][rook.color.ordinal][move.fromR * 8 + 5]
            } else { // queenside
                val rook = g.board[move.fromR][0]!!
                hash = hash xor pieceKeys[rook.type.ordinal][rook.color.ordinal][move.fromR * 8 + 0]
                hash = hash xor pieceKeys[rook.type.ordinal][rook.color.ordinal][move.fromR * 8 + 3]
            }
        }

        // Toggle side to move
        hash = hash xor sideKey

        // Remove old en passant key
        val oldEp = g.enPassant
        if (oldEp != null) hash = hash xor enPassantKeys[oldEp.second]

        // Add new en passant key
        if (move.isTwoStep) {
            val newEpFile = move.fromC
            hash = hash xor enPassantKeys[newEpFile]
        }

        // XOR out castling rights that may have changed (king/rook moves)
        hash = xorCastlingDelta(g, move, hash)

        return hash
    }

    /** XOR out / in castling keys that change because of [move]. */
    private fun xorCastlingDelta(g: ChessGame, move: Move, hash: Long): Long {
        var h = hash
        val piece = g.board[move.fromR][move.fromC] ?: return h

        // White king moved → lose both white castling rights
        if (piece.type == PieceType.KING && piece.color == PieceColor.WHITE) {
            val wK = g.board[7][4]
            if (wK != null && !wK.hasMoved) { h = h xor castlingKeys[0]; h = h xor castlingKeys[1] }
        }
        // Black king moved
        if (piece.type == PieceType.KING && piece.color == PieceColor.BLACK) {
            val bK = g.board[0][4]
            if (bK != null && !bK.hasMoved) { h = h xor castlingKeys[2]; h = h xor castlingKeys[3] }
        }
        // White rook from a1 moved
        if (move.fromR == 7 && move.fromC == 0) {
            val wr = g.board[7][0]
            if (wr != null && wr.type == PieceType.ROOK && !wr.hasMoved) h = h xor castlingKeys[1]
        }
        // White rook from h1 moved
        if (move.fromR == 7 && move.fromC == 7) {
            val wr = g.board[7][7]
            if (wr != null && wr.type == PieceType.ROOK && !wr.hasMoved) h = h xor castlingKeys[0]
        }
        // Black rook from a8 moved
        if (move.fromR == 0 && move.fromC == 0) {
            val br = g.board[0][0]
            if (br != null && br.type == PieceType.ROOK && !br.hasMoved) h = h xor castlingKeys[3]
        }
        // Black rook from h8 moved
        if (move.fromR == 0 && move.fromC == 7) {
            val br = g.board[0][7]
            if (br != null && br.type == PieceType.ROOK && !br.hasMoved) h = h xor castlingKeys[2]
        }
        // Capturing a rook on its starting square
        val captured = g.board[move.toR][move.toC]
        if (captured != null && captured.type == PieceType.ROOK && !captured.hasMoved) {
            if (move.toR == 7 && move.toC == 0) h = h xor castlingKeys[1]
            if (move.toR == 7 && move.toC == 7) h = h xor castlingKeys[0]
            if (move.toR == 0 && move.toC == 0) h = h xor castlingKeys[3]
            if (move.toR == 0 && move.toC == 7) h = h xor castlingKeys[2]
        }

        return h
    }
}

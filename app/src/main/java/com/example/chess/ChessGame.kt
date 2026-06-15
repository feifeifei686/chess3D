package com.example.chess

/** Piece colour. */
enum class PieceColor {
    WHITE, BLACK;

    fun opposite(): PieceColor = if (this == WHITE) BLACK else WHITE
}

enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }

data class Piece(val color: PieceColor, val type: PieceType, var hasMoved: Boolean = false)

/** A move from one square to another, with flags for the special moves. */
data class Move(
    val fromR: Int,
    val fromC: Int,
    val toR: Int,
    val toC: Int,
    val isCastle: Boolean = false,
    val isEnPassant: Boolean = false,
    val isTwoStep: Boolean = false,
    val promotion: PieceType? = null
)

enum class GameStatus { ONGOING, CHECK, CHECKMATE, STALEMATE }

/**
 * Full international-chess rules for local two-player (pass-and-play).
 * Board indexing: board[row][col], row 0 is the top (black's back rank),
 * row 7 is the bottom (white's back rank). White moves toward row 0.
 */
class ChessGame {

    val board: Array<Array<Piece?>> = Array(8) { arrayOfNulls<Piece>(8) }

    var turn: PieceColor = PieceColor.WHITE
        private set

    /** Square that may be captured via en passant on the next move, or null. */
    var enPassant: Pair<Int, Int>? = null
        private set

    var status: GameStatus = GameStatus.ONGOING
        private set

    /** Set when the game ends in checkmate. */
    var winner: PieceColor? = null
        private set

    /** Snapshot of the full game state taken before each move, for undo. */
    private class Memento(
        val board: Array<Array<Piece?>>,
        val turn: PieceColor,
        val enPassant: Pair<Int, Int>?,
        val status: GameStatus,
        val winner: PieceColor?
    )

    private val history = ArrayList<Memento>()

    /** Number of moves that can still be undone. */
    val movesPlayed: Int get() = history.size

    init {
        reset()
    }

    fun reset() {
        history.clear()
        for (r in 0 until 8) for (c in 0 until 8) board[r][c] = null

        val backRank = arrayOf(
            PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
            PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
        )
        for (c in 0 until 8) {
            board[0][c] = Piece(PieceColor.BLACK, backRank[c])
            board[1][c] = Piece(PieceColor.BLACK, PieceType.PAWN)
            board[6][c] = Piece(PieceColor.WHITE, PieceType.PAWN)
            board[7][c] = Piece(PieceColor.WHITE, backRank[c])
        }
        turn = PieceColor.WHITE
        enPassant = null
        status = GameStatus.ONGOING
        winner = null
    }

    fun pieceAt(r: Int, c: Int): Piece? =
        if (r in 0 until 8 && c in 0 until 8) board[r][c] else null

    /** Legal moves originating from the given square for the side to move. */
    fun legalMovesFrom(r: Int, c: Int): List<Move> {
        val p = pieceAt(r, c) ?: return emptyList()
        if (p.color != turn) return emptyList()
        return pseudoLegalMoves(board, r, c, enPassant)
            .filter { !leavesKingInCheck(it, p.color) }
    }

    /** Applies a move (assumed legal) and advances the game. */
    fun makeMove(move: Move) {
        history.add(Memento(copyBoard(board), turn, enPassant, status, winner))
        applyToBoard(board, move)

        // Track en passant target: only after a two-step pawn advance.
        enPassant = if (move.isTwoStep) {
            val midRow = (move.fromR + move.toR) / 2
            Pair(midRow, move.fromC)
        } else {
            null
        }

        turn = turn.opposite()
        recomputeStatus()
    }

    /**
     * Reverts the last move, restoring the previous board, turn and flags.
     * Returns true if a move was undone, false if there was nothing to undo.
     */
    fun undo(): Boolean {
        val m = history.removeLastOrNull() ?: return false
        for (r in 0 until 8) for (c in 0 until 8) board[r][c] = m.board[r][c]
        turn = m.turn
        enPassant = m.enPassant
        status = m.status
        winner = m.winner
        return true
    }

    /** Every legal move for the side to move (used by the AI). */
    fun allLegalMoves(): List<Move> {
        val res = ArrayList<Move>()
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            if (p.color == turn) res.addAll(legalMovesFrom(r, c))
        }
        return res
    }

    /** Is the side to move currently in check? */
    fun inCheck(): Boolean = isInCheck(board, turn)

    /** A deep copy of the whole game, so the AI can search without side effects. */
    fun snapshot(): ChessGame {
        val g = ChessGame()
        for (r in 0 until 8) for (c in 0 until 8) g.board[r][c] = board[r][c]?.copy()
        g.turn = turn
        g.enPassant = enPassant
        g.status = status
        g.winner = winner
        return g
    }

    private fun recomputeStatus() {
        val inCheck = isInCheck(board, turn)
        val hasMove = hasAnyLegalMove(turn)
        status = when {
            inCheck && !hasMove -> {
                winner = turn.opposite()
                GameStatus.CHECKMATE
            }
            !inCheck && !hasMove -> GameStatus.STALEMATE
            inCheck -> GameStatus.CHECK
            else -> GameStatus.ONGOING
        }
    }

    private fun hasAnyLegalMove(color: PieceColor): Boolean {
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = board[r][c] ?: continue
            if (p.color != color) continue
            val moves = pseudoLegalMoves(board, r, c, enPassant)
            if (moves.any { !leavesKingInCheck(it, color) }) return true
        }
        return false
    }

    /** True if applying [move] would leave [color]'s king attacked. */
    private fun leavesKingInCheck(move: Move, color: PieceColor): Boolean {
        val copy = copyBoard(board)
        applyToBoard(copy, move)
        return isInCheck(copy, color)
    }

    // ---- pure board helpers (operate on a supplied board) ----

    private fun copyBoard(src: Array<Array<Piece?>>): Array<Array<Piece?>> =
        Array(8) { r -> Array(8) { c -> src[r][c]?.copy() } }

    /** Mutates [b], moving the piece and handling castling, en passant, promotion. */
    private fun applyToBoard(b: Array<Array<Piece?>>, move: Move) {
        val piece = b[move.fromR][move.fromC] ?: return

        // En passant: remove the pawn that was passed.
        if (move.isEnPassant) {
            b[move.fromR][move.toC] = null
        }

        b[move.fromR][move.fromC] = null
        val moved = piece.copy(hasMoved = true)

        // Promotion.
        b[move.toR][move.toC] = if (move.promotion != null) {
            moved.copy(type = move.promotion)
        } else {
            moved
        }

        // Castling: move the rook too.
        if (move.isCastle) {
            if (move.toC > move.fromC) { // king side
                val rook = b[move.fromR][7]
                b[move.fromR][7] = null
                b[move.fromR][5] = rook?.copy(hasMoved = true)
            } else { // queen side
                val rook = b[move.fromR][0]
                b[move.fromR][0] = null
                b[move.fromR][3] = rook?.copy(hasMoved = true)
            }
        }
    }

    fun isInCheck(b: Array<Array<Piece?>>, color: PieceColor): Boolean {
        var kr = -1
        var kc = -1
        outer@ for (r in 0 until 8) for (c in 0 until 8) {
            val p = b[r][c]
            if (p != null && p.color == color && p.type == PieceType.KING) {
                kr = r; kc = c; break@outer
            }
        }
        if (kr < 0) return false
        return isSquareAttacked(b, kr, kc, color.opposite())
    }

    /** Is square (r,c) attacked by any piece of [by]? */
    private fun isSquareAttacked(b: Array<Array<Piece?>>, r: Int, c: Int, by: PieceColor): Boolean {
        // Pawns.
        val pawnRow = if (by == PieceColor.WHITE) r + 1 else r - 1
        for (dc in intArrayOf(-1, 1)) {
            val p = if (pawnRow in 0 until 8 && c + dc in 0 until 8) b[pawnRow][c + dc] else null
            if (p != null && p.color == by && p.type == PieceType.PAWN) return true
        }
        // Knights.
        val kn = arrayOf(
            -2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1
        )
        for ((dr, dc) in kn) {
            val p = pieceOn(b, r + dr, c + dc)
            if (p != null && p.color == by && p.type == PieceType.KNIGHT) return true
        }
        // King.
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val p = pieceOn(b, r + dr, c + dc)
            if (p != null && p.color == by && p.type == PieceType.KING) return true
        }
        // Sliding: bishop/queen on diagonals.
        val diag = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        for ((dr, dc) in diag) {
            if (rayHits(b, r, c, dr, dc, by, PieceType.BISHOP, PieceType.QUEEN)) return true
        }
        // Sliding: rook/queen on files & ranks.
        val orth = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in orth) {
            if (rayHits(b, r, c, dr, dc, by, PieceType.ROOK, PieceType.QUEEN)) return true
        }
        return false
    }

    private fun rayHits(
        b: Array<Array<Piece?>>, r: Int, c: Int, dr: Int, dc: Int,
        by: PieceColor, t1: PieceType, t2: PieceType
    ): Boolean {
        var rr = r + dr
        var cc = c + dc
        while (rr in 0 until 8 && cc in 0 until 8) {
            val p = b[rr][cc]
            if (p != null) {
                return p.color == by && (p.type == t1 || p.type == t2)
            }
            rr += dr; cc += dc
        }
        return false
    }

    private fun pieceOn(b: Array<Array<Piece?>>, r: Int, c: Int): Piece? =
        if (r in 0 until 8 && c in 0 until 8) b[r][c] else null

    /** Pseudo-legal moves (king-safety not yet checked) for the piece at (r,c). */
    private fun pseudoLegalMoves(
        b: Array<Array<Piece?>>, r: Int, c: Int, ep: Pair<Int, Int>?
    ): List<Move> {
        val p = b[r][c] ?: return emptyList()
        val moves = ArrayList<Move>()
        when (p.type) {
            PieceType.PAWN -> pawnMoves(b, r, c, p, ep, moves)
            PieceType.KNIGHT -> {
                val kn = arrayOf(
                    -2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1
                )
                for ((dr, dc) in kn) addIfTarget(b, r, c, r + dr, c + dc, p, moves)
            }
            PieceType.BISHOP -> slide(b, r, c, p, arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1), moves)
            PieceType.ROOK -> slide(b, r, c, p, arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1), moves)
            PieceType.QUEEN -> slide(
                b, r, c, p,
                arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1, -1 to 0, 1 to 0, 0 to -1, 0 to 1),
                moves
            )
            PieceType.KING -> kingMoves(b, r, c, p, moves)
        }
        return moves
    }

    private fun addIfTarget(
        b: Array<Array<Piece?>>, r: Int, c: Int, tr: Int, tc: Int,
        p: Piece, moves: MutableList<Move>
    ) {
        if (tr !in 0 until 8 || tc !in 0 until 8) return
        val target = b[tr][tc]
        if (target == null || target.color != p.color) {
            moves.add(Move(r, c, tr, tc))
        }
    }

    private fun slide(
        b: Array<Array<Piece?>>, r: Int, c: Int, p: Piece,
        dirs: Array<Pair<Int, Int>>, moves: MutableList<Move>
    ) {
        for ((dr, dc) in dirs) {
            var rr = r + dr
            var cc = c + dc
            while (rr in 0 until 8 && cc in 0 until 8) {
                val target = b[rr][cc]
                if (target == null) {
                    moves.add(Move(r, c, rr, cc))
                } else {
                    if (target.color != p.color) moves.add(Move(r, c, rr, cc))
                    break
                }
                rr += dr; cc += dc
            }
        }
    }

    private fun pawnMoves(
        b: Array<Array<Piece?>>, r: Int, c: Int, p: Piece,
        ep: Pair<Int, Int>?, moves: MutableList<Move>
    ) {
        val dir = if (p.color == PieceColor.WHITE) -1 else 1
        val startRow = if (p.color == PieceColor.WHITE) 6 else 1
        val promoteRow = if (p.color == PieceColor.WHITE) 0 else 7

        // Forward one.
        val one = r + dir
        if (one in 0 until 8 && b[one][c] == null) {
            addPawnMove(r, c, one, c, false, false, one == promoteRow, moves)
            // Forward two from the start.
            val two = r + 2 * dir
            if (r == startRow && b[two][c] == null) {
                moves.add(Move(r, c, two, c, isTwoStep = true))
            }
        }
        // Captures (incl. en passant).
        for (dc in intArrayOf(-1, 1)) {
            val tc = c + dc
            if (tc !in 0 until 8 || one !in 0 until 8) continue
            val target = b[one][tc]
            if (target != null && target.color != p.color) {
                addPawnMove(r, c, one, tc, false, true, one == promoteRow, moves)
            } else if (ep != null && ep.first == one && ep.second == tc) {
                moves.add(Move(r, c, one, tc, isEnPassant = true))
            }
        }
    }

    private fun addPawnMove(
        fr: Int, fc: Int, tr: Int, tc: Int,
        twoStep: Boolean, capture: Boolean, promote: Boolean, moves: MutableList<Move>
    ) {
        if (promote) {
            for (t in arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                moves.add(Move(fr, fc, tr, tc, isTwoStep = twoStep, promotion = t))
            }
        } else {
            moves.add(Move(fr, fc, tr, tc, isTwoStep = twoStep))
        }
    }

    private fun kingMoves(
        b: Array<Array<Piece?>>, r: Int, c: Int, p: Piece, moves: MutableList<Move>
    ) {
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            addIfTarget(b, r, c, r + dr, c + dc, p, moves)
        }
        // Castling.
        if (!p.hasMoved && !isSquareAttacked(b, r, c, p.color.opposite())) {
            // King side: rook at column 7.
            val kRook = b[r][7]
            if (kRook != null && kRook.type == PieceType.ROOK && !kRook.hasMoved &&
                b[r][5] == null && b[r][6] == null &&
                !isSquareAttacked(b, r, 5, p.color.opposite()) &&
                !isSquareAttacked(b, r, 6, p.color.opposite())
            ) {
                moves.add(Move(r, c, r, 6, isCastle = true))
            }
            // Queen side: rook at column 0.
            val qRook = b[r][0]
            if (qRook != null && qRook.type == PieceType.ROOK && !qRook.hasMoved &&
                b[r][1] == null && b[r][2] == null && b[r][3] == null &&
                !isSquareAttacked(b, r, 3, p.color.opposite()) &&
                !isSquareAttacked(b, r, 2, p.color.opposite())
            ) {
                moves.add(Move(r, c, r, 2, isCastle = true))
            }
        }
    }
}

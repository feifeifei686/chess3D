package com.example.chess

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight entry in the [index.json] file — just enough to show the history
 * list without parsing every full game record.
 */
data class GameIndexEntry(
    val id: String,
    val mode: String,
    val opponentName: String,
    val winner: String?,        // "WHITE", "BLACK", or null (draw)
    val status: String,         // "CHECKMATE" or "STALEMATE"
    val endTime: Long,
    val durationMs: Long,
    val totalMoves: Int
)

/**
 * A single move as stored inside a [GameRecord].
 */
data class MoveRecord(
    val fromR: Int, val fromC: Int,
    val toR: Int, val toC: Int,
    val isCastle: Boolean = false,
    val isEnPassant: Boolean = false,
    val isTwoStep: Boolean = false,
    val promotion: String? = null   // "QUEEN" | "ROOK" | "BISHOP" | "KNIGHT" | null
) {
    fun toMove(): Move = Move(
        fromR, fromC, toR, toC,
        isCastle, isEnPassant, isTwoStep,
        promotion?.let { PieceType.valueOf(it) }
    )
}

/**
 * Complete game record, serialised to a single JSON file.
 */
data class GameRecord(
    val id: String,
    val mode: String,
    val aiCharacterId: String?,
    val aiCharacterName: String?,
    val aiDepth: Int?,
    val aiColor: String?,
    val winner: String?,
    val status: String,
    val totalMoves: Int,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val moves: List<MoveRecord>
)

/**
 * All persistence lives under [context.getFilesDir]/game_history/.
 *
 * Directory layout:
 *   game_history/
 *     index.json                 ← array of [GameIndexEntry]
 *     game_<id>.json             ← single [GameRecord]
 *     game_<id>.png              ← 120×120 thumbnail
 *
 * The history is capped at 50 games; when a 51st game is saved the oldest entry
 * (by id = timestamp) is deleted.
 */
object GameStorage {

    private const val DIR_NAME = "game_history"
    private const val INDEX_FILE = "index.json"
    private const val MAX_GAMES = 50

    private lateinit var dir: File

    /** Must be called once, e.g. in Activity.onCreate(). */
    fun init(context: Context) {
        dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
    }

    // ---- save ----

    fun saveGame(record: GameRecord, thumbnail: Bitmap?) {
        if (!::dir.isInitialized) return

        // Write the full JSON record.
        File(dir, "game_${record.id}.json").writeText(record.toJson())

        // Save the thumbnail as a small PNG (120×120).
        if (thumbnail != null) {
            val scaled = Bitmap.createScaledBitmap(thumbnail, 120, 120, true)
            FileOutputStream(File(dir, "game_${record.id}.png")).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 80, out)
            }
            if (scaled !== thumbnail) scaled.recycle()
        }

        // Update the index.
        val index = loadIndexInternal().toMutableList()
        index.add(
            0,  // newest first
            GameIndexEntry(
                id = record.id,
                mode = record.mode,
                opponentName = record.aiCharacterName
                    ?: if (record.mode == "TWO_PLAYER") "双人对战" else "未知",
                winner = record.winner,
                status = record.status,
                endTime = record.endTime,
                durationMs = record.durationMs,
                totalMoves = record.totalMoves
            )
        )
        // Enforce the cap: delete oldest files beyond MAX_GAMES.
        while (index.size > MAX_GAMES) {
            val removed = index.removeAt(index.size - 1)
            deleteGameFiles(removed.id)
        }
        writeIndex(index)
    }

    // ---- load index ----

    fun loadIndex(): List<GameIndexEntry> {
        if (!::dir.isInitialized) return emptyList()
        return loadIndexInternal()
    }

    private fun loadIndexInternal(): List<GameIndexEntry> {
        val f = File(dir, INDEX_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i -> parseIndexEntry(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- load single game ----

    fun loadGame(id: String): GameRecord? {
        if (!::dir.isInitialized) return null
        val f = File(dir, "game_${id}.json")
        if (!f.exists()) return null
        return try { parseGameRecord(JSONObject(f.readText())) } catch (_: Exception) { null }
    }

    // ---- load thumbnail ----

    fun loadThumbnail(id: String): Bitmap? {
        if (!::dir.isInitialized) return null
        val f = File(dir, "game_${id}.png")
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    // ---- delete ----

    fun deleteGame(id: String) {
        if (!::dir.isInitialized) return
        deleteGameFiles(id)
        val index = loadIndexInternal().filter { it.id != id }
        writeIndex(index)
    }

    private fun deleteGameFiles(id: String) {
        File(dir, "game_${id}.json").delete()
        File(dir, "game_${id}.png").delete()
    }

    private fun writeIndex(index: List<GameIndexEntry>) {
        val arr = JSONArray()
        for (e in index) arr.put(indexEntryToJson(e))
        File(dir, INDEX_FILE).writeText(arr.toString(2))
    }

    // ---- JSON conversions ----

    private fun parseIndexEntry(o: JSONObject): GameIndexEntry = GameIndexEntry(
        id = o.getString("id"),
        mode = o.getString("mode"),
        opponentName = o.getString("opponentName"),
        winner = if (o.has("winner")) o.getString("winner") else null,
        status = o.getString("status"),
        endTime = o.getLong("endTime"),
        durationMs = o.getLong("durationMs"),
        totalMoves = o.getInt("totalMoves")
    )

    private fun indexEntryToJson(e: GameIndexEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("mode", e.mode)
        put("opponentName", e.opponentName)
        if (e.winner != null) put("winner", e.winner)
        put("status", e.status)
        put("endTime", e.endTime)
        put("durationMs", e.durationMs)
        put("totalMoves", e.totalMoves)
    }

    internal fun GameRecord.toJson(): String = JSONObject().apply {
        put("id", id)
        put("mode", mode)
        if (aiCharacterId != null) put("aiCharacterId", aiCharacterId)
        if (aiCharacterName != null) put("aiCharacterName", aiCharacterName)
        if (aiDepth != null) put("aiDepth", aiDepth)
        if (aiColor != null) put("aiColor", aiColor)
        if (winner != null) put("winner", winner)
        put("status", status)
        put("totalMoves", totalMoves)
        put("startTime", startTime)
        put("endTime", endTime)
        put("durationMs", durationMs)
        val ma = JSONArray()
        for (m in moves) ma.put(JSONObject().apply {
            put("fromR", m.fromR); put("fromC", m.fromC)
            put("toR", m.toR); put("toC", m.toC)
            if (m.isCastle) put("isCastle", true)
            if (m.isEnPassant) put("isEnPassant", true)
            if (m.isTwoStep) put("isTwoStep", true)
            if (m.promotion != null) put("promotion", m.promotion)
        })
        put("moves", ma)
    }.toString(2)

    private fun parseGameRecord(o: JSONObject): GameRecord {
        val ma = o.getJSONArray("moves")
        val moves = (0 until ma.length()).map { i ->
            val mo = ma.getJSONObject(i)
            MoveRecord(
                fromR = mo.getInt("fromR"), fromC = mo.getInt("fromC"),
                toR = mo.getInt("toR"), toC = mo.getInt("toC"),
                isCastle = mo.optBoolean("isCastle", false),
                isEnPassant = mo.optBoolean("isEnPassant", false),
                isTwoStep = mo.optBoolean("isTwoStep", false),
                promotion = if (mo.has("promotion")) mo.getString("promotion") else null
            )
        }
        return GameRecord(
            id = o.getString("id"),
            mode = o.getString("mode"),
            aiCharacterId = if (o.has("aiCharacterId")) o.getString("aiCharacterId") else null,
            aiCharacterName = if (o.has("aiCharacterName")) o.getString("aiCharacterName") else null,
            aiDepth = if (o.has("aiDepth")) o.getInt("aiDepth") else null,
            aiColor = if (o.has("aiColor")) o.getString("aiColor") else null,
            winner = if (o.has("winner")) o.getString("winner") else null,
            status = o.getString("status"),
            totalMoves = o.getInt("totalMoves"),
            startTime = o.getLong("startTime"),
            endTime = o.getLong("endTime"),
            durationMs = o.getLong("durationMs"),
            moves = moves
        )
    }
}

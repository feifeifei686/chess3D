package com.example.chess

/**
 * Character definitions and dialogue system for chess3d v2.6.
 * Five AI opponents with distinct names, avatars, playing strengths,
 * and speaking styles.
 */

// ===================================================================
//  Enums
// ===================================================================

enum class SpeakingStyle { INNOCENT, CHEERFUL, POLITE, BOLD, PHILOSOPHICAL }

enum class DialogTrigger {
    GREETING, THINKING,
    MOVE_CAPTURE, MOVE_CHECK, MOVE_CASTLE, MOVE_PROMOTE, MOVE_GENERIC,
    STATE_WINNING, STATE_LOSING, STATE_EVEN,
    PHASE_OPENING, PHASE_MIDDLEGAME, PHASE_ENDGAME
}

// ===================================================================
//  Character definition
// ===================================================================

data class CharacterDef(
    val id: String,
    val name: String,
    val title: String,
    val depth: Int,
    val strength: Int,
    val avatarResId: Int,
    val style: SpeakingStyle,
    val themeColor: Int,
    val themeColorDark: Int
)

// ===================================================================
//  Registry of all five characters
// ===================================================================

object Characters {
    val ALL = listOf(
        CharacterDef(
            id = "xiaoming",
            name = "小明",
            title = "初学者",
            depth = 1,
            strength = 800,
            avatarResId = R.drawable.avatar_xiaoming,
            style = SpeakingStyle.INNOCENT,
            themeColor = 0xFF66BB6A.toInt(),
            themeColorDark = 0xFF2E7D32.toInt()
        ),
        CharacterDef(
            id = "anna",
            name = "Anna",
            title = "休闲玩家",
            depth = 2,
            strength = 1200,
            avatarResId = R.drawable.avatar_anna,
            style = SpeakingStyle.CHEERFUL,
            themeColor = 0xFF42A5F5.toInt(),
            themeColorDark = 0xFF1565C0.toInt()
        ),
        CharacterDef(
            id = "sato",
            name = "佐藤",
            title = "中级",
            depth = 3,
            strength = 1600,
            avatarResId = R.drawable.avatar_sato,
            style = SpeakingStyle.POLITE,
            themeColor = 0xFF7E57C2.toInt(),
            themeColorDark = 0xFF4527A0.toInt()
        ),
        CharacterDef(
            id = "boris",
            name = "Boris",
            title = "高级",
            depth = 4,
            strength = 2000,
            avatarResId = R.drawable.avatar_boris,
            style = SpeakingStyle.BOLD,
            themeColor = 0xFFEF5350.toInt(),
            themeColorDark = 0xFFC62828.toInt()
        ),
        CharacterDef(
            id = "grandmaster",
            name = "大师",
            title = "特级大师",
            depth = 5,
            strength = 2400,
            avatarResId = R.drawable.avatar_grandmaster,
            style = SpeakingStyle.PHILOSOPHICAL,
            themeColor = 0xFFFFA726.toInt(),
            themeColorDark = 0xFFE65100.toInt()
        )
    )

    fun byId(id: String): CharacterDef? = ALL.find { it.id == id }
}

// ===================================================================
//  Dialogue manager
// ===================================================================

object DialogManager {

    /**
     * Pick a random dialogue line for a character given a trigger.
     * [moveDesc] is a human-readable piece name for the move (e.g. "后", "马").
     */
    fun generate(char: CharacterDef, trigger: DialogTrigger, moveDesc: String?): String {
        val pool = dialoguePool(char.id, trigger, moveDesc)
        return if (pool.isNotEmpty()) pool[(Math.random() * pool.size).toInt()] else "..."
    }

    /**
     * Determine the dialog trigger caused by a move.
     */
    fun triggerForMove(move: Move, game: ChessGame): DialogTrigger {
        if (move.isCastle) return DialogTrigger.MOVE_CASTLE
        if (move.isEnPassant || game.board[move.toR][move.toC] != null) return DialogTrigger.MOVE_CAPTURE
        if (move.promotion != null) return DialogTrigger.MOVE_PROMOTE
        // Check if this move gives check by making it on a snapshot.
        val snap = game.snapshot()
        snap.makeMove(move)
        if (snap.status == GameStatus.CHECK || snap.status == GameStatus.CHECKMATE) {
            return DialogTrigger.MOVE_CHECK
        }
        return DialogTrigger.MOVE_GENERIC
    }

    /**
     * Determine the state-based trigger from an evaluation centipawn score
     * (from Black's perspective, so negate if the AI is White).
     */
    fun stateTrigger(evalCp: Int, aiIsBlack: Boolean): DialogTrigger {
        val aiCenti = if (aiIsBlack) evalCp else -evalCp
        return when {
            aiCenti > 120 -> DialogTrigger.STATE_WINNING
            aiCenti < -120 -> DialogTrigger.STATE_LOSING
            else -> DialogTrigger.STATE_EVEN
        }
    }

    /**
     * Human-readable description of the piece moved or captured.
     */
    fun moveDesc(move: Move, game: ChessGame): String {
        val piece = game.board[move.fromR][move.fromC]
        return pieceName(piece?.type)
    }

    fun pieceName(type: PieceType?): String = when (type) {
        PieceType.PAWN -> "兵"
        PieceType.KNIGHT -> "马"
        PieceType.BISHOP -> "象"
        PieceType.ROOK -> "车"
        PieceType.QUEEN -> "后"
        PieceType.KING -> "王"
        null -> "子"
    }

    // ================================================================
    //  Dialogue pools per character and trigger
    // ================================================================

    private fun dialoguePool(charId: String, trigger: DialogTrigger, moveDesc: String?): List<String> {
        val md = moveDesc ?: ""

        return when (charId) {

            // ---- 小明 (INNOCENT) ----
            "xiaoming" -> when (trigger) {
                DialogTrigger.GREETING -> listOf(
                    "你好！我叫小明，请多指教！",
                    "我们来下棋吧！我还在学……",
                    "今天老师教了我一个新开局！"
                )
                DialogTrigger.THINKING -> listOf(
                    "让我想想……",
                    "这个子能走哪里呢？",
                    "嗯…有点难……",
                    "等等，我再看看……"
                )
                DialogTrigger.MOVE_CAPTURE -> listOf(
                    "吃掉你的${md}！嘿嘿~",
                    "哇，我吃到一个！",
                    "这个${md}归我啦！"
                )
                DialogTrigger.MOVE_CHECK -> listOf(
                    "将军！……是这样说的对吧？",
                    "将！老师说要喊将军！"
                )
                DialogTrigger.MOVE_CASTLE -> listOf(
                    "王车易位！这个我记得！",
                    "躲到安全的地方……"
                )
                DialogTrigger.MOVE_PROMOTE -> listOf(
                    "升变！我的兵变厉害了！",
                    "哇，兵可以变成${md}了！"
                )
                DialogTrigger.MOVE_GENERIC -> listOf(
                    "走这个好了……",
                    "这样走应该没问题吧？",
                    "这一步我想了很久！"
                )
                DialogTrigger.STATE_WINNING -> listOf(
                    "我好像要赢了？真的吗！",
                    "今天运气真好！"
                )
                DialogTrigger.STATE_LOSING -> listOf(
                    "哎呀，好像走错了……",
                    "老师说我应该多想想再走",
                    "又输了吗……没关系，再来！"
                )
                DialogTrigger.STATE_EVEN -> listOf(
                    "看起来差不多……",
                    "我们旗鼓相当呢！"
                )
                DialogTrigger.PHASE_OPENING -> listOf(
                    "老师说开局要占中心……",
                    "先出马还是先出象呢？"
                )
                DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                    "好多子在打架……",
                    "要小心不要送子……"
                )
                DialogTrigger.PHASE_ENDGAME -> listOf(
                    "老师说残局兵很重要！",
                    "把王走出来帮忙！"
                )
            }

            // ---- Anna (CHEERFUL) ----
            "anna" -> when (trigger) {
                DialogTrigger.GREETING -> listOf(
                    "Hi! Ready for a fun game?",
                    "Hello there! Let's play some chess!",
                    "Good to see you! Shall we?"
                )
                DialogTrigger.THINKING -> listOf(
                    "Hmm, let me think...",
                    "What would be fun here?",
                    "So many interesting choices!",
                    "Just a moment~"
                )
                DialogTrigger.MOVE_CAPTURE -> listOf(
                    "Got your ${md}! Nice!",
                    "Yum, a capture!",
                    "I'll take that ${md}, thanks!"
                )
                DialogTrigger.MOVE_CHECK -> listOf(
                    "Check! Don't worry, it happens!",
                    "Look out, your king!",
                    "Check! Just a little one~"
                )
                DialogTrigger.MOVE_CASTLE -> listOf(
                    "Safety first! Castling.",
                    "Tucking the king in bed~"
                )
                DialogTrigger.MOVE_PROMOTE -> listOf(
                    "A new ${md}! How exciting!",
                    "Promotion time! I love this part!"
                )
                DialogTrigger.MOVE_GENERIC -> listOf(
                    "That feels right!",
                    "Let's go with this one.",
                    "Nice and steady~"
                )
                DialogTrigger.STATE_WINNING -> listOf(
                    "Looking good for me, isn't it?",
                    "I'm on a roll!",
                    "Things are going well!"
                )
                DialogTrigger.STATE_LOSING -> listOf(
                    "Oh dear, that didn't go as planned...",
                    "You're really good at this!",
                    "Hmm, tough spot. But I'm still smiling!"
                )
                DialogTrigger.STATE_EVEN -> listOf(
                    "Neck and neck!",
                    "Anyone's game right now!"
                )
                DialogTrigger.PHASE_OPENING -> listOf(
                    "The beginning is always full of hope!",
                    "Let's see how this unfolds~"
                )
                DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                    "The real game begins now!",
                    "Things are heating up!"
                )
                DialogTrigger.PHASE_ENDGAME -> listOf(
                    "Down to the wire!",
                    "Every move counts now!"
                )
            }

            // ---- 佐藤 (POLITE) ----
            "sato" -> when (trigger) {
                DialogTrigger.GREETING -> listOf(
                    "よろしくお願いします。正々堂々と戦いましょう。",
                    "佐藤です。良い対局にしましょう。",
                    "お相手いたします。どうぞ。"
                )
                DialogTrigger.THINKING -> listOf(
                    "検討中です……",
                    "慎重に考えましょう",
                    "どれが最善でしょうか……",
                    "一手一手、丁寧に。"
                )
                DialogTrigger.MOVE_CAPTURE -> listOf(
                    "${md}をいただきます",
                    "良い交換です",
                    "この${md}、取らせていただきます"
                )
                DialogTrigger.MOVE_CHECK -> listOf(
                    "王手です。ご注意ください。",
                    "王手。油断なさらず。"
                )
                DialogTrigger.MOVE_CASTLE -> listOf(
                    "安全を確保します。",
                    "キャスリングで守りを固めます。"
                )
                DialogTrigger.MOVE_PROMOTE -> listOf(
                    "昇格です。${md}になります。",
                    "兵が${md}に。良い昇格です。"
                )
                DialogTrigger.MOVE_GENERIC -> listOf(
                    "これが良さそうです",
                    "よし、これで行きましょう",
                    "妥当な一手かと"
                )
                DialogTrigger.STATE_WINNING -> listOf(
                    "優勢です。このまま慎重に。",
                    "有利な局面です。気を緩めずに。"
                )
                DialogTrigger.STATE_LOSING -> listOf(
                    "厳しい局面ですが、まだチャンスはあります",
                    "粘りどころですね……",
                    "最後まで諦めません。"
                )
                DialogTrigger.STATE_EVEN -> listOf(
                    "互角の勝負です",
                    "まだまだこれからですね"
                )
                DialogTrigger.PHASE_OPENING -> listOf(
                    "定跡通りに進めましょう。",
                    "良い序盤展開です。"
                )
                DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                    "中盤の読みが勝負を決めます。",
                    "力を込めて考えます。"
                )
                DialogTrigger.PHASE_ENDGAME -> listOf(
                    "終盤の正確さが問われます。",
                    "最後の局面を丁寧に。"
                )
            }

            // ---- Boris (BOLD) ----
            "boris" -> when (trigger) {
                DialogTrigger.GREETING -> listOf(
                    "Sit down, my friend. You face Boris!",
                    "Ha! A challenger appears! I am Boris!",
                    "Prepare yourself! This will be glorious!"
                )
                DialogTrigger.THINKING -> listOf(
                    "The board is speaking to me!",
                    "Da! I see it!",
                    "A brilliant move is coming...",
                    "Let the pieces sing!"
                )
                DialogTrigger.MOVE_CAPTURE -> listOf(
                    "YOUR ${md} IS MINE! Hahaha!",
                    "Devoured! Like a bear eating honey!",
                    "I take your ${md} with great pleasure!"
                )
                DialogTrigger.MOVE_CHECK -> listOf(
                    "The king trembles before me!",
                    "CHECK! Feel the thunder!",
                    "Your king is in my crosshairs!"
                )
                DialogTrigger.MOVE_CASTLE -> listOf(
                    "Fortress secured! The fortress is ready!",
                    "My king retreats to his bunker!"
                )
                DialogTrigger.MOVE_PROMOTE -> listOf(
                    "A ${md} is born! Rise, my soldier!",
                    "Promotion! The ultimate reward!"
                )
                DialogTrigger.MOVE_GENERIC -> listOf(
                    "This is the way of the warrior!",
                    "Boris sees all, Boris knows all!",
                    "One step closer to victory!"
                )
                DialogTrigger.STATE_WINNING -> listOf(
                    "Victory approaches! Can you feel it?",
                    "The avalanche has begun! None can stop it!",
                    "I am unstoppable today!"
                )
                DialogTrigger.STATE_LOSING -> listOf(
                    "A temporary setback! The storm will turn!",
                    "You fight well! But the battle is long!",
                    "Boris does not surrender! NEVER!"
                )
                DialogTrigger.STATE_EVEN -> listOf(
                    "A worthy battle! Two titans clash!",
                    "The tension! The drama! I love it!"
                )
                DialogTrigger.PHASE_OPENING -> listOf(
                    "The curtain rises! The actors take the stage!",
                    "Every great story starts here!"
                )
                DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                    "Now the real fight begins!",
                    "Blood and thunder on the board!"
                )
                DialogTrigger.PHASE_ENDGAME -> listOf(
                    "The final act! History will remember this!",
                    "Only the brave survive the endgame!"
                )
            }

            // ---- 大师 (PHILOSOPHICAL) ----
            "grandmaster" -> when (trigger) {
                DialogTrigger.GREETING -> listOf(
                    "请。让我们在棋盘上对话。",
                    "落子吧。棋局自会说明一切。",
                    "来。以棋会友。"
                )
                DialogTrigger.THINKING -> listOf(
                    "……",
                    "棋如人生，落子无悔。",
                    "静观其变。",
                    "一着千年。"
                )
                DialogTrigger.MOVE_CAPTURE -> listOf(
                    "取得${md}。以静制动。",
                    "得失之间，方见真章。",
                    "${md}已入我手。顺势而为。"
                )
                DialogTrigger.MOVE_CHECK -> listOf(
                    "将。危局之中见真章。",
                    "王危。此乃转机之始。"
                )
                DialogTrigger.MOVE_CASTLE -> listOf(
                    "安王。守正出奇。",
                    "固本培元。王已安。"
                )
                DialogTrigger.MOVE_PROMOTE -> listOf(
                    "兵至极处，化为${md}。",
                    "升变。小卒亦可成大事。"
                )
                DialogTrigger.MOVE_GENERIC -> listOf(
                    "此手。可战。",
                    "落子。不悔。",
                    "这一着，恰到好处。"
                )
                DialogTrigger.STATE_WINNING -> listOf(
                    "胜负未定，请继续。",
                    "棋局如人生，不可大意。",
                    "优势如浮云，转瞬即逝。"
                )
                DialogTrigger.STATE_LOSING -> listOf(
                    "失势亦是修行。",
                    "败中求道。",
                    "逆境之中，方见本色。"
                )
                DialogTrigger.STATE_EVEN -> listOf(
                    "平衡。如阴阳交融。",
                    "均势之中，暗藏玄机。"
                )
                DialogTrigger.PHASE_OPENING -> listOf(
                    "开局如棋之序曲。",
                    "万变之始。谨而慎之。"
                )
                DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                    "中局之变，如人生之抉择。",
                    "千头万绪，心静则明。"
                )
                DialogTrigger.PHASE_ENDGAME -> listOf(
                    "残局如老僧入定。",
                    "收官之时，寸土必争。"
                )
            }

            else -> listOf("...")
        }
    }
}

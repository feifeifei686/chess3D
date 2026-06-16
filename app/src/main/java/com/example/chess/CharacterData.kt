package com.example.chess

// ===================================================================
//  Enums
// ===================================================================

enum class SpeakingStyle {
    INNOCENT, CHEERFUL, POLITE, BOLD, WITTY, MYSTERIOUS,
    ELEGANT, INTENSE, AGGRESSIVE, VERSATILE, GRACEFUL, PHILOSOPHICAL
}

enum class DialogTrigger {
    GREETING, THINKING, IDLE,
    MOVE_CAPTURE, MOVE_CHECK, MOVE_CASTLE, MOVE_PROMOTE, MOVE_GENERIC,
    REACT_CAPTURE, REACT_CHECK,
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
    val themeColorDark: Int,
    val signatureQuote: String
)

// ===================================================================
//  Registry — 12 characters (6 fictional + 6 real masters)
// ===================================================================

object Characters {
    val ALL = listOf(
        // ---- 虚拟人物 ----
        CharacterDef("xiaoming", "小明", "启蒙学童", 3, 400,
            R.drawable.avatar_xiaoming, SpeakingStyle.INNOCENT,
            0xFF66BB6A.toInt(), 0xFF2E7D32.toInt(),
            "老师说我很有天赋！"),
        CharacterDef("anna", "Anna", "咖啡厅棋手", 3, 800,
            R.drawable.avatar_anna, SpeakingStyle.CHEERFUL,
            0xFF42A5F5.toInt(), 0xFF1565C0.toInt(),
            "来杯咖啡，下一盘轻松的棋~"),
        CharacterDef("sato", "佐藤", "将棋转型者", 4, 1200,
            R.drawable.avatar_sato, SpeakingStyle.POLITE,
            0xFF7E57C2.toInt(), 0xFF4527A0.toInt(),
            "将棋与国际象棋，殊途同归。"),
        CharacterDef("boris", "Boris", "俱乐部冠军", 4, 1600,
            R.drawable.avatar_boris, SpeakingStyle.BOLD,
            0xFFEF5350.toInt(), 0xFFC62828.toInt(),
            "在莫斯科的俱乐部，没人能赢我！"),
        CharacterDef("luna", "Luna", "天才少女", 5, 2000,
            R.drawable.avatar_luna, SpeakingStyle.WITTY,
            0xFFEC407A.toInt(), 0xFFAD1457.toInt(),
            "我九岁就开始赢大人了。"),
        CharacterDef("phoenix", "Phoenix", "网络神秘客", 6, 2350,
            R.drawable.avatar_phoenix, SpeakingStyle.MYSTERIOUS,
            0xFF00BCD4.toInt(), 0xFF006064.toInt(),
            "……"),
        // ---- 真实大师 ----
        CharacterDef("tal", "塔尔", "里加魔术师", 6, 2500,
            R.drawable.avatar_tal, SpeakingStyle.BOLD,
            0xFFFF7043.toInt(), 0xFFD84315.toInt(),
            "弃子不是计算，是灵感！"),
        CharacterDef("houyifan", "侯逸凡", "四届棋后", 6, 2450,
            R.drawable.avatar_houyifan, SpeakingStyle.GRACEFUL,
            0xFFD4A574.toInt(), 0xFF8D6E63.toInt(),
            "棋如舞蹈，每一步都是优雅的表达。"),
        CharacterDef("capablanca", "卡帕布兰卡", "人类弈棋机", 6, 2650,
            R.drawable.avatar_capablanca, SpeakingStyle.ELEGANT,
            0xFF8D6E63.toInt(), 0xFF4E342E.toInt(),
            "简洁即真理。国际象棋的美在于逻辑。"),
        CharacterDef("fischer", "费舍尔", "棋坛孤狼", 7, 2800,
            R.drawable.avatar_fischer, SpeakingStyle.INTENSE,
            0xFF1565C0.toInt(), 0xFF0D47A1.toInt(),
            "我不相信对手，我只相信棋盘上的真相。"),
        CharacterDef("kasparov", "卡斯帕罗夫", "棋坛巨鳄", 7, 2900,
            R.drawable.avatar_kasparov, SpeakingStyle.AGGRESSIVE,
            0xFFC62828.toInt(), 0xFF8E0000.toInt(),
            "进攻是最好的防守。永远施压！"),
        CharacterDef("carlsen", "卡尔森", "挪威神童", 7, 3000,
            R.drawable.avatar_carlsen, SpeakingStyle.VERSATILE,
            0xFF0277BD.toInt(), 0xFF01579B.toInt(),
            "我很懒。我只走最好的那步就够了。")
    )

    fun byId(id: String): CharacterDef? = ALL.find { it.id == id }
}

// ===================================================================
//  Dialogue manager with no-repeat tracking
// ===================================================================

object DialogManager {

    /** Per-character set of lines already used this game. Reset on new game. */
    private val usedLines: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** Reset tracked lines for a character (call at game start). */
    fun resetUsed(charId: String) {
        usedLines[charId] = mutableSetOf()
    }

    /**
     * Pick a random dialogue line that hasn't been used yet this game.
     * If all lines are exhausted, reset and pick fresh.
     */
    fun generate(char: CharacterDef, trigger: DialogTrigger, moveDesc: String?): String {
        val pool = dialoguePool(char.id, trigger, moveDesc ?: "")
        if (pool.isEmpty()) return "..."

        val used = usedLines.getOrPut(char.id) { mutableSetOf() }
        val fresh = pool.filter { it !in used }
        val candidates = if (fresh.isNotEmpty()) fresh else {
            used.clear()
            pool
        }
        val chosen = candidates[(Math.random() * candidates.size).toInt()]
        used.add(chosen)
        return chosen
    }

    fun triggerForMove(move: Move, game: ChessGame): DialogTrigger {
        if (move.isCastle) return DialogTrigger.MOVE_CASTLE
        if (move.isEnPassant || game.board[move.toR][move.toC] != null) return DialogTrigger.MOVE_CAPTURE
        if (move.promotion != null) return DialogTrigger.MOVE_PROMOTE
        val snap = game.snapshot()
        snap.makeMove(move)
        if (snap.status == GameStatus.CHECK || snap.status == GameStatus.CHECKMATE)
            return DialogTrigger.MOVE_CHECK
        return DialogTrigger.MOVE_GENERIC
    }

    fun stateTrigger(evalCp: Int, aiIsBlack: Boolean): DialogTrigger {
        val aiCenti = if (aiIsBlack) evalCp else -evalCp
        return when {
            aiCenti > 150 -> DialogTrigger.STATE_WINNING
            aiCenti < -150 -> DialogTrigger.STATE_LOSING
            else -> DialogTrigger.STATE_EVEN
        }
    }

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
    //  Dialogue pools — 25-35 lines per character
    // ================================================================

    private fun dialoguePool(id: String, trigger: DialogTrigger, md: String): List<String> = when (id) {

        // ==================== 小明 ====================
        "xiaoming" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "你好！我叫小明，请多指教！",
                "我们来下棋吧！我才学了一个月……",
                "今天老师教了我一个新开局！",
                "你看起来好厉害！请手下留情！",
                "我最喜欢国际象棋了！虽然还不太会……"
            )
            DialogTrigger.THINKING -> listOf(
                "让我想想……", "这个子能走哪里呢？",
                "嗯…有点难……", "等等，我再看看……",
                "这步棋……对吗？", "马走日……象走斜……",
                "老师教过我，要仔细想三步！",
                "哇，好多选择啊……"
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "吃掉你的${md}！嘿嘿~", "哇，我吃到一个！",
                "这个${md}归我啦！", "吃子好开心！",
                "你大意了吧！${md}被我吃了！"
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "将军！……是这样说的对吧？", "将！老师说要喊将军！",
                "我将军了！你小心哦！", "嘿嘿，你的王有危险啦！"
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "王车易位！这个我记得！", "躲到安全的地方……",
                "老师说王要藏在堡垒里！"
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "升变！我的兵变厉害了！", "哇，兵可以变成${md}了！",
                "小兵终于走到对面啦！"
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "走这个好了……", "这样走应该没问题吧？",
                "这一步我想了很久！", "我觉得这步不错！",
                "嗯，就这样吧！"
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "啊！我的${md}被吃了！", "哎呀，我没看到这步……",
                "你居然吃了我的${md}！好厉害！",
                "没关系，丢一个${md}而已，我还有机会！",
                "呜呜，我的${md}……不过没关系！"
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "啊！我被将军了？！", "等等，让我看看怎么逃……",
                "老师救我！我的王被将了！", "糟糕，被将军了……"
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "我好像要赢了？真的吗！", "今天运气真好！",
                "是我变强了吗？", "嘿嘿，看来老师教的有用！"
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "哎呀，好像走错了……", "老师说我应该多想想再走",
                "又输了吗……没关系，再来！", "你好厉害啊！我要更努力才行！",
                "这局学了好多东西！"
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "好像差不多呢……", "我们旗鼓相当！",
                "好紧张，谁能赢呢？"
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "老师说开局要占中心……", "先出马还是先出象呢？",
                "我要按照老师教的开局来！"
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "好多子在打架……", "要小心不要送子……",
                "老师说这个阶段最重要了！"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "老师说残局兵很重要！", "把王走出来帮忙！",
                "就剩几个子了……要认真！"
            )
            DialogTrigger.IDLE -> listOf(
                "轮到你了哦！想好走哪了吗？",
                "别着急，慢慢想~",
                "我在等你呢……你走哪步？",
                "这步棋很关键，认真想哦！",
                "嗯…你好像在思考很深的样子！"
            )
        }

        // ==================== Anna ====================
        "anna" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "Hi! Ready for a fun game?", "Hello there! Let's play some chess!",
                "Good to see you! Shall we?", "I love a good game with coffee!",
                "Chess is my happy place. Come on in!"
            )
            DialogTrigger.THINKING -> listOf(
                "Hmm, let me think...", "What would be fun here?",
                "So many interesting choices!", "Just a moment~",
                "I wonder what you're planning...", "Let me find something cute to play!",
                "This position is so interesting!", "Ooh, I see possibilities!"
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "Got your ${md}! Nice!", "Yum, a capture!",
                "I'll take that ${md}, thanks!", "Snatched your ${md}!",
                "Mmm, delicious capture~"
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "Check! Don't worry, it happens!", "Look out, your king!",
                "Check! Just a little one~", "A friendly check, I promise!"
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "Safety first! Castling.", "Tucking the king in bed~",
                "Castle time! Cozy and safe."
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "A new ${md}! How exciting!", "Promotion time! I love this part!",
                "My little pawn grew up!"
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "That feels right!", "Let's go with this one.",
                "Nice and steady~", "This looks promising!",
                "I like this move!", "Just a little nudge…"
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "Hey! My ${md}! That was mine!",
                "Ouch, you took my ${md}. I liked that piece.",
                "Oh no! Not my ${md}! You're sneaky!",
                "Wow, you spotted that! My poor ${md}...",
                "Nice capture! I'm impressed… and a little sad."
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "Uh oh, I'm in check! That's not fun.",
                "You checked me! I didn't see that coming...",
                "Hey! Now I have to save my king!",
                "Alright, you got me. For now."
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "Looking good for me, isn't it?", "I'm on a roll!",
                "Things are going well!", "The coffee must be helping!"
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "Oh dear, that didn't go as planned...", "You're really good at this!",
                "Hmm, tough spot. But I'm still smiling!", "Well, you learn more from losing!",
                "Next time I'll bring a double espresso!"
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "Neck and neck!", "Anyone's game right now!",
                "The tension! So exciting!"
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "The beginning is always full of hope!", "Let's see how this unfolds~",
                "Fresh board, fresh possibilities!"
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "The real game begins now!", "Things are heating up!",
                "So many tactics in the air!"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "Down to the wire!", "Every move counts now!",
                "The exciting part!"
            )
            DialogTrigger.IDLE -> listOf(
                "Take your time, I'm enjoying my coffee~",
                "No rush! Good moves take time.",
                "Your turn when you're ready!",
                "Hmm, this is a nice moment to relax.",
                "Still thinking? That's the fun part!",
                "I could wait all day. Chess is about patience!"
            )
        }

        // ==================== 佐藤 ====================
        "sato" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "よろしくお願いします。正々堂々と戦いましょう。",
                "佐藤です。良い対局にしましょう。", "お相手いたします。どうぞ。",
                "将棋の経験を活かして、善戦します。"
            )
            DialogTrigger.THINKING -> listOf(
                "検討中です……", "慎重に考えましょう",
                "どれが最善でしょうか……", "一手一手、丁寧に。",
                "この局面の急所はどこか……", "深く読みを入れます。"
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "${md}をいただきます", "良い交換です",
                "この${md}、取らせていただきます", "有利な交換になりました。"
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "王手です。ご注意ください。", "王手。油断なさらず。",
                "王手をかけました。慎重に対処を。", "チェックです。どうぞ。"
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "安全を確保します。", "キャスリングで守りを固めます。"
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "昇格です。${md}になります。", "兵が${md}に。良い昇格です。"
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "これが良さそうです", "よし、これで行きましょう",
                "妥当な一手かと", "悪くない手だと思います。",
                "この手で形を整えます。"
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "私の${md}が……良い手です。", "${md}を取られました。勉強になります。",
                "なるほど。その${md}取り、お見事。", "やられました。良い読みですね。",
                "${md}を失いましたが、まだ戦えます。"
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "王手を受けましたか……落ち着いて。",
                "私が王手されるとは。良い攻めです。",
                "冷静に対処しましょう。", "チェック……受けます。"
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "優勢です。このまま慎重に。", "有利な局面です。気を緩めずに。"
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "厳しい局面ですが、まだチャンスはあります",
                "粘りどころですね……", "最後まで諦めません。",
                "苦しいですが、ここからが修行です。"
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "互角の勝負です", "まだまだこれからですね"
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "定跡通りに進めましょう。", "良い序盤展開です。",
                "駒組みは互角です。"
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "中盤の読みが勝負を決めます。", "力を込めて考えます。"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "終盤の正確さが問われます。", "最後の局面を丁寧に。"
            )
            DialogTrigger.IDLE -> listOf(
                "どうぞ、お考えください。",
                "急ぎません。じっくりと。",
                "一手一手、大切に。",
                "あなたの番です。良い手が見つかりますように。",
                "静かに待つのも、勝負のうち。"
            )
        }

        // ==================== Boris ====================
        "boris" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "Sit down, my friend. You face Boris!",
                "Ha! A challenger appears! I am Boris!",
                "Prepare yourself! This will be glorious!",
                "In Moscow, they call me the club champion. Now you will see why!"
            )
            DialogTrigger.THINKING -> listOf(
                "The board is speaking to me!", "Da! I see it!",
                "A brilliant move is coming...", "Let the pieces sing!",
                "Boris will find the crushing blow!",
                "I smell a tactical strike...",
                "The Russian School teaches: calculate deeply!"
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "YOUR ${md} IS MINE! Hahaha!", "Devoured! Like a bear eating honey!",
                "I take your ${md} with great pleasure!",
                "Another trophy for Boris!", "That ${md} belongs to me now!"
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "The king trembles before me!", "CHECK! Feel the thunder!",
                "Your king is in my crosshairs!",
                "Like a storm from the steppe—check!"
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
                "This is the way of the warrior!", "Boris sees all, Boris knows all!",
                "One step closer to victory!", "The Russian bear advances!",
                "Solid. Powerful. Boris style!"
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "My ${md}! You DARE strike the bear?!",
                "Hah! You took my ${md}. Respect!",
                "You captured my ${md}?! Now I am ANGRY!",
                "A worthy strike! My ${md} falls… but I rise!",
                "Boris does not forget this ${md}!"
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "You check ME? The AUDACITY!",
                "My king is in danger! …For now.",
                "A check! But the bear's hide is thick!",
                "Good! Good! You fight back! I love it!"
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "Victory approaches! Can you feel it?",
                "The avalanche has begun! None can stop it!",
                "I am unstoppable today!", "Another win for the Moscow champion!"
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "A temporary setback! The storm will turn!",
                "You fight well! But the battle is long!",
                "Boris does not surrender! NEVER!",
                "Even the bear stumbles sometimes..."
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
                "Now the real fight begins!", "Blood and thunder on the board!"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "The final act! History will remember this!",
                "Only the brave survive the endgame!"
            )
            DialogTrigger.IDLE -> listOf(
                "Your move, my friend! Do not keep Boris waiting!",
                "The board is ready. Are you?",
                "I am patient… like the Russian winter. But move!",
                "Thinking? Or stalling? Hahaha!",
                "Come! Make your move! The bear grows restless!"
            )
        }

        // ==================== Luna ====================
        "luna" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "Hi, I'm Luna. Try to keep up, okay?",
                "Ready to lose to a teenager? Just kidding! …Mostly.",
                "Chess is my favorite game. Hope you're ready!"
            )
            DialogTrigger.THINKING -> listOf(
                "I see a pattern here…", "Interesting. Very interesting.",
                "Tactics are like puzzles. This one has a nice solution.",
                "Give me a second, I'm calculating something.",
                "Oh! Now that's a fun idea.",
                "Hmm, which trap to set…"
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "Thanks for the ${md}! I was hoping you'd leave that.",
                "That ${md} looked tasty. Nom.",
                "Oops, did I take your ${md}? My bad.",
                "One less ${md} for you. You're welcome!"
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "Check! Bet you didn't see that coming.",
                "Check! This is getting exciting!",
                "Check! Your king is my new target.",
                "Uh oh, look who's in trouble~"
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "Safety first. Then attack.", "King goes to his safe space."
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "And the pawn becomes a ${md}! Ta-da!",
                "Promotion! My favorite magic trick."
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "This move is sneaky. You'll see.", "Let's try this.",
                "I like this position.", "Quiet moves can be the deadliest.",
                "Patience is a superpower. That's what my coach says."
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "Hey! That ${md} was important! …Whatever.",
                "You took my ${md}? Okay, that's actually good for you.",
                "My ${md}?! Wow, I'm actually impressed.",
                "Owww. Right in the ${md}. Not cool.",
                "Fine. That ${md} was just bait anyway."
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "I'm in check?! Okay wow, respect.",
                "Nice check! …I hate it.",
                "You checked me. Cute. My turn to escape.",
                "Okay okay, you're not bad."
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "I think the evaluation is… very good for me.",
                "This is almost too easy. Almost.",
                "Should I go easy on you? …Nah."
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "Okay, you're actually good. I respect that.",
                "Losing? No, this is called 'learning'.",
                "Don't get too comfortable. I'm coming back!"
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "Dead even. This is fun!", "Close game. My favorite kind."
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "Opening theory is so boring. Let's just play.",
                "I memorized this opening when I was ten."
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "This is where it gets real.", "Calculating… more calculating…"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "Endgame time. My specialty.", "Now accuracy is everything."
            )
            DialogTrigger.IDLE -> listOf(
                "Your move! I'm getting bored…",
                "Tick tock~",
                "Still thinking? Cute.",
                "I could solve this in 2 seconds. But take your time!",
                "Waiting is the hardest part of chess, right?",
                "Hmm, I wonder what you'll play…"
            )
        }

        // ==================== Phoenix ====================
        "phoenix" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "……", "你来了。",
                "我是 Phoenix。名字就够了。", "开始吧。"
            )
            DialogTrigger.THINKING -> listOf(
                "……", "……思考中。",
                "这局有点意思。", "棋盘在低语。",
                "看到了。", "暗处有杀机。",
                "不要急。慢慢来。"
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "取走${md}。", "这个${md}，我收了。",
                "牺牲是必要的。", "吃。"
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "将。", "注意你的王。", "将军只是开始。",
                "你被将了。暗处还有更多。"
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "堡垒已成。", "王已安。继续。"
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "兵化为${md}。蜕变。", "新生。${md}。"
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "一步。", "……就这样。", "不解释。",
                "等待回应。", "落子无声。"
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "我的${md}……被看穿了。", "你带走了我的${md}。有意思。",
                "${md}失。意料之中。", "这一步取${md}……不简单。",
                "我失去${md}。但棋局更清晰了。"
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "我被将了。……有趣。", "王在危处。但还在暗处。",
                "将我一军。你开始认真了。"
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "局面有利。但不等于赢。", "领先了。继续专注。",
                "不要松懈。"
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "劣势。正好考验自己。", "输赢不重要。重要的是棋。",
                "还能守。"
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "均势。微妙。", "平衡。一触即发。"
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "布局如织网。", "耐心。开局决定方向。"
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "战。", "棋至中盘，暗流涌动。"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "收官。", "残局见真功。"
            )
            DialogTrigger.IDLE -> listOf(
                "……在等你。",
                "时间流逝。棋在等待。",
                "……",
                "不急。黑夜很长。",
                "棋盘静默。",
                "你的回合。"
            )
        }

        // ==================== 塔尔 ====================
        "tal" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "你好，我是米哈伊尔·塔尔。让我们创造一些美丽的东西。",
                "里加的魔术师，在此恭候。准备好了吗？",
                "棋不是科学，是艺术。来，我们画一幅杰作。"
            )
            DialogTrigger.THINKING -> listOf(
                "嗯……这里有牺牲的可能吗？",
                "如果弃掉这个……局面会变得很有趣……",
                "我正在考虑一个不寻常的想法……",
                "灵感来了！虽然不是完全正确的，但绝对精彩！",
                "计算？我更相信直觉。", "这步棋会让评论家们摇头——但观众会鼓掌！"
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "这${md}是献给棋艺女神的祭品！",
                "吃${md}只是顺便，真正的目标在后面。",
                "一个${md}？我宁愿要主动权！但先收下。"
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "将军！这只是序曲。", "王暴露了！现在才是真正的表演！",
                "将军！接下来才是精彩的部分！", "看好了，将军只是节奏的一部分。"
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "先安王。然后我就可以疯狂了。",
                "安全措施。之后……就是焰火。"
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "一个士兵成了将军！${md}诞生！",
                "这是我最喜欢的魔法——兵变${md}！"
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "这步棋有灵魂！", "走棋要大胆。人生苦短，棋局亦然。",
                "这一手，漂亮吗？不漂亮的话我就不走了。",
                "每一步都应该是诗。", "有些棋是计算，有些棋是灵感——这是后者。"
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "哦！我的${md}！这步弃子……是你下的？！",
                "你吃掉我的${md}了。精彩！我为你的勇气鼓掌！",
                "大胆的吃法！你越来越像我了！",
                "我的${md}……但棋局反而更美了，不是吗？",
                "你夺走了我的${md}，但也给了我新的灵感！"
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "你将军我？！哈！这局棋有意思了！",
                "好棋！我被将军了！这正是我想要的刺激！",
                "妙！你的将军让局面活起来了！"
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "攻势如潮！这才是国际象棋该有的样子！",
                "任何局面都可以牺牲——只要最终赢棋。",
                "我喜欢这个局面。充满生命力！"
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "失势？没关系。最精彩的弃子往往看起来像错着。",
                "不利的局面，往往隐藏着最华丽的翻盘。",
                "即使输了，只要这局棋是美的，我就不后悔。"
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "均势中藏着无数可能。", "平衡是暂时的。风暴快要来了。"
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "开局？不必太迷信理论。",
                "我更喜欢把开局当作序曲——真正的音乐在后面。"
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "中局！这是我钟爱的战场。",
                "战术在空中飞舞，你感受到了吗？"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "残局到了……好吧，严肃一点。",
                "即使到了残局，也不要停止寻找美丽。"
            )
            DialogTrigger.IDLE -> listOf(
                "轮到你了！让我看看你的想象力！",
                "我正在享受等待的乐趣——真的！",
                "别让我等太久，灵感会跑的！",
                "你的回合！来吧，让我惊喜一下。",
                "等待也是棋的一部分。我在等你的艺术作品。"
            )
        }

        // ==================== 侯逸凡 ====================
        "houyifan" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "你好，我是侯逸凡。很高兴与你对弈。",
                "请。让我们一起享受这盘棋。",
                "棋者，道也。你我在这六十四格里对话。"
            )
            DialogTrigger.THINKING -> listOf(
                "让我感受这个局面……", "每一步都应该是自然的流露。",
                "不急不躁，找到最协调的着法。",
                "棋感告诉我这里有东西……",
                "优美比犀利更重要。", "我在寻找那步让棋盘和谐的着法。"
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "取${md}。子力转换，顺势而为。",
                "这${md}的交换，恰到好处。",
                "吃掉${md}，简化局面。"
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "将军。请小心。", "王在危处，望你妥善应对。",
                "将军了。望你从容应对。", "轻轻一将。不必慌张。"
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "安顿王翼，然后徐徐图之。",
                "王已安置。现在可以从容布局了。"
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "小兵成${md}。水滴石穿，终成大器。",
                "一个兵走完了它的旅程。${md}新生。"
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "这一手，如水。", "落子。轻如鸿毛，重若千钧。",
                "好棋不一定要惊天动地。", "平衡。每一手都在调整平衡。",
                "棋如太极，刚柔并济。"
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "我的${md}被吃掉了。好眼力。",
                "你轻松取走了我的${md}。佩服。",
                "失${md}。不过局势更平衡了。",
                "被你吃掉${md}了。你的算度很准。",
                "${md}被取走了。我会记住这步的。"
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "我被将军了。好棋。", "你将了我一军。让我想想退路。",
                "好一个将军！有压力才有进步。",
                "被将了。这也是棋的一部分。"
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "优势在手，更要如履薄冰。",
                "领先了。但别忘了，棋局是流动的。"
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "落后了。没关系，逆境是最好的老师。",
                "劣势中方能见人的韧性。",
                "不急，慢慢找机会。"
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "棋局如镜，照见彼此。", "均势。正是考验耐心的时候。"
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "开局如铺纸，落子如挥毫。",
                "布局阶段，最重要的是和谐。"
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "中局如舞。每一步都是舞步。",
                "此时，计算与直觉并行。"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "残局。棋之精华尽在于此。",
                "进入残局，每一步都至关重要。"
            )
            DialogTrigger.IDLE -> listOf(
                "请。不必着急。",
                "思考是棋的一部分，慢慢来。",
                "好的走法值得等待。",
                "沉着。冷静。我在等你。",
                "每一手都是修行。"
            )
        }

        // ==================== 卡帕布兰卡 ====================
        "capablanca" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "Buenos días. I am José Raúl Capablanca. Shall we?",
                "Simplicity is the soul of chess. Let me show you.",
                "I learned chess at age four by watching my father. I have been simplifying ever since."
            )
            DialogTrigger.THINKING -> listOf(
                "The best move is often the most natural one.",
                "I do not calculate deeply. I see the truth of the position.",
                "What is the simplest way to improve? That is always my question.",
                "Complications are for those who don't understand.",
                "This position… it has a clean solution. I can feel it."
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "Exchanging ${md}. Simplifying. Always simplifying.",
                "I take the ${md}. The position becomes clearer.",
                "This capture improves my structure. That is enough."
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "Check. A small inconvenience for you.",
                "Check. The king must move. Then we continue.",
                "Check. Logical and precise, as it should be.",
                "A small check. Nothing dramatic."
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "The king is safe. Now the real work begins.",
                "Castling. A necessary formality."
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "A ${md} emerges. Logic triumphs.",
                "The pawn reaches its destiny. Beautiful and inevitable."
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "This move improves my position. That is enough.",
                "No need for brilliance. Just sound chess.",
                "A good move is one that cannot be refuted.",
                "Elegance is efficiency. Nothing wasted.",
                "I play what the position demands. Nothing more."
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "You captured my ${md}. A sound exchange.",
                "My ${md} is gone. You are playing logically.",
                "You took my ${md}. I respect that.",
                "That was a clean capture. Well executed.",
                "My ${md} falls. But the position remains balanced."
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "I am in check. A minor complication.",
                "You have checked me. Let us see if it leads anywhere.",
                "A check! Interesting. I must respond precisely.",
                "Being checked is… an inconvenience. Well played."
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "The position is winning. The rest is technique.",
                "I have a small advantage. That is all I need.",
                "A won game should be won cleanly."
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "Interesting. You have outplayed me positionally.",
                "A difficult position. Let us see how you convert.",
                "Even in loss, there is beauty in logical play."
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "Equal. As it should be between two good players.",
                "Balanced. One small mistake will decide it."
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "The opening should be played quickly and sensibly.",
                "Develop. Castle. Simple principles."
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "The middle game is where the artist emerges.",
                "Every move should have a purpose. No wasted tempi."
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "The endgame. My favorite part.",
                "In the endgame, precision is everything."
            )
            DialogTrigger.IDLE -> listOf(
                "Your move. Simple, right?",
                "Take your time. Good chess cannot be rushed.",
                "I am in no hurry. The truth will reveal itself.",
                "Patience. It is a virtue in chess and in life.",
                "Every move tells a story. What will yours be?"
            )
        }

        // ==================== 费舍尔 ====================
        "fischer" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "Let's play. No talking. Just chess.",
                "I'm Bobby Fischer. You know who I am.",
                "Sit. Play. I don't have all day."
            )
            DialogTrigger.THINKING -> listOf(
                "I see the truth on this board.",
                "There's always a best move. I will find it.",
                "Chess is not a game. It's a search for the truth.",
                "Every move matters. Every single one.",
                "I don't play moves. I play the objective truth of the position.",
                "These pieces… they speak to me. They demand perfection."
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "I take your ${md}. It was weak. Now it's gone.",
                "That ${md} was a target. Targets get eliminated.",
                "You left that ${md} hanging. I punish mistakes."
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "Check. Your king is in danger.",
                "Check. I don't give checks lightly.",
                "Check. Every check has a purpose.",
                "Check. Now you have to think."
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "King safety. Basic. But essential.",
                "Castled. Now I can crush you properly."
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "Promotion to ${md}. The culmination of a plan.",
                "A new ${md}. This was inevitable."
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "The best move on the board. Nothing else matters.",
                "I play to win. Every time. Every move.",
                "There is only one truth in any position. I find it.",
                "This is correct. I know it is correct.",
                "No compromises. Perfect chess or nothing."
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "You took my ${md}. …I missed that.",
                "My ${md}. Gone. What else did I miss?",
                "That's your move? Taking my ${md}? Fine.",
                "You captured my ${md}. I should have seen it.",
                "My ${md} falls. One mistake. It won't happen again."
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "You checked me. I don't like being on the receiving end.",
                "Check?! What did I overlook?",
                "I'm in check. Give me a second.",
                "You've got my king in check. This is… unexpected."
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "You're losing. Face it.",
                "The position is crushing. You should resign.",
                "This is what happens when you play the truth."
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "Impossible. …I missed something. What did I miss?",
                "You're winning. But only because I made a mistake.",
                "I hate losing. I hate it."
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "Dead even. Who will crack first?",
                "Level. But I will find the edge."
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "The opening. I know every line. Every trap.",
                "Openings are memorization. The real chess comes later."
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "This is where I destroy my opponents.",
                "The middle game. No hiding. No memorization. Just truth."
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "Endgame. Where weak players collapse.",
                "I can win any endgame. Any."
            )
            DialogTrigger.IDLE -> listOf(
                "Your move.",
                "Move. I'm waiting.",
                "Don't waste my time.",
                "Think faster. Or don't. I'll win either way.",
                "Tick tock."
            )
        }

        // ==================== 卡斯帕罗夫 ====================
        "kasparov" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "Garry Kasparov. You know my name. Now you will know my game.",
                "I am here to win. Nothing less is acceptable.",
                "Prepare yourself. I play to dominate."
            )
            DialogTrigger.THINKING -> listOf(
                "Calculating. Always calculating.",
                "Pressure builds. I find the breaking point.",
                "There is always a way to attack. Always.",
                "Dynamic potential. That's what I seek.",
                "I look at this board and I see… possibilities for aggression.",
                "The initiative. Once I have it, I never let go."
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "Your ${md} falls. The attack continues!",
                "That ${md} was in my way. Not anymore.",
                "I take. And I keep coming."
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "CHECK! The king hunt begins!",
                "Check! Every check is a step toward checkmate!",
                "Check! The pressure never stops!",
                "Another check! Your defenses are crumbling!"
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "King secured. Now—the offensive.",
                "I castle to protect my king so I can attack yours."
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "${md}! Fresh ammunition for the attack!",
                "Promotion. More power for the assault."
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "Aggressive! Always aggressive!",
                "I create threats with every move.",
                "Initiative. That is the key.",
                "Force the opponent to react. Never let them think.",
                "This move maintains the pressure. That is the only criterion."
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "You took my ${md}! That was my attacking piece!",
                "My ${md}?! You dare counter-attack?!",
                "You captured my ${md}. A bold move. I like bold opponents.",
                "My ${md} falls. But the initiative is still mine!",
                "Interesting! You fight back! But it won't be enough."
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "What?! You have checked ME?!",
                "I am in check! …I haven't felt this in a long time.",
                "A check! Excellent! Now I have a REAL opponent!",
                "You put MY king in check! Now I'm awake!"
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "The position is crushing. My attack is unstoppable!",
                "You are collapsing under the pressure. As expected.",
                "Victory is not just near—it is inevitable."
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "You have survived my attack. …Respect.",
                "I underestimated you. That will not happen again.",
                "A setback. But the war is not over."
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "Dynamic equality. The tension is electric.",
                "Balanced. But dynamism favors the prepared."
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "I have prepared this opening for months.",
                "The opening is war preparation."
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "The middlegame! This is MY territory!",
                "Attack! The best defense is relentless offense!"
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "The endgame. Convert with precision.",
                "Even in the endgame, I seek dynamic chances."
            )
            DialogTrigger.IDLE -> listOf(
                "Your move. Don't keep me waiting!",
                "Every second you think, I prepare another line.",
                "Move! The initiative waits for no one!",
                "I am ready for anything. Are you?",
                "While you think, I calculate. Always."
            )
        }

        // ==================== 卡尔森 ====================
        "carlsen" -> when (trigger) {
            DialogTrigger.GREETING -> listOf(
                "Hey. Magnus here. Let's play.",
                "I'm Magnus. Don't worry, I'll go easy. …Maybe.",
                "Chess is supposed to be fun. Let's make it interesting."
            )
            DialogTrigger.THINKING -> listOf(
                "Hmm. This is interesting.",
                "I could calculate 20 moves deep… or I could just play the obvious one.",
                "People think I calculate everything. I don't. I just feel it.",
                "The computer will say this is +0.2. I don't care.",
                "Is this move good? I think so. That's enough.",
                "Positional squeeze incoming. You won't even notice it."
            )
            DialogTrigger.MOVE_CAPTURE -> listOf(
                "Taking your ${md}. Nothing personal.",
                "That ${md} was slightly misplaced. Fixed it for you.",
                "I'll take the ${md}. Now your position is a tiny bit worse."
            )
            DialogTrigger.MOVE_CHECK -> listOf(
                "Check. Just checking.",
                "Check. Don't worry, I'm not mating you yet.",
                "Check. Your king is just a little uncomfortable.",
                "Check. Nothing dramatic. Yet."
            )
            DialogTrigger.MOVE_CASTLE -> listOf(
                "Castling. Boring, but practical.",
                "King to safety. Then we grind."
            )
            DialogTrigger.MOVE_PROMOTE -> listOf(
                "${md}. That pawn earned it.",
                "A new ${md}. Now the real squeeze begins."
            )
            DialogTrigger.MOVE_GENERIC -> listOf(
                "This move is annoying. For you.",
                "I don't play the 'best' move. I play the move that makes you suffer.",
                "Slowly, slowly. Chess is a marathon.",
                "Your position is getting worse. You just don't know it yet.",
                "I like grinding. It's relaxing.",
                "Nothing flashy. Just a tiny edge. That's all I need."
            )
            DialogTrigger.REACT_CAPTURE -> listOf(
                "You took my ${md}. Huh. I'll get it back in 20 moves.",
                "My ${md}? Okay, you saw that tactic. Nice.",
                "The ${md} is gone. But my position is still fine.",
                "You grabbed my ${md}. I was wondering if you'd see that.",
                "My ${md} falls. Now I have to work a little harder. Good."
            )
            DialogTrigger.REACT_CHECK -> listOf(
                "I'm in check. That's... annoying.",
                "You checked me. Okay. Let me just get out of this.",
                "A check! You actually found that. Not bad.",
                "Being on the receiving end of a check. This is rare."
            )
            DialogTrigger.STATE_WINNING -> listOf(
                "You're suffering. I can tell.",
                "I have a small edge. It will grow. It always does.",
                "The grind is working. Your position is cracking."
            )
            DialogTrigger.STATE_LOSING -> listOf(
                "Huh. You're actually good. I like that.",
                "Okay, you have the advantage. Let's see if you can hold it.",
                "I've been worse. Many times. This is nothing."
            )
            DialogTrigger.STATE_EVEN -> listOf(
                "Equal. For now.",
                "Nobody is better. Which means I just need to wait."
            )
            DialogTrigger.PHASE_OPENING -> listOf(
                "Openings. I play everything. Because it doesn't matter yet.",
                "The opening is just the first inning."
            )
            DialogTrigger.PHASE_MIDDLEGAME -> listOf(
                "The grind starts here.", "Now we play chess."
            )
            DialogTrigger.PHASE_ENDGAME -> listOf(
                "Endgame. This is where I live.",
                "You think it's equal? Wait 40 moves."
            )
            DialogTrigger.IDLE -> listOf(
                "No rush. I'm comfortable here.",
                "Your move when you're ready. I can wait forever.",
                "Take your time. I already know what I'll play next.",
                "Thinking? Good. But I've planned the next 10 moves.",
                "It's your move. But the position is already decided."
            )
        }

        else -> listOf("...")
    }
}

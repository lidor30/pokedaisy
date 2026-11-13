package com.pokedaisy.app.companion.i18n.guide

import com.pokedaisy.app.companion.i18n.Tr

/** GuideUnbound.kt's text (see GuideText.kt): one line per English string, names as the localized games show them. */
val guideTextUnbound: Map<String, Tr> = mapOf(
    // TIPS. Difficulty names: the French ROM's own list (Difficile / Vanilla / Expert, Dément beside it).
    "BATTLES" to Tr(ja = "バトル", fr = "COMBATS", de = "KÄMPFE", it = "LOTTE", es = "COMBATES", zh = "对战"),
    "Why does NEXT BOSS show four teams?" to Tr(ja = "つぎの ボスに チームが 4つ あるのは なぜ?", fr = "Pourquoi PROCHAIN BOSS montre-t-il quatre équipes ?", de = "Warum zeigt NÄCHSTER BOSS vier Teams?", it = "Perché PROSSIMO BOSS mostra quattro squadre?", es = "¿Por qué PRÓXIMO JEFE muestra cuatro equipos?", zh = "为什么「下一个头目」显示四套队伍？"),
    "Unbound gives each gym leader a different team for each difficulty (VANILLA, DIFFICULT, EXPERT, INSANE). Look at the one you're playing on." to Tr(ja = "Unboundでは ジムリーダーの チームが むずかしさ (バニラ、 むずかしい、 エキスパート、 げきむず)ごとに ちがいます。 いま あそんでいる むずかしさの チームを みてください。", fr = "Unbound donne à chaque champion d'arène une équipe différente par difficulté (VANILLA, DIFFICILE, EXPERT, DÉMENT). Regarde celle de ta partie.", de = "Unbound gibt jedem Arenaleiter je Schwierigkeitsgrad ein anderes Team (VANILLA, SCHWER, EXPERTE, WAHNSINNIG). Schau dir das deiner Stufe an.", it = "Unbound dà a ogni capopalestra una squadra diversa per ogni difficoltà (VANILLA, DIFFICILE, ESPERTO, FOLLE). Guarda quella con cui giochi.", es = "Unbound da a cada líder de gimnasio un equipo distinto por dificultad (VANILLA, DIFÍCIL, EXPERTO, DEMENCIAL). Mira el de la dificultad que juegas.", zh = "Unbound 给每个道馆馆主按难度各准备了一套队伍（简单、困难、专家、疯狂）。看你现在玩的那一档。"),
    "VANILLA" to Tr(ja = "バニラ", fr = "VANILLA", de = "VANILLA", it = "VANILLA", es = "VANILLA", zh = "简单"),
    "DIFFICULT" to Tr(ja = "むずかしい", fr = "DIFFICILE", de = "SCHWER", it = "DIFFICILE", es = "DIFÍCIL", zh = "困难"),
    "EXPERT" to Tr(ja = "エキスパート", fr = "EXPERT", de = "EXPERTE", it = "ESPERTO", es = "EXPERTO", zh = "专家"),
    "INSANE" to Tr(ja = "げきむず", fr = "DÉMENT", de = "WAHNSINNIG", it = "FOLLE", es = "DEMENCIAL", zh = "疯狂"),

    // NEXT BOSS. French: the leaders and towns of the French ROM's gym signs ("Arène Pokémon de Cratéris /
    // Champion : Véga"); the other languages keep the hack's English names.
    "LEADER MIRSKLE" to Tr(ja = "ジムリーダー MIRSKLE", fr = "CHAMPION SYLVAIN", de = "ARENALEITER MIRSKLE", it = "CAPOPALESTRA MIRSKLE", es = "LÍDER MIRSKLE", zh = "馆主米尔斯克"),
    "DRESCO TOWN GYM" to Tr(ja = "DRESCO TOWN ジム", fr = "ARÈNE DE DRESCO", de = "ARENA VON DRESCO TOWN", it = "PALESTRA DI DRESCO TOWN", es = "GIMNASIO DE DRESCO TOWN", zh = "德雷斯科镇道馆"),
    "LEADER VÉGA" to Tr(ja = "ジムリーダー VÉGA", fr = "CHAMPION VÉGA", de = "ARENALEITER VÉGA", it = "CAPOPALESTRA VÉGA", es = "LÍDER VÉGA", zh = "馆主维加"),
    "CRATER TOWN GYM" to Tr(ja = "CRATER TOWN ジム", fr = "ARÈNE DE CRATÉRIS", de = "ARENA VON CRATER TOWN", it = "PALESTRA DI CRATER TOWN", es = "GIMNASIO DE CRATER TOWN", zh = "火山口镇道馆"),
    "LEADER ALICE" to Tr(ja = "ジムリーダー ALICE", fr = "CHAMPION ALICE", de = "ARENALEITERIN ALICE", it = "CAPOPALESTRA ALICE", es = "LÍDER ALICE", zh = "馆主爱丽丝"),
    "BLIZZARD CITY GYM" to Tr(ja = "BLIZZARD CITY ジム", fr = "ARÈNE DE CIMISTRAL", de = "ARENA VON BLIZZARD CITY", it = "PALESTRA DI BLIZZARD CITY", es = "GIMNASIO DE BLIZZARD CITY", zh = "暴雪市道馆"),
    "LEADER MEL" to Tr(ja = "ジムリーダー MEL", fr = "CHAMPION MÉL", de = "ARENALEITERIN MEL", it = "CAPOPALESTRA MEL", es = "LÍDER MEL", zh = "馆主梅尔"),
    "FALLSHORE CITY GYM" to Tr(ja = "FALLSHORE CITY ジム", fr = "ARÈNE DE RIVAPOLIS", de = "ARENA VON FALLSHORE CITY", it = "PALESTRA DI FALLSHORE CITY", es = "GIMNASIO DE FALLSHORE CITY", zh = "落岸市道馆"),
    "LEADER GALAVAN" to Tr(ja = "ジムリーダー GALAVAN", fr = "CHAMPION GALVANE", de = "ARENALEITER GALAVAN", it = "CAPOPALESTRA GALAVAN", es = "LÍDER GALAVAN", zh = "馆主加拉班"),
    "DEHARA CITY GYM" to Tr(ja = "DEHARA CITY ジム", fr = "ARÈNE DE DAHERAPOLIS", de = "ARENA VON DEHARA CITY", it = "PALESTRA DI DEHARA CITY", es = "GIMNASIO DE DEHARA CITY", zh = "德哈拉市道馆"),
    "LEADER BIG MO" to Tr(ja = "ジムリーダー BIG MO", fr = "CHAMPION MOMO", de = "ARENALEITER BIG MO", it = "CAPOPALESTRA BIG MO", es = "LÍDER BIG MO", zh = "馆主大莫"),
    "ANTISIS CITY GYM" to Tr(ja = "ANTISIS CITY ジム", fr = "ARÈNE D'ANTÉSIA", de = "ARENA VON ANTISIS CITY", it = "PALESTRA DI ANTISIS CITY", es = "GIMNASIO DE ANTISIS CITY", zh = "安提西斯市道馆"),
    "LEADER TESSY" to Tr(ja = "ジムリーダー TESSY", fr = "CHAMPION TESSIE", de = "ARENALEITERIN TESSY", it = "CAPOPALESTRA TESSY", es = "LÍDER TESSY", zh = "馆主泰西"),
    "POLDER TOWN GYM" to Tr(ja = "POLDER TOWN ジム", fr = "ARÈNE DE POLDERIVE", de = "ARENA VON POLDER TOWN", it = "PALESTRA DI POLDER TOWN", es = "GIMNASIO DE POLDER TOWN", zh = "圩田镇道馆"),
    "LEADER BENJAMIN" to Tr(ja = "ジムリーダー BENJAMIN", fr = "CHAMPION BENJAMIN", de = "ARENALEITER BENJAMIN", it = "CAPOPALESTRA BENJAMIN", es = "LÍDER BENJAMIN", zh = "馆主本杰明"),
    "REDWOOD VILLAGE GYM" to Tr(ja = "REDWOOD VILLAGE ジム", fr = "ARÈNE DE ROUGEBOIS", de = "ARENA VON REDWOOD VILLAGE", it = "PALESTRA DI REDWOOD VILLAGE", es = "GIMNASIO DE REDWOOD VILLAGE", zh = "红木村道馆"),
)

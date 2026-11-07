package com.pokedaisy.app.companion.i18n

/**
 * Co-op's PLAY TOGETHER ONLY (companion/coop/lock): its row, the confirms and the waiting screen.
 * YES, NO, ACCEPT, CLOSE GAME, ON and OFF are other tables'. Japanese is kana only, in the Emerald games' words.
 */
internal val trCoopLock: Map<String, Tr> = mapOf(
    "PLAY TOGETHER ONLY" to Tr(ja = "ふたりでだけ あそぶ", fr = "JOUER À DEUX SEULEMENT", de = "NUR ZUSAMMEN SPIELEN", it = "SOLO IN DUE", es = "SOLO EN PAREJA", br = "SÓ JOGAR JUNTOS", pt = "SÓ JOGAR JUNTOS"),
    "Pair with a partner first" to Tr(ja = "さきに パートナーと ペアリング", fr = "Associe d'abord un partenaire", de = "Zuerst einen Partner koppeln", it = "Prima associa un partner", es = "Primero vincula un socio", br = "Pareie com um parceiro antes", pt = "Emparelha primeiro com um parceiro"),
    "Your partner must be connected" to Tr(ja = "パートナーが せつぞく してる ひつよう", fr = "Ton partenaire doit être connecté", de = "Dein Partner muss verbunden sein", it = "Il partner deve essere connesso", es = "Tu socio debe estar conectado", br = "Seu parceiro precisa estar conectado", pt = "O teu parceiro tem de estar ligado"),
    "You already have a POKéMON" to Tr(ja = "もう ポケモンが いる", fr = "Tu as déjà un POKéMON", de = "Du hast schon ein POKéMON", it = "Hai già un POKéMON", es = "Ya tienes un POKéMON", br = "Você já tem um POKéMON", pt = "Já tens um POKéMON"),
    "Your partner already has a POKéMON" to Tr(ja = "あいてに ポケモンが いる", fr = "Ton partenaire a déjà un POKéMON", de = "Dein Partner hat schon ein POKéMON", it = "Il partner ha già un POKéMON", es = "Tu socio ya tiene un POKéMON", br = "Seu parceiro já tem um POKéMON", pt = "O teu parceiro já tem um POKéMON"),
    "Waiting for your partner to accept" to Tr(ja = "パートナーの へんじを まっています", fr = "En attente de l'accord de ton partenaire", de = "Warte auf die Zusage deines Partners", it = "In attesa che il partner accetti", es = "Esperando que tu socio acepte", br = "Esperando seu parceiro aceitar", pt = "À espera que o teu parceiro aceite"),
    "Turn on PLAY TOGETHER ONLY for both games? You can only play while your partner is connected, and story events wait for both of you." to Tr(
        ja = "ふたりの ゲームで ふたりでだけ あそぶを オンに する? パートナーが せつぞく してる あいだだけ あそべて、ストーリーは ふたりを まちます。",
        fr = "Activer JOUER À DEUX SEULEMENT pour les deux parties ? Tu ne joues que si ton partenaire est connecté, et les scènes de l'histoire attendent vous deux.",
        de = "NUR ZUSAMMEN SPIELEN für beide Spiele einschalten? Du kannst nur spielen, solange dein Partner verbunden ist, und Story-Ereignisse warten auf euch beide.",
        it = "Attivare SOLO IN DUE per entrambe le partite? Puoi giocare solo mentre il partner è connesso, e gli eventi della storia aspettano entrambi.",
        es = "¿Activar SOLO EN PAREJA en las dos partidas? Solo puedes jugar mientras tu socio esté conectado, y los eventos de la historia esperan a los dos.",
        br = "Ligar SÓ JOGAR JUNTOS nos dois jogos? Você só joga enquanto seu parceiro estiver conectado, e os eventos da história esperam os dois.",
        pt = "Ligar SÓ JOGAR JUNTOS nos dois jogos? Só podes jogar enquanto o teu parceiro estiver ligado, e os eventos da história esperam pelos dois.",
    ),
    "{0} wants to PLAY TOGETHER ONLY." to Tr(ja = "{0}は ふたりでだけ あそびたい。", fr = "{0} veut JOUER À DEUX SEULEMENT.", de = "{0} möchte NUR ZUSAMMEN SPIELEN.", it = "{0} vuole giocare SOLO IN DUE.", es = "{0} quiere jugar SOLO EN PAREJA.", br = "{0} quer SÓ JOGAR JUNTOS.", pt = "{0} quer SÓ JOGAR JUNTOS."),
    "Turn off PLAY TOGETHER ONLY? It can't be turned on again for these games." to Tr(
        ja = "ふたりでだけ あそぶを オフに する? この ゲームでは もう オンに できません。",
        fr = "Désactiver JOUER À DEUX SEULEMENT ? Impossible de le réactiver pour ces parties.",
        de = "NUR ZUSAMMEN SPIELEN ausschalten? Für diese Spiele lässt es sich nicht wieder einschalten.",
        it = "Disattivare SOLO IN DUE? Non si potrà riattivare per queste partite.",
        es = "¿Desactivar SOLO EN PAREJA? No se podrá volver a activar en estas partidas.",
        br = "Desligar SÓ JOGAR JUNTOS? Não dá para ligar de novo nesses jogos.",
        pt = "Desligar SÓ JOGAR JUNTOS? Não dá para voltar a ligar nestes jogos.",
    ),
    "Waiting for your partner..." to Tr(ja = "パートナーを まっています...", fr = "En attente de ton partenaire...", de = "Warte auf deinen Partner...", it = "In attesa del partner...", es = "Esperando a tu socio...", br = "Esperando seu parceiro...", pt = "À espera do teu parceiro..."),
    "Waiting for {0} to get here." to Tr(ja = "{0}が くるのを まっています。", fr = "En attente de {0}.", de = "Warte darauf, dass {0} hier ankommt.", it = "In attesa che {0} arrivi.", es = "Esperando a que {0} llegue.", br = "Esperando {0} chegar aqui.", pt = "À espera que {0} chegue aqui."),
    "Step away to cancel." to Tr(ja = "はなれると キャンセル", fr = "Éloigne-toi pour annuler.", de = "Geh weg, um abzubrechen.", it = "Allontanati per annullare.", es = "Aléjate para cancelar.", br = "Afaste-se para cancelar.", pt = "Afasta-te para cancelar."),
    "Not while you play together only" to Tr(ja = "ふたりでだけ あそぶ あいだは できません", fr = "Pas en jouant à deux seulement", de = "Nicht beim Nur-zusammen-Spielen", it = "Non mentre giochi solo in due", es = "No mientras juegas solo en pareja", br = "Não enquanto só joga junto", pt = "Não enquanto só jogas juntos"),
    "Your partner turned off PLAY TOGETHER ONLY." to Tr(ja = "パートナーが ふたりでだけ あそぶを オフに しました。", fr = "Ton partenaire a désactivé JOUER À DEUX SEULEMENT.", de = "Dein Partner hat NUR ZUSAMMEN SPIELEN ausgeschaltet.", it = "Il partner ha disattivato SOLO IN DUE.", es = "Tu socio desactivó SOLO EN PAREJA.", br = "Seu parceiro desligou SÓ JOGAR JUNTOS.", pt = "O teu parceiro desligou SÓ JOGAR JUNTOS."),
    "Story events can't wait for your partner in this game" to Tr(ja = "このゲームでは ストーリーは パートナーを まてません", fr = "Dans ce jeu, l'histoire n'attend pas ton partenaire", de = "In diesem Spiel wartet die Story nicht auf deinen Partner", it = "In questo gioco la storia non aspetta il partner", es = "En este juego la historia no espera a tu socio", br = "Neste jogo, a história não espera seu parceiro", pt = "Neste jogo, a história não espera o teu parceiro"),
)

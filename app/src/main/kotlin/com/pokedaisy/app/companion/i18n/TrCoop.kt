package com.pokedaisy.app.companion.i18n

/**
 * Co-op (companion/coop): the CO-OP settings group, the invite pages and the relay row.
 * BUILT-IN, CANCEL, DONE, SAVE, NONE, TRY AGAIN and CHEATS ON are other tables'. Japanese is kana only, in the Emerald games' words.
 */
internal val trCoop: Map<String, Tr> = mapOf(
    "That invite link isn't valid" to Tr(ja = "しょうたいリンクが ただしくありません", fr = "Ce lien d'invitation n'est pas valide", de = "Dieser Einladungslink ist ungültig", it = "Questo link d'invito non è valido", es = "Este enlace de invitación no es válido", br = "Esse link de convite não é válido", pt = "Esta ligação de convite não é válida"),

    // The group and its rows.
    "CO-OP" to Tr(ja = "いっしょに あそぶ", fr = "COOP", de = "KOOP", it = "COOP", es = "COOP", br = "COOP", pt = "COOP"),
    "PAIR" to Tr(ja = "ペアリング", fr = "ASSOCIER", de = "KOPPELN", it = "ASSOCIA", es = "VINCULAR", br = "PAREAR", pt = "EMPARELHAR"),
    "PARTNERS" to Tr(ja = "パートナー", fr = "PARTENAIRES", de = "PARTNER", it = "PARTNER", es = "SOCIOS", br = "PARCEIROS", pt = "PARCEIROS"),
    "RELAY" to Tr(ja = "リレー", fr = "RELAIS", de = "RELAIS", it = "RELAY", es = "RETRANSMISOR", br = "RELÉ", pt = "RELÉ"),
    "RELAY ONLY" to Tr(ja = "リレーのみ", fr = "RELAIS SEUL", de = "NUR RELAIS", it = "SOLO RELAY", es = "SOLO RETRANS.", br = "SÓ RELÉ", pt = "SÓ RELÉ"),
    "Link one of your Emerald saves with a friend's" to Tr(ja = "じぶんの エメラルドの セーブを ともだちと つなぐ", fr = "Relie une de tes sauvegardes Émeraude à celle d'un ami", de = "Verbinde einen deiner Smaragd-Spielstände mit dem eines Freundes", it = "Collega un tuo salvataggio Smeraldo a quello di un amico", es = "Vincula una de tus partidas de Esmeralda con la de un amigo", br = "Ligue um dos seus saves de Emerald ao de um amigo", pt = "Liga um dos teus saves de Emerald ao de um amigo"),
    "Hide your IP address from your partner" to Tr(ja = "あいてに IPアドレスを みせない", fr = "Cache ton adresse IP à ton partenaire", de = "Verbirg deine IP-Adresse vor deinem Partner", it = "Nascondi il tuo indirizzo IP al partner", es = "Oculta tu dirección IP a tu socio", br = "Esconda seu endereço IP do seu parceiro", pt = "Esconde o teu endereço IP do teu parceiro"),

    // The companion's CO-OP rows (LINK, PAIR's note, RELAY's note).
    "LINK" to Tr(ja = "リンク", fr = "LIEN", de = "VERBINDUNG", it = "COLLEGAMENTO", es = "ENLACE", br = "LINK", pt = "LIGAÇÃO"),
    "Pair it on the top screen: CO-OP" to Tr(ja = "うえの がめんで ペアリング", fr = "Associe-le sur l'écran du haut", de = "Koppeln: oberer Bildschirm", it = "Associa sullo schermo sopra", es = "Vincula en la pantalla de arriba", br = "Pareie na tela de cima", pt = "Emparelha no ecrã de cima"),
    "Turn cheats off to play together" to Tr(ja = "チートを オフに して ください", fr = "Triches off pour jouer ensemble", de = "Cheats aus zum Zusammenspielen", it = "Spegni i trucchi per giocare", es = "Quita los trucos para jugar", br = "Desligue trapaças para jogar", pt = "Desliga batotas para jogar"),
    "{0}: trying again in {1} s" to Tr(ja = "{0}: {1}びょうごに もういちど ためします", fr = "{0} : nouvel essai dans {1} s", de = "{0}: neuer Versuch in {1} s", it = "{0}: riprovo tra {1} s", es = "{0}: reintentando en {1} s", br = "{0}: tentando de novo em {1} s", pt = "{0}: a tentar de novo em {1} s"),
    "On the top screen: Settings > CO-OP" to Tr(ja = "うえの がめんの せってい", fr = "En haut : Réglages > COOP", de = "Oben: Einstellungen > KOOP", it = "Sopra: Impostazioni > COOP", es = "Arriba: Ajustes > COOP", br = "Em cima: Ajustes > COOP", pt = "Em cima: Definições > COOP"),
    "Change it in Settings on the top screen" to Tr(ja = "うえの がめんの せっていで", fr = "À changer sur l'écran du haut", de = "Oben in den Einstellungen ändern", it = "Cambialo sullo schermo sopra", es = "Cámbialo arriba, en Ajustes", br = "Mude na tela de cima", pt = "Muda no ecrã de cima"),

    // The link's state (coopLinkLabel).
    "CONNECTING" to Tr(ja = "せつぞくちゅう", fr = "CONNEXION", de = "VERBINDEN", it = "CONNESSIONE", es = "CONECTANDO", br = "CONECTANDO", pt = "A LIGAR"),
    "CONNECTED" to Tr(ja = "せつぞくずみ", fr = "CONNECTÉ", de = "VERBUNDEN", it = "CONNESSO", es = "CONECTADO", br = "CONECTADO", pt = "LIGADO"),
    "RECONNECTING" to Tr(ja = "さいせつぞくちゅう", fr = "RECONNEXION", de = "NEU VERBINDEN", it = "RICONNESSIONE", es = "RECONECTANDO", br = "RECONECTANDO", pt = "A RELIGAR"),
    "NOT PAIRED" to Tr(ja = "ペアリングなし", fr = "NON ASSOCIÉ", de = "OHNE PAAR", it = "NON ASSOCIATO", es = "SIN VINCULAR", br = "SEM PAR", pt = "SEM PAR"),
    "NOT RECOGNISED" to Tr(ja = "にんしきできない", fr = "NON RECONNU", de = "NICHT ERKANNT", it = "NON RICONOSCIUTO", es = "NO RECONOCIDO", br = "NÃO RECONHECIDO", pt = "NÃO RECONHECIDO"),
    "NOT EMERALD" to Tr(ja = "エメラルドじゃない", fr = "PAS ÉMERAUDE", de = "KEIN SMARAGD", it = "NON SMERALDO", es = "NO ESMERALDA", br = "NÃO É EMERALD", pt = "NÃO É EMERALD"),

    // Picking a save.
    "PICK A SAVE" to Tr(ja = "セーブを えらぶ", fr = "CHOISIS UNE SAUVEGARDE", de = "SPIELSTAND WÄHLEN", it = "SCEGLI UN SALVATAGGIO", es = "ELIGE UNA PARTIDA", br = "ESCOLHA UM SAVE", pt = "ESCOLHE UM SAVE"),
    "NO EMERALD SAVES" to Tr(ja = "エメラルドの セーブが ありません", fr = "AUCUNE SAUVEGARDE ÉMERAUDE", de = "KEINE SMARAGD-SPIELSTÄNDE", it = "NESSUN SALVATAGGIO SMERALDO", es = "NO HAY PARTIDAS DE ESMERALDA", br = "NENHUM SAVE DE EMERALD", pt = "NENHUM SAVE DE EMERALD"),
    "Co-op needs English Pokémon Emerald." to Tr(ja = "きょうりょくプレイには えいごばんの ポケットモンスター エメラルドが ひつようです", fr = "Le coop demande Pokémon Émeraude en anglais.", de = "Koop braucht Pokémon Smaragd auf Englisch.", it = "Il coop richiede Pokémon Smeraldo in inglese.", es = "El coop necesita Pokémon Esmeralda en inglés.", br = "O co-op precisa do Pokémon Emerald em inglês.", pt = "O co-op precisa do Pokémon Emerald em inglês."),
    "Save in the game once first" to Tr(ja = "さきに ゲームで いちど セーブして ください", fr = "Sauvegarde d'abord une fois dans le jeu", de = "Speichere zuerst einmal im Spiel", it = "Salva prima una volta nel gioco", es = "Guarda primero una vez en el juego", br = "Salve uma vez no jogo primeiro", pt = "Guarda primeiro uma vez no jogo"),
    "Paired with {0}" to Tr(ja = "{0}と ペアリングずみ", fr = "Associé à {0}", de = "Mit {0} gekoppelt", it = "Associato a {0}", es = "Vinculado con {0}", br = "Pareado com {0}", pt = "Emparelhado com {0}"),

    // The invite.
    "COPY LINK" to Tr(ja = "リンクを コピー", fr = "COPIER LE LIEN", de = "LINK KOPIEREN", it = "COPIA LINK", es = "COPIAR ENLACE", br = "COPIAR LINK", pt = "COPIAR LIGAÇÃO"),
    "SHARE LINK" to Tr(ja = "リンクを おくる", fr = "PARTAGER LE LIEN", de = "LINK TEILEN", it = "CONDIVIDI LINK", es = "COMPARTIR ENLACE", br = "COMPARTILHAR LINK", pt = "PARTILHAR LIGAÇÃO"),
    "Link copied" to Tr(ja = "リンクを コピーしました", fr = "Lien copié", de = "Link kopiert", it = "Link copiato", es = "Enlace copiado", br = "Link copiado", pt = "Ligação copiada"),
    "Play Emerald with me in PokeDaisy: {0}" to Tr(ja = "PokeDaisyで いっしょに エメラルドを あそぼう: {0}", fr = "Joue à Émeraude avec moi dans PokeDaisy : {0}", de = "Spiel Smaragd mit mir in PokeDaisy: {0}", it = "Gioca a Smeraldo con me in PokeDaisy: {0}", es = "Juega a Esmeralda conmigo en PokeDaisy: {0}", br = "Jogue Emerald comigo no PokeDaisy: {0}", pt = "Joga Emerald comigo no PokeDaisy: {0}"),
    "JOIN WITH A LINK" to Tr(ja = "リンクで さんか", fr = "REJOINDRE PAR LIEN", de = "MIT LINK BEITRETEN", it = "UNISCITI CON UN LINK", es = "UNIRSE CON ENLACE", br = "ENTRAR COM LINK", pt = "ENTRAR COM LIGAÇÃO"),
    "Paste an invite link" to Tr(ja = "しょうたいリンクを はる", fr = "Colle un lien d'invitation", de = "Einladungslink einfügen", it = "Incolla un link d'invito", es = "Pega un enlace de invitación", br = "Cole um link de convite", pt = "Cola uma ligação de convite"),
    "CONNECT" to Tr(ja = "せつぞく", fr = "SE CONNECTER", de = "VERBINDEN", it = "CONNETTI", es = "CONECTAR", br = "CONECTAR", pt = "LIGAR"),
    "SAME WI-FI" to Tr(ja = "おなじ Wi-Fi", fr = "MÊME WI-FI", de = "GLEICHES WLAN", it = "STESSO WI-FI", es = "MISMO WI-FI", br = "MESMO WI-FI", pt = "MESMO WI-FI"),
    "SEARCHING…" to Tr(ja = "さがしています…", fr = "RECHERCHE…", de = "SUCHE…", it = "RICERCA…", es = "BUSCANDO…", br = "PROCURANDO…", pt = "A PROCURAR…"),
    "BY IP" to Tr(ja = "IPで せつぞく", fr = "PAR IP", de = "PER IP", it = "PER IP", es = "POR IP", br = "POR IP", pt = "POR IP"),
    "YOUR ADDRESS" to Tr(ja = "あなたの アドレス", fr = "TON ADRESSE", de = "DEINE ADRESSE", it = "IL TUO INDIRIZZO", es = "TU DIRECCIÓN", br = "SEU ENDEREÇO", pt = "O TEU ENDEREÇO"),
    "PARTNER'S ADDRESS" to Tr(ja = "あいての アドレス", fr = "ADRESSE DU PARTENAIRE", de = "PARTNER-ADRESSE", it = "INDIRIZZO DEL PARTNER", es = "DIRECCIÓN DEL SOCIO", br = "ENDEREÇO DO PARCEIRO", pt = "ENDEREÇO DO PARCEIRO"),
    "No Wi-Fi address found" to Tr(ja = "Wi-Fiの アドレスが みつかりません", fr = "Aucune adresse Wi-Fi trouvée", de = "Keine WLAN-Adresse gefunden", it = "Nessun indirizzo Wi-Fi trovato", es = "No se encontró dirección Wi-Fi", br = "Nenhum endereço Wi-Fi encontrado", pt = "Nenhum endereço Wi-Fi encontrado"),
    "That isn't a valid address" to Tr(ja = "アドレスが ただしくありません", fr = "Cette adresse n'est pas valide", de = "Das ist keine gültige Adresse", it = "Questo indirizzo non è valido", es = "Esa dirección no es válida", br = "Esse endereço não é válido", pt = "Esse endereço não é válido"),

    // Pairing.
    "ACCEPT THIS PLAYER?" to Tr(ja = "このプレイヤーを うけいれますか?", fr = "ACCEPTER CE JOUEUR ?", de = "SPIELER AKZEPTIEREN?", it = "ACCETTI QUESTO GIOCATORE?", es = "¿ACEPTAR A ESTE JUGADOR?", br = "ACEITAR ESTE JOGADOR?", pt = "ACEITAR ESTE JOGADOR?"),
    "{0}  ID {1}" to Tr(ja = "{0}  ID {1}", fr = "{0}  ID {1}", de = "{0}  ID {1}", it = "{0}  ID {1}", es = "{0}  ID {1}", br = "{0}  ID {1}", pt = "{0}  ID {1}"),
    "ACCEPT" to Tr(ja = "うけいれる", fr = "ACCEPTER", de = "AKZEPTIEREN", it = "ACCETTA", es = "ACEPTAR", br = "ACEITAR", pt = "ACEITAR"),
    "DENY" to Tr(ja = "ことわる", fr = "REFUSER", de = "ABLEHNEN", it = "RIFIUTA", es = "RECHAZAR", br = "RECUSAR", pt = "RECUSAR"),
    "WAITING FOR YOUR PARTNER…" to Tr(ja = "あいてを まっています…", fr = "EN ATTENTE DU PARTENAIRE…", de = "WARTE AUF DEINEN PARTNER…", it = "IN ATTESA DEL PARTNER…", es = "ESPERANDO A TU SOCIO…", br = "AGUARDANDO SEU PARCEIRO…", pt = "À ESPERA DO TEU PARCEIRO…"),
    "PAIRED WITH {0}" to Tr(ja = "{0}と ペアリングしました", fr = "ASSOCIÉ À {0}", de = "MIT {0} GEKOPPELT", it = "ASSOCIATO A {0}", es = "VINCULADO CON {0}", br = "PAREADO COM {0}", pt = "EMPARELHADO COM {0}"),

    // Why it failed.
    "Your partner's PokeDaisy is a different version. Update both to the same one." to Tr(ja = "あいての PokeDaisyの バージョンが ちがいます。りょうほうを おなじものに アップデートして ください", fr = "Le PokeDaisy de ton partenaire n'est pas dans la même version. Mets les deux à jour.", de = "Das PokeDaisy deines Partners hat eine andere Version. Aktualisiert beide auf dieselbe.", it = "Il PokeDaisy del partner è di un'altra versione. Aggiornateli alla stessa.", es = "El PokeDaisy de tu socio es de otra versión. Actualizad los dos a la misma.", br = "O PokeDaisy do seu parceiro é de outra versão. Atualizem os dois para a mesma.", pt = "O PokeDaisy do teu parceiro é de outra versão. Atualizem os dois para a mesma."),
    "Your partner plays a different Emerald ROM." to Tr(ja = "あいては ちがう エメラルドROMで あそんでいます", fr = "Ton partenaire joue avec une autre ROM d'Émeraude.", de = "Dein Partner spielt ein anderes Smaragd-ROM.", it = "Il partner usa un'altra ROM di Smeraldo.", es = "Tu socio juega con otra ROM de Esmeralda.", br = "Seu parceiro joga com outra ROM de Emerald.", pt = "O teu parceiro joga com outra ROM de Emerald."),
    "This save is already paired. Unpair it in PARTNERS first." to Tr(ja = "この セーブは ペアリングずみです。さきに パートナーで かいじょして ください", fr = "Cette sauvegarde est déjà associée. Dissocie-la d'abord dans PARTENAIRES.", de = "Dieser Spielstand ist schon gekoppelt. Löse ihn zuerst unter PARTNER.", it = "Questo salvataggio è già associato. Dissocialo prima in PARTNER.", es = "Esta partida ya está vinculada. Desvincúlala antes en SOCIOS.", br = "Este save já está pareado. Desfaça o par antes em PARCEIROS.", pt = "Este save já está emparelhado. Desfaz o par primeiro em PARCEIROS."),
    "The other player said no." to Tr(ja = "あいては ことわりました", fr = "L'autre joueur a refusé.", de = "Der andere Spieler hat abgelehnt.", it = "L'altro giocatore ha rifiutato.", es = "El otro jugador ha dicho que no.", br = "O outro jogador recusou.", pt = "O outro jogador recusou."),
    "Couldn't reach your partner. Check the internet and try again." to Tr(ja = "あいてに つながりません。インターネットを かくにんして もういちど どうぞ", fr = "Impossible de joindre ton partenaire. Vérifie Internet et réessaie.", de = "Dein Partner ist nicht erreichbar. Prüfe das Internet und versuche es nochmal.", it = "Impossibile raggiungere il partner. Controlla Internet e riprova.", es = "No se pudo contactar con tu socio. Revisa Internet e inténtalo de nuevo.", br = "Não foi possível falar com seu parceiro. Verifique a internet e tente de novo.", pt = "Não foi possível contactar o teu parceiro. Verifica a internet e tenta outra vez."),
    "No answer. Try again." to Tr(ja = "へんじが ありません。もういちど ためして ください", fr = "Pas de réponse. Réessaie.", de = "Keine Antwort. Versuche es nochmal.", it = "Nessuna risposta. Riprova.", es = "Sin respuesta. Inténtalo de nuevo.", br = "Sem resposta. Tente de novo.", pt = "Sem resposta. Tenta outra vez."),

    // PARTNERS and RELAY.
    "UNPAIR" to Tr(ja = "かいじょ", fr = "DISSOCIER", de = "TRENNEN", it = "DISSOCIA", es = "DESVINCULAR", br = "DESFAZER PAR", pt = "DESFAZER PAR"),
    "Unpair {0}? You'll need a new invite to play together again." to Tr(ja = "{0}との ペアを かいじょしますか? また あそぶには あたらしい しょうたいが ひつようです", fr = "Dissocier {0} ? Il faudra une nouvelle invitation pour rejouer ensemble.", de = "{0} trennen? Zum erneuten Zusammenspielen brauchst du eine neue Einladung.", it = "Dissociare {0}? Serve un nuovo invito per giocare di nuovo insieme.", es = "¿Desvincular a {0}? Necesitaréis una invitación nueva para volver a jugar juntos.", br = "Desfazer o par com {0}? Vocês vão precisar de um novo convite para jogar juntos de novo.", pt = "Desfazer o par com {0}? Vão precisar de um novo convite para jogar juntos outra vez."),
    "NO PARTNERS YET" to Tr(ja = "パートナーは まだ いません", fr = "AUCUN PARTENAIRE", de = "NOCH KEINE PARTNER", it = "NESSUN PARTNER", es = "SIN SOCIOS AÚN", br = "NENHUM PARCEIRO", pt = "NENHUM PARCEIRO"),
    "USE BUILT-IN" to Tr(ja = "ないぞうを つかう", fr = "UTILISER L'INTÉGRÉ", de = "EINGEBAUTES NUTZEN", it = "USA QUELLO INTEGRATO", es = "USAR EL INTEGRADO", br = "USAR O INTEGRADO", pt = "USAR O INTEGRADO"),
    "That isn't an https:// address" to Tr(ja = "https:// の アドレスでは ありません", fr = "Ce n'est pas une adresse https://", de = "Das ist keine https://-Adresse", it = "Non è un indirizzo https://", es = "No es una dirección https://", br = "Não é um endereço https://", pt = "Não é um endereço https://"),
    "A relay helps two devices find each other. Keep the built-in one unless a friend runs their own." to Tr(ja = "リレーは 2だいの きかいが おたがいを みつけるのを たすけます。ともだちが じぶんで もっていないかぎり ないぞうの ままで ください", fr = "Un relais aide deux appareils à se trouver. Garde l'intégré, sauf si un ami en héberge un.", de = "Ein Relais hilft zwei Geräten, sich zu finden. Lass das eingebaute, außer ein Freund betreibt ein eigenes.", it = "Un relay aiuta due dispositivi a trovarsi. Tieni quello integrato, salvo che un amico ne gestisca uno.", es = "Un retransmisor ayuda a que dos dispositivos se encuentren. Deja el integrado salvo que un amigo tenga el suyo.", br = "Um relé ajuda dois aparelhos a se encontrarem. Mantenha o integrado, a menos que um amigo tenha o dele.", pt = "Um relé ajuda dois aparelhos a encontrarem-se. Mantém o integrado, a não ser que um amigo tenha o dele."),
)

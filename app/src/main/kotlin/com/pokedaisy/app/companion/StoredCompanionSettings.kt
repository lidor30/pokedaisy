package com.pokedaisy.app.companion

/** Persisted choices used by the shared settings UI, independent of a running game's actions and capabilities.
 * Defaults retain the existing UI contract for hosts which do not expose every choice. */
interface StoredCompanionSettings {
    val quickMenuActions: Set<QuickAction> get() = QuickAction.entries.toSet()
    fun setQuickMenuActions(actions: Set<QuickAction>) {}
    val quickMenuStyle: QuickMenuStyle get() = QuickMenuStyle.RING
    fun setQuickMenuStyle(style: QuickMenuStyle) {}
    val quickMenuLabels: Boolean get() = true
    fun setQuickMenuLabels(on: Boolean) {}
    /** Fast-forward speed cap. 0 = unlimited. */
    val ffMaxSpeed: Float
    fun setFfMaxSpeed(v: Float)
    val battleSpeed: Float get() = 0f
    fun setBattleSpeed(v: Float) {}
    val textSpeed: Float get() = 0f
    fun setTextSpeed(v: Float) {}
    val menuSpeed: Float get() = 0f
    fun setMenuSpeed(v: Float) {}
    /** BIKE / SURF SPEED - see [com.pokedaisy.app.Prefs.bikeSpeed]; [CompanionSettings.fieldMovesReadable]: the game's bike / surf state
     * is read (FireRed / Emerald engines with their player struct found, Ruby / Sapphire by sprite). */
    val bikeSpeed: Float get() = 0f
    fun setBikeSpeed(v: Float) {}
    val surfSpeed: Float get() = 0f
    fun setSurfSpeed(v: Float) {}
    val hmFree: Boolean get() = false
    fun setHmFree(on: Boolean) {}
    val expShare: com.pokedaisy.app.companion.data.ExpShare.Mode get() = com.pokedaisy.app.companion.data.ExpShare.Mode.OFF
    fun setExpShare(mode: com.pokedaisy.app.companion.data.ExpShare.Mode) {}
    val levelCap: Boolean get() = false
    fun setLevelCap(on: Boolean) {}
    val autoRun: Boolean get() = false
    fun setAutoRun(on: Boolean) {}
    /** RUN INDOORS - see [com.pokedaisy.app.Prefs.runIndoors]; [CompanionSettings.runIndoorsSupported]: a FireRed / Emerald engine the
     * field reads know (Ruby / Sapphire tell indoor maps by their type, which other things read too). */
    val runIndoors: Boolean get() = false
    fun setRunIndoors(on: Boolean) {}
    /** WILD ENCOUNTERS: the percent left - see [com.pokedaisy.app.Prefs.wildEncounters]; [CompanionSettings.wildEncountersSupported]: the
     * game's switch was found in the ROM ([com.pokedaisy.app.companion.data.WildEncounters]) and its field is read. */
    val wildEncounters: Int get() = 100
    fun setWildEncounters(percent: Int) {}
    val reusableTms: Boolean get() = false
    fun setReusableTms(on: Boolean) {}
    /** INSTANT TEXT - see [com.pokedaisy.app.Prefs.instantText]; [CompanionSettings.instantTextSupported]: the ROM's text code is known
     * ([com.pokedaisy.app.companion.data.InstantText]). */
    val instantText: Boolean get() = false
    fun setInstantText(on: Boolean) {}
    /** TRADE EVOLUTIONS - see [com.pokedaisy.app.Prefs.tradeEvolutions]; [CompanionSettings.tradeEvolutionsSupported]: this game's
     * evolution code is known ([com.pokedaisy.app.companion.data.tradeEvoFor]). */
    val tradeEvolutions: Boolean get() = false
    fun setTradeEvolutions(on: Boolean) {}
    val rivalLook: Boolean get() = true
    fun setRivalLook(on: Boolean) {}
    val follower: Boolean get() = false
    fun setFollower(on: Boolean) {}
    val dayNight: Boolean get() = false
    fun setDayNight(on: Boolean) {}
    /** Whether fast-forward is currently toggled on (independent of the hold trigger). */
    val ffToggled: Boolean
    fun setFfToggled(on: Boolean)
    /** What fast-forward sounds like ([FfMusicMode]). */
    val ffMusicMode: FfMusicMode
    fun setFfMusicMode(mode: FfMusicMode)
    /** Where fast-forward applies ([FfMode]). */
    val ffMode: FfMode
    fun setFfMode(mode: FfMode)
    /** REWIND on / off ([Prefs.rewind]); hosts without a game leave it off. */
    val rewind: Boolean get() = false
    fun setRewind(on: Boolean) {}
    /** SETTINGS > TOUCH CONTROLS - see [com.pokedaisy.app.Prefs.touchControlsStyle]. */
    val touchStyle: TouchControlsStyle
    fun setTouchStyle(style: TouchControlsStyle)
    /** Whether the hotkeys fire at all ([com.pokedaisy.app.Prefs.hotkeysEnabled]). */
    val hotkeysEnabled: Boolean
    fun setHotkeysEnabled(on: Boolean)
    /** Whether battle-only strategic hints (move effectiveness chips, foe
     * Weak-to/Resists/Immune-to) are shown — see [Prefs.showHints]. */
    val showHints: Boolean
    fun setShowHints(on: Boolean)
    /** The app's language setting ("AUTO" or an AppLanguage code) - see [Prefs.appLanguage]. */
    val appLanguage: String get() = com.pokedaisy.app.companion.i18n.LANGUAGE_AUTO
    fun setAppLanguage(code: String) {}
    /** Saves DARK MODE (an ordinal of DarkModeSetting, see [Prefs.darkMode]); the look itself is DarkMode's state. */
    fun setDarkMode(v: Int) {}
    fun setAppFont(v: Int) {}
    /** Whether battle INFO has a STATS page with the foe's IVs - see [Prefs.showFoeIvs]. */
    val showFoeIvs: Boolean get() = false
    fun setShowFoeIvs(on: Boolean) {}
    /** BATTLE INFO - see [com.pokedaisy.app.Prefs.battleInfo]. */
    val battleInfo: Boolean get() = true
    fun setBattleInfo(on: Boolean) {}
    /** BATTLE VIEW - see [com.pokedaisy.app.Prefs.battleInfoFirst]. */
    val battleInfoFirst: Boolean get() = false
    fun setBattleInfoFirst(on: Boolean) {}
    /** BATTLE VIEW > SUGGESTIONS - see [com.pokedaisy.app.Prefs.battleSuggestionsFirst]. */
    val battleSuggestionsFirst: Boolean get() = false
    fun setBattleSuggestionsFirst(on: Boolean) {}
    /** CHALLENGE ICONS - see [com.pokedaisy.app.Prefs.raIcons]. */
    val raIcons: Boolean get() = true
    fun setRaIcons(on: Boolean) {}
    /** PARTY PICTURES - see [Prefs.partyPictures]. */
    val partyPictures: Boolean get() = false
    fun setPartyPictures(on: Boolean) {}
    /** ONE-TAP MOVES - see [Prefs.oneTapMoves]. */
    val oneTapMoves: Boolean get() = false
    fun setOneTapMoves(on: Boolean) {}
    /** BATTLE STYLE - see [Prefs.battleStyle]. */
    val battleStyle: BattleStyle get() = BattleStyle.GEN4
    fun setBattleStyle(style: BattleStyle) {}
    /** Whether battle INFO shows FOE DETAILS - see [Prefs.foeDetails]. */
    val foeDetails: Boolean get() = false
    fun setFoeDetails(on: Boolean) {}
    /** See [Prefs.battleFocus]. */
    val battleFocus: Boolean get() = false
    fun setBattleFocus(on: Boolean) {}
    /** Whether a launch picks up where the game was left - see [Prefs.autoResume]; off, CLOSE GAME warns. */
    val autoResume: Boolean get() = true
    fun setAutoResume(on: Boolean) {}
    /** Whether the companion's buttons play the game's click - see [Prefs.clickSound]. */
    val clickSound: Boolean
    fun setClickSound(on: Boolean)
    /** Whether the top screen shows [com.pokedaisy.app.companion.ui.GameStatusBar]
     * above the game - see [Prefs.statusBar]. */
    val statusBar: Boolean
    fun setStatusBar(on: Boolean)
    /** SOUND > GAME VOLUME, in percent of the device's volume (100 = the device's) - see [Prefs.gameVolume]. */
    val gameVolume: Int get() = 100
    fun setGameVolume(percent: Int) {}
    /** SOUND > MUTE GAME (also the MUTE hotkey) - see [Prefs.gameMuted]. */
    val gameMuted: Boolean get() = false
    fun setGameMuted(on: Boolean) {}
    /** SOUND > AUDIO EFFECTS: the device's audio effects on the game's sound, from its next start - see [Prefs.audioEffects]. */
    val audioEffects: Boolean get() = false
    fun setAudioEffects(on: Boolean) {}
    /** How the game sits on the top screen: 3:2, stretched or WIDE - see [Prefs.gameAspect]. */
    val gameAspect: GameAspect
    fun setGameAspect(aspect: GameAspect)
    /** APP > TIPS ([com.pokedaisy.app.Prefs.tips], ON): a card now and then about a feature the player hasn't found. */
    val tips: Boolean get() = true
    fun setTips(on: Boolean) {}
    /** The GBA LCD's colours on the game - see [Prefs.gbaColors]. */
    val gbaColors: Boolean
    fun setGbaColors(on: Boolean)
    /** The unlit GBA screen on the game - see [Prefs.unlitScreen]. */
    val unlitScreen: Boolean get() = false
    fun setUnlitScreen(on: Boolean) {}
    /** The screen effect over the game - see [Prefs.screenFilter]. */
    val screenFilter: ScreenFilter
    fun setScreenFilter(filter: ScreenFilter)
    /** Whether SHADERS also draw over the companion - see [Prefs.companionShaders]. */
    val companionShaders: Boolean
    fun setCompanionShaders(on: Boolean)
    /** How strong the grid filters' grid is - see [Prefs.gridStrength]. */
    val gridStrength: GridStrength
    fun setGridStrength(strength: GridStrength)
    /** The edge-smoothing upscaler on the game - see [Prefs.smoothing]. */
    val smoothing: Smoothing
    fun setSmoothing(mode: Smoothing)
    /** The CHEATS master switch - see [com.pokedaisy.app.Prefs.cheatsEnabled]. Applies at once. */
    val cheatsEnabled: Boolean get() = false
    fun setCheatsEnabled(on: Boolean) {}
    /** Which of [COMPANION_TABS] sit in the tab bar (at most [MAX_BAR_TABS]);
     * the rest open from the MORE tab. See [Prefs.companionTabs]. */
    val companionTabs: List<String>
    fun setCompanionTabs(tabs: List<String>)
    /** Whether the GUIDE's "written with AI help" notice was accepted for [game]
     * (a [com.pokedaisy.app.companion.data.GameKind] name). */
    fun guideNoticeAccepted(game: String): Boolean
    fun acceptGuideNotice(game: String)
}

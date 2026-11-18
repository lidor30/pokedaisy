package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.i18n.L10n

/** Concrete persistence delegate. Hosts compose it with game actions rather than inheriting implementation.
 * Changes publish only after persistence; the owning host installs its notification callback before exposing UI. */
class PrefsCompanionSettings(
    private val prefs: Prefs,
    private val languageRomCode: () -> String? = { null },
    private val allowDisableResume: () -> Boolean = { true },
) : StoredCompanionSettings {
    enum class Change { PLAYBACK, FIELD_OPTIONS }
    var changed: (Change) -> Unit = {}
    private var hudRevision by mutableIntStateOf(0)
    override val fieldHud get() = hudRevision.let { prefs.fieldHud }
    override fun setFieldHud(on: Boolean) { prefs.fieldHud = on; hudRevision++ }
    override val fieldHudStyle get() = hudRevision.let { prefs.fieldHudStyle }
    override fun setFieldHudStyle(style: FieldHudStyle) { prefs.fieldHudStyle = style; hudRevision++ }
    override val quickMenuActions get() = prefs.quickMenuActions
    override fun setQuickMenuActions(actions: Set<QuickAction>) { prefs.quickMenuActions = actions }
    override val quickMenuStyle get() = prefs.quickMenuStyle
    override fun setQuickMenuStyle(style: QuickMenuStyle) { prefs.quickMenuStyle = style }
    override val quickMenuLabels get() = prefs.quickMenuLabels
    override fun setQuickMenuLabels(on: Boolean) { prefs.quickMenuLabels = on }
    override val showHints get() = prefs.showHints
    override fun setShowHints(on: Boolean) { prefs.showHints = on }
    override val showFoeIvs get() = prefs.showFoeIvs
    override fun setShowFoeIvs(on: Boolean) { prefs.showFoeIvs = on }
    override val battleInfo get() = prefs.battleInfo
    override fun setBattleInfo(on: Boolean) { prefs.battleInfo = on }
    override val pokemonPictures get() = prefs.pokemonPictures
    override fun setPokemonPictures(on: Boolean) {
        prefs.pokemonPictures = on
        com.pokedaisy.app.companion.ui.PokemonPicturesSwitch.flipped()
    }
    override val oneTapMoves get() = prefs.oneTapMoves
    override fun setOneTapMoves(on: Boolean) { prefs.oneTapMoves = on }
    override val battleInfoFirst get() = prefs.battleInfoFirst
    override fun setBattleInfoFirst(on: Boolean) { prefs.battleInfoFirst = on }
    override val battleSuggestionsFirst get() = prefs.battleSuggestionsFirst
    override fun setBattleSuggestionsFirst(on: Boolean) { prefs.battleSuggestionsFirst = on }
    override val battleFocus get() = prefs.battleFocus
    override fun setBattleFocus(on: Boolean) { prefs.battleFocus = on }
    override val foeDetails get() = prefs.foeDetails
    override fun setFoeDetails(on: Boolean) { prefs.foeDetails = on }
    override val cheatsEnabled get() = prefs.cheatsEnabled
    override fun setCheatsEnabled(on: Boolean) { prefs.cheatsEnabled = on }
    override val rewind get() = prefs.rewind
    override fun setRewind(on: Boolean) { prefs.rewind = on }
    override val hotkeysEnabled get() = prefs.hotkeysEnabled
    override fun setHotkeysEnabled(on: Boolean) { prefs.hotkeysEnabled = on }
    override val clickSound get() = prefs.clickSound
    override fun setClickSound(on: Boolean) { prefs.clickSound = on }
    override val statusBar get() = prefs.statusBar
    override fun setStatusBar(on: Boolean) { prefs.statusBar = on }
    override val gbaColors get() = prefs.gbaColors
    override fun setGbaColors(on: Boolean) { prefs.gbaColors = on }
    override val companionShaders get() = prefs.companionShaders
    override fun setCompanionShaders(on: Boolean) { prefs.companionShaders = on }
    override val unlitScreen get() = prefs.unlitScreen
    override fun setUnlitScreen(on: Boolean) { prefs.unlitScreen = on }
    override val audioEffects get() = prefs.audioEffects
    override fun setAudioEffects(on: Boolean) { prefs.audioEffects = on }
    override val tips get() = prefs.tips
    override fun setTips(on: Boolean) { prefs.tips = on }
    override val raIcons get() = prefs.raIcons
    override fun setRaIcons(on: Boolean) { prefs.raIcons = on }
    override val dayNight get() = prefs.dayNight
    override fun setDayNight(on: Boolean) { prefs.dayNight = on }
    override val follower get() = prefs.follower
    override fun setFollower(on: Boolean) { prefs.follower = on }
    override val rivalLook get() = prefs.rivalLook
    override fun setRivalLook(on: Boolean) { prefs.rivalLook = on }
    override val tradeEvolutions get() = prefs.tradeEvolutions
    override fun setTradeEvolutions(on: Boolean) { prefs.tradeEvolutions = on }
    override val hmFree get() = prefs.hmFree
    override fun setHmFree(on: Boolean) { prefs.hmFree = on; changed(Change.FIELD_OPTIONS) }
    override val expShare get() = com.pokedaisy.app.companion.data.ExpShare.Mode.of(prefs.expShare)
    override fun setExpShare(mode: com.pokedaisy.app.companion.data.ExpShare.Mode) { prefs.expShare = mode.id; changed(Change.FIELD_OPTIONS) }
    override val levelCap get() = prefs.levelCap
    override fun setLevelCap(on: Boolean) { prefs.levelCap = on; changed(Change.FIELD_OPTIONS) }
    override val autoRun get() = prefs.autoRun
    override fun setAutoRun(on: Boolean) { prefs.autoRun = on; changed(Change.FIELD_OPTIONS) }
    override val runIndoors get() = prefs.runIndoors
    override fun setRunIndoors(on: Boolean) { prefs.runIndoors = on; changed(Change.FIELD_OPTIONS) }
    override val instantText get() = prefs.instantText
    override fun setInstantText(on: Boolean) { prefs.instantText = on; changed(Change.FIELD_OPTIONS) }
    override val reusableTms get() = prefs.reusableTms
    override fun setReusableTms(on: Boolean) { prefs.reusableTms = on; changed(Change.FIELD_OPTIONS) }
    override val ffMaxSpeed get() = prefs.ffMaxSpeed
    override fun setFfMaxSpeed(v: Float) { if (v in FF_SPEEDS) { prefs.ffMaxSpeed = v; changed(Change.PLAYBACK) } }
    override val battleSpeed get() = prefs.battleSpeed.takeIf { it in SCENE_SPEEDS } ?: 0f
    override fun setBattleSpeed(v: Float) { if (v in SCENE_SPEEDS) { prefs.battleSpeed = v; changed(Change.PLAYBACK) } }
    override val textSpeed get() = prefs.textSpeed.takeIf { it in SCENE_SPEEDS } ?: 0f
    override fun setTextSpeed(v: Float) { if (v in SCENE_SPEEDS) { prefs.textSpeed = v; changed(Change.PLAYBACK) } }
    override val menuSpeed get() = prefs.menuSpeed.takeIf { it in SCENE_SPEEDS } ?: 0f
    override fun setMenuSpeed(v: Float) { if (v in SCENE_SPEEDS) { prefs.menuSpeed = v; changed(Change.PLAYBACK) } }
    override val bikeSpeed get() = prefs.bikeSpeed.takeIf { it in SCENE_SPEEDS } ?: 0f
    override fun setBikeSpeed(v: Float) { if (v in SCENE_SPEEDS) { prefs.bikeSpeed = v; changed(Change.PLAYBACK) } }
    override val surfSpeed get() = prefs.surfSpeed.takeIf { it in SCENE_SPEEDS } ?: 0f
    override fun setSurfSpeed(v: Float) { if (v in SCENE_SPEEDS) { prefs.surfSpeed = v; changed(Change.PLAYBACK) } }
    override val ffToggled get() = prefs.ffToggled
    override fun setFfToggled(on: Boolean) { prefs.ffToggled = on; changed(Change.PLAYBACK) }
    override val ffMode get() = prefs.ffMode
    override fun setFfMode(mode: FfMode) { prefs.ffMode = mode; changed(Change.PLAYBACK) }
    override val ffMusicMode get() = prefs.ffMusicMode
    override fun setFfMusicMode(mode: FfMusicMode) { prefs.ffMusicMode = mode; changed(Change.PLAYBACK) }
    override val gameVolume get() = prefs.gameVolume
    override fun setGameVolume(percent: Int) { prefs.gameVolume = percent; changed(Change.PLAYBACK) }
    override val gameMuted get() = prefs.gameMuted
    override fun setGameMuted(on: Boolean) { prefs.gameMuted = on; changed(Change.PLAYBACK) }
    override val wildEncounters get() = prefs.wildEncounters
    override fun setWildEncounters(percent: Int) { prefs.wildEncounters = percent; changed(Change.FIELD_OPTIONS) }
    override val screenFilter get() = prefs.screenFilter
    override fun setScreenFilter(filter: ScreenFilter) { prefs.screenFilter = filter }
    override val gridStrength get() = prefs.gridStrength
    override fun setGridStrength(strength: GridStrength) { prefs.gridStrength = strength }
    override val smoothing get() = prefs.smoothing
    override fun setSmoothing(mode: Smoothing) { prefs.smoothing = mode }
    override val gameAspect get() = prefs.gameAspect
    override fun setGameAspect(aspect: GameAspect) { prefs.gameAspect = aspect }
    override val autoResume get() = prefs.autoResume
    override fun setAutoResume(on: Boolean) {
        if (on != autoResume && (on || allowDisableResume())) prefs.autoResume = on
    }
    override val appLanguage get() = prefs.appLanguage
    override fun setAppLanguage(code: String) { prefs.appLanguage = code; L10n.apply(code, languageRomCode()) }
    override fun setAppFont(v: Int) { prefs.appFont = v }
    override fun setDarkMode(v: Int) { prefs.darkMode = v }
    override val companionTabs get() = prefs.companionTabs
    override fun setCompanionTabs(tabs: List<String>) { prefs.companionTabs = tabs }
    override fun guideNoticeAccepted(game: String) = prefs.guideNoticeAccepted(game)
    override fun acceptGuideNotice(game: String) = prefs.acceptGuideNotice(game)
    override val touchStyle get() = prefs.touchControlsStyle
    override fun setTouchStyle(style: TouchControlsStyle) { prefs.touchControlsStyle = style }
    override val battleStyle get() = BattleStyle.of(prefs.battleStyle)
    override fun setBattleStyle(style: BattleStyle) { prefs.battleStyle = style.id }
}

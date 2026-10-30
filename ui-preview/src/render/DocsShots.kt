@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package render

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.DocsApi
import com.pokedaisy.app.DocsStore
import com.pokedaisy.app.SettingsActivity
import com.pokedaisy.app.companion.AchievementGame
import com.pokedaisy.app.companion.Achievement
import com.pokedaisy.app.companion.AchievementPopup
import com.pokedaisy.app.companion.AchievementsState
import com.pokedaisy.app.companion.BatteryStatus
import com.pokedaisy.app.companion.CompanionAchievements
import com.pokedaisy.app.companion.DeviceBattery
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.GameGuide
import com.pokedaisy.app.companion.data.GuideEntry
import com.pokedaisy.app.companion.data.GuidePage
import com.pokedaisy.app.companion.data.GuideSection
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.speciesName
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.DocsSource
import com.pokedaisy.app.companion.ui.DocsSources
import com.pokedaisy.app.companion.ui.GuideScreen
import com.pokedaisy.app.companion.ui.GuideSource
import com.pokedaisy.app.companion.ui.GuideUiState
import com.pokedaisy.app.companion.ui.GameBackdrop
import com.pokedaisy.app.companion.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * The docs' screenshots (`gradle docsShots` -> docs/manual/shots): the app's real screens, but never a real
 * Pokémon, move, item or place name and never a game's art (the project's rule for anything public). The
 * fake save's species, moves, items and places are swapped for invented ones ([disguised]), party icons are
 * the invented creatures from website/src/lib/creatures.js (`npm run docs-art`), and no ROM art is made:
 * the docs render runs on a scratch folder of its own, with only the invented region map in it.
 */
val docsMode: Boolean get() = System.getProperty("docs") == "true"

object Invented {
    private val ALL = listOf("emberpup", "galefinch", "voltmouse", "pebblon", "sproutling", "thornlet", "bubblefin", "wisplet", "mothwick", "cragmaw")
    // The preview save's species (Main.kt) -> a creature of the same types.
    private val BY_SPECIES = mapOf(
        4 to "emberpup", 16 to "galefinch", 7 to "bubblefin", 1 to "thornlet", 74 to "pebblon", 95 to "cragmaw",
        111 to "wisplet", 27 to "voltmouse", 104 to "mothwick", 25 to "voltmouse",
    )

    fun creature(species: Int): String = BY_SPECIES[species] ?: ALL[Math.floorMod(species, ALL.size)]
    fun name(species: Int): String = creature(species).uppercase()

    private val MOVES = mapOf(
        "scratch" to "PAW SWIPE", "growl" to "WARY GLARE", "ember" to "CINDER SPIT", "metal claw" to "IRON NIP",
        "tackle" to "BODY BUMP", "sand-attack" to "DUST FLICK", "gust" to "BREEZE CUT", "tail whip" to "TAIL FLICK",
        "bubble" to "FIZZ POP", "water gun" to "SPLASH JET", "vine whip" to "BRAMBLE LASH",
    )
    fun move(name: String): String = MOVES[name.lowercase()] ?: name

    private val ITEMS = mapOf("potion" to "SODA SPRAY", "poké ball" to "DAISY BALL", "poke ball" to "DAISY BALL", "oak's parcel" to "SEALED LETTER")
    fun item(name: String): String = ITEMS[name.lowercase()] ?: name

    private val PLACES = mapOf("pallet town" to "DAISY TOWN")
    fun place(name: String): String = PLACES[name.lowercase()] ?: name

    /** A real species' name as the save shows it -> its creature's (move verdicts name the foe). */
    fun renamed(name: String): String =
        (BY_SPECIES.keys + (1..151)).firstOrNull { speciesName(it).equals(name, ignoreCase = true) }?.let(::name) ?: name
}

/** The docs' party icon for [species]: an invented creature's sheet, relative to the render's assets folder. */
fun creatureIcon(species: Int): String? =
    System.getProperty("monIcons").orEmpty().takeIf { it.isNotBlank() }?.let { "$it/${Invented.creature(species)}.png" }

fun MoveView.disguised() = copy(name = Invented.move(name), vs = vs.map { it.copy(vsName = Invented.renamed(it.vsName)) })

fun MonView.disguised() = copy(
    name = if (isEgg) name else Invented.name(species),
    iconAsset = creatureIcon(species),
    moves = moves.map { it.disguised() },
    heldItem = heldItem?.let(Invented::item),
)

/** [this] with every species, move, item and place name swapped for an invented one. */
fun SnapshotView.disguised() = copy(
    party = party.map { it.disguised() },
    battlePlayer = battlePlayer.map { it.disguised() },
    battleOpponent = battleOpponent.map { it.disguised() },
    enemyParty = enemyParty.map { it.disguised() },
    // Ids no game has, so the bag can't look the real names up by id; no ball pocket (its tab is named for them).
    items = items.mapIndexed { i, it ->
        it.copy(
            itemId = 9000 + i, name = Invented.item(it.name),
            description = if (it.description.isEmpty()) "" else "A fizzy spray that brings back a little HP.",
            pocket = if (it.pocket == com.pokedaisy.app.companion.data.POCKET_POKE_BALLS) com.pokedaisy.app.companion.data.POCKET_ITEMS else it.pocket,
        )
    },
    location = location.copy(mapSecName = Invented.place(location.mapSecName)),
)

/** No ROM art in the docs: a scratch folder of their own, holding only the invented region map. */
fun installDocsArt() {
    val manual = File(System.getProperty("docsDir").orEmpty())
    val dir = RomArt.dir(android.content.Context().filesDir)
    dir.deleteRecursively()
    val map = File(manual, "art/regionmap.png")
    if (map.isFile) map.copyTo(File(dir, "regionmap/kanto.png").apply { parentFile.mkdirs() }, overwrite = true)
    else println("docs: no art/regionmap.png (run npm run docs-art in website/): the MAP shot has no picture")
}

/** The manual from the website's local build (npm run build), its pictures from docs/manual. */
class LocalDocsSource(private val api: File, private val manualDir: File) : DocsSource {
    private val manual by lazy { DocsApi.parse(api.readText()) }
    override fun cached() = manual
    override fun refresh() = DocsStore.Refresh.Unchanged
    override fun image(manual: DocsApi.Manual, image: DocsApi.Block.Image) =
        File(manualDir, image.src.removePrefix("/docs/")).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
}

fun installDocsSource(): Boolean {
    val api = File(System.getProperty("docsApi").orEmpty())
    if (!api.isFile) {
        println("docs: no $api (run npm run build in website/): the HELP shots are skipped")
        return false
    }
    DocsSources.override = LocalDocsSource(api, File(System.getProperty("docsDir").orEmpty()))
    return true
}

/** A guide of our own for the GUIDE shot: the real pages name the games' places and items. */
val DOCS_GUIDE = GameGuide(
    verified = false,
    pages = listOf(
        GuidePage(
            "TIPS",
            listOf(
                GuideSection(
                    "GETTING AROUND",
                    listOf(
                        GuideEntry("Running", "Hold B while you walk. The SPEED SHOES in DAISY TOWN make it faster.", hint = "Try holding a button while you walk."),
                        GuideEntry("Healing for free", "Every town's red-roofed CARE HALL heals your whole team.", hint = "Look for a red roof."),
                        GuideEntry("Catching tips", "A creature at low HP with a status like SLEEP is much easier to catch.", hint = "Wear it down first."),
                    ),
                ),
            ),
        ),
        GuidePage(
            "WHERE IS",
            listOf(
                GuideSection(
                    "USEFUL THINGS",
                    listOf(
                        GuideEntry("The SPEED SHOES", "Talk to the gardener east of DAISY TOWN's square.", hint = "Someone in your home town."),
                        GuideEntry("A fishing rod", "The old sailor on MEADOW PIER gives one away.", hint = "Near the water."),
                    ),
                ),
            ),
        ),
        GuidePage(
            "STUCK?",
            listOf(
                GuideSection(
                    "THE ROAD NORTH",
                    listOf(
                        GuideEntry("A tree blocks the path", "Win the badge in PETAL CITY first: its leader teaches you how to clear it.", hint = "Someone in the next city can help."),
                    ),
                ),
            ),
        ),
    ),
)

/** Invented achievements for the ACHIEVEMENTS shot (a real set's titles are its authors'). */
class DocsAchievements : CompanionAchievements {
    private fun a(id: Int, title: String, desc: String, points: Int, unlocked: Boolean, section: String, progress: String = "", pct: Float = 0f) =
        Achievement(id, title, desc, points, unlocked, section, null, null, progress, pct, rarity = 40f)
    override val state = MutableStateFlow(
        AchievementsState(
            user = "DaisyFan",
            game = AchievementGame(1, "Daisy Quest", "0123456789abcdef", total = 6, unlocked = 2, points = 60, pointsUnlocked = 15),
            achievements = listOf(
                a(1, "First Friend", "Catch your first creature", 5, true, "RECENTLY UNLOCKED"),
                a(2, "Fresh Petals", "Win your first trainer battle", 10, true, "RECENTLY UNLOCKED"),
                a(3, "Collector", "Catch 30 different creatures", 10, false, "ALMOST THERE", "24/30", 80f),
                a(4, "Petal Badge", "Beat the leader of PETAL CITY", 10, false, "LOCKED"),
                a(5, "Long Walk", "Walk 10,000 steps", 5, false, "LOCKED"),
                a(6, "Island Champion", "Become the champion of DAISY ISLE", 20, false, "LOCKED"),
            ),
        ),
    )
    override val popups = emptyFlow<AchievementPopup>()
    override fun retry() {}
}

/** The companion on the Thor's bottom screen, like Main.kt's companion() but over a disguised save. */
fun docsCompanion(
    tab: String,
    mon: Int? = null,
    oneTap: Boolean = false,
    achievements: CompanionAchievements? = null,
    popup: AchievementPopup? = null,
    snap: () -> SnapshotView = { snapshot().withBench() },
): () -> (@Composable () -> Unit) = {
    com.pokedaisy.app.companion.ui.OptionColors.inGame = true
    previewCapture = com.pokedaisy.app.companion.KeyCapture()
    DeviceBattery.status.value = BatteryStatus(76, charging = false)
    val sv = snap().disguised()
    val back = com.pokedaisy.app.companion.ui.CompanionBack().also { previewBack = it }
    val content: @Composable () -> Unit = {
        CompanionScreen(
            sv, FakeSlots(), FakeSettings(true, oneTap = oneTap), FakeBattleInput(false), initialTab = tab, initialMonIndex = mon,
            back = back, achievements = achievements, initialPopup = popup,
        )
    }
    content
}

/**
 * Every docs screenshot. Top-screen ones come from Main.kt's list ([all]) under the docs' names; the
 * companion ones are built here over the disguised save.
 */
fun docsShots(all: List<Shot>, bw: Int, bh: Int, bd: Float, tw: Int, th: Int, td: Float): List<Shot> {
    val help = installDocsSource()
    // Main.kt's top-screen shots the docs reuse: its name -> the docs' name.
    val reuse = linkedMapOf(
        "library-views" to "library-list", "library-views-gbc" to "library-views", "library-grid" to "library-grid", "library-list-menu" to "library-menu",
        "library-info" to "library-info", "library-sort" to "library-sort", "library-cover-picker" to "library-cover-picker",
        "library-update" to "library-update", "library-list-phone" to "phone-library",
        "setup-roms" to "setup-roms", "setup-saves" to "setup-saves", "setup-buttons" to "setup-buttons", "setup-covers" to "setup-covers",
        "setup-buttons-capture" to "setup-buttons-capture", "game-capture-hotkey" to "game-capture",
        "settings-home" to "settings-home", "settings-search" to "settings-search", "settings-aspect" to "settings-aspect",
        "settings-shaders" to "settings-shaders", "settings-hotkeys" to "settings-hotkeys", "settings-buttons" to "settings-buttons",
        "settings-cheats" to "settings-cheats", "settings-cheats-add" to "settings-cheats-add",
        "settings-retroachievements" to "settings-retroachievements", "settings-coverart" to "settings-coverart",
        "settings-overlay" to "settings-overlay", "settings-theme" to "settings-theme", "overlay-daisy" to "overlay-daisy",
        "overlay-bezel" to "overlay-bezel",
        "settings-touch-phone" to "settings-touch-phone", "settings-touch-edit" to "settings-touch-edit",
    )
    val byName = all.associateBy { it.name }
    val top = reuse.mapNotNull { (from, to) ->
        byName[from]?.let { Shot(to, it.w, it.h, it.density, it.content, it.action) } ?: run { println("docs: no shot $from in Main.kt"); null }
    }
    val trainer = { trainerBattle(BATTLE_INPUT_ACTION_SELECT).withBench() }
    val companion = listOf(
        Shot("companion-party", bw, bh, bd, docsCompanion("PARTY")),
        Shot("companion-summary", bw, bh, bd, docsCompanion("PARTY", mon = 0)),
        Shot("companion-bag", bw, bh, bd, docsCompanion("ITEMS")),
        Shot("companion-bag-item", bw, bh, bd, docsCompanion("ITEMS")) {
            // The first row of the list (a tap on its text node missed).
            onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(800f, 105f)) }
        },
        Shot("companion-battle", bw, bh, bd, docsCompanion("PARTY", snap = trainer)),
        Shot("companion-battle-moves", bw, bh, bd, docsCompanion("PARTY") { battle(BATTLE_INPUT_MOVE_SELECT).withBench() }),
        Shot("companion-battle-onetap", bw, bh, bd, docsCompanion("PARTY", oneTap = true) { battle(BATTLE_INPUT_ACTION_SELECT).withBench() }),
        Shot("companion-battle-info", bw, bh, bd, docsCompanion("PARTY", snap = trainer)) { onNodeWithText("INFO").performClick() },
        Shot("companion-battle-suggestions", bw, bh, bd, docsCompanion("PARTY", snap = trainer)) { onNodeWithText("SUGGESTIONS").performClick() },
        Shot("companion-battle-switch", bw, bh, bd, docsCompanion("PARTY") {
            trainer().let { s -> s.copy(party = s.party.mapIndexed { i, m -> m.copy(personality = 0x1000L + i, hp = if (i == 1) 0 else m.hp) }) }
        }) { onNodeWithText("POKéMON").performClick() },
        Shot("companion-map", bw, bh, bd, docsCompanion("MAP")),
        Shot("companion-guide", bw, bh, bd, {
            val content: @Composable () -> Unit = {
                AppTheme {
                    // The companion's own backdrop colour with no game art (as the other companion shots have it).
                    Box(Modifier.fillMaxSize().background(Color(0xFF2C7868))) {
                        GameBackdrop(com.pokedaisy.app.companion.data.activeGame)
                        GuideScreen(GuideSource(DOCS_GUIDE, null, null, null), GuideUiState().apply { page = "TIPS" }, snapshot().disguised(), Modifier.padding(12.dp))
                    }
                }
            }
            content
        }) { onNodeWithText("Running").performClick(); onNodeWithText("Running").performClick() },
        Shot("companion-states", bw, bh, bd, docsCompanion("STATES")),
        Shot("companion-achievements", bw, bh, bd, docsCompanion("ACHIEVEMENTS", achievements = DocsAchievements())),
        Shot("companion-achievement-popup", bw, bh, bd, docsCompanion(
            "PARTY", achievements = DocsAchievements(),
            popup = AchievementPopup(AchievementPopup.Kind.UNLOCKED, "Fresh Petals", "Win your first trainer battle", points = 10, achievementId = 2),
        )),
        Shot("companion-settings", bw, bh, bd, docsCompanion("SETTINGS")),
        Shot("companion-settings-ffspeed", bw, bh, bd, docsCompanion("SETTINGS")) { onNodeWithText("FF SPEED").performClick() },
        Shot("companion-settings-companion", bw, bh, bd, docsCompanion("SETTINGS")) { onNodeWithText("ONE-TAP MOVES").performScrollTo() },
        Shot("companion-settings-tabs", bw, bh, bd, docsCompanion("SETTINGS")) {
            onNodeWithText("TAB BAR").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // MORE: the tabs off the bar as app icons (STATES, CHEEVOS and, with a card, CARD).
        Shot("companion-more", bw, bh, bd, docsCompanion("MORE", achievements = DocsAchievements())),
        Shot("companion-settings-shaders", bw, bh, bd, docsCompanion("SETTINGS")) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("companion-settings-hotkeys", bw, bh, bd, docsCompanion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // A hotkey waiting for keys: SELECT held, R1 on its way (the game's screen asks too: game-capture).
        Shot("companion-settings-hotkeys-capture", bw, bh, bd, docsCompanion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("LOAD STATE").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            pressKeys(android.view.KeyEvent.KEYCODE_BUTTON_SELECT, android.view.KeyEvent.KEYCODE_BUTTON_R1)
        },
        Shot("companion-settings-tweaks", bw, bh, bd, docsCompanion("SETTINGS")) {
            onNodeWithText("TWEAKS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("companion-settings-bottom", bw, bh, bd, docsCompanion("SETTINGS")) { onNodeWithText("CLOSE COMPANION").performScrollTo() },
        Shot("status-bar", tw, th, td, {
            {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(Modifier.width(592.dp)) {
                        com.pokedaisy.app.companion.ui.GameStatusBar("Daisy Quest", "Daisy Town", 3150, "14:05", battery = BatteryStatus(82, charging = false))
                        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Color(0xFF3A6B3A)))
                    }
                }
            }
        }),
    )
    // Main.kt's FOLDERS tap misses (the row sits at the list's edge): click the row itself.
    val fixed = listOf(
        Shot("settings-folders", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("FOLDERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
    )
    val helpShots = if (!help) emptyList() else listOf(
        Shot("help-contents", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("HELP").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("help-search", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("HELP").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("fast forward")
        },
        Shot("help-page", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("HELP").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onAllNodesWithText("Install and first setup").onFirst().performClick()
        },
        Shot("companion-help", bw, bh, bd, docsCompanion("SETTINGS")) {
            onNodeWithText("HELP").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
    )
    return top + fixed + companion + helpShots
}

package com.pokedaisy.app.companion.data.coop

/** How a story event starts: stepping on a tile, talking to an object, or a map's frame script. */
enum class StoryKind { COORD, OBJECT, FRAME }

/** One scripted Emerald story moment both co-op players must reach together (generated, see [COOP_STORY_EVENTS]). */
data class StoryEvent(
    val id: Int,            // 1..n, stable within one generated table (0 = none on the wire)
    val kind: StoryKind,
    val mapGroup: Int, val mapNum: Int,
    val x: Int, val y: Int, // COORD: the trigger tile; OBJECT: the object's tile; FRAME: -1, -1 (the whole map)
    val script: Long,       // ROM address of the script ScriptContext_SetupScript receives
    val label: String,      // the script's pokeemerald label, for logs / tests only (never shown)
    val trainers: List<Int>,// story trainer ids its script battles (empty for a scene)
)

/** Flags a won story trainer sets beyond its own trainer flag (a badge, FLAG_DEFEATED_*). */
data class TrainerWin(val trainerId: Int, val flags: List<Int>)

/** Whether a held story battle can become a co-op battle; the battle engine (plan 4) sets the real answer. */
fun interface CoopStoryBattle {
    fun joinable(event: StoryEvent): Boolean

    companion object { val NONE = CoopStoryBattle { false } }
}

object CoopStory {
    @Volatile var storyBattle: CoopStoryBattle = CoopStoryBattle.NONE

    private val events: Map<Int, StoryEvent> by lazy { COOP_STORY_EVENTS.associateBy { it.id } }

    fun byId(id: Int): StoryEvent? = events[id]

    private val storyTrainers: Set<Int> by lazy { COOP_STORY_EVENTS.flatMap { it.trainers }.toSet() + COOP_TRAINER_WINS.map { it.trainerId } }

    /** A trainer that one of the story events battles, or whose win sets more than its own flag (a badge, a League member, a rival): a joined battle is never offered for it. */
    fun isStoryTrainer(trainerId: Int): Boolean = trainerId in storyTrainers

    /** The trainer's own flag plus its [TrainerWin] flags; empty = not an allowed trainer id. */
    fun winFlags(trainerId: Int): List<Int> = winFlagsFrom(COOP_TRAINER_WINS, trainerId)

    internal fun winFlagsFrom(table: List<TrainerWin>, trainerId: Int): List<Int> {
        if (trainerId !in 1 until COOP_TRAINERS_COUNT) return emptyList()
        val extra = table.firstOrNull { it.trainerId == trainerId }?.flags ?: emptyList()
        return listOf(COOP_TRAINER_FLAGS_START + trainerId) + extra
    }
}

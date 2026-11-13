package com.pokedaisy.app.companion

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridge for the bottom screen's ACHIEVEMENTS tab and unlock popups -
 * RetroAchievements' state for the running game. Implemented by
 * `achievements/RetroAchievements` (rcheevos), faked by the screenshot tests,
 * so the companion stays independent of the native client.
 */
interface CompanionAchievements {
    val state: StateFlow<AchievementsState>
    /** One per thing worth a popup (an unlock, a leaderboard attempt, ...). */
    val popups: Flow<AchievementPopup>
    /** Try signing in again after the server couldn't be reached. */
    fun retry()

    /** The leaderboard opened with [openLeaderboard], as its entries arrive; null = none open. */
    val leaderboardPage: StateFlow<LeaderboardPage?> get() = NO_PAGE

    /** Fetch leaderboard [id]'s top entries and the player's own spot into [leaderboardPage]. */
    fun openLeaderboard(id: Int) {}

    fun closeLeaderboard() {}

    private companion object {
        val NO_PAGE: StateFlow<LeaderboardPage?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    }
}

data class AchievementsState(
    /** Null = signed out (sign in: top-screen Settings > RETROACHIEVEMENTS). */
    val user: String? = null,
    /** Signed in, but the server couldn't be reached; [retry] tries again. */
    val offline: Boolean = false,
    /** The running ROM: null while it's being looked up. */
    val game: AchievementGame? = null,
    val achievements: List<Achievement> = emptyList(),
    /** Achievements whose challenge is under way right now ("without healing" and the
     * like): rc_client shows them while failing would still be possible. */
    val challenges: List<ChallengeIndicator> = emptyList(),
    /** Running leaderboard attempts: the value being tracked (a time, a score). */
    val trackers: List<LeaderboardTracker> = emptyList(),
    /** The game's leaderboards (none for most sets). */
    val leaderboards: List<Leaderboard> = emptyList(),
    /** A cheat is on: nothing is checked or unlocked until every cheat is off. */
    val cheatsPaused: Boolean = false,
    /** SHINY ODDS is raised: paused the same way until it's NORMAL again. */
    val shinyOddsPaused: Boolean = false,
    /** FLIGHT AREA is ANYWHERE and the player has taken off with it: paused the same way until it's VISITED again. */
    val flightPaused: Boolean = false,
)

data class Leaderboard(
    val id: Int,
    val title: String,
    val description: String,
    /** rc_client's group: the set (or subset) it belongs to. */
    val section: String,
    val lowerIsBetter: Boolean,
)

data class LeaderboardEntry(val rank: Int, val user: String, val score: String, val isMe: Boolean)

data class LeaderboardPage(
    val id: Int,
    val loading: Boolean = true,
    val error: String? = null,
    /** The top of the board. */
    val top: List<LeaderboardEntry> = emptyList(),
    /** The entries around the player's own; empty when they have no entry. */
    val nearMe: List<LeaderboardEntry> = emptyList(),
    val total: Int = 0,
)

data class ChallengeIndicator(val id: Int, val title: String, val badgeUrl: String?)

data class LeaderboardTracker(val id: Int, val display: String)

data class AchievementGame(
    /** 0 = RetroAchievements has no set for this ROM's exact hash. */
    val id: Int,
    val title: String?,
    /** The ROM's hash (MD5), the thing RetroAchievements matches on. */
    val hash: String?,
    val total: Int,
    val unlocked: Int,
    val points: Int,
    val pointsUnlocked: Int,
)

data class Achievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val unlocked: Boolean,
    /** rc_client's group: RECENTLY UNLOCKED, ALMOST THERE, LOCKED, UNLOCKED, ... */
    val section: String,
    val badgeUrl: String?,
    val lockedBadgeUrl: String?,
    /** "3/10" when the achievement counts towards something, else "". */
    val progress: String = "",
    /** 0..100 */
    val percent: Float = 0f,
    /** % of players who have it. */
    val rarity: Float = 0f,
)

data class AchievementPopup(
    val kind: Kind,
    val title: String,
    val detail: String = "",
    val badgeUrl: String? = null,
    val points: Int = 0,
    /** The achievement it's about (an unlock, its progress): a tap opens it in the list. 0 = none. */
    val achievementId: Int = 0,
) {
    enum class Kind { UNLOCKED, MASTERED, GAME_LOADED, PROGRESS, LEADERBOARD, NOTICE }
}

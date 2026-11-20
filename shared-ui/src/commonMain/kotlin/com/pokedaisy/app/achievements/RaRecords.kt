package com.pokedaisy.app.achievements

import com.pokedaisy.app.companion.Achievement
import com.pokedaisy.app.companion.AchievementPopup
import com.pokedaisy.app.companion.Leaderboard
import com.pokedaisy.app.companion.LeaderboardEntry
import com.pokedaisy.app.companion.i18n.tr

/** The signed-in RetroAchievements account. */
data class RaUser(
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val score: Int,
    val softcoreScore: Int,
)

/** One rc_client event; which fields are set depends on [type] (see the native side's raEvent). */
data class RaEvent(
    val type: Int,
    val id: Int,
    val title: String?,
    val description: String?,
    val badgeUrl: String?,
    /** Measured progress, a leaderboard tracker / submitted score, ... */
    val extra: String,
    /** Achievement points, or the new rank for [SCOREBOARD]. */
    val points: Int,
) {
    companion object {
        // RC_CLIENT_EVENT_* (rc_client.h)
        const val ACHIEVEMENT_TRIGGERED = 1
        const val LEADERBOARD_STARTED = 2
        const val LEADERBOARD_FAILED = 3
        const val LEADERBOARD_SUBMITTED = 4
        const val CHALLENGE_SHOW = 5
        const val CHALLENGE_HIDE = 6
        const val PROGRESS_SHOW = 7
        const val PROGRESS_UPDATE = 9
        const val TRACKER_SHOW = 10
        const val TRACKER_HIDE = 11
        const val TRACKER_UPDATE = 12
        const val SCOREBOARD = 13
        const val GAME_COMPLETED = 15
        const val SERVER_ERROR = 16
        const val DISCONNECTED = 17
        const val RECONNECTED = 18
    }
}

/** A game started but its achievements couldn't load: no server. */
fun offlinePopup() = AchievementPopup(
    AchievementPopup.Kind.NOTICE, tr("ACHIEVEMENTS OFFLINE"),
    tr("Can't reach RetroAchievements, so achievements are off for now. They come on by themselves once it's back."),
)

/** Whether a [kind] popup shows, given Settings > RetroAchievements' two notification switches. */
fun popupAllowed(kind: AchievementPopup.Kind, notifications: Boolean, progressNotifications: Boolean): Boolean =
    notifications && (progressNotifications || kind != AchievementPopup.Kind.PROGRESS)

/** The native achievement list's records (0x1E) of fields (0x1F) -> [Achievement]s. */
fun parseAchievementList(bytes: ByteArray): List<Achievement> =
    bytes.decodeToString().split('\u001E').mapNotNull { rec ->
        val f = rec.split('\u001F')
        if (f.size < 13) return@mapNotNull null
        Achievement(
            id = f[2].toIntOrNull() ?: return@mapNotNull null,
            title = f[3],
            description = f[4],
            points = f[5].toIntOrNull() ?: 0,
            unlocked = (f[6].toIntOrNull() ?: 0) != 0,
            section = f[1].uppercase(),
            badgeUrl = f[7].ifEmpty { null },
            lockedBadgeUrl = f[8].ifEmpty { null },
            progress = f[9],
            percent = f[10].toFloatOrNull() ?: 0f,
            rarity = f[11].toFloatOrNull() ?: 0f,
        )
    }

/** The native leaderboard list's records -> [Leaderboard]s. */
fun parseLeaderboardList(bytes: ByteArray): List<Leaderboard> =
    bytes.decodeToString().split('\u001E').mapNotNull { rec ->
        val f = rec.split('\u001F')
        if (f.size < 5) return@mapNotNull null
        Leaderboard(
            id = f[1].toIntOrNull() ?: return@mapNotNull null,
            title = f[2],
            description = f[3],
            section = f[0].uppercase(),
            lowerIsBetter = f[4] == "1",
        )
    }

/** A leaderboard fetch's records (rank, user, score) -> entries, [me]'s marked. */
fun parseLeaderboardEntries(bytes: ByteArray, me: String?): List<LeaderboardEntry> =
    bytes.decodeToString().split('\u001E').mapNotNull { rec ->
        val f = rec.split('\u001F')
        if (f.size < 3) return@mapNotNull null
        LeaderboardEntry(
            rank = f[0].toIntOrNull() ?: return@mapNotNull null,
            user = f[1],
            score = f[2],
            isMe = me != null && f[1].equals(me, ignoreCase = true),
        )
    }

package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.achievements.RaSession
import com.pokedaisy.app.companion.NewFeatures
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.launch

/** Sign in / out of session. Only the token the server returns is
 * kept (Prefs); the password goes straight to the login call. */
@Composable
fun RaAccountScreen(session: RaSession, prefs: Prefs, m: GbaTextMetrics, small: GbaTextMetrics) {
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") revision
    val scope = rememberCoroutineScope()
    val user by session.user.collectAsState()
    val signingIn by session.signingIn.collectAsState()
    val state by session.state.collectAsState()
    var name by remember { mutableStateOf(prefs.raUsername ?: "") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val savedName = prefs.raUsername.takeIf { prefs.raToken != null }
    fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        PageHint(
            tr("Earn RetroAchievements (retroachievements.org) as you play. Unlocks pop up on the second screen, and its CHEEVOS tab (under MORE there) lists the game's set. Only ROMs RetroAchievements knows by their exact hash have achievements. Softcore only for now. Your password isn't stored: the app keeps the login token RetroAchievements sends back."),
            m, small,
        )
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = m.u * 4)) {
                val u = user
                if (u != null || savedName != null) {
                    GbaText(tr("SIGNED IN AS"), OptionColors.label, OptionColors.labelShadow, m)
                    GbaText((u?.displayName ?: savedName!!).uppercase(), OptionColors.value, OptionColors.valueShadow, m)
                    GbaText(
                        when {
                            u != null -> tr("{0} SOFTCORE POINTS · {1} HARDCORE POINTS", u.softcoreScore, u.score)
                            state.offline -> tr("CAN'T REACH RetroAchievements RIGHT NOW · TRIED AGAIN BY ITSELF")
                            else -> tr("CONNECTING…")
                        },
                        OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 2,
                    )
                    Row(Modifier.padding(vertical = m.u * 4), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                        if (u == null && state.offline) OptionButton(tk("TRY AGAIN"), m, onClick = { session.retry() })
                        OptionButton(tk("SIGN OUT"), m, onClick = { session.logout(); password = ""; revision++ })
                    }
                } else {
                    GbaText(tr("USERNAME"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                    OptionTextField(name, { name = it; error = null }, small, placeholder = tr("retroachievements.org username"))
                    Spacer(Modifier.height(m.u * 4))
                    GbaText(tr("PASSWORD"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                    OptionTextField(password, { password = it; error = null }, small, placeholder = tr("password"), password = true)
                    error?.let {
                        GbaText(it.uppercase(), OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(top = m.u * 4), maxLines = 3)
                    }
                    Row(Modifier.padding(vertical = m.u * 4), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                        OptionButton(
                            if (signingIn) tk("SIGNING IN…") else tk("SIGN IN"), m, emphasis = true,
                            enabled = !signingIn && name.isNotBlank() && password.isNotEmpty(),
                            onClick = {
                                error = null
                                session.login(name, password) { ok, message ->
                                    scope.launch {
                                        if (ok) { password = ""; revision++ }
                                        else error = message ?: tr("Couldn't sign in")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        // What shows over the game / companion; tracking and submissions carry on either way.
        Spacer(Modifier.height(m.u * 4))
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Column {
                GroupTitleLine(tk("NOTIFICATIONS"), m, first = true)
                OptionLine(
                    tk("ACHIEVEMENT POPUPS"), onOff(prefs.raPopups), selected = false, m,
                    height = m.rowHeight * 1.2f + m.lineHeight, divider = true,
                    subtitle = "Unlocks, game complete, leaderboards and connection notices",
                    labelBadge = NewFeatures.badge(NewFeatures.RA_POPUPS),
                ) {
                    prefs.raPopups = !prefs.raPopups
                    revision++
                }
                OptionLine(
                    tk("PROGRESS POPUPS"), onOff(prefs.raProgressPopups), selected = false, m,
                    height = m.rowHeight * 1.2f + m.lineHeight, enabled = prefs.raPopups,
                    subtitle = "A notice when a counting achievement gets closer",
                    labelBadge = NewFeatures.badge(NewFeatures.RA_POPUPS),
                ) {
                    prefs.raProgressPopups = !prefs.raProgressPopups
                    revision++
                }
            }
        }
    }
}

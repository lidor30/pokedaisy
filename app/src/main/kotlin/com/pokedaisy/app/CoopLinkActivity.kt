package com.pokedaisy.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.pokedaisy.app.companion.coop.CoopInvite
import com.pokedaisy.app.companion.i18n.tr

/**
 * Opens a `pokedaisy://coop/<ticket>` invite link: hands the parsed ticket to Settings' co-op pairing and goes away.
 * Exported, so it reads nothing but the link itself: no extras, files or other URIs.
 */
class CoopLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FeatureFlags.coop(this)) {
            finish()
            return
        }
        val ticket = CoopInvite.parse(intent?.dataString.orEmpty())
        if (ticket == null) {
            Toast.makeText(this, tr("That invite link isn't valid"), Toast.LENGTH_LONG).show()
        } else {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_COOP_TICKET, ticket))
        }
        finish()
    }

    companion object {
        const val EXTRA_COOP_TICKET = "com.pokedaisy.app.COOP_TICKET"
    }
}

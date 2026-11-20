package com.pokedaisy.app.companion.data.coop

/** What the host's side of a joined battle shows: the wait for the partner's answer, the link-drop wait, notices. */
interface CoopHostUi {
    fun asking(partner: String, secondsLeft: Int)
    fun linkWait(secondsLeft: Int)
    fun notice(text: String)
    fun clear()
}

/** What the partner's side shows: the join prompt, the link-drop wait, notices. */
interface CoopPartnerUi {
    fun prompt(offer: CoopBattleMsg.Offer, secondsLeft: Int)
    fun linkWait(secondsLeft: Int)
    fun notice(text: String)
    fun clear()
}

package com.pokedaisy.app

import com.pokedaisy.app.companion.data.coop.PartnerPlacement

/** [MgbaCore] as a [PartnerCore]. */
object MgbaPartnerCore : PartnerCore {
    override fun self(): IntArray? = MgbaCore.pkPartnerSelf()
    override fun setArt(art: ByteArray?) = MgbaCore.pkPartnerSet(art)
    override fun place(p: PartnerPlacement?) {
        if (p == null) MgbaCore.pkPartnerPlace(false, 0, 0, 0, false, -1, 0)
        else MgbaCore.pkPartnerPlace(true, p.fineX, p.fineY, p.frame, p.hflip, p.blobFrame, p.bob)
    }
}

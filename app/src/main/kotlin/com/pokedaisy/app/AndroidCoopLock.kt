package com.pokedaisy.app

object MgbaLockNative : LockNative {
    override fun install() = CoopLockNative.install() == 1
    override fun uninstall() = CoopLockNative.uninstall()
    override fun setActive(on: Boolean) = CoopLockNative.setActive(on)
    override fun held(): IntArray = CoopLockNative.held()
    override fun go(id: Int) = CoopLockNative.go(id)
    override fun cancel() = CoopLockNative.cancel()
}

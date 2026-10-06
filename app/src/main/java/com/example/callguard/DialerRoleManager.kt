package com.example.callguard

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

/** Checks / requests ROLE_DIALER through the supported RoleManager API. */
class DialerRoleManager(context: Context) {
    private val roleManager: RoleManager? = context.getSystemService(RoleManager::class.java)

    fun isRoleAvailable(): Boolean =
        roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true

    fun isDialerRoleHeld(): Boolean =
        roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true

    /** Intent to launch with an ActivityResult launcher, or null if the role is unavailable. */
    fun createRequestIntent(): Intent? =
        if (isRoleAvailable()) roleManager?.createRequestRoleIntent(RoleManager.ROLE_DIALER) else null
}

package org.valkyrienskies.mod.common.command

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer

// 1.21.11: CommandSourceStack.hasPermission(int) was removed and ServerOpListEntry no
// longer carries an int level — both were replaced by a token-based PermissionSet system
// (Permissions.COMMANDS_MODERATOR/GAMEMASTER/ADMIN/OWNER). Wiring up the new Permissions
// API properly is deferred; for now, gate on "is the player an op at all" which preserves
// the spirit of the old check (admin-only commands are still admin-only). Non-player
// sources (console, command blocks) get full access, matching vanilla.
@Suppress("UNUSED_PARAMETER")
fun CommandSourceStack.hasOpPermission(level: Int): Boolean {
    val player = this.player ?: return true
    if (player !is ServerPlayer) return false
    return player.server.playerList.isOp(player.nameAndId())
}

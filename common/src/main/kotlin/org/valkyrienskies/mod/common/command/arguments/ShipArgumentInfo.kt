package org.valkyrienskies.mod.common.command.arguments

import com.google.gson.JsonObject
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.synchronization.ArgumentTypeInfo
import net.minecraft.network.FriendlyByteBuf
import org.valkyrienskies.mod.common.command.arguments.ShipArgument.Companion

// 1.21.11 port: public (was internal) so VS2's Fabric initializer can register this
// argument type via Fabric API's ArgumentTypeRegistry. See ValkyrienSkiesModFabric.
class ShipArgumentInfo : ArgumentTypeInfo<ShipArgument, ShipArgumentInfoTemplate> {
    override fun serializeToNetwork(template: ShipArgumentInfoTemplate, friendlyByteBuf: FriendlyByteBuf) {
        friendlyByteBuf.writeBoolean(template.selectorOnly)
    }

    override fun deserializeFromNetwork(friendlyByteBuf: FriendlyByteBuf): ShipArgumentInfoTemplate {
        return ShipArgumentInfoTemplate(
            this, friendlyByteBuf.readBoolean()
        )
    }

    override fun unpack(argumentType: ShipArgument): ShipArgumentInfoTemplate {
        return ShipArgumentInfoTemplate(this, argumentType.selectorOnly)
    }

    override fun serializeToJson(template: ShipArgumentInfoTemplate, jsonObject: JsonObject) {
        jsonObject.addProperty("selectorOnly", template.selectorOnly)
    }
}

class ShipArgumentInfoTemplate(private val info: ShipArgumentInfo, internal val selectorOnly: Boolean) :
    ArgumentTypeInfo.Template<ShipArgument> {
    override fun instantiate(commandBuildContext: CommandBuildContext): ShipArgument {
        return if (selectorOnly) {
            ShipArgument.selectorOnly()
        } else {
            Companion.ships()
        }
    }

    override fun type(): ArgumentTypeInfo<ShipArgument, *> {
        return info
    }
}

package org.valkyrienskies.mod.fabric.common

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import org.valkyrienskies.core.internal.hooks.VsiCoreHooksIn
import org.valkyrienskies.core.internal.world.VsiPlayer
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.networking.VSPacketFragmenter
import org.valkyrienskies.mod.common.playerWrapper
import org.valkyrienskies.mod.common.util.MinecraftPlayer
import org.valkyrienskies.mod.fabric.common.VSFragmentPacket.Companion.VS_FRAGMENT_CODEC
import org.valkyrienskies.mod.fabric.common.VSFragmentPacket.Companion.VS_FRAGMENT_TYPE
import org.valkyrienskies.mod.fabric.common.VSPacket.Companion.VS_PACKET_CODEC
import org.valkyrienskies.mod.fabric.common.VSPacket.Companion.VS_PACKET_TYPE

/**
 * Registers VS with the Fabric networking API.
 */
class VSFabricNetworking(
    private val isClient: Boolean
) {
    private fun registerClientNetworking(hooks: VsiCoreHooksIn) {
        ClientPlayNetworking.registerGlobalReceiver(VS_PACKET_TYPE) { packet, context ->
            context.client().execute {
                hooks.onReceiveClient(Unpooled.wrappedBuffer(packet.data))
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(VS_FRAGMENT_TYPE) { packet, context ->
            context.client().execute {
                val assembled = VSPacketFragmenter.onReceiveFragment(Unpooled.wrappedBuffer(packet.data))
                if (assembled != null) {
                    hooks.onReceiveClient(assembled)
                }
            }
        }
    }

    fun register(hooks: VsiCoreHooksIn) {
        PayloadTypeRegistry.playC2S().register(VS_PACKET_TYPE, VS_PACKET_CODEC)
        PayloadTypeRegistry.playS2C().register(VS_PACKET_TYPE, VS_PACKET_CODEC)
        PayloadTypeRegistry.playC2S().register(VS_FRAGMENT_TYPE, VS_FRAGMENT_CODEC)
        PayloadTypeRegistry.playS2C().register(VS_FRAGMENT_TYPE, VS_FRAGMENT_CODEC)

        if (isClient) {
            registerClientNetworking(hooks)
        }

        ServerPlayNetworking.registerGlobalReceiver(VS_PACKET_TYPE) { packet, context ->
            context.server().execute {
                hooks.onReceiveServer(Unpooled.wrappedBuffer(packet.data), context.player().playerWrapper)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(VS_FRAGMENT_TYPE) { packet, context ->
            context.server().execute {
                val assembled = VSPacketFragmenter.onReceiveFragment(Unpooled.wrappedBuffer(packet.data))
                if (assembled != null) {
                    hooks.onReceiveServer(assembled, context.player().playerWrapper)
                }
            }
        }
    }

    fun sendToClient(data: ByteBuf, player: VsiPlayer) {
        val serverPlayer = (player as MinecraftPlayer).player as ServerPlayer
        if (VSPacketFragmenter.needsSplitting(data)) {
            for (fragment in VSPacketFragmenter.split(data.copy())) {
                ServerPlayNetworking.send(serverPlayer, VSFragmentPacket(fragment.copyToByteArray()))
            }
        } else {
            ServerPlayNetworking.send(serverPlayer, VSPacket(data.copyToByteArray()))
        }
    }

    fun sendToServer(data: ByteBuf) {
        if (VSPacketFragmenter.needsSplitting(data)) {
            for (fragment in VSPacketFragmenter.split(data.copy())) {
                ClientPlayNetworking.send(VSFragmentPacket(fragment.copyToByteArray()))
            }
        } else {
            ClientPlayNetworking.send(VSPacket(data.copyToByteArray()))
        }
    }

    private fun ByteBuf.copyToByteArray(): ByteArray {
        val byteArray = ByteArray(readableBytes())
        getBytes(readerIndex(), byteArray)
        return byteArray
    }
}

class VSPacket(internal val data: ByteArray) : CustomPacketPayload {
    override fun type() = VS_PACKET_TYPE

    companion object {
        val VS_PACKET_RL: Identifier = Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "vs_packet")
        val VS_PACKET_TYPE = CustomPacketPayload.Type<VSPacket>(VS_PACKET_RL)
        val VS_PACKET_CODEC: StreamCodec<FriendlyByteBuf, VSPacket> = StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, VSPacket::data) {
            VSPacket(it)
        }
    }
}

class VSFragmentPacket(internal val data: ByteArray) : CustomPacketPayload {
    override fun type() = VS_FRAGMENT_TYPE

    companion object {
        val VS_FRAGMENT_RL: Identifier = Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "vs_fragment")
        val VS_FRAGMENT_TYPE = CustomPacketPayload.Type<VSFragmentPacket>(VS_FRAGMENT_RL)
        val VS_FRAGMENT_CODEC: StreamCodec<FriendlyByteBuf, VSFragmentPacket> = StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, VSFragmentPacket::data) {
            VSFragmentPacket(it)
        }
    }
}

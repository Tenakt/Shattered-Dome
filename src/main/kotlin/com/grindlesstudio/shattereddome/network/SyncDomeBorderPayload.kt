
package com.grindlesstudio.shattereddome.network

import com.grindlesstudio.shattereddome.ShatteredDome
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

data class SyncDomeBorderPayload(
    val radius: Int
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SyncDomeBorderPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SyncDomeBorderPayload>(
            ResourceLocation.fromNamespaceAndPath(
                ShatteredDome.MOD_ID,
                "sync_dome_border"
            )
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SyncDomeBorderPayload> =
            StreamCodec.composite(
                ByteBufCodecs.INT,
                SyncDomeBorderPayload::radius,
                ::SyncDomeBorderPayload
            )

        fun handle(
            payload: SyncDomeBorderPayload,
            context: IPayloadContext
        ) {
            context.enqueueWork {
                com.grindlesstudio.shattereddome.client.DomeBorderClientState.radius =
                    payload.radius
            }
        }
    }
}

package com.grindlesstudio.shattereddome.network

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.client.SkyBreakClientState
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

data class SyncSkyBreakPayload(
    val isBroken: Boolean,
    val riftProgress: Float = 0f
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SyncSkyBreakPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SyncSkyBreakPayload>(
            ResourceLocation.fromNamespaceAndPath(ShatteredDome.MOD_ID, "sync_sky_break")
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, SyncSkyBreakPayload> = StreamCodec.composite(
            ByteBufCodecs.BOOL, SyncSkyBreakPayload::isBroken,
            ByteBufCodecs.FLOAT, SyncSkyBreakPayload::riftProgress,
            ::SyncSkyBreakPayload
        )

        fun handle(payload: SyncSkyBreakPayload, context: IPayloadContext) {
            context.enqueueWork {
                SkyBreakClientState.isBroken = payload.isBroken
                SkyBreakClientState.riftProgress = payload.riftProgress
            }
        }
    }
}
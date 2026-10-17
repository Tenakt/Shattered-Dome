package com.grindlesstudio.shattereddome.network

import com.grindlesstudio.shattereddome.client.SkyBreakClientState
import com.grindlesstudio.shattereddome.client.fx.ScreenShakeHandler
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

// 1. Пакет вспышки неба (Singleton объект)
object SyncSkyFlashPayload : CustomPacketPayload {
    val TYPE = CustomPacketPayload.Type<SyncSkyFlashPayload>(
        ResourceLocation.fromNamespaceAndPath("shattereddome", "sky_flash")
    )

    val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SyncSkyFlashPayload> = StreamCodec.unit(SyncSkyFlashPayload)

    override fun type(): CustomPacketPayload.Type<SyncSkyFlashPayload> = TYPE

    fun handle(payload: SyncSkyFlashPayload, context: IPayloadContext) {
        context.enqueueWork {
            SkyBreakClientState.skyFlashIntensity = 1.0f
        }
    }
}

// 2. Пакет тряски экрана
data class SyncScreenShakePayload(val intensity: Float, val duration: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SyncScreenShakePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SyncScreenShakePayload>(
            ResourceLocation.fromNamespaceAndPath("shattereddome", "screen_shake")
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SyncScreenShakePayload> = StreamCodec.composite(
            ByteBufCodecs.FLOAT, SyncScreenShakePayload::intensity,
            ByteBufCodecs.INT, SyncScreenShakePayload::duration,
            ::SyncScreenShakePayload
        )

        fun handle(payload: SyncScreenShakePayload, context: IPayloadContext) {
            context.enqueueWork {
                ScreenShakeHandler.addShake(payload.intensity, payload.duration)
            }
        }
    }
}
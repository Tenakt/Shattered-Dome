package com.grindlesstudio.shattereddome.network

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.client.SkyBreakClientState
import com.grindlesstudio.shattereddome.client.fx.ScreenShakeHandler
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

data class SyncSkyFlashPayload(
    val sourceX: Double,
    val sourceY: Double,
    val sourceZ: Double,
    val targetX: Double,
    val targetY: Double,
    val targetZ: Double
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SyncSkyFlashPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SyncSkyFlashPayload>(
            ResourceLocation.fromNamespaceAndPath(ShatteredDome.MOD_ID, "sky_flash")
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SyncSkyFlashPayload> =
            StreamCodec.composite(
                ByteBufCodecs.DOUBLE, SyncSkyFlashPayload::sourceX,
                ByteBufCodecs.DOUBLE, SyncSkyFlashPayload::sourceY,
                ByteBufCodecs.DOUBLE, SyncSkyFlashPayload::sourceZ,
                ByteBufCodecs.DOUBLE, SyncSkyFlashPayload::targetX,
                ByteBufCodecs.DOUBLE, SyncSkyFlashPayload::targetY,
                ByteBufCodecs.DOUBLE, SyncSkyFlashPayload::targetZ,
                ::SyncSkyFlashPayload
            )

        fun handle(payload: SyncSkyFlashPayload, context: IPayloadContext) {
            context.enqueueWork {
                SkyBreakClientState.finaleFlashSource = Vec3(
                    payload.sourceX,
                    payload.sourceY,
                    payload.sourceZ
                )

                SkyBreakClientState.finaleFlashTarget = Vec3(
                    payload.targetX,
                    payload.targetY,
                    payload.targetZ
                )

                SkyBreakClientState.finaleFlashActive = true
                SkyBreakClientState.skyFlashIntensity = 0f
            }
        }
    }
}

// Пакет тряски экрана оставлен совместимым с остальным модом.
data class SyncScreenShakePayload(
    val intensity: Float,
    val duration: Int
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SyncScreenShakePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SyncScreenShakePayload>(
            ResourceLocation.fromNamespaceAndPath(ShatteredDome.MOD_ID, "screen_shake")
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SyncScreenShakePayload> =
            StreamCodec.composite(
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
package com.grindlesstudio.shattereddome.network

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.client.SkyBreakClientState
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

/** Client-visible stages for the sky-break sequence. */
object SkyEventStage {
    const val INACTIVE = 0
    const val AWAKENING = 1
    const val RIFT_OPENING = 2
    const val METEOR_STORM = 3
    const val RESTORING = 4
}

data class SyncSkyBreakPayload(
    val isBroken: Boolean,
    val riftProgress: Float = 0f,
    val eventStage: Int = SkyEventStage.INACTIVE
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SyncSkyBreakPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SyncSkyBreakPayload>(
            ResourceLocation.fromNamespaceAndPath(ShatteredDome.MOD_ID, "sync_sky_break")
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, SyncSkyBreakPayload> = StreamCodec.composite(
            ByteBufCodecs.BOOL, SyncSkyBreakPayload::isBroken,
            ByteBufCodecs.FLOAT, SyncSkyBreakPayload::riftProgress,
            ByteBufCodecs.INT, SyncSkyBreakPayload::eventStage,
            ::SyncSkyBreakPayload
        )

        fun handle(payload: SyncSkyBreakPayload, context: IPayloadContext) {
            context.enqueueWork {
                val previousStage = SkyBreakClientState.eventStage
                SkyBreakClientState.isBroken = payload.isBroken
                SkyBreakClientState.riftProgress = payload.riftProgress
                SkyBreakClientState.eventStage = payload.eventStage

                if (
                    payload.isBroken &&
                    payload.eventStage == SkyEventStage.RIFT_OPENING &&
                    previousStage != SkyEventStage.RIFT_OPENING
                ) {
                    // A short flash accompanies the initial fracture, before the long finale flash.
                    SkyBreakClientState.openingFlashTicks = 38
                }

                if (!payload.isBroken || payload.eventStage == SkyEventStage.INACTIVE) {
                    SkyBreakClientState.openingFlashTicks = 0
                    SkyBreakClientState.openingFlashIntensity = 0f
                }
            }
        }
    }
}

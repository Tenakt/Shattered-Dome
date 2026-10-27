package com.grindlesstudio.shattereddome.world

import com.grindlesstudio.shattereddome.ShatteredDome
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.common.world.chunk.LoadingValidationCallback
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent
import net.neoforged.neoforge.common.world.chunk.TicketController
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Chunk tickets только для большого сюжетного метеорита.
 * ticking=true означает, что чанки продолжают обрабатывать тики без игрока.
 */
@EventBusSubscriber(
    modid = ShatteredDome.MOD_ID,
    bus = EventBusSubscriber.Bus.MOD
)
object MeteorChunkTickets {

    private val controller = TicketController(
        ResourceLocation.fromNamespaceAndPath(
            ShatteredDome.MOD_ID,
            "giant_meteor_flight"
        ),
        LoadingValidationCallback { _, ticketHelper ->
            // Полёт временный. После рестарта старые билеты не нужны.
            ticketHelper.getEntityTickets().keys.toList().forEach { owner ->
                ticketHelper.removeAllTickets(owner)
            }
        }
    )

    @SubscribeEvent
    @JvmStatic
    fun registerTicketController(event: RegisterTicketControllersEvent) {
        event.register(controller)
    }

    /** Загружает все чанки, пересекаемые траекторией полёта. */
    fun forceFlightPath(
        level: ServerLevel,
        owner: UUID,
        start: Vec3,
        target: Vec3
    ): Set<Pair<Int, Int>> {
        val largestHorizontalDelta = maxOf(
            abs(target.x - start.x),
            abs(target.z - start.z)
        )
        val steps = ceil(largestHorizontalDelta / 8.0).toInt().coerceAtLeast(1)
        val chunks = linkedSetOf<Pair<Int, Int>>()

        for (step in 0..steps) {
            val fraction = step.toDouble() / steps.toDouble()
            val point = start.lerp(target, fraction)
            val chunkX = floor(point.x / 16.0).toInt()
            val chunkZ = floor(point.z / 16.0).toInt()
            chunks.add(chunkX to chunkZ)
        }

        for ((chunkX, chunkZ) in chunks) {
            controller.forceChunk(
                level,
                owner,
                chunkX,
                chunkZ,
                true,
                true
            )
        }

        return chunks
    }

    fun releaseFlightPath(
        level: ServerLevel,
        owner: UUID,
        chunks: Set<Pair<Int, Int>>
    ) {
        for ((chunkX, chunkZ) in chunks) {
            controller.forceChunk(
                level,
                owner,
                chunkX,
                chunkZ,
                false,
                true
            )
        }
    }
}

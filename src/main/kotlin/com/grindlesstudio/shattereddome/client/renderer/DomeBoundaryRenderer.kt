package com.grindlesstudio.shattereddome.client.renderer

import com.grindlesstudio.shattereddome.client.DomeBorderClientState
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderGuiEvent
import kotlin.math.abs

@EventBusSubscriber(
    modid = "shattereddome",
    value = [Dist.CLIENT],
    bus = EventBusSubscriber.Bus.GAME
)
object DomeBoundaryRenderer {

    private const val DARKEN_START_RATIO = 0.90

    @SubscribeEvent
    @JvmStatic
    fun onRenderGui(event: RenderGuiEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return

        val radius = DomeBorderClientState.radius
            .coerceAtLeast(1)
            .toDouble()

        // Квадратная граница: проверяем X и Z независимо.
        val distanceToEdge = maxOf(abs(player.x), abs(player.z))
        val darkenStart = radius * DARKEN_START_RATIO

        if (distanceToEdge <= darkenStart) return

        val range = radius - darkenStart
        if (range <= 0.0) return

        val progress = (
                (distanceToEdge - darkenStart) / range
                ).coerceIn(0.0, 1.0)

        val alpha = (progress * progress * 255.0).toInt()
        if (alpha <= 0) return

        event.guiGraphics.fill(
            0,
            0,
            minecraft.window.guiScaledWidth,
            minecraft.window.guiScaledHeight,
            alpha shl 24
        )
    }
}
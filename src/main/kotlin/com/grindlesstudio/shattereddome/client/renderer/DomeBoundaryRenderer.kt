package com.grindlesstudio.shattereddome.client.renderer

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.neoforged.api.distmarker.Dist
import net.neoforged.neoforge.client.event.RenderGuiEvent
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import kotlin.math.sqrt

@EventBusSubscriber(
    modid = "shattereddome",
    value = [Dist.CLIENT],
    bus = EventBusSubscriber.Bus.GAME
)
object DomeBoundaryRenderer {

    // На каком расстоянии начинается затемнение
    private const val DARKEN_START = 9000.0

    // На каком расстоянии экран становится полностью чёрным
    private const val DEATH_DISTANCE = 10000.0

    @SubscribeEvent
    @JvmStatic
    fun onRenderGui(event: RenderGuiEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return

        // Расстояние от центра мира (0, 0)
        val distance = sqrt(
            player.x * player.x +
                    player.z * player.z
        )

        // До 9000 блоков ничего не делаем
        if (distance <= DARKEN_START) return

        // От 9000 до 10000 превращаем 0..1
        val progress = (
                (distance - DARKEN_START) /
                        (DEATH_DISTANCE - DARKEN_START)
                ).coerceIn(0.0, 1.0)

        // Небольшое ускорение затемнения в конце
        val alpha = (progress * progress * 255.0).toInt()

        if (alpha <= 0) return

        val gui = event.guiGraphics

        // ARGB:
        // alpha + чёрный цвет
        val color = (alpha shl 24)

        gui.fill(
            0,
            0,
            minecraft.window.guiScaledWidth,
            minecraft.window.guiScaledHeight,
            color
        )
    }
}
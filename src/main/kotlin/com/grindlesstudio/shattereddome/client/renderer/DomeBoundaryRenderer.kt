package com.grindlesstudio.shattereddome.client.renderer

import com.grindlesstudio.shattereddome.client.DomeBorderClientState
import com.grindlesstudio.shattereddome.client.SkyBreakClientState
import com.grindlesstudio.shattereddome.network.SkyEventStage
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@EventBusSubscriber(
    modid = "shattereddome",
    value = [Dist.CLIENT],
    bus = EventBusSubscriber.Bus.GAME
)
object DomeBoundaryRenderer {

    private const val DARKEN_START_RATIO = 0.90
    private const val MAX_PANEL_PULSES = 18
    private const val PANEL_COLUMNS = 8
    private const val PANEL_ROWS = 5

    private data class PanelCell(
        val xRatio: Float,
        val yRatio: Float,
        val order: Int
    )

    private data class PanelPulse(
        val xRatio: Float,
        val yRatio: Float,
        val size: Int,
        val lifetime: Int,
        var remaining: Int,
        val rotation: Float
    )

    private val panelCells: List<PanelCell> = run {
        val raw = mutableListOf<Triple<Float, Float, Double>>()
        for (row in 0 until PANEL_ROWS) {
            for (column in 0 until PANEL_COLUMNS) {
                val x = (column + 0.5f + if (row % 2 == 1) 0.5f else 0f) /
                        (PANEL_COLUMNS + 0.5f)
                val y = (row + 0.5f) / PANEL_ROWS
                val dx = x.toDouble() - 0.5
                val dy = y.toDouble() - 0.5
                raw.add(Triple(x, y, dx * dx + dy * dy))
            }
        }
        raw.sortedBy { it.third }.mapIndexed { index, entry ->
            PanelCell(entry.first, entry.second, index)
        }
    }

    private val panelPulses = mutableListOf<PanelPulse>()
    private var previousStage = SkyEventStage.INACTIVE
    private var stageTicks = 0

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val stage = SkyBreakClientState.eventStage
        if (stage != previousStage) {
            previousStage = stage
            stageTicks = 0
            if (stage == SkyEventStage.INACTIVE || stage == SkyEventStage.RESTORING) {
                panelPulses.clear()
            }
        } else if (stage != SkyEventStage.INACTIVE) {
            stageTicks++
        }

        for (pulse in panelPulses) pulse.remaining--
        panelPulses.removeAll { it.remaining <= 0 }

        when (stage) {
            SkyEventStage.AWAKENING -> {
                // Сначала от центра к краям последовательно загораются панели купола.
                if (stageTicks % 2 == 0) {
                    val cellIndex = stageTicks / 2
                    if (cellIndex < panelCells.size) {
                        addPulse(panelCells[cellIndex], lifetime = 24, sizeMultiplier = 1.0f)
                    }
                }
                // Последние мгновения перед разломом дают нервное мерцание.
                if (stageTicks >= 55 && Random.nextInt(4) == 0) {
                    addPulse(panelCells.random(), lifetime = 12, sizeMultiplier = 0.72f)
                }
            }

            SkyEventStage.RIFT_OPENING -> {
                if (panelPulses.size < MAX_PANEL_PULSES && Random.nextInt(4) == 0) {
                    addPulse(panelCells.random(), lifetime = Random.nextInt(8, 17), sizeMultiplier = 0.88f)
                }
            }

            SkyEventStage.METEOR_STORM -> {
                if (panelPulses.size < MAX_PANEL_PULSES && Random.nextInt(7) == 0) {
                    addPulse(panelCells.random(), lifetime = Random.nextInt(6, 13), sizeMultiplier = 0.66f)
                }
            }

            else -> Unit
        }
    }

    private fun addPulse(cell: PanelCell, lifetime: Int, sizeMultiplier: Float) {
        val minecraft = Minecraft.getInstance()
        val width = minecraft.window.guiScaledWidth
        val height = minecraft.window.guiScaledHeight
        val baseSize = (min(width / PANEL_COLUMNS.toFloat(), height / PANEL_ROWS.toFloat()) * 0.29f)
            .toInt().coerceIn(12, 44)
        if (panelPulses.size >= MAX_PANEL_PULSES) {
            val oldest = panelPulses.minByOrNull { it.remaining }
            if (oldest != null) panelPulses.remove(oldest)
        }
        panelPulses.add(
            PanelPulse(
                xRatio = cell.xRatio,
                yRatio = cell.yRatio,
                size = (baseSize * sizeMultiplier).toInt().coerceAtLeast(8),
                lifetime = lifetime,
                remaining = lifetime,
                rotation = 22.5f + Random.nextFloat() * 12f - 6f
            )
        )
    }

    @SubscribeEvent
    @JvmStatic
    fun onRenderGui(event: RenderGuiEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val width = minecraft.window.guiScaledWidth
        val height = minecraft.window.guiScaledHeight

        val radius = DomeBorderClientState.radius.coerceAtLeast(1).toDouble()
        val distanceToEdge = max(kotlin.math.abs(player.x), kotlin.math.abs(player.z))
        val darkenStart = radius * DARKEN_START_RATIO

        if (distanceToEdge > darkenStart) {
            val range = radius - darkenStart
            if (range > 0.0) {
                val progress = ((distanceToEdge - darkenStart) / range).coerceIn(0.0, 1.0)
                val alpha = (progress * progress * 255.0).toInt()
                if (alpha > 0) {
                    event.guiGraphics.fill(0, 0, width, height, alpha shl 24)
                }
            }
        }

        // Экранные восьмиугольники отключены: они выглядели как лишние круги поверх мира.
        // Весь код анимации панелей и drawOctagon оставлен, чтобы не терять логику эффекта.
        // drawPanelPulses(event, width, height)
        drawOpeningFlash(event, width, height)
    }

    private fun drawPanelPulses(event: RenderGuiEvent.Post, width: Int, height: Int) {
        if (panelPulses.isEmpty()) return
        val gui = event.guiGraphics

        for (pulse in panelPulses) {
            val lifeRatio = (pulse.remaining.toFloat() / pulse.lifetime).coerceIn(0f, 1f)
            val envelope = sin((1f - lifeRatio) * PI).toFloat().coerceIn(0f, 1f)
            if (envelope <= 0.01f) continue

            val alpha = (envelope * 220f).toInt().coerceIn(0, 220)
            val centerX = (pulse.xRatio * width).toInt()
            val centerY = (pulse.yRatio * height).toInt()

            // Две тонкие восьмиугольные линии делают импульс похожим на подсветку панели.
            drawOctagon(gui, centerX, centerY, pulse.size, alpha, pulse.rotation, 0x00BCA8FF)
            drawOctagon(
                gui,
                centerX,
                centerY,
                (pulse.size * 0.66f).toInt().coerceAtLeast(5),
                (alpha * 0.60f).toInt(),
                pulse.rotation,
                0x00F0E8FF
            )
        }
    }

    private fun drawOctagon(
        gui: net.minecraft.client.gui.GuiGraphics,
        centerX: Int,
        centerY: Int,
        radius: Int,
        alpha: Int,
        rotation: Float,
        rgb: Int
    ) {
        if (alpha <= 0 || radius <= 1) return
        val points = Array(8) { index ->
            val angle = Math.toRadians(rotation.toDouble() + index * 45.0)
            (centerX + kotlin.math.cos(angle) * radius) to
                    (centerY + kotlin.math.sin(angle) * radius)
        }
        val thickness = (radius / 9).coerceIn(1, 3)
        val color = (alpha.coerceIn(0, 255) shl 24) or rgb
        val pose = gui.pose()

        for (index in points.indices) {
            val p1 = points[index]
            val p2 = points[(index + 1) % points.size]
            val dx = p2.first - p1.first
            val dy = p2.second - p1.second
            val length = sqrt(dx * dx + dy * dy).toInt().coerceAtLeast(1)
            val angle = Math.toDegrees(atan2(dy, dx)).toFloat()
            val midX = ((p1.first + p2.first) * 0.5).toDouble()
            val midY = ((p1.second + p2.second) * 0.5).toDouble()

            pose.pushPose()
            pose.translate(midX, midY, 0.0)
            pose.mulPose(Axis.ZP.rotationDegrees(angle))
            gui.fill(-length / 2, -thickness / 2, length / 2, thickness / 2, color)
            pose.popPose()
        }
    }

    private fun drawOpeningFlash(event: RenderGuiEvent.Post, width: Int, height: Int) {
        val intensity = SkyBreakClientState.openingFlashIntensity.coerceIn(0f, 1f)
        if (intensity <= 0.001f) return

        // Экранная вспышка поверх HUD: игрок на мгновение почти полностью ослепляется,
        // затем бело-фиолетовый свет быстро сходит на нет и открывает сцену разломов.
        val alpha = (intensity * 248f).toInt().coerceIn(0, 248)
        val color = (alpha shl 24) or 0x00F7F2FF
        event.guiGraphics.fill(0, 0, width, height, color)
    }
}

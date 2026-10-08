package com.grindlesstudio.shattereddome.client

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.sound.ModSounds
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.material.FogType
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.ViewportEvent
import team.lodestar.lodestone.registry.common.particle.LodestoneParticleTypes
import team.lodestar.lodestone.systems.particle.builder.WorldParticleBuilder
import team.lodestar.lodestone.systems.particle.data.GenericParticleData
import team.lodestar.lodestone.systems.particle.data.color.ColorParticleData
import team.lodestar.lodestone.systems.particle.data.spin.SpinParticleData
import java.awt.Color

object SkyBreakClientState {
    var isBroken: Boolean = false
}

// Зацикленный источник звука для сирены
class SkyBreakSirenSound : AbstractTickableSoundInstance(
    ModSounds.SIREN_WARNING.get(),
    SoundSource.MASTER,
    SoundInstance.createUnseededRandom()
) {
    init {
        this.looping = true
        this.delay = 0
        this.volume = 1.0f
        this.pitch = 1.0f
        this.relative = true
        this.attenuation = SoundInstance.Attenuation.NONE
    }

    fun stopSiren() {
        this.stop()
    }

    override fun tick() {
        if (!SkyBreakClientState.isBroken) {
            stop()
        }
    }
}

@EventBusSubscriber(modid = ShatteredDome.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = [Dist.CLIENT])
object SkyBreakClientHandler {

    private var currentSirenSound: SkyBreakSirenSound? = null

    // Астрально-багровые цвета
    private const val SKY_RED = 0.35f
    private const val SKY_GREEN = 0.05f
    private const val SKY_BLUE = 0.12f

    // 1. Окрашивание цвета тумана на горизонте
    @SubscribeEvent
    @JvmStatic
    fun onComputeFogColor(event: ViewportEvent.ComputeFogColor) {
        if (!SkyBreakClientState.isBroken) return
        if (event.camera.fluidInCamera != FogType.NONE) return

        event.red = SKY_RED
        event.green = SKY_GREEN
        event.blue = SKY_BLUE
    }

    // 2. Плотность тумана
    @SubscribeEvent
    @JvmStatic
    fun onRenderFog(event: ViewportEvent.RenderFog) {
        if (!SkyBreakClientState.isBroken) return
        if (event.camera.fluidInCamera != FogType.NONE) return

        event.scaleFarPlaneDistance(0.75f)
    }

    // 3. Перекрытие синего ванильного неба багровым куполом
    @SubscribeEvent
    @JvmStatic
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_SKY) return
        if (!SkyBreakClientState.isBroken) return

        val mc = Minecraft.getInstance()
        val camera = event.camera
        if (camera.fluidInCamera != FogType.NONE) return

        val poseStack = event.poseStack
        poseStack.pushPose()

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)
        RenderSystem.setShader(GameRenderer::getPositionColorShader)

        val matrix = poseStack.last().pose()
        val tesselator = Tesselator.getInstance()
        val buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)

        val r = SKY_RED
        val g = SKY_GREEN
        val b = SKY_BLUE
        val a = 1.0f

        // Адаптивный размер куба под радиус прорисовки игрока
        val renderDistanceBlocks = (mc.options.renderDistance().get() * 16 - 8).toFloat()
        val s = renderDistanceBlocks.coerceAtLeast(32.0f)

        // Верх (Up)
        buffer.addVertex(matrix, -s, s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, -s, s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, s, -s).setColor(r, g, b, a)

        // Низ (Down)
        buffer.addVertex(matrix, -s, -s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, -s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, -s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, -s, -s, s).setColor(r, g, b, a)

        // Север (North)
        buffer.addVertex(matrix, -s, -s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, -s, s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, -s, -s).setColor(r, g, b, a)

        // Юг (South)
        buffer.addVertex(matrix, s, -s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, -s, s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, -s, -s, s).setColor(r, g, b, a)

        // Запад (West)
        buffer.addVertex(matrix, -s, -s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, -s, s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, -s, s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, -s, -s, -s).setColor(r, g, b, a)

        // Восток (East)
        buffer.addVertex(matrix, s, -s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, s, -s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, s, s).setColor(r, g, b, a)
        buffer.addVertex(matrix, s, -s, s).setColor(r, g, b, a)

        BufferUploader.drawWithShader(buffer.buildOrThrow())

        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
        poseStack.popPose()
    }

    // 4. Управление сиреной и частицами Lodestone
    @SubscribeEvent
    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return

        // Сирена
        if (SkyBreakClientState.isBroken) {
            if (currentSirenSound == null || currentSirenSound!!.isStopped || !mc.soundManager.isActive(currentSirenSound!!)) {
                currentSirenSound = SkyBreakSirenSound()
                mc.soundManager.play(currentSirenSound!!)
            }
        } else {
            if (currentSirenSound != null && !currentSirenSound!!.isStopped) {
                currentSirenSound!!.stopSiren()
                currentSirenSound = null
            }
        }

        // Астральные частицы Lodestone
        if (SkyBreakClientState.isBroken && !mc.isPaused && level.random.nextFloat() < 0.6f) {
            val px = player.x + (level.random.nextDouble() - 0.5) * 35.0
            val py = player.y + 10.0 + level.random.nextDouble() * 12.0
            val pz = player.z + (level.random.nextDouble() - 0.5) * 35.0

            val startColor = Color(200, 30, 60)
            val endColor = Color(40, 5, 20)

            WorldParticleBuilder.create(LodestoneParticleTypes.WISP_PARTICLE)
                .setColorData(ColorParticleData.create(startColor, endColor).build())
                .setScaleData(GenericParticleData.create(0.45f, 0.0f).build())
                .setTransparencyData(GenericParticleData.create(0.85f, 0.0f).build())
                .setSpinData(SpinParticleData.create(0.1f, 0.3f).build())
                .setLifetime(70)
                .setMotion(0.0, -0.04, 0.0)
                .spawn(level, px, py, pz)
        }
    }
}

package com.grindlesstudio.shattereddome.client

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.client.renderer.SkyRiftRenderer
import com.grindlesstudio.shattereddome.network.SkyEventStage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.material.FogType
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.ViewportEvent

object SkyBreakClientState {
    var isBroken: Boolean = false
    var riftProgress: Float = 0f
    var skyFlashIntensity: Float = 0f

    // Координаты начальной точки и цели реального метеорита.
    var finaleFlashActive: Boolean = false
    var finaleFlashSource: Vec3? = null
    var finaleFlashTarget: Vec3? = null

    // Стадия общей сцены синхронизируется сервером.
    var eventStage: Int = SkyEventStage.INACTIVE

    // Короткая вспышка в момент первичного разрыва неба.
    var openingFlashTicks: Int = 0
    var openingFlashIntensity: Float = 0f
}

/**
 * Встроенный трек Creator вместо сирены.
 */
class SkyBreakCreatorMusic : AbstractTickableSoundInstance(
    SoundEvents.MUSIC_DISC_CREATOR.value(),
    SoundSource.MUSIC,
    SoundInstance.createUnseededRandom()
) {
    init {
        looping = false
        delay = 0
        volume = 0.85f
        pitch = 1.0f
        relative = true
        attenuation = SoundInstance.Attenuation.NONE
    }

    override fun tick() {
        if (!SkyBreakClientState.isBroken) {
            Minecraft.getInstance().soundManager.stop(this)
        }
    }
}

@EventBusSubscriber(
    modid = ShatteredDome.MOD_ID,
    bus = EventBusSubscriber.Bus.GAME,
    value = [Dist.CLIENT]
)
object SkyBreakClientHandler {

    private var creatorMusic: SkyBreakCreatorMusic? = null
    private var creatorMusicStarted = false

    private const val FOG_R = 0.08f
    private const val FOG_G = 0.01f
    private const val FOG_B = 0.12f

    @SubscribeEvent
    @JvmStatic
    fun onComputeFogColor(event: ViewportEvent.ComputeFogColor) {
        if (!SkyBreakClientState.isBroken) return
        if (event.camera.fluidInCamera != FogType.NONE) return

        val healingFade =
            if (SkyBreakClientState.eventStage == SkyEventStage.RESTORING) {
                (0.25f + SkyBreakClientState.riftProgress * 0.75f)
                    .coerceIn(0.25f, 1f)
            } else {
                1f
            }

        event.red *= FOG_R * healingFade
        event.green *= FOG_G * healingFade
        event.blue *= FOG_B * healingFade
    }

    @SubscribeEvent
    @JvmStatic
    fun onRenderFog(event: ViewportEvent.RenderFog) {
        if (!SkyBreakClientState.isBroken) return
        if (event.camera.fluidInCamera != FogType.NONE) return

        event.scaleFarPlaneDistance(0.75f)
    }

    @SubscribeEvent
    @JvmStatic
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (
            !SkyBreakClientState.isBroken &&
            SkyBreakClientState.eventStage == SkyEventStage.INACTIVE
        ) {
            return
        }

        if (event.stage != RenderLevelStageEvent.Stage.AFTER_SKY) return
        if (event.camera.fluidInCamera != FogType.NONE) return

        val poseStack = event.poseStack
        val camera = event.camera

        poseStack.pushPose()

        poseStack.mulPose(Axis.XP.rotationDegrees(camera.xRot))
        poseStack.mulPose(Axis.YP.rotationDegrees(camera.yRot + 180.0f))

        RenderSystem.enableBlend()
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)

        try {
            SkyRiftRenderer.render(event)
        } finally {
            RenderSystem.depthMask(true)
            RenderSystem.enableCull()
            RenderSystem.enableDepthTest()
            RenderSystem.disableBlend()
            poseStack.popPose()
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()

        // Обновляем метеориты и визуальные таймеры каждый тик.
        SkyRiftRenderer.tickMeteor()
        SkyRiftRenderer.tick(mc)

        if (!SkyBreakClientState.isBroken) {
            creatorMusic?.let { mc.soundManager.stop(it) }
            creatorMusic = null
            creatorMusicStarted = false
            return
        }

        // Запускаем музыку один раз в начале события.
        if (
            !creatorMusicStarted &&
            SkyBreakClientState.eventStage == SkyEventStage.AWAKENING
        ) {
            val music = SkyBreakCreatorMusic()
            creatorMusic = music
            mc.soundManager.play(music)
            creatorMusicStarted = true
        }

        // Если трек закончился естественным образом, не запускаем его повторно.
        if (creatorMusic?.isStopped == true) {
            creatorMusic = null
        }
    }
}

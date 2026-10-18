
package com.grindlesstudio.shattereddome.client

import com.grindlesstudio.shattereddome.ShatteredDome
import com.grindlesstudio.shattereddome.client.renderer.SkyRiftRenderer
import com.grindlesstudio.shattereddome.sound.ModSounds
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
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

object SkyBreakClientState {
    var isBroken: Boolean = false
    var riftProgress: Float = 0f
    var skyFlashIntensity: Float = 0f
}

class SkyBreakSirenSound : AbstractTickableSoundInstance(
    ModSounds.SIREN_WARNING.get(),
    SoundSource.MASTER,
    SoundInstance.createUnseededRandom()
) {
    init {
        looping = true
        delay = 0
        volume = 1.0f
        pitch = 1.0f
        relative = true
        attenuation = SoundInstance.Attenuation.NONE
    }

    fun stopSiren() = stop()

    override fun tick() {
        if (!SkyBreakClientState.isBroken) {
            stop()
        }
    }
}

@EventBusSubscriber(
    modid = ShatteredDome.MOD_ID,
    bus = EventBusSubscriber.Bus.GAME,
    value = [Dist.CLIENT]
)
object SkyBreakClientHandler {

    private var siren: SkyBreakSirenSound? = null

    private const val FOG_R = 0.08f
    private const val FOG_G = 0.01f
    private const val FOG_B = 0.12f

    @SubscribeEvent
    @JvmStatic
    fun onComputeFogColor(event: ViewportEvent.ComputeFogColor) {
        if (!SkyBreakClientState.isBroken) return
        if (event.camera.fluidInCamera != FogType.NONE) return

        event.red = FOG_R
        event.green = FOG_G
        event.blue = FOG_B
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
        if (!SkyBreakClientState.isBroken) return
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

        SkyRiftRenderer.render(event)

        RenderSystem.depthMask(true)
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.disableBlend()

        poseStack.popPose()
    }

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()

        // Обновляем анимацию всегда, чтобы она сбрасывалась после остановки.
        SkyRiftRenderer.tickMeteor()

        if (SkyBreakClientState.isBroken) {
            if (
                siren == null ||
                siren!!.isStopped ||
                !mc.soundManager.isActive(siren!!)
            ) {
                siren = SkyBreakSirenSound()
                mc.soundManager.play(siren!!)
            }
        } else {
            siren?.stopSiren()
            siren = null
        }
    }
}
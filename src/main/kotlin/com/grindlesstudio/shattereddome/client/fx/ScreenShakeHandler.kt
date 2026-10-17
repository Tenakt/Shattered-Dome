package com.grindlesstudio.shattereddome.client.fx

import com.grindlesstudio.shattereddome.ShatteredDome
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.ViewportEvent
import java.util.Random

@EventBusSubscriber(modid = ShatteredDome.MOD_ID, value = [Dist.CLIENT])
object ScreenShakeHandler {
    private var shakeIntensity = 0f
    private var shakeDuration = 0
    private val random = Random()

    fun addShake(intensity: Float, duration: Int) {
        shakeIntensity = intensity.coerceAtLeast(shakeIntensity)
        shakeDuration = duration.coerceAtLeast(shakeDuration)
    }

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        if (shakeDuration > 0) {
            shakeDuration--
            shakeIntensity *= 0.92f
        } else {
            shakeIntensity = 0f
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onComputeCameraAngles(event: ViewportEvent.ComputeCameraAngles) {
        if (shakeIntensity > 0.01f) {
            val yawOffset = (random.nextFloat() - 0.5f) * shakeIntensity
            val pitchOffset = (random.nextFloat() - 0.5f) * shakeIntensity
            val rollOffset = (random.nextFloat() - 0.5f) * shakeIntensity * 0.5f

            event.yaw += yawOffset
            event.pitch += pitchOffset
            event.roll += rollOffset
        }
    }
}
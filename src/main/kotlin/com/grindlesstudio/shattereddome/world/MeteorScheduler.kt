package com.grindlesstudio.shattereddome.world

import net.minecraft.server.level.ServerLevel

object MeteorScheduler {
    private var isShowerActive: Boolean = false
    private var showerRemainingTicks: Int = 0
    private var nextMeteorTicks: Int = 0
    private var cooldownTicks: Int = -1

    fun tick(level: ServerLevel) {
        val data = DomeWorldData.get(level)

        // РЕЖИМ SKYBREAK: метеориты спавнятся с заданной в командах частотой
        if (data.isSkyBroken) {
            nextMeteorTicks--
            if (nextMeteorTicks <= 0) {
                MeteorSpawner.triggerRandomMeteorShower(level)

                val minTicks = (data.skyBreakMinIntervalSeconds * 20).coerceAtLeast(20)
                val maxTicks = (data.skyBreakMaxIntervalSeconds * 20).coerceAtLeast(minTicks)

                nextMeteorTicks = if (maxTicks > minTicks) {
                    minTicks + (Math.random() * (maxTicks - minTicks)).toInt()
                } else {
                    minTicks
                }
            }
            return
        }

        if (!data.meteorEnabled) return

        if (cooldownTicks < 0 && !isShowerActive) {
            resetCooldown(data)
        }

        if (isShowerActive) {
            showerRemainingTicks--
            nextMeteorTicks--

            if (nextMeteorTicks <= 0) {
                MeteorSpawner.triggerRandomMeteorShower(level)
                nextMeteorTicks = 300 + (Math.random() * 300).toInt()
            }

            if (showerRemainingTicks <= 0) {
                isShowerActive = false
                resetCooldown(data)
            }
        } else {
            cooldownTicks--
            if (cooldownTicks <= 0) {
                startMeteorShower()
            }
        }
    }

    // Метод принудительного запуска дождя для команд
    fun forceStartShower() {
        startMeteorShower()
    }

    private fun startMeteorShower() {
        isShowerActive = true
        showerRemainingTicks = 12000 + (Math.random() * 24000).toInt()
        nextMeteorTicks = 0
    }

    private fun resetCooldown(data: DomeWorldData) {
        val minTicks = data.meteorMinIntervalMinutes * 60 * 20
        val maxTicks = data.meteorMaxIntervalMinutes * 60 * 20
        cooldownTicks = if (maxTicks > minTicks) {
            minTicks + (Math.random() * (maxTicks - minTicks)).toInt()
        } else {
            minTicks
        }
    }
}
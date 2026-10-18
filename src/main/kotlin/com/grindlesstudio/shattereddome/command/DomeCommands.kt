
package com.grindlesstudio.shattereddome.command

import com.grindlesstudio.shattereddome.SkyBreakServerManager
import com.grindlesstudio.shattereddome.world.DomeWorldData
import com.grindlesstudio.shattereddome.world.MeteorScheduler
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object DomeCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("dome")
                .requires { source -> source.hasPermission(2) }
                .executes { context ->
                    sendHelp(context.source)
                    1
                }
                .then(
                    Commands.literal("help").executes { context ->
                        sendHelp(context.source)
                        1
                    }
                )
                .then(
                    Commands.literal("status").executes { context ->
                        val data = DomeWorldData.get(context.source.level)

                        val text = """
                            §a=== [Shattered Dome Settings] ===
                            §fРадиус купола: §e${data.borderRadius} блоков
                            §fРазлом неба (SkyBreak): §e${data.isSkyBroken}
                            §fЗвездопад активен: §e${data.isStarfallActive}
                            §fСила взрыва метеоритов: §e${data.meteorExplosionPower}
                        """.trimIndent()

                        context.source.sendSuccess(
                            { Component.literal(text) },
                            false
                        )
                        1
                    }
                )

                // --- Звездопад ---
                .then(
                    Commands.literal("starfall")
                        .then(
                            Commands.literal("start").executes { context ->
                                val data = DomeWorldData.get(context.source.level)
                                data.isStarfallActive = true
                                data.setDirty()

                                context.source.sendSuccess(
                                    { Component.literal("§a[Купол] Звездопад запущен!") },
                                    true
                                )
                                1
                            }
                        )
                        .then(
                            Commands.literal("stop").executes { context ->
                                val data = DomeWorldData.get(context.source.level)
                                data.isStarfallActive = false
                                data.setDirty()

                                context.source.sendSuccess(
                                    { Component.literal("§c[Купол] Звездопад остановлен!") },
                                    true
                                )
                                1
                            }
                        )
                )

                // --- Обычные метеориты ---
                .then(
                    Commands.literal("meteor")
                        .then(
                            Commands.literal("spawn")
                                .executes { context ->
                                    val player = context.source.playerOrException

                                    MeteorScheduler.spawnSingleMeteor(
                                        context.source.level,
                                        player
                                    )

                                    context.source.sendSuccess(
                                        { Component.literal("§c[Купол] Вызван метеорит!") },
                                        true
                                    )
                                    1
                                }
                                .then(
                                    Commands.argument(
                                        "amount",
                                        IntegerArgumentType.integer(1, 50)
                                    )
                                        .executes { context ->
                                            val player = context.source.playerOrException
                                            val amount =
                                                IntegerArgumentType.getInteger(
                                                    context,
                                                    "amount"
                                                )
                                            val level = context.source.level

                                            for (i in 0 until amount) {
                                                MeteorScheduler.spawnSingleMeteor(
                                                    level,
                                                    player
                                                )
                                            }

                                            context.source.sendSuccess(
                                                {
                                                    Component.literal(
                                                        "§c[Купол] Заспавнено метеоритов: §e$amount"
                                                    )
                                                },
                                                true
                                            )
                                            1
                                        }
                                )
                        )
                        .then(
                            Commands.literal("power")
                                .then(
                                    Commands.argument(
                                        "power",
                                        IntegerArgumentType.integer(0)
                                    )
                                        .executes { context ->
                                            val power =
                                                IntegerArgumentType.getInteger(
                                                    context,
                                                    "power"
                                                )
                                            val data =
                                                DomeWorldData.get(context.source.level)

                                            data.meteorExplosionPower = power.toFloat()
                                            data.setDirty()

                                            context.source.sendSuccess(
                                                {
                                                    Component.literal(
                                                        "§a[Купол] Сила взрыва установлена на §e$power"
                                                    )
                                                },
                                                true
                                            )
                                            1
                                        }
                                )
                        )
                )

                // --- Разлом неба ---
                .then(
                    Commands.literal("skybreak")
                        .then(
                            Commands.literal("start").executes { context ->
                                SkyBreakServerManager.startSkyBreak(
                                    context.source.level
                                )

                                context.source.sendSuccess(
                                    {
                                        Component.literal(
                                            "§a[Купол] Разлом неба запущен, начинается звездопад!"
                                        )
                                    },
                                    true
                                )
                                1
                            }
                        )
                        .then(
                            Commands.literal("stop").executes { context ->
                                SkyBreakServerManager.stopSkyBreak(
                                    context.source.level
                                )

                                context.source.sendSuccess(
                                    {
                                        Component.literal(
                                            "§c[Купол] Разлом неба и звездопад остановлены!"
                                        )
                                    },
                                    true
                                )
                                1
                            }
                        )
                )
        )
    }

    private fun sendHelp(source: CommandSourceStack) {
        val helpText = """
            §a=== [Shattered Dome Commands] ===
            §e/dome skybreak start §7- Запустить разлом неба и звездопад
            §e/dome skybreak stop §7- Остановить разлом и звездопад
            §e/dome starfall start/stop §7- Управление звездопадом
            §e/dome meteor spawn [кол-во] §7- Вызвать обычные метеориты
            §e/dome meteor power <сила> §7- Сила взрыва метеоритов
            §e/dome status §7- Посмотреть текущие настройки
        """.trimIndent()

        source.sendSuccess(
            { Component.literal(helpText) },
            false
        )
    }
}
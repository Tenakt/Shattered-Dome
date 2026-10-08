package com.grindlesstudio.shattereddome.command

import com.grindlesstudio.shattereddome.world.DomeWorldData
import com.grindlesstudio.shattereddome.world.MeteorScheduler
import com.grindlesstudio.shattereddome.world.MeteorSpawner
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object DomeCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("dome")
                .requires { source -> source.hasPermission(2) }

                // Статус
                .then(Commands.literal("status").executes { context ->
                    val data = DomeWorldData.get(context.source.level)
                    val text = """
                        §a=== [Shattered Dome Settings] ===
                        §fРадиус купола: §e${data.borderRadius} блоков
                        §fМетеориты включены: §e${data.meteorEnabled}
                        §fИнтервал метеоритов: §e${data.meteorMinIntervalMinutes}-${data.meteorMaxIntervalMinutes} мин
                        §fСила взрыва метеорита: §e${data.meteorExplosionPower}
                        §fСостояние неба (SkyBreak): §e${data.isSkyBroken}
                        §fИнтервал SkyBreak: §e${data.skyBreakMinIntervalSeconds}-${data.skyBreakMaxIntervalSeconds} сек
                        §fЗвездопад активен: §e${data.isStarfallActive}
                    """.trimIndent()
                    context.source.sendSuccess({ Component.literal(text) }, false)
                    1
                })

                // Настройки границы
                .then(Commands.literal("border")
                    .then(Commands.literal("set")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                            .executes { context ->
                                val radius = IntegerArgumentType.getInteger(context, "radius")
                                val data = DomeWorldData.get(context.source.level)
                                data.borderRadius = radius
                                data.setDirty()
                                context.source.sendSuccess({ Component.literal("§a[Купол] Радиус успешно установлен на §e$radius §aблоков!") }, true)
                                1
                            }
                        )
                    )
                )

                // Ветка Метеоритов
                .then(Commands.literal("meteor")
                    // /dome meteor spawn [число]
                    .then(Commands.literal("spawn")
                        .executes { context ->
                            val player = context.source.playerOrException
                            MeteorSpawner.spawnMeteorNearPlayer(context.source.level, player)
                            context.source.sendSuccess({ Component.literal("§c[Купол] Вызван 1 метеорит!") }, true)
                            1
                        }
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100))
                            .executes { context ->
                                val player = context.source.playerOrException
                                val amount = IntegerArgumentType.getInteger(context, "amount")
                                val level = context.source.level

                                for (i in 0 until amount) {
                                    MeteorSpawner.spawnMeteorNearPlayer(level, player)
                                }

                                context.source.sendSuccess({ Component.literal("§c[Купол] Принудительно заспавнено метеоритов: §e$amount") }, true)
                                1
                            }
                        )
                    )

                    // /dome meteor shower start
                    .then(Commands.literal("shower")
                        .then(Commands.literal("start")
                            .executes { context ->
                                MeteorScheduler.forceStartShower()
                                context.source.sendSuccess({ Component.literal("§c[Купол] Метеоритный дождь принудительно начат!") }, true)
                                1
                            }
                        )
                    )

                    // Включение / выключение авто-падения
                    .then(Commands.literal("toggle")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes { context ->
                                val enabled = BoolArgumentType.getBool(context, "enabled")
                                val data = DomeWorldData.get(context.source.level)
                                data.meteorEnabled = enabled
                                data.setDirty()
                                context.source.sendSuccess({ Component.literal("§a[Купол] Авто-падение метеоритов: §e$enabled") }, true)
                                1
                            }
                        )
                    )

                    // Настройка силы взрыва
                    .then(Commands.literal("power")
                        .then(Commands.argument("power", IntegerArgumentType.integer(0))
                            .executes { context ->
                                val power = IntegerArgumentType.getInteger(context, "power")
                                val data = DomeWorldData.get(context.source.level)
                                data.meteorExplosionPower = power.toFloat()
                                data.setDirty()
                                context.source.sendSuccess({ Component.literal("§a[Купол] Сила взрыва метеоритов установлена на §e$power") }, true)
                                1
                            }
                        )
                    )
                )

                // Ветка Состояния Неба (SkyBreak)
                .then(Commands.literal("skybreak")
                    // /dome skybreak <true|false>
                    .then(Commands.argument("broken", BoolArgumentType.bool())
                        .executes { context ->
                            val broken = BoolArgumentType.getBool(context, "broken")
                            val level = context.source.level
                            val data = DomeWorldData.get(level)

                            data.isSkyBroken = broken
                            data.setDirty()

                            // Рассылаем визуальный пакет
                            val payload = com.grindlesstudio.shattereddome.network.SyncSkyBreakPayload(broken)
                            for (player in level.players()) {
                                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload)
                            }

                            context.source.sendSuccess({ Component.literal("§a[Купол] Эффект 'SkyBreak' установлен на: §e$broken") }, true)
                            1
                        }
                    )
                    // /dome skybreak interval <мин_сек> [макс_сек]
                    .then(Commands.literal("interval")
                        .then(Commands.argument("minSeconds", IntegerArgumentType.integer(1))
                            .then(Commands.argument("maxSeconds", IntegerArgumentType.integer(1))
                                .executes { context ->
                                    val minSec = IntegerArgumentType.getInteger(context, "minSeconds")
                                    val maxSec = IntegerArgumentType.getInteger(context, "maxSeconds")
                                    val level = context.source.level
                                    val data = DomeWorldData.get(level)

                                    data.skyBreakMinIntervalSeconds = minSec
                                    data.skyBreakMaxIntervalSeconds = maxSec
                                    data.setDirty()

                                    context.source.sendSuccess({
                                        Component.literal("§a[Купол] Интервал спавна метеоритов при SkyBreak: §e$minSec - $maxSec сек.")
                                    }, true)
                                    1
                                }
                            )
                            .executes { context ->
                                val sec = IntegerArgumentType.getInteger(context, "minSeconds")
                                val level = context.source.level
                                val data = DomeWorldData.get(level)

                                data.skyBreakMinIntervalSeconds = sec
                                data.skyBreakMaxIntervalSeconds = sec
                                data.setDirty()

                                context.source.sendSuccess({
                                    Component.literal("§a[Купол] Интервал спавна метеоритов при SkyBreak: §e$sec сек.")
                                }, true)
                                1
                            }
                        )
                    )
                )
        )
    }
}
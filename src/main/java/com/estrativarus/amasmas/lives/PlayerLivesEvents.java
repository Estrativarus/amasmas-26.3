package com.estrativarus.amasmas.lives;

import com.estrativarus.amasmas.Amasmas;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = Amasmas.MOD_ID)
public class PlayerLivesEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /*
     * Inventarios pendientes de restaurar (muerte -> reaparición).
     * Que exista una entrada aquí es lo que decide si se cancelan los drops.
     */
    private static final Map<UUID, List<ItemStack>>
            INVENTARIOS_PENDIENTES = new HashMap<>();

    /*
     * Lugar de la muerte, para soltar los objetos si el jugador
     * se desconecta antes de reaparecer.
     */
    private record PosicionMuerte(ServerLevel nivel, Vec3 pos) {}

    private static final Map<UUID, PosicionMuerte>
            POSICIONES_MUERTE = new HashMap<>();

    /*
     * LOWEST: solo contamos muertes que otros listeners no hayan cancelado.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeath(LivingDeathEvent event) {

        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        MinecraftServer server = level.getServer();
        UUID uuid = player.getUUID();

        LOGGER.info("[Vidas] Muerte detectada: {} ({}) causa={}",
                player.getGameProfile().name(), uuid,
                event.getSource().type().msgId());

        // 1) Lo crítico primero: vidas e inventario.
        PlayerLivesSavedData datos = PlayerLivesSavedData.get(server);
        int vidasRestantes = datos.registrarMuerte(uuid);
        boolean debeConservar = vidasRestantes > 0;

        if (debeConservar) {
            List<ItemStack> copia = new ArrayList<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                copia.add(player.getInventory().getItem(i).copy());
            }
            INVENTARIOS_PENDIENTES.put(uuid, copia);
            POSICIONES_MUERTE.put(uuid, new PosicionMuerte(level, player.position()));
        }

        // 2) Lo cosmético, aislado para que un fallo no afecte al inventario.
        try {
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            String nombre = player.getName().getString();

            server.execute(() -> {
                try {
                    mostrarAvisoGlobalDeMuerte(server, nombre, level, x, y, z);
                } catch (Throwable t) {
                    LOGGER.error("[Vidas] Error mostrando el aviso de muerte", t);
                }
            });

            enviarMensajeVidas(player, vidasRestantes, debeConservar);
        } catch (Throwable t) {
            LOGGER.error("[Vidas] Error en las notificaciones de muerte", t);
        }
    }

    /*
     * Solo cancelamos los drops si tenemos copia del inventario.
     */
    @SubscribeEvent
    public static void onPlayerDrops(LivingDropsEvent event) {

        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (INVENTARIOS_PENDIENTES.containsKey(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    /*
     * Restauramos el inventario en el nuevo jugador creado al reaparecer.
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {

        if (!event.isWasDeath()) return;

        UUID uuid = event.getEntity().getUUID();

        List<ItemStack> copia = INVENTARIOS_PENDIENTES.remove(uuid);
        POSICIONES_MUERTE.remove(uuid);

        if (copia == null) return;

        var inventario = event.getEntity().getInventory();
        int cantidadSlots = Math.min(copia.size(), inventario.getContainerSize());

        for (int slot = 0; slot < cantidadSlots; slot++) {
            inventario.setItem(slot, copia.get(slot).copy());
        }

        inventario.setChanged();

        LOGGER.info("[Vidas] Inventario restaurado para {}", uuid);
    }

    /*
     * Si el jugador se desconecta con el inventario pendiente
     * (por ejemplo, desde la pantalla de muerte), lo soltamos
     * en el lugar de la muerte en vez de perderlo.
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {

        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID uuid = player.getUUID();
        List<ItemStack> pendientes = INVENTARIOS_PENDIENTES.remove(uuid);
        PosicionMuerte p = POSICIONES_MUERTE.remove(uuid);

        if (pendientes == null || p == null) return;

        LOGGER.warn("[Vidas] {} se desconectó sin reaparecer; soltando su inventario", uuid);

        for (ItemStack stack : pendientes) {
            if (!stack.isEmpty()) {
                p.nivel().addFreshEntity(new ItemEntity(
                        p.nivel(), p.pos().x, p.pos().y, p.pos().z, stack));
            }
        }
    }

    private static void mostrarAvisoGlobalDeMuerte(
            MinecraftServer server,
            String nombreJugador,
            ServerLevel nivel,
            double muerteX,
            double muerteY,
            double muerteZ
    ) {

        Component titulo =
                Component.literal("¡MUERTO!")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);

        Component subtitulo =
                Component.empty()
                        .append(Component.literal(nombreJugador)
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal(" ha muerto")
                                .withStyle(ChatFormatting.GRAY));

        for (ServerPlayer jugador : server.getPlayerList().getPlayers()) {

            // 10 ticks de entrada, 70 visible, 20 de salida.
            jugador.connection.send(
                    new ClientboundSetTitlesAnimationPacket(10, 70, 20));

            jugador.connection.send(
                    new ClientboundSetSubtitleTextPacket(subtitulo));

            jugador.connection.send(
                    new ClientboundSetTitleTextPacket(titulo));
        }

        CommandSourceStack source =
                server.createCommandSourceStack().withSuppressedOutput();

        server.getCommands().performPrefixedCommand(
                source,
                "execute as @a at @s run playsound "
                        + "minecraft:entity.wither.death "
                        + "master @s ~ ~ ~ 1 0.5"
        );

        Component mensajeChat =
                Component.empty()
                        .append(Component.literal("¡MUERTO! ")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                        .append(Component.literal(nombreJugador)
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal(" ha muerto.")
                                .withStyle(ChatFormatting.GRAY));

        server.getPlayerList().broadcastSystemMessage(mensajeChat, false);

        // floor para que las coordenadas negativas se redondeen bien.
        int x = (int) Math.floor(muerteX);
        int y = (int) Math.floor(muerteY);
        int z = (int) Math.floor(muerteZ);

        Component mensajeUbicacion =
                Component.empty()
                        .append(Component.literal("Lugar de la muerte: ")
                                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                        .append(Component.literal(obtenerNombreDimension(nivel))
                                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
                        .append(Component.literal(" | ")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("X: ")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.valueOf(x))
                                .withStyle(ChatFormatting.AQUA))
                        .append(Component.literal("  Y: ")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.valueOf(y))
                                .withStyle(ChatFormatting.AQUA))
                        .append(Component.literal("  Z: ")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.valueOf(z))
                                .withStyle(ChatFormatting.AQUA));

        server.getPlayerList().broadcastSystemMessage(mensajeUbicacion, false);
    }

    private static String obtenerNombreDimension(ServerLevel nivel) {

        if (nivel.dimension().equals(Level.OVERWORLD)) {
            return "Overworld";
        }

        if (nivel.dimension().equals(Level.NETHER)) {
            return "Nether";
        }

        if (nivel.dimension().equals(Level.END)) {
            return "The End";
        }

        // Dimensión de otro mod: mostramos su identificador.
        return nivel.dimension().toString();
    }

    private static void enviarMensajeVidas(
            ServerPlayer player,
            int vidasRestantes,
            boolean conservaInventario
    ) {

        Component mensaje = Component.empty()
                .append(Component.literal("Has perdido una vida. ")
                        .withStyle(ChatFormatting.RED))
                .append(Component.literal("Vidas restantes: ")
                        .withStyle(ChatFormatting.GOLD))
                .append(Component.literal(String.valueOf(vidasRestantes))
                        .withStyle(colorSegunVidas(vidasRestantes), ChatFormatting.BOLD));

        player.sendSystemMessage(mensaje);

        if (conservaInventario) {
            player.sendSystemMessage(
                    Component.literal("Has conservado tu inventario.")
                            .withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(
                    Component.literal("No te quedan vidas: tus objetos se han soltado.")
                            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        }
    }

    private static ChatFormatting colorSegunVidas(int vidas) {
        return switch (vidas) {
            case 3 -> ChatFormatting.GREEN;
            case 2 -> ChatFormatting.YELLOW;
            case 1 -> ChatFormatting.GOLD;
            default -> ChatFormatting.RED;
        };
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {

        MinecraftServer server = event.getServer();

        // Desactivamos el mensaje vanilla de muerte: el mod envía el suyo.
        CommandSourceStack source =
                server.createCommandSourceStack().withSuppressedOutput();

        server.getCommands().performPrefixedCommand(
                source,
                "gamerule minecraft:show_death_messages false"
        );
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {

        // Salir del End no es una reaparición por muerte.
        if (event.isEndConquered()) return;

        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID uuid = player.getUUID();
        MinecraftServer server = player.level().getServer();

        // Si aún le quedan vidas, no hacemos nada.
        if (PlayerLivesSavedData.get(server).getVidas(uuid) > 0) return;

        boolean esOperador =
                player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);

        String nombre = player.getGameProfile().name();

        // Ejecutamos después de terminar el proceso actual de reaparición.
        server.execute(() -> {

            CommandSourceStack source =
                    server.createCommandSourceStack().withSuppressedOutput();

            server.getCommands().performPrefixedCommand(
                    source,
                    "gamemode spectator " + nombre
            );

            if (esOperador) {

                player.sendSystemMessage(
                        Component.literal(
                                "Has agotado todas tus vidas. Permanecerás como espectador porque eres operador."
                        ).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                );

            } else {

                server.getCommands().performPrefixedCommand(
                        source,
                        "ban " + nombre + " Has agotado todas tus vidas"
                );
            }
        });
    }

    private PlayerLivesEvents() {
    }
}
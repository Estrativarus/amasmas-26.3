package com.estrativarus.amasmas.lives;

import com.estrativarus.amasmas.Amasmas;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerLivesSavedData extends SavedData {

    public static final int VIDAS_INICIALES = 3;
    public static final int VIDAS_MAXIMAS = 3;

    public static final SavedDataType<PlayerLivesSavedData> TYPE =
            new SavedDataType<>(
                    Identifier.fromNamespaceAndPath(
                            Amasmas.MOD_ID,
                            "vidas_jugadores"
                    ),

                    PlayerLivesSavedData::new,

                    RecordCodecBuilder.create(instance ->
                            instance.group(
                                    Codec.unboundedMap(
                                                    Codec.STRING,
                                                    Codec.INT
                                            )
                                            .optionalFieldOf(
                                                    "vidas",
                                                    Map.of()
                                            )
                                            .forGetter(
                                                    data -> data.vidasPorJugador
                                            )
                            ).apply(
                                    instance,
                                    PlayerLivesSavedData::new
                            )
                    )
            );

    /*
     * Usamos el UUID convertido en texto como clave.
     *
     * Ejemplo:
     * "550e8400-e29b-41d4-a716-446655440000" -> 2
     */


    private final Map<String, Integer> vidasPorJugador;

    /*
     * Constructor para un mundo nuevo.
     */
    public PlayerLivesSavedData() {
        this(new HashMap<>());
    }

    /*
     * Constructor utilizado al cargar los datos.
     *
     * Creamos un HashMap mutable porque el mapa recibido
     * por el Codec podría no permitir modificaciones.
     */
    public PlayerLivesSavedData(
            Map<String, Integer> vidasPorJugador
    ) {
        this.vidasPorJugador =
                new HashMap<>(vidasPorJugador);
    }

    public static PlayerLivesSavedData get(
            MinecraftServer server
    ) {
        return server
                .getDataStorage()
                .computeIfAbsent(TYPE);
    }

    /*
     * Si el jugador todavía no aparece en el mapa,
     * tendrá automáticamente tres vidas.
     */
    public int getVidas(UUID uuid) {
        return vidasPorJugador.getOrDefault(
                uuid.toString(),
                VIDAS_INICIALES
        );
    }

    public void establecerVidas(
            UUID uuid,
            int cantidad
    ) {
        int cantidadLimitada =
                Math.max(
                        0,
                        Math.min(cantidad, VIDAS_MAXIMAS)
                );

        vidasPorJugador.put(
                uuid.toString(),
                cantidadLimitada
        );

        this.setDirty();
    }

    public int anadirVidas(
            UUID uuid,
            int cantidad
    ) {
        if (cantidad <= 0) {
            return getVidas(uuid);
        }

        int nuevasVidas =
                Math.min(
                        VIDAS_MAXIMAS,
                        getVidas(uuid) + cantidad
                );

        establecerVidas(uuid, nuevasVidas);

        return nuevasVidas;
    }

    public int quitarVidas(
            UUID uuid,
            int cantidad
    ) {
        if (cantidad <= 0) {
            return getVidas(uuid);
        }

        int nuevasVidas =
                Math.max(
                        0,
                        getVidas(uuid) - cantidad
                );

        establecerVidas(uuid, nuevasVidas);

        return nuevasVidas;
    }

    /*
     * Resta una vida y devuelve la cantidad restante.
     */
    public int registrarMuerte(UUID uuid) {

        int vidasRestantes =
                Math.max(0, getVidas(uuid) - 1);

        establecerVidas(uuid, vidasRestantes);

        return vidasRestantes;
    }
}
package com.estrativarus.amasmas.event;

import com.estrativarus.amasmas.Amasmas;
import com.estrativarus.amasmas.day.SistemaDiasSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

@EventBusSubscriber(modid = Amasmas.MOD_ID)
public final class Day21BrokenElytraEvents {

    private static final int DIA_INICIO =
            21;

    private static final String TAG_REVISADO =
            "amasmas_elytra_end_revisada";

    @SubscribeEvent
    public static void onItemFrameJoinLevel(
            EntityJoinLevelEvent event
    ) {

        if (!(event.getLevel()
                instanceof ServerLevel level)) {

            return;
        }

        if (!(event.getEntity()
                instanceof ItemFrame itemFrame)) {

            return;
        }

        if (level.dimension()
                != Level.END) {

            return;
        }

        if (event.loadedFromDisk()) {
            return;
        }

        if (itemFrame
                .getPersistentData()
                .contains(
                        TAG_REVISADO
                )) {

            return;
        }

        itemFrame
                .getPersistentData()
                .putBoolean(
                        TAG_REVISADO,
                        true
                );

        level.getServer().execute(() ->
                romperElytraSiCorresponde(
                        level,
                        itemFrame
                )
        );
    }

    private static void romperElytraSiCorresponde(
            ServerLevel level,
            ItemFrame itemFrame
    ) {

        if (itemFrame.isRemoved()) {
            return;
        }

        int diaActual =
                SistemaDiasSavedData
                        .get(level.getServer())
                        .getDiaActual();

        if (diaActual < DIA_INICIO) {
            return;
        }

        ItemStack contenido =
                itemFrame.getItem();

        if (!contenido.is(
                Items.ELYTRA
        )) {

            return;
        }

        if (!contenido.isDamageableItem()) {
            return;
        }

        ItemStack elytraRota =
                contenido.copy();

        int danoMaximo =
                elytraRota.getMaxDamage()
                        - 1;

        elytraRota.setDamageValue(
                danoMaximo
        );

        itemFrame.setItem(
                elytraRota,
                false
        );
    }

    private Day21BrokenElytraEvents() {
    }
}
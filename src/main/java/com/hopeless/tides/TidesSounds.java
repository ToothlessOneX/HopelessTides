package com.hopeless.tides;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 0.8.0: the water's sounds (assets/hopelesstides/sounds.json; CC0 recordings, see the README). Registered on both sides. */
public final class TidesSounds {
    private TidesSounds() {}
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, HopelessTides.MODID);
    static RegistryObject<SoundEvent> reg(String n) { return SOUNDS.register(n, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(HopelessTides.MODID, n))); }
    public static final RegistryObject<SoundEvent> OCEAN = reg("water.ocean"), SHORE = reg("water.shore"), WADE = reg("water.wade");
    public static void register(IEventBus modBus) { SOUNDS.register(modBus); }
}

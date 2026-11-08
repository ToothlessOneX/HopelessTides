package com.hopeless.tides;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 0.7.0: the splash particle types, registered on both sides so the registries match (drawn by client.TidesParticles). */
public final class TidesParticleTypes {
    private TidesParticleTypes() {}
    public static final DeferredRegister<ParticleType<?>> TYPES = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, HopelessTides.MODID);
    public static final RegistryObject<SimpleParticleType> DROP = TYPES.register("drop", () -> new SimpleParticleType(false) {});
    public static final RegistryObject<SimpleParticleType> MIST = TYPES.register("mist", () -> new SimpleParticleType(false) {});
    public static final RegistryObject<SimpleParticleType> EMBER = TYPES.register("ember", () -> new SimpleParticleType(false) {});   // 0.12.0
    public static void register(IEventBus modBus) { TYPES.register(modBus); }
}

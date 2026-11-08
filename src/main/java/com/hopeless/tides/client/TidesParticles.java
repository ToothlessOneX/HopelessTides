package com.hopeless.tides.client;

import com.hopeless.tides.HopelessTides;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.tags.FluidTags;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 0.7.0: the splash particles (Hydro's splash layers). A drop flies under real gravity (9.81 m/s2) and is gone when it falls back
 *  into water; mist rises a little, spreads and fades. Textures are vanilla's splash and generic sprites. */
public final class TidesParticles {
    private TidesParticles() {}
    public static final RegistryObject<SimpleParticleType> DROP = com.hopeless.tides.TidesParticleTypes.DROP, MIST = com.hopeless.tides.TidesParticleTypes.MIST, EMBER = com.hopeless.tides.TidesParticleTypes.EMBER;
    public static void providers(RegisterParticleProvidersEvent e) {
        e.registerSpriteSet(DROP.get(), s -> (ParticleProvider<SimpleParticleType>) (t, l, x, y, z, vx, vy, vz) -> new Drop(l, x, y, z, vx, vy, vz, s));
        e.registerSpriteSet(EMBER.get(), s -> (ParticleProvider<SimpleParticleType>) (t, l, x, y, z, vx, vy, vz) -> new Ember(l, x, y, z, vx, vy, vz, s));
        e.registerSpriteSet(MIST.get(), s -> (ParticleProvider<SimpleParticleType>) (t, l, x, y, z, vx, vy, vz) -> new Mist(l, x, y, z, vx, vy, vz, s));
    }

    static final class Drop extends TextureSheetParticle {
        final SpriteSet sprites; boolean rose;
        Drop(ClientLevel l, double x, double y, double z, double vx, double vy, double vz, SpriteSet s) {
            super(l, x, y, z); sprites = s; xd = vx; yd = vy; zd = vz;
            gravity = (float) (0.613 * com.hopeless.tides.Tides.gravity(level) / 9.81);   // 0.13.0: the planet's gravity                                  // vanilla moves yd by -0.04 x gravity a tick: 9.81 m/s2 = 0.0245 blocks/tick2
            friction = 0.99f; lifetime = 60; quadSize = 0.05f + random.nextFloat() * 0.06f; hasPhysics = true;
            setColor(0.85f, 0.9f, 1f); alpha = 0.85f; pickSprite(s);
        }
        @Override public void tick() {
            super.tick();
            if (yd > 0) rose = true;
            if (yd < 0 && level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER)) remove();   // back in the water
            if (onGround) remove();
        }
        @Override public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }
    }
    static final class Mist extends TextureSheetParticle {
        final SpriteSet sprites;
        Mist(ClientLevel l, double x, double y, double z, double vx, double vy, double vz, SpriteSet s) {
            super(l, x, y, z); sprites = s; xd = vx; yd = vy; zd = vz; gravity = -0.02f; friction = 0.93f;
            lifetime = 30 + random.nextInt(20); quadSize = 0.35f + random.nextFloat() * 0.25f; setColor(0.95f, 0.97f, 1f); alpha = 0.35f; hasPhysics = false; setSpriteFromAge(s);
        }
        @Override public void tick() { super.tick(); setSpriteFromAge(sprites); quadSize *= 1.02f; alpha = 0.35f * (1f - (float) age / lifetime); }
        @Override public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }
    }
    /** 0.12.0: an ember from lava: rises on the hot air (buoyant, slowing), drifts, flickers, cools from yellow to dark red and is gone
     *  in 1-2 s; drawn at full brightness (it glows). Texture made for the mod. */
    static final class Ember extends TextureSheetParticle {
        final SpriteSet sprites; final float flick;
        Ember(ClientLevel l, double x, double y, double z, double vx, double vy, double vz, SpriteSet s) {
            super(l, x, y, z); sprites = s; xd = vx; yd = vy; zd = vz; gravity = -0.05f; friction = 0.96f; hasPhysics = false;
            lifetime = 20 + random.nextInt(25); quadSize = 0.03f + random.nextFloat() * 0.035f; flick = random.nextFloat() * 6.28f; setSpriteFromAge(s);
        }
        @Override public void tick() {
            super.tick(); setSpriteFromAge(sprites); float k = (float) age / lifetime;
            xd += (random.nextFloat() - 0.5f) * 0.004f; zd += (random.nextFloat() - 0.5f) * 0.004f;
            setColor(1f, 0.85f - 0.55f * k, 0.45f - 0.4f * k); alpha = (1f - k * k) * (0.75f + 0.25f * (float) Math.sin(age * 1.7f + flick));
        }
        @Override protected int getLightColor(float pt) { return 0xF000F0; }
        @Override public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }
    }
}

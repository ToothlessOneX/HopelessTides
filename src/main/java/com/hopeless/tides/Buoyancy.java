package com.hopeless.tides;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/**
 * 0.5.0: how a dropped item floats, with three numbers per kind of thing as Star Citizen gives each floating object
 * (SEntityBuoyancyParams: density, resistance, damping; their icebergs 0.1-0.5, metal debris 1, canisters 160+).
 * Here density is against water (1.0): below 1 the item floats, riding lower the denser it is; above 1 it sinks, slower the higher
 * its resistance. Damping is how fast its bobbing settles.
 */
public final class Buoyancy {
    private Buoyancy() {}
    public record Kind(String name, double density, double resistance, double damping) {}
    public static final Kind LIGHT = new Kind("light", 0.25, 1, 0.6), WOOD = new Kind("wood", 0.6, 1, 0.5), ICE = new Kind("ice", 0.92, 1, 0.5),
        PLAIN = new Kind("plain", 0.5, 1, 0.6), STONE = new Kind("stone", 2.6, 2, 0.8), METAL = new Kind("metal", 7.8, 1.5, 0.8);
    public static volatile long light, wood, ice, plain, stone, metal, sinking;   // probes
    public static Kind of(ItemStack s) {
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(ItemTags.WOODEN_SLABS) || s.is(ItemTags.WOODEN_STAIRS) || s.is(ItemTags.BOATS) || s.is(ItemTags.SAPLINGS) || s.is(net.minecraft.world.item.Items.STICK) || s.is(net.minecraft.world.item.Items.BOWL)) { wood++; return WOOD; }
        if (s.is(ItemTags.WOOL) || s.is(ItemTags.LEAVES) || s.is(net.minecraft.world.item.Items.FEATHER) || s.is(net.minecraft.world.item.Items.PAPER) || s.is(net.minecraft.world.item.Items.STRING) || s.is(net.minecraft.world.item.Items.SNOWBALL)) { light++; return LIGHT; }
        if (s.is(net.minecraft.world.item.Items.ICE) || s.is(net.minecraft.world.item.Items.PACKED_ICE) || s.is(net.minecraft.world.item.Items.BLUE_ICE)) { ice++; return ICE; }
        if (s.is(net.minecraftforge.common.Tags.Items.INGOTS) || s.is(net.minecraftforge.common.Tags.Items.NUGGETS) || s.is(net.minecraftforge.common.Tags.Items.STORAGE_BLOCKS) || s.is(net.minecraftforge.common.Tags.Items.RAW_MATERIALS)
            || s.is(net.minecraft.world.item.Items.ANVIL) || s.is(net.minecraft.world.item.Items.IRON_BARS) || s.is(net.minecraft.world.item.Items.CHAIN)) { metal++; return METAL; }
        if (s.is(net.minecraftforge.common.Tags.Items.STONE) || s.is(net.minecraftforge.common.Tags.Items.COBBLESTONE) || s.is(net.minecraftforge.common.Tags.Items.ORES) || s.is(net.minecraftforge.common.Tags.Items.GEMS)
            || s.is(ItemTags.STONE_BRICKS) || s.is(net.minecraft.world.item.Items.BRICKS) || s.is(net.minecraft.world.item.Items.FLINT)) { stone++; return STONE; }
        plain++; return PLAIN;
    }
}

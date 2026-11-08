package com.hopeless.tides;
/** A boat's wave tilt (degrees), smoothed between ticks, for the renderer. */
public interface BoatTilt { float tides$pitch(float partial); float tides$roll(float partial); }

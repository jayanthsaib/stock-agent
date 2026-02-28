package com.jay.stagent.model.enums;

public enum MarketRegime {
    BULL,           // Nifty above 200 DMA, VIX < 15, FII buying
    BEAR,           // Nifty below 200 DMA, VIX > 25, sustained FII selling
    SIDEWAYS,       // Nifty in ±5% range for 3+ months, low volume
    HIGH_VOLATILITY // VIX 20-25, erratic moves
}

package app.quantusnode.node;

import android.graphics.Color;

/**
 * Design tokens: the single home for the palette, the type
 * scale, the spacing scale and the shared dimensions (UI/UX
 * design v2, section 13.1). The values are the ones the UI
 * already used when this file was extracted in the UI/UX
 * phase 1 work; changing a value here is a design change,
 * not a refactor.
 */
final class Tokens {

    private Tokens() {
    }

    // Palette. The base is the official Quantus colors:
    // void #0E0E0E, surface #181818, flare #FF6B35,
    // content #E8E6E0.
    static final int VOID = Color.rgb(0x0E, 0x0E, 0x0E);
    static final int SURFACE = Color.rgb(0x18, 0x18, 0x18);
    static final int SURFACE2 = Color.rgb(0x20, 0x20, 0x20);
    static final int CONTENT = Color.rgb(0xE8, 0xE6, 0xE0);
    static final int MUTED = Color.rgb(0x91, 0x90, 0x8C);
    /** Text meant to be read at small sizes (5.08:1 on surface). */
    static final int FAINT = Color.rgb(0x8A, 0x89, 0x86);
    /** Pure decoration only: dots, rules, stopped state. */
    static final int DECOR = Color.rgb(0x65, 0x64, 0x62);
    static final int HAIRLINE = Color.rgb(0x28, 0x28, 0x27);
    /** The card line: content at 8%. */
    static final int LINE = Color.argb(20, 0xE8, 0xE6, 0xE0);
    static final int FIELD_BORDER = Color.rgb(0x4F, 0x4F, 0x4D);
    static final int FLARE = Color.rgb(0xFF, 0x6B, 0x35);
    /** Flare at 12%, for ripples. */
    static final int FLARE_RIPPLE = Color.argb(0x1F, 0xFF, 0x6B, 0x35);
    /** For alerts; always paired with wording and an icon. */
    static final int ALERT = Color.rgb(0xFF, 0x4D, 0x6D);
    static final int STATE_STOPPED = DECOR;
    static final int STATE_STARTING = Color.rgb(0x93, 0xB4, 0xEC);
    static final int STATE_SYNCING = Color.rgb(0xF2, 0xA9, 0x3B);
    static final int STATE_SYNCED = Color.rgb(0x4A, 0xDE, 0x80);
    static final int STATE_FAILED = Color.rgb(0xF2, 0xA2, 0x98);

    // Type scale (sp).
    static final int HERO_MAX = 56;
    static final int DISPLAY_L = 44;
    static final int DISPLAY_M = 28;
    static final int TITLE = 20;
    static final int BODY_L = 16;
    static final int BODY = 14;
    static final int CAPTION = 13;
    static final int MICRO = 11;

    // Spacing scale (dp).
    static final int S4 = 4;
    static final int S8 = 8;
    static final int S12 = 12;
    static final int S16 = 16;
    static final int S24 = 24;
    static final int S32 = 32;

    // Shared dimensions (dp).
    static final int ROW_H = 56;
    static final int TAP_MIN = 48;
    static final int CARD_RADIUS = 24;
    static final int CARD_PAD = 26;
    static final int GAP = 16;
    static final int SIDE = 24;
}

package com.oAT.web.common;

public final class PaletteColors {

    private static final String[] PRIMARY_COLORS = {"#00b5ad", "#fbbd08", "#f2711c", "#db2828", "#2185d0", "#FF9A8A", "#b5cc18", "#a333c8", "#00b5cc", "#21ba45", "#f2c037", "#e07b53"};
    private static final String[] ACCENT_COLORS = {"#66d5d0", "#fbdc78", "#ffb26b", "#f47f7f", "#67aee6", "#ffb8b0", "#c9da5c", "#c28ddc", "#66d8ea", "#6fd681", "#f6d06c", "#f09b79"};
    private static final String[] HALO_COLORS = {"rgba(0,181,173,0.18)", "rgba(251,189,8,0.18)", "rgba(242,113,28,0.18)",
            "rgba(219,40,40,0.18)", "rgba(33,133,208,0.18)", "rgba(255,154,138,0.18)", "rgba(181,204,24,0.18)",
            "rgba(163,51,200,0.18)", "rgba(0,181,204,0.18)", "rgba(33,186,69,0.18)", "rgba(242,192,55,0.18)", "rgba(224,123,83,0.18)"};

    private PaletteColors() {
    }

    public static String pickPrimary(int seed) {
        return pick(PRIMARY_COLORS, seed);
    }

    public static String pickAccent(int seed) {
        return pick(ACCENT_COLORS, seed);
    }

    public static String pickHalo(int seed) {
        return pick(HALO_COLORS, seed);
    }

    private static String pick(String[] values, int seed) {
        if (values.length == 0) {
            return "";
        }
        return values[seed % values.length];
    }
}

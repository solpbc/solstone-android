// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package solstone.i18n;

public final class Select {
    private Select() {}

    public static String resolveResourceKey(String messageId, String enumArg) {
        switch (messageId) {
            case "push.proof.count": return "m707573682e70726f6f662e636f756e74";
            case "push.proof.state":
                if (enumArg == null) throw new IllegalArgumentException("Missing enum argument");
                switch (enumArg) {
                    case "ready": return "m707573682e70726f6f662e7374617465_v7265616479";
                    case "waiting": return "m707573682e70726f6f662e7374617465_v77616974696e67";
                    default: return "m707573682e70726f6f662e7374617465_v77616974696e67";
                }
            case "push.test.body": return "m707573682e746573742e626f6479";
            case "push.test.title": return "m707573682e746573742e7469746c65";
            default: throw new IllegalArgumentException("Unknown message identity");
        }
    }

    public static String formatIntegerUngrouped(long val) {
        return Long.toString(val);
    }

    public static String formatIntegerLocale(long val, String locale) {
        android.icu.util.ULocale uloc = new android.icu.util.ULocale(locale);
        return android.icu.text.NumberFormat.getInstance(uloc).format(val);
    }
}

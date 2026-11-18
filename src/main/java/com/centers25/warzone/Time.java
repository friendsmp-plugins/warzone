package com.centers25.warzone;

import java.util.List;
import java.util.regex.Pattern;

final class Time {
    static final List<String> SUGGESTIONS = List.of("30s", "1m", "5m", "15m", "30m", "1h", "5h", "12h", "24h");
    private static final Pattern PATTERN = Pattern.compile("(?i)([1-9][0-9]*)([smh])");

    static long parse(String text) {
        var match = PATTERN.matcher(text);
        if (!match.matches()) throw new IllegalArgumentException("Use a duration like <hl>30s</hl>, <hl>5m</hl> or <hl>5h</hl>.");
        long unit = switch (Character.toLowerCase(match.group(2).charAt(0))) {
            case 's' -> 1_000L;
            case 'm' -> 60_000L;
            default -> 3_600_000L;
        };
        try {
            long millis = Math.multiplyExact(Long.parseLong(match.group(1)), unit);
            Math.addExact(System.currentTimeMillis(), millis);
            return millis;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("That duration is too large.");
        }
    }

    static long parseCooldown(String text) {
        return text.matches("(?i)0[smh]") ? 0 : parse(text);
    }

    static String format(long millis) {
        long s = Math.max(0, (millis + 999) / 1000);
        String text = (s >= 3600 ? s / 3600 + "h " : "") + (s % 3600 >= 60 ? s % 3600 / 60 + "m " : "");
        return (s % 60 > 0 || text.isEmpty() ? text + s % 60 + "s" : text).trim();
    }
}

package com.example.inventory.util;

public final class CsvUtils {
    
    private CsvUtils() {
        // Prevent instantiation
    }

    public static String escape(String s) {
        if (s == null) return "";
        String escaped = s.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}

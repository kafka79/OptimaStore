package com.example.inventory.util;

public final class CsvUtils {
    
    private CsvUtils() {
        // Prevent instantiation
    }

    public static String escape(String s) {
        if (s == null) return "";
        String escaped = s.replace("\"", "\"\"");
        
        String trimmed = escaped.trim();
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") || trimmed.startsWith("@") || trimmed.startsWith("\t") || trimmed.startsWith("\r")) {
            escaped = "'" + escaped;
        }

        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}

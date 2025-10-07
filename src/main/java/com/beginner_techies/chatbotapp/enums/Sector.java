package com.beginner_techies.chatbotapp.enums;

public enum Sector {
    GOVERNMENT, PRIVATE, OTHER;

    public static Sector fromCode(String code) {
        if (code == null) return OTHER;
        code = code.trim().toUpperCase();
        return switch (code) {
            case "G", "GOV", "GOVERNMENT", "حكومي" -> GOVERNMENT;
            case "P", "PRV", "PRIVATE", "خاص" -> PRIVATE;
            default -> OTHER;
        };
    }
}
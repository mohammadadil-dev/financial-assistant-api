package com.beginner_techies.chatbotapp.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.beginner_techies.chatbotapp.dto.IntentResult;
import com.beginner_techies.chatbotapp.enums.Intent;

public class EmiHeuristics {

    private static final Pattern EN = Pattern.compile(
            "amount\\s*(\\d[\\d,]*)\\s*.*?tenure\\s*(\\d{1,3})\\s*(?:month|months)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AR = Pattern.compile(
            "مبلغ\\s*(\\d[\\d,]*)\\s*.*?مدة\\s*(\\d{1,3})\\s*(?:شهر|أشهر)",
            Pattern.CASE_INSENSITIVE);

    public IntentResult tryParse(String msg) {
        if (msg == null || msg.isBlank()) return null;
        String m = normalizeDigits(msg);

        Matcher ar = AR.matcher(m);
        if (ar.find()) {
            var r = baseEmi();
            r.amount = parseDouble(ar.group(1));
            r.tenureMonths = parseInt(ar.group(2));
            return r;
        }

        Matcher en = EN.matcher(m);
        if (en.find()) {
            var r = baseEmi();
            r.amount = parseDouble(en.group(1));
            r.tenureMonths = parseInt(en.group(2));
            return r;
        }

        // Short triggers: "emi", "حاسبة القسط"
        String lower = m.toLowerCase();
        if (lower.contains("emi") || m.contains("حاسبة") || m.contains("قسط")) {
            return baseEmi();
        }
        return null;
    }

    private static IntentResult baseEmi() {
        var r = new IntentResult();
        r.intent = Intent.EMI_CALC;
        r.annualRate = null; // internal decision
        r.currency = "SAR";
        return r;
    }

    private static double parseDouble(String s) { return Double.parseDouble(s.replace(",", "").trim()); }
    private static int parseInt(String s) { return Integer.parseInt(s.replace(",", "").trim()); }

    private static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cp = c;
            if (cp >= 0x0660 && cp <= 0x0669) { out.append((char)('0' + (cp - 0x0660))); }
            else if (cp >= 0x06F0 && cp <= 0x06F9) { out.append((char)('0' + (cp - 0x06F0))); }
            else { out.append(c); }
        }
        return out.toString();
    }
}

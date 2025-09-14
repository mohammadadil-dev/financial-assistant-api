package com.beginner_techies.chatbotapp.util;

import java.util.regex.Pattern;

import com.beginner_techies.chatbotapp.dto.IntentResult;
import com.beginner_techies.chatbotapp.enums.Intent;

public class EligibilityHeuristics {

	// Examples accepted:
	// "income 12000", "دخل 12000", "employer government/contract/private/self",
	// "جهة العمل حكومي"
	// "service 12 months", "خدمة 12 شهر", "loans yes/no", "قروض موجودة نعم/لا",
	// "Indian / سعودي"
	private static final Pattern INCOME_EN = Pattern.compile("\\b(income|salary)\\s*(\\d[\\d,]*)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern INCOME_AR = Pattern.compile("(?:(?:دخل|راتب)\\s*)(\\d[\\d,]*)");

	private static final Pattern SERVICE_EN = Pattern.compile("\\bservice\\s*(\\d{1,3})\\s*(?:month|months)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern SERVICE_AR = Pattern.compile("خدمة\\s*(\\d{1,3})\\s*(?:شهر|أشهر)");

	public static IntentResult tryParse(String msg) {
		if (msg == null || msg.isBlank())
			return null;
		String m = normalizeDigits(msg);

		var r = new IntentResult();
		boolean touched = false;
		r.intent = Intent.ELIGIBILITY_SLOT_UPDATE;

		var incAr = INCOME_AR.matcher(m);
		var incEn = INCOME_EN.matcher(m);
		if (incAr.find()) {
			r.monthlyIncome = parseDouble(incAr.group(1));
			touched = true;
		} else if (incEn.find()) {
			r.monthlyIncome = parseDouble(incEn.group(2));
			touched = true;
		}

		var srvAr = SERVICE_AR.matcher(m);
		var srvEn = SERVICE_EN.matcher(m);
		if (srvAr.find()) {
			r.serviceMonths = parseInt(srvAr.group(1));
			touched = true;
		} else if (srvEn.find()) {
			r.serviceMonths = parseInt(srvEn.group(1));
			touched = true;
		}

		if (m.contains("government") || m.contains("حكومي")) {
			r.employerType = "government";
			touched = true;
		} else if (m.contains("private") || m.contains("خاص")) {
			r.employerType = "private";
			touched = true;
		} else if (m.contains("contract") || m.contains("متعاقد")) {
			r.employerType = "contract";
			touched = true;
		} else if (m.contains("self") || m.contains("عمل حر")) {
			r.employerType = "self-employed";
			touched = true;
		}

		if (m.matches(".*\\b(loans?\\s*yes|قروض\\s*موجودة\\s*نعم)\\b.*")) {
			r.hasExistingLoans = true;
			touched = true;
		}
		if (m.matches(".*\\b(loans?\\s*no|قروض\\s*موجودة\\s*لا)\\b.*")) {
			r.hasExistingLoans = false;
			touched = true;
		}

		if (m.contains("سعودي")) {
			r.nationality = "Saudi";
			touched = true;
		} else if (m.toLowerCase().contains("indian") || m.contains("هندي")) {
			r.nationality = "Indian";
			touched = true;
		}

		return touched ? r : null;
	}

	private static double parseDouble(String s) {
		return Double.parseDouble(s.replace(",", "").trim());
	}

	private static int parseInt(String s) {
		return Integer.parseInt(s.replace(",", "").trim());
	}

	private static String normalizeDigits(String s) {
		if (s == null)
			return "";
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			int cp = c;
			if (cp >= 0x0660 && cp <= 0x0669) {
				out.append((char) ('0' + (cp - 0x0660)));
			} else if (cp >= 0x06F0 && cp <= 0x06F9) {
				out.append((char) ('0' + (cp - 0x06F0)));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}
}

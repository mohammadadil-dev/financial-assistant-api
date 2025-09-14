package com.beginner_techies.chatbotapp.util;

public final class LanguageUtil {
	private LanguageUtil() {
	}

	public static boolean isArabic(String s) {
		if (s == null)
			return false;
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if ((ch >= 0x0600 && ch <= 0x06FF) || (ch >= 0x0750 && ch <= 0x077F) || (ch >= 0x08A0 && ch <= 0x08FF)
					|| (ch >= 0xFB50 && ch <= 0xFDFF) || (ch >= 0xFE70 && ch <= 0xFEFF))
				return true;
		}
		return false;
	}
}

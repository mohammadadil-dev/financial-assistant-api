package com.beginner_techies.chatbotapp.enums;

public enum LoanType {
	PERSONAL, MORTGAGE, HOME, AUTO;

	// Simple aliases if you prefer to normalize
	public static LoanType fromText(String s) {
		if (s == null)
			return null;
		String x = s.toLowerCase();
		if (x.contains("personal") || x.contains("تمويل شخصي"))
			return PERSONAL;
		if (x.contains("mortgage") || x.contains("رهن"))
			return MORTGAGE;
		if (x.contains("home") || x.contains("سكني"))
			return HOME;
		if (x.contains("car") || x.contains("auto") || x.contains("vehicle") || x.contains("سيارة"))
			return AUTO;
		return null;
	}

	// Optionally unify HOME→MORTGAGE
	public static LoanType normalize(LoanType t) {
		if (t == HOME)
			return MORTGAGE;
		return t;
	}
}

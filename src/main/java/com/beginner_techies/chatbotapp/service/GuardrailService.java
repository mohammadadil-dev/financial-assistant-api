package com.beginner_techies.chatbotapp.service;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Guardrails layer:
 *  - maskPii(): output filter — masks card-like and national-ID-like digit runs
 *    before model text reaches the user or the audit log.
 *  - isInjectionAttempt(): conservative prompt-injection heuristics, checked
 *    before free text is sent to the LLM. Deterministic (rule-engine) replies
 *    never pass through the LLM, so they are immune by construction.
 */
@Service
public class GuardrailService {

	// 15-16 digit runs (cards), allowing space/dash separators
	private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){14,15}\\d\\b");
	// 10-digit runs (National ID / Iqama / mobile) → keep last 4
	private static final Pattern TEN_DIGITS = Pattern.compile("\\b(\\d{6})(\\d{4})\\b");
	// 3-4 digit codes following CVV/PIN/OTP keywords
	private static final Pattern SECRET_CODE = Pattern
			.compile("(?i)\\b(cvv|pin|otp|password|رمز)\\s*[:=]?\\s*\\d{3,8}");

	public String maskPii(String text) {
		if (text == null || text.isBlank())
			return text;
		String out = CARD.matcher(text).replaceAll("**** **** **** ****");
		out = TEN_DIGITS.matcher(out).replaceAll("******$2");
		out = SECRET_CODE.matcher(out).replaceAll("$1 ******");
		return out;
	}

	private static final List<Pattern> INJECTION = List.of(
			Pattern.compile("(?i)ignore\\s+(all|any|previous|prior|the|above)\\s+(instructions|rules|prompts)"),
			Pattern.compile("(?i)disregard\\s+(your|the|all)\\s+(instructions|rules|guidelines)"),
			Pattern.compile("(?i)(reveal|show|print|repeat|output).{0,40}(system\\s+prompt|your\\s+instructions|your\\s+rules)"),
			Pattern.compile("(?i)\\b(jailbreak|developer\\s+mode|dan\\s+mode)\\b"),
			Pattern.compile("(?i)pretend\\s+(you\\s+are|to\\s+be)\\s+(not\\s+)?an?\\s+"),
			Pattern.compile("(?i)you\\s+are\\s+no\\s+longer\\s+"),
			Pattern.compile("تجاهل\\s+(كل\\s+)?التعليمات"),
			Pattern.compile("اكشف\\s+(عن\\s+)?(التعليمات|البرومبت)"));

	public boolean isInjectionAttempt(String text) {
		if (text == null || text.isBlank())
			return false;
		for (Pattern p : INJECTION) {
			if (p.matcher(text).find())
				return true;
		}
		return false;
	}
}

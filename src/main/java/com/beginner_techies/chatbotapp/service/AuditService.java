package com.beginner_techies.chatbotapp.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compliance audit trail: one structured line per conversation turn and per
 * tool invocation, on a dedicated "AUDIT" logger so it can be routed to its
 * own appender/retention policy. User ids are hashed and content is
 * PII-masked before logging.
 */
@Service
public class AuditService {

	private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
	private static final int MAX_LEN = 500;

	private final GuardrailService guardrails;

	public AuditService(GuardrailService guardrails) {
		this.guardrails = guardrails;
	}

	public void turn(String userId, String direction, String text) {
		AUDIT.info("turn user={} dir={} text=\"{}\"", hash(userId), direction, sanitize(text));
	}

	public void toolCall(String userId, String tool, String argsSummary) {
		AUDIT.info("tool user={} name={} args=\"{}\"", hash(userId), tool, sanitize(argsSummary));
	}

	public void guardrail(String userId, String kind) {
		AUDIT.info("guardrail user={} kind={}", hash(userId), kind);
	}

	private String sanitize(String text) {
		if (text == null)
			return "";
		String masked = guardrails.maskPii(text.replace('\n', ' ').replace('"', '\''));
		return masked.length() > MAX_LEN ? masked.substring(0, MAX_LEN) + "…" : masked;
	}

	/** Short stable hash so audit lines are joinable per user without storing the raw id. */
	public static String hash(String userId) {
		if (userId == null)
			return "anon";
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(userId.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d, 0, 4); // 8 hex chars
		} catch (NoSuchAlgorithmException e) {
			return "hasherr";
		}
	}
}

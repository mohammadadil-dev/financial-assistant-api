package com.beginner_techies.chatbotapp.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Captures "talk to an agent" callback requests.
 * In-memory for now — swap for a CRM/DB integration later. Every lead is also
 * logged so requests aren't silently lost on restart in the meantime.
 */
@Service
@Slf4j
public class LeadStore {

	public record Lead(String ref, String userId, String name, String mobile, String preferredTime,
			Instant createdAt) {
	}

	private final Map<String, Lead> leads = new ConcurrentHashMap<>();

	public Lead save(String userId, String name, String mobile, String preferredTime) {
		String ref = "CB-" + (100000 + ThreadLocalRandom.current().nextInt(900000));
		Lead lead = new Lead(ref, userId, name, mobile, preferredTime, Instant.now());
		leads.put(ref, lead);
		// Mask the mobile in logs (PII)
		String masked = mobile.length() > 4 ? "*".repeat(mobile.length() - 4) + mobile.substring(mobile.length() - 4)
				: "****";
		log.info("Callback lead captured: ref={} name={} mobile={} time={}", ref, name, masked, preferredTime);
		return lead;
	}

	public Lead get(String ref) {
		return leads.get(ref);
	}
}

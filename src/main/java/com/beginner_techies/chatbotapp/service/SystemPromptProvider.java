package com.beginner_techies.chatbotapp.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the governed system prompt from resources/prompts/system-prompt.md and
 * fills the runtime variables ({botName}, {company}, {datetime}, {language}).
 * Bot/company names are configurable via application properties so the prompt
 * file never needs code changes per deployment.
 */
@Component
public class SystemPromptProvider {

	private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a",
			Locale.ENGLISH);
	private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

	private final String template;
	private final String botName;
	private final String company;

	public SystemPromptProvider(@Value("${app.bot.name:Quara Assistant}") String botName,
			@Value("${app.company.name:Quara Finance}") String company) {
		this.botName = botName;
		this.company = company;
		try (var in = new ClassPathResource("prompts/system-prompt.md").getInputStream()) {
			this.template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load prompts/system-prompt.md", e);
		}
	}

	/** Renders the system prompt for the given session language ("en"/"ar"). */
	public String render(String lang) {
		return template.replace("{botName}", botName).replace("{company}", company)
				.replace("{datetime}", ZonedDateTime.now(RIYADH).format(FMT))
				.replace("{language}", "ar".equals(lang) ? "Arabic" : "English");
	}
}

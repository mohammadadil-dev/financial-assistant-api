package com.beginner_techies.chatbotapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebCorsConfig {

	@Value("${app.cors.allowed-origin-patterns:http://localhost:[*]}")
	private String[] allowedOriginPatterns;

	@Bean
	public WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				// [*] = any port, so dev servers on 3000/3001/etc. all work.
				// Override via app.cors.allowed-origin-patterns for deployed environments.
				registry.addMapping("/api/**").allowedOriginPatterns(allowedOriginPatterns).allowedMethods("GET",
						"POST", "OPTIONS");
			}
		};
	}
}
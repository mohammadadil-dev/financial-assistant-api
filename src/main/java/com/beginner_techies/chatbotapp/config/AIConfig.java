package com.beginner_techies.chatbotapp.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIConfig {

	@Bean
	public ChatClient chatClient(ChatClient.Builder builder) {
		ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(30) // keep last 30 messages
				.build();
		return builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build();
	}
}

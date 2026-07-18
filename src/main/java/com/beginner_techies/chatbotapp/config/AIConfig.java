package com.beginner_techies.chatbotapp.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.beginner_techies.chatbotapp.enums.AiProvider;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class AIConfig {

	/**
	 * Both the Ollama and OpenAI(-compatible, e.g. Groq) starters are on the
	 * classpath so their ChatModel beans always get autoconfigured; app.ai.provider
	 * decides which one backs the shared ChatClient.
	 */
	@Bean
	public ChatClient chatClient(@Value("${app.ai.provider:ollama}") String provider,
			ObjectProvider<OllamaChatModel> ollamaChatModel, ObjectProvider<OpenAiChatModel> openAiChatModel) {

		AiProvider selected = AiProvider.valueOf(provider.trim().toUpperCase());
		ChatModel chatModel = switch (selected) {
		case GROQ -> openAiChatModel.getObject();
		case OLLAMA -> ollamaChatModel.getObject();
		};
		log.info("Active chat provider: {} ({})", selected, chatModel.getClass().getSimpleName());

		ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(30) // keep last 30 messages
				.build();
		return ChatClient.builder(chatModel).defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
				.build();
	}

	/**
	 * Groq has no embeddings endpoint, so RAG embeddings always go through Ollama
	 * regardless of app.ai.provider. @Primary resolves the ambiguity between
	 * ollamaEmbeddingModel and openAiEmbeddingModel for PineconeVectorStoreAutoConfiguration.
	 */
	@Bean
	@Primary
	public EmbeddingModel embeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel) {
		return ollamaEmbeddingModel;
	}
}

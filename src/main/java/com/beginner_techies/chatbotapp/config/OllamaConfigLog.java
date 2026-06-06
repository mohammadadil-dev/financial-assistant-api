package com.beginner_techies.chatbotapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class OllamaConfigLog {
  OllamaConfigLog(@Value("${spring.ai.ollama.base-url:NOT_SET}") String url) {
    System.out.println("Ollama base-url = [" + url + "]");
  }
}

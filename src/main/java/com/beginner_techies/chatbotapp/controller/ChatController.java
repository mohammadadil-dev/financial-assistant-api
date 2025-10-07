package com.beginner_techies.chatbotapp.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.beginner_techies.chatbotapp.record.ChatReply;
import com.beginner_techies.chatbotapp.service.ChatbotService;

import lombok.extern.slf4j.Slf4j;

record ChatRequest(String content, String sender, String lang) {
}

record ApiError(String error, String message) {
}

@RestController
@Slf4j
public class ChatController {

	@Autowired
	private ChatbotService chatbotService;

	@PostMapping("/api/chat")
	public ResponseEntity<?> promptRequest(@RequestBody ChatRequest req) {
		try {
			if (req == null || req.content() == null || req.content().isBlank()) {
				return ResponseEntity.badRequest().body(new ApiError("BadRequest", "content is required"));
			}
			String userId = (req.sender() == null || req.sender().isBlank()) ? "anonymous" : req.sender();
			ChatReply reply = chatbotService.handleMessage(userId, req.content(), req.lang());
			return ResponseEntity.ok(reply);
		} catch (Exception e) {
			log.error("chat error", e);
			return ResponseEntity.status(500).body(new ApiError(e.getClass().getSimpleName(),
					e.getMessage() == null ? "Internal error" : e.getMessage()));
		}

	}
}

package com.beginner_techies.chatbotapp.controller;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.beginner_techies.chatbotapp.record.ChatReply;
import com.beginner_techies.chatbotapp.service.AuditService;
import com.beginner_techies.chatbotapp.service.ChatbotService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Streaming variant of /api/chat using Server-Sent Events.
 *
 * Event protocol (all payloads JSON):
 *   delta → {"text":"<token>"}          incremental LLM output (RAG answers only)
 *   final → ChatReply                    the complete, normalized reply — always sent;
 *                                        clients should replace streamed text with this
 *   error → {"error":"...","message":"..."}
 *
 * Rule-engine replies (menus, EMI, eligibility) produce no deltas — just `final`.
 */
@RestController
@Slf4j
public class ChatStreamController {

	private static final long TIMEOUT_MS = 120_000L;

	private final ChatbotService chatbotService;
	private final AuditService audit;
	private final ObjectMapper mapper = new ObjectMapper();
	private final ExecutorService executor = Executors.newCachedThreadPool();

	public ChatStreamController(ChatbotService chatbotService, AuditService audit) {
		this.chatbotService = chatbotService;
		this.audit = audit;
	}

	@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter streamChat(@RequestBody ChatRequest req) {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

		if (req == null || req.content() == null || req.content().isBlank()) {
			sendQuietly(emitter, "error", new ApiError("BadRequest", "content is required"));
			emitter.complete();
			return emitter;
		}

		String userId = (req.sender() == null || req.sender().isBlank()) ? "anonymous" : req.sender();

		executor.execute(() -> {
			try {
				audit.turn(userId, "in", req.content());
				ChatReply reply = chatbotService.handleMessage(userId, req.content(), req.lang(),
						delta -> send(emitter, "delta", Map.of("text", delta)));
				audit.turn(userId, "out", reply == null ? "" : reply.text());
				send(emitter, "final", reply);
				emitter.complete();
			} catch (ClientDisconnectedException e) {
				log.debug("SSE client disconnected mid-stream for user {}", userId);
				emitter.complete();
			} catch (Exception e) {
				log.error("chat stream error", e);
				sendQuietly(emitter, "error", new ApiError("StreamError", "Something went wrong. Please try again."));
				emitter.complete();
			}
		});

		return emitter;
	}

	private void send(SseEmitter emitter, String event, Object payload) {
		try {
			emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(payload)));
		} catch (IOException | IllegalStateException e) {
			// Client went away — abort the LLM stream by unwinding the worker.
			throw new ClientDisconnectedException(e);
		}
	}

	private void sendQuietly(SseEmitter emitter, String event, Object payload) {
		try {
			send(emitter, event, payload);
		} catch (ClientDisconnectedException ignored) {
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	private static final class ClientDisconnectedException extends RuntimeException {
		ClientDisconnectedException(Throwable cause) {
			super(cause);
		}
	}
}

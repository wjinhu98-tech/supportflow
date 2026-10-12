package dev.supportflow.chat.api.controller;

import dev.supportflow.chat.api.request.ChatRequest;
import dev.supportflow.chat.api.response.ChatResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import dev.supportflow.chat.application.ChatService;
import jakarta.validation.Valid;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/api/v1/chat/welcome")
    public String welcome() {
        return "SupportFlow 已启动，欢迎使用 AI 售后客服助手。";
    }

    @PostMapping("/api/v1/chat/messages")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String message = request.message();
        String reply = chatService.reply(message);
        return new ChatResponse(reply);
    }


}
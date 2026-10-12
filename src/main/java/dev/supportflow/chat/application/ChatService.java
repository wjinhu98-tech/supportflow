package dev.supportflow.chat.application;

import org.springframework.stereotype.Service;

@Service
public class ChatService {

    public String reply(String message) {
        return "收到你的问题：" + message;
    }
}
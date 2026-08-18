package com.interview.agent.chat;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.result.Result;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 知识笔记 AI 问答 REST API：会话 CRUD + SSE 流式提问。
 * 所有接口的 userId 取自登录态，会话归属校验在 Service 层完成。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/sessions")
    public Result<ChatSession> createSession() {
        return Result.success(chatService.createSession(BaseContext.getCurrentId()));
    }

    @GetMapping("/sessions")
    public Result<List<ChatSession>> listSessions() {
        return Result.success(chatService.listSessions(BaseContext.getCurrentId()));
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long id) {
        return Result.success(chatService.getMessages(id, BaseContext.getCurrentId()));
    }

    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(@PathVariable Long id) {
        chatService.deleteSession(id, BaseContext.getCurrentId());
        return Result.success();
    }

    /** SSE 流式问答：delta 增量 / sources 来源 / refusal 拒答 / done 结束 / error 失败 */
    @PostMapping(value = "/sessions/{id}/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@PathVariable Long id, @RequestBody AskDTO dto) {
        return chatService.ask(id, BaseContext.getCurrentId(), dto.getQuestion());
    }

    public static class AskDTO {
        private String question;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
    }
}

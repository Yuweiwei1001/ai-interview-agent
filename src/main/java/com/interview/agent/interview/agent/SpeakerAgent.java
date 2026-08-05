package com.interview.agent.interview.agent;

import org.springframework.stereotype.Component;

/**
 * Speaker Agent - Phase 1 文字面试阶段跳过，Phase 2 数字人再启用
 * 当前作为占位，直接透传原文
 */
@Component
public class SpeakerAgent {

    public String speak(String text) {
        // Phase 1: 文字面试阶段，直接返回原文
        return text;
    }
}

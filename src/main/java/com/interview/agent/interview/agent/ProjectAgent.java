package com.interview.agent.interview.agent;

import com.interview.agent.common.ai.LlmCallWrapper;
import com.interview.agent.common.ai.ReActAgent;
import com.interview.agent.common.context.BaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProjectAgent {
    private static final Logger log = LoggerFactory.getLogger(ProjectAgent.class);
    private final ReActAgent reactAgent;
    private final InterviewTools interviewTools;

    public ProjectAgent(ReActAgent reactAgent, InterviewTools interviewTools) {
        this.reactAgent = reactAgent;
        this.interviewTools = interviewTools;
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, "neutral", List.of(), "", "", "");
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, persona, List.of(), "", "", "");
    }

    /** 出题（ASR 热词纠错方案 P0：注入会话热词表，题目优先围绕候选人术语体系提问） */
    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics,
                                   String persona, List<String> sessionHotwords) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, persona, sessionHotwords, "", "", "");
    }

    /** 出题（含对话历史注入：最近 N 轮全量 Q&A + 更早轮次 LLM 摘要 + 完整历史通过工具按需获取） */
    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics,
                                   String persona, List<String> sessionHotwords,
                                   String conversationHistory, String recentConversation, String conversationSummary) {
        Long userId = BaseContext.getCurrentId();
        return LlmCallWrapper.callWithRetry("project", () -> {
            // 传播用户上下文到 LLM 调用线程（@Tool 方法依赖 BaseContext 获取用户 ID）
            if (userId != null) {
                BaseContext.setCurrentId(userId);
            }
            // 传播完整对话历史到 LLM 调用线程（getConversationHistory 工具读取）
            if (conversationHistory != null && !conversationHistory.isBlank()) {
                ConversationContext.set(conversationHistory);
            }
            try {
                String systemPrompt = buildSystemPrompt(topic, difficulty, persona, sessionHotwords, resumeText, conversationSummary, recentConversation);
                String userPrompt = buildUserPrompt(topic, difficulty, askedTopics, persona);
                String result = reactAgent.execute(systemPrompt, userPrompt, interviewTools);
                if (result == null || result.isBlank()) {
                    throw new RuntimeException("Agent 出题返回空");
                }
                return result;
            } finally {
                ConversationContext.clear();
                if (userId != null) {
                    BaseContext.removeCurrentId();
                }
            }
        }, () -> fallbackQuestion(topic, difficulty));
    }

    /** 系统提示：角色设定 + 上下文 + ReAct 行为指引 */
    private String buildSystemPrompt(String topic, String difficulty, String persona,
                                     List<String> sessionHotwords, String resumeText,
                                     String conversationSummary, String recentConversation) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深项目面试官，负责考察候选人的项目经验。\n\n");
        sb.append("考察主题：").append(topic).append("\n");
        sb.append("难度级别：").append(difficulty).append("\n\n");
        // 前情摘要（更早轮次的 LLM 总结）
        if (conversationSummary != null && !conversationSummary.isBlank()) {
            sb.append("【前情摘要】").append(conversationSummary).append("\n\n");
        }
        // 最近 N 轮全量对话
        if (recentConversation != null && !recentConversation.isBlank()) {
            sb.append("【最近对话】\n").append(recentConversation).append("\n");
        }
        // 项目经验题需要简历信息
        if (resumeText != null && !resumeText.isBlank()) {
            sb.append("候选人简历：").append(resumeText).append("\n\n");
        }
        // P0 热词注入：题目优先围绕候选人简历/JD 中的技术术语提问，并使用官方写法
        if (sessionHotwords != null && !sessionHotwords.isEmpty()) {
            sb.append("候选人技术栈术语表（若与考察主题相关，优先围绕这些术语出题并使用官方写法）：")
                    .append(String.join("、", sessionHotwords)).append("\n\n");
        }
        // ReAct 行为指引：LLM 在出题前可先调用工具了解候选人背景，然后再出题
        sb.append("【ReAct 工作模式】\n");
        sb.append("请遵循以下步骤来完成出题任务：\n");
        sb.append("1. 思考：分析面试主题和难度，思考需要什么信息来出好题\n");
        sb.append("2. 行动：如果需要了解候选人的背景，使用提供的工具获取信息\n");
        sb.append("3. 观察：查看工具返回的结果，了解候选人的知识掌握情况\n");
        sb.append("4. 再思考：基于获取的信息，决定出题方向和深度\n");
        sb.append("5. 最终：生成一道有针对性的面试题\n\n");
        sb.append("可用工具：\n");
        sb.append("- getCandidateWeakPoints：获取候选人历史薄弱知识点，用于针对性出题\n");
        sb.append("- getConversationHistory：获取当前会话的完整对话历史\n");
        return sb.toString();
    }

    /** 用户消息：具体的出题请求 */
    private String buildUserPrompt(String topic, String difficulty, List<String> askedTopics, String persona) {
        StringBuilder sb = new StringBuilder();
        if (askedTopics != null && !askedTopics.isEmpty()) {
            sb.append("已考察主题（请避免重复）：").append(String.join("、", askedTopics)).append("\n\n");
        }
        sb.append("请出一道项目经验题，要求候选人结合真实项目经历回答。\n");
        sb.append("要求：\n");
        sb.append("1. 像真实面试官聊天一样自然：只问一个简短的问题（最好不超过 50 字），口语化，");
        sb.append("例如“聊聊你在项目里负责的那块，具体是怎么做的？”。\n");
        sb.append("2. 严禁一次列出多个子问题或用编号列表要求逐条回答；细节留给后续追问，不要一次问完。\n");
        sb.append("3. 严禁输出任何 Markdown 格式（列表、加粗、标题），只输出一段自然的口语问句。\n");
        sb.append("4. 优先考察项目经历细节（项目背景、难点、方案、权衡、踩坑），系统设计题最多占三分之一。\n");
        sb.append("5. 严禁与已考察主题重复；不要反复围绕简历中同一个项目/同一技术点出题（如已问过ID生成器，就不许再问ID、RingBuffer、时钟回拨）。\n");
        if ("gentle".equalsIgnoreCase(persona)) {
            sb.append("6. 候选人选择温和人格：优先基础的项目介绍/职责/技术栈类问题，避免大型架构设计题。\n");
        }
        sb.append("直接输出题目内容，不要带任何前缀或说明。");
        return sb.toString();
    }

    private String fallbackQuestion(String topic, String difficulty) {
        Map<String, String> bank = Map.ofEntries(
            Map.entry("系统设计", "请设计一个高并发的短链接服务，要求从架构设计、数据库选型、缓存策略等方面进行阐述。"),
            Map.entry("架构设计", "请描述一个你参与过的微服务架构项目，包括服务拆分原则、服务间通信方式、以及遇到的挑战和解决方案。"),
            Map.entry("技术选型", "在项目中进行技术选型时，你会考虑哪些因素？请举例说明你在实际项目中做过的重要技术决策。"),
            Map.entry("项目管理", "请描述一个你在项目中遇到的重大困难，以及你是如何协调团队资源、推动问题解决的。"),
            Map.entry("综合项目", "请描述你最满意的一个项目，包括项目背景、你的角色、技术亮点和业务成果。")
        );
        return bank.getOrDefault(topic, bank.get("综合项目"));
    }
}
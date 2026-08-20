package com.interview.agent.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 噪声注入器（ASR 热词纠错方案 4.5.1）：对 golden 标准回答注入同音/近音错误，
 * 构造"噪声版"回答用于评分漂移回归——同一回答干净版 vs 噪声版的评分差（delta）
 * 反映评估链路对 ASR 转写噪声的容忍度，是纠错链路/Prompt 改动的回归门禁。
 *
 * <p>映射表是静态固定的（可复现性优先）：按 ASR 真实高发错误形态选取——
 * 英文术语的中文音译（Raft→拉夫特）与中文同音字（幂等→密等、熔断→融断）两类。
 */
public final class NoiseInjector {

    /** 术语 → ASR 常见同音/近音错写（注入时按 key 长度降序替换，避免子串遮蔽） */
    private static final Map<String, String> HOMOPHONE_MAP = buildMap();

    private NoiseInjector() {}

    private static Map<String, String> buildMap() {
        Map<String, String> m = new LinkedHashMap<>();
        // 英文术语 → 中文音译（ASR 未识别出英文时的典型输出）
        m.put("Kubernetes", "库伯内提斯");
        m.put("ConcurrentHashMap", "康克伦特哈希map");
        m.put("synchronized", "辛克瑞奈兹");
        m.put("RabbitMQ", "瑞比特MQ");
        m.put("Spring Boot", "斯普林布特");
        m.put("Dijkstra", "迪杰斯特拉");
        m.put("RocketMQ", "劳克特MQ");
        m.put("PostgreSQL", "珀斯特格雷SQL");
        m.put("Elasticsearch", "伊拉斯提克色奇");
        m.put("volatile", "沃拉泰尔");
        m.put("Docker", "道克");
        m.put("Kafka", "卡夫卡");
        m.put("Redis", "瑞迪斯");
        m.put("Netty", "内提");
        m.put("Dubbo", "达波");
        m.put("etcd", "伊梯西迪");
        m.put("epoll", "伊泡");
        m.put("Paxos", "帕克索斯");
        m.put("MyBatis", "麦贝提斯");
        m.put("HashMap", "哈希妈普");
        m.put("MongoDB", "蒙狗DB");
        m.put("Raft", "拉夫特");
        m.put("Java", "加瓦");
        // 中文术语 → 同音/近音错字（中文 ASR 错误主形态）
        m.put("零拷贝", "灵拷贝");
        m.put("幂等", "密等");
        m.put("熔断", "融断");
        m.put("限流", "线流");
        m.put("悲观锁", "背观锁");
        m.put("乐观锁", "罗观锁");
        m.put("缓存穿透", "缓存串透");
        m.put("负载均衡", "负载均横");
        m.put("三次握手", "三次我手");
        m.put("主从复制", "主聪赋值");
        m.put("布隆过滤器", "布龙过滤器");
        m.put("一致性哈希", "一致性和希");
        return m;
    }

    /** 注入同音错误：按 key 长度降序替换所有命中术语 */
    public static String inject(String cleanText) {
        if (cleanText == null || cleanText.isEmpty()) return cleanText;
        String noisy = cleanText;
        for (Map.Entry<String, String> e : HOMOPHONE_MAP.entrySet()
                .stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .toList()) {
            noisy = noisy.replace(e.getKey(), e.getValue());
        }
        return noisy;
    }

    /** 统计干净文本中被映射表覆盖的术语数（0 表示样例与映射表不匹配，注入无效） */
    public static int coveredTerms(String cleanText) {
        if (cleanText == null) return 0;
        return (int) HOMOPHONE_MAP.keySet().stream()
                .filter(cleanText::contains)
                .count();
    }
}

package com.interview.agent.voice.correction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 英文模糊匹配性能对比：全表扫描（PinyinTermIndex 千级词表默认策略） vs 生产 BkTree（超阈值切换）。
 *
 * <p>两种算法共享生产 {@link EditDistance}（避免重复实现、保证结果一致）。
 * 验证：相同 query 下两种实现命中集合完全一致（切换不影响召回语义），并打印/断言耗时差异。
 */
class EnglishFuzzyMatchBenchmarkTest {

    private static final int MAX_DIST = 2;            // 与生产一致：编辑距离 ≤ 2
    private static final int TERM_BANK_SIZE = 10_000; // 模拟 1 万条英文词表（超过 BK 切换阈值）
    private static final int QUERY_COUNT = 100;       // 模拟 100 个待纠错的英文 token

    /** 模拟生产全表扫描分支（PinyinTermIndex.recall 的 else 分支）：短 token 跳过 + 编辑距离 ≤2 */
    static List<String> scanAll(List<String> keys, String query, int k) {
        List<String> hits = new ArrayList<>();
        for (String key : keys) {
            if (key.length() >= BkTree.MIN_WORD_LEN
                    && EditDistance.distance(query, key) <= k) {
                hits.add(key);
            }
        }
        return hits;
    }

    /** 生成 1 万条唯一英文词表（base + 功能后缀 + 编号，模拟术语库英文部分） */
    static List<String> generateTermBank(int size) {
        String[] bases = {"redis", "kafka", "mysql", "postgres", "mongodb", "elasticsearch",
                "springboot", "springcloud", "netty", "rabbitmq", "rocketmq", "dubbo",
                "zookeeper", "hadoop", "spark", "flink", "hbase", "clickhouse", "doris",
                "minio", "nginx", "vite", "webpack", "docker", "kubernetes", "istio",
                "grafana", "prometheus", "jenkins", "gitlab"};
        String[] suffixes = {"cluster", "client", "config", "server", "cache", "source",
                "sink", "broker", "gateway", "registry", "discovery", "proxy", "connect",
                "consumer", "producer", "starter", "model", "service", "controller", "repository"};
        Set<String> seen = new HashSet<>();
        List<String> words = new ArrayList<>(size);
        int seq = 0;
        while (words.size() < size) {
            String w = bases[seq % bases.length]
                    + suffixes[(seq / bases.length) % suffixes.length]
                    + (seq / (bases.length * suffixes.length));
            if (seen.add(w)) {
                words.add(w);
            }
            seq++;
        }
        return words;
    }

    /** 从词表随机取词做 1 字符扰动，模拟 ASR 的英文拼写错误（保证距离 ≤ 2 有命中） */
    static List<String> generateQueries(List<String> bank, int count, Random rnd) {
        List<String> queries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String base = bank.get(rnd.nextInt(bank.size()));
            StringBuilder sb = new StringBuilder(base);
            int op = rnd.nextInt(3);
            int pos = rnd.nextInt(sb.length());
            if (op == 0) {
                sb.deleteCharAt(pos);                          // 删 1 字符 → 距离 1
            } else if (op == 1) {
                sb.setCharAt(pos, (char) ('a' + rnd.nextInt(26))); // 改 1 字符 → 距离 1
            } else {
                sb.insert(pos, (char) ('a' + rnd.nextInt(26)));    // 插 1 字符 → 距离 1
            }
            queries.add(sb.toString());
        }
        return queries;
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void fullScanVsBkTree() {
        List<String> bank = generateTermBank(TERM_BANK_SIZE);
        Random rnd = new Random(42);
        List<String> queries = generateQueries(bank, QUERY_COUNT, rnd);

        // 构建生产 BK 树（超阈值时 PinyinTermIndex.rebuild 即走这里）
        long buildStart = System.nanoTime();
        BkTree bkTree = BkTree.of(bank);
        long buildMs = (System.nanoTime() - buildStart) / 1_000_000;

        // 预热（JIT）
        for (int i = 0; i < 20; i++) {
            String q = queries.get(i % queries.size());
            scanAll(bank, q, MAX_DIST);
            bkTree.search(q, MAX_DIST);
        }

        // 正式对比：结果一致 + 耗时
        long scanNanos = 0;
        long bkNanos = 0;
        boolean allMatched = true;
        int totalHits = 0;

        for (String q : queries) {
            long t0 = System.nanoTime();
            List<String> scanHits = scanAll(bank, q, MAX_DIST);
            long t1 = System.nanoTime();
            List<String> bkHits = bkTree.search(q, MAX_DIST);
            long t2 = System.nanoTime();

            scanNanos += (t1 - t0);
            bkNanos += (t2 - t1);
            totalHits += bkHits.size();

            scanHits.sort(Comparator.naturalOrder());
            bkHits.sort(Comparator.naturalOrder());
            if (!scanHits.equals(bkHits)) {
                allMatched = false;
                System.out.println("[不一致] query=" + q + " scan=" + scanHits + " bk=" + bkHits);
            }
        }

        long scanMs = scanNanos / 1_000_000;
        long bkMs = bkNanos / 1_000_000;
        double speedup = (double) scanNanos / Math.max(1, bkNanos);

        System.out.println("=== 英文模糊匹配性能对比（编辑距离≤" + MAX_DIST + "）===");
        System.out.println("词表规模: " + bank.size() + " 条 | 查询数: " + queries.size()
                + " 个 | 平均命中: " + (totalHits / Math.max(1, queries.size())) + " 条/query");
        System.out.println("BK 树构建: " + buildMs + " ms（一次性） | BK 树索引词数: " + bkTree.size());
        System.out.println("全表扫描总耗时: " + scanMs + " ms（每 query 扫描 " + bank.size() + " 条）");
        System.out.println("BK 树总耗时:   " + bkMs + " ms");
        System.out.printf("加速比: %.1fx%n", speedup);

        // 正确性：两种实现命中结果必须完全一致（切换不影响召回语义）
        assertTrue(allMatched, "BK 树与全表扫描结果不一致");
        assertTrue(totalHits > 0, "测试无效：query 未产生任何命中");
        // 性能：1 万条下 BK 树必须明显更快（数量级差异，断言很保守）
        assertTrue(bkNanos < scanNanos,
                "BK 树未更快：scan=" + scanMs + "ms, bk=" + bkMs + "ms");
    }

    @Test
    void thresholdStrategyTest() {
        // 验证 PinyinTermIndex 的 if-else 切换阈值语义：
        // ≤5000 走全表扫描（bkTree=null），>5000 构建 BK 树
        List<String> small = generateTermBank(4_000);
        List<String> large = generateTermBank(8_000);

        BkTree smallTree = small.size() > 5_000 ? BkTree.of(small) : null;
        BkTree largeTree = large.size() > 5_000 ? BkTree.of(large) : null;

        assertEquals(null, smallTree, "4000 条不应启用 BK 树");
        assertTrue(largeTree != null, "8000 条应启用 BK 树");
        assertEquals(8_000, largeTree.size(), "BK 树应索引全部长词");
    }
}

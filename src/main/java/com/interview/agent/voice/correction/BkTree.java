package com.interview.agent.voice.correction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BK 树（Burkhard-Keller）：按编辑距离组织词表 + 三角不等式剪枝的模糊匹配索引。
 *
 * <p>用于大规模英文词表（词表 key 数超过 {@link PinyinTermIndex#BK_TREE_THRESHOLD} 时启用）：
 * 全表扫描对每个 query 做 O(N) 次编辑距离，BK 树只访问距离在容差范围内的子树，
 * 单 query 访问量从 N 降到近 O(log N)。
 *
 * <p>前提：距离函数必须是度量（满足三角不等式）——编辑距离满足。构建一次性 O(N·log N)。
 */
public class BkTree {

    /** 只索引长度 ≥4 的词：与全表扫描的短 token 跳过规则一致（过短词编辑距离模糊无意义、易误召回） */
    static final int MIN_WORD_LEN = 4;

    private static final class Node {
        final String word;
        final Map<Integer, Node> children = new HashMap<>();

        Node(String word) {
            this.word = word;
        }
    }

    private final Node root;
    private final int size;

    private BkTree(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    /** 从词表构建 BK 树（null / 过短词被过滤） */
    public static BkTree of(Collection<String> words) {
        Node root = null;
        int count = 0;
        for (String w : words) {
            if (w == null || w.length() < MIN_WORD_LEN) continue;
            if (root == null) {
                root = new Node(w);
            } else {
                insert(root, w);
            }
            count++;
        }
        return new BkTree(root, count);
    }

    private static void insert(Node node, String word) {
        int d = EditDistance.distance(word, node.word);
        Node child = node.children.get(d);
        if (child == null) {
            node.children.put(d, new Node(word));
        } else {
            insert(child, word);
        }
    }

    /** 返回与 query 编辑距离 ≤ maxDist 的全部词（含 query 本身若在树中） */
    public List<String> search(String query, int maxDist) {
        List<String> hits = new ArrayList<>();
        if (root != null) {
            search(root, query, maxDist, hits);
        }
        return hits;
    }

    private static void search(Node node, String query, int maxDist, List<String> hits) {
        int d = EditDistance.distance(query, node.word);
        if (d <= maxDist) hits.add(node.word);
        // 三角不等式剪枝：q 到当前词距离 d，子树内词与当前词距离 childDist，
        // 则 q 到子树内词距离 ≥ |d - childDist|，> maxDist 时整棵子树不可能命中
        for (Map.Entry<Integer, Node> e : node.children.entrySet()) {
            if (Math.abs(e.getKey() - d) <= maxDist) {
                search(e.getValue(), query, maxDist, hits);
            }
        }
    }

    public int size() {
        return size;
    }
}

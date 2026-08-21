package com.interview.agent.voice.correction;

/**
 * 编辑距离工具（经典 Levenshtein，双行 DP）。
 * 全表扫描与 BK 树模糊匹配共享同一实现，避免重复维护、保证结果一致。
 */
public final class EditDistance {

    private EditDistance() {
    }

    /** 计算 a 到 b 的编辑距离：单字符插入/删除/替换各计 1 步 */
    public static int distance(String a, String b) {
        if (a.equals(b)) return 0;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}

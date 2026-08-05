package com.interview.agent.common.result;

import java.util.List;

public class PageResult<T> {
    private int code;
    private String msg;
    private List<T> data;
    private long total;
    private int page;
    private int pageSize;

    public PageResult() {}

    public PageResult(int code, String msg, List<T> data, long total, int page, int pageSize) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public static <T> PageResult<T> success(List<T> data, long total, int page, int pageSize) {
        return new PageResult<>(1, "success", data, total, page, pageSize);
    }

    // getters/setters
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public List<T> getData() { return data; }
    public void setData(List<T> data) { this.data = data; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}

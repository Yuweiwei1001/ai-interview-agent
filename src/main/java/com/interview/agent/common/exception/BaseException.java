package com.interview.agent.common.exception;

public class BaseException extends RuntimeException {
    private int code;

    public BaseException(String msg) {
        super(msg);
        this.code = 0;
    }

    public BaseException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() { return code; }
}

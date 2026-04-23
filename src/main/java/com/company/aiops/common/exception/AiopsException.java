package com.company.aiops.common.exception;

public class AiopsException extends RuntimeException {

    private final ErrorCode errorCode;

    public AiopsException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public AiopsException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

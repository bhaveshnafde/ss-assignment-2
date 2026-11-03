package com.moneytransfer.limits.domain;

/**
 * A business rule refused the request. Carries the HTTP status and error code the API returns,
 * e.g. 422 LIMIT_EXCEEDED or 404 RESERVATION_NOT_FOUND.
 */
public class RuleViolationException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public RuleViolationException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() { return httpStatus; }
    public String code() { return code; }
}

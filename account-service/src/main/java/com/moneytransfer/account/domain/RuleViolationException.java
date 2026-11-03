package com.moneytransfer.account.domain;

/**
 * A business rule refused the request. Carries the HTTP status and error code the API returns,
 * e.g. 409 INSUFFICIENT_FUNDS or 422 ACCOUNT_NOT_ACTIVE.
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

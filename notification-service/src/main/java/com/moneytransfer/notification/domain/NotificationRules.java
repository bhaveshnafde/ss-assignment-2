package com.moneytransfer.notification.domain;

import java.util.regex.Pattern;

/** Checks on a notification request beyond simple field validation. */
public final class NotificationRules {

    public static final int MAX_MESSAGE_LENGTH = 500;
    private static final Pattern TEMPLATE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private NotificationRules() { }

    public static void check(String template, String message) {
        if (!TEMPLATE.matcher(template).matches()) {
            throw new RuleViolationException(422, "INVALID_TEMPLATE",
                    "template must be 1-40 upper-case letters, digits or '_', starting with a letter");
        }
        if (message.isBlank()) {
            throw new RuleViolationException(422, "INVALID_MESSAGE", "message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new RuleViolationException(422, "INVALID_MESSAGE",
                    "message may be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
    }
}

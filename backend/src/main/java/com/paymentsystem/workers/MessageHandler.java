package com.paymentsystem.workers;

import java.util.Map;

@FunctionalInterface
public interface MessageHandler {

    void handle(Map<String, String> fields);
}

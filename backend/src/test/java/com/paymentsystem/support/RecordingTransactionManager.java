package com.paymentsystem.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

// Runs transaction callbacks inline and records each commit, so unit tests can check what happens after commit.
public class RecordingTransactionManager implements PlatformTransactionManager {

    private final List<String> events;

    public RecordingTransactionManager(List<String> events) {
        this.events = events;
    }

    public RecordingTransactionManager() {
        this(new ArrayList<>());
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        events.add("commit");
    }

    @Override
    public void rollback(TransactionStatus status) {
        events.add("rollback");
    }
}

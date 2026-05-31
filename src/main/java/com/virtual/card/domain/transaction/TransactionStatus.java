package com.virtual.card.domain.transaction;

/**
 * Processing outcome of a transaction.
 *
 * <p>{@code PENDING} is the initial state. The system transitions synchronously to
 * {@code SUCCESSFUL} or {@code DECLINED} before returning a response. {@code PENDING}
 * is retained as a first-class state to support future async processing pipelines
 * (e.g. external authorisation checks, fraud screening).
 */
public enum TransactionStatus {
    PENDING,
    SUCCESSFUL,
    DECLINED
}

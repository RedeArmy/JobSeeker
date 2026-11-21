package com.usac.archfixture.arch10.cv.application.usecase;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Viola ARCH-10: un caso de uso que usa TransactionSynchronizationManager (afterCommit). */
public class BadSync {
  boolean active() {
    return TransactionSynchronizationManager.isSynchronizationActive();
  }
}

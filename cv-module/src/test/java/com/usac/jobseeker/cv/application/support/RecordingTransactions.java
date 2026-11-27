package com.usac.jobseeker.cv.application.support;

import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Una transacción de prueba que solo dice si está abierta: así se comprueba qué ocurre dentro de la
 * transacción corta y qué fuera (TD-044) sin una base de datos.
 */
public final class RecordingTransactions implements TransactionOperations {

  private boolean open;
  private int started;

  @Override
  public <T> T execute(TransactionCallback<T> action) throws TransactionException {
    open = true;
    started++;
    try {
      return action.doInTransaction(null);
    } finally {
      open = false;
    }
  }

  public boolean open() {
    return open;
  }

  public int started() {
    return started;
  }
}

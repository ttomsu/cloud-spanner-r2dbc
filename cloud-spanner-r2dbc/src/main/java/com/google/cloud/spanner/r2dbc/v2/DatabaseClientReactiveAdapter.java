/*
 * Copyright 2019-2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spanner.r2dbc.v2;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.cloud.spanner.AsyncResultSet;
import com.google.cloud.spanner.AsyncResultSet.CallbackResponse;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.r2dbc.SpannerConnectionConfiguration;
import com.google.common.annotations.VisibleForTesting;
import com.google.spanner.v1.ExecuteSqlRequest.QueryOptions;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

/**
 * Converts gRPC/Cloud Spanner client library asyncronous abstractions into reactive ones.
 * Encapsulates useful per-connection state.
 */
class DatabaseClientReactiveAdapter {

  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseClientReactiveAdapter.class);

  // used for DDL operations
  private final SpannerConnectionConfiguration config;

  private final Spanner spannerClient;

  private final DatabaseClient dbClient;

  private final DatabaseAdminClient dbAdminClient;

  private final ExecutorService executorService;

  private DatabaseClientTransactionManager txnManager;

  private boolean autoCommit = true;

  private QueryOptions queryOptions;

  /**
   * Instantiates the adapter with given client library {@code DatabaseClient} and executor.
   *
   * @param spannerClient Cloud Spanner client used to run queries and manage transactions
   * @param config User-provided connection configuration options
   */
  DatabaseClientReactiveAdapter(
      Spanner spannerClient,
      SpannerConnectionConfiguration config) {
    this.spannerClient = spannerClient;
    this.dbClient = spannerClient.getDatabaseClient(
        DatabaseId.of(config.getProjectId(), config.getInstanceName(), config.getDatabaseName()));
    this.dbAdminClient = spannerClient.getDatabaseAdminClient();
    this.executorService = Executors.newFixedThreadPool(config.getThreadPoolSize());
    this.config = config;
    this.txnManager = new DatabaseClientTransactionManager(this.dbClient, this.executorService);

    QueryOptions.Builder builder =  QueryOptions.newBuilder();
    if (config.getOptimizerVersion() != null) {
      builder.setOptimizerVersion(config.getOptimizerVersion());
    }
    this.queryOptions = builder.build();
  }

  /**
   * Allows starting a Cloud Spanner transaction.
   *
   * @return reactive pipeline for starting a transaction
   */
  Mono<Void> beginTransaction() {
    return convertFutureToMono(() -> this.txnManager.beginTransaction()).then();
  }

  /**
   * Allows starting a Cloud Spanner readonly transaction with custom staleness.
   *
   * @return reactive pipeline for starting a transaction
   */
  Mono<Void> beginReadonlyTransaction(TimestampBound timestampBound) {

    return Mono.defer(() -> {
      this.txnManager.beginReadonlyTransaction(timestampBound);
      // TODO: it would be good for client library to signal when a session is ready for this
      // transaction, so a meaningful mono is returned.
      return Mono.empty();
    });
  }

  /**
   * Allows committing a Cloud Spanner transaction.
   *
   * @return reactive pipeline for committing a transaction
   */
  Mono<Void> commitTransaction() {
    return convertFutureToMono(() -> this.txnManager.commitTransaction())
        .doOnTerminate(this.txnManager::clearTransactionManager)
        .then();
  }

  /**
   * Allows rolling back a Cloud Spanner transaction.
   *
   * @return reactive pipeline for rolling back a transaction
   */
  Publisher<Void> rollback() {
    return convertFutureToMono(() -> this.txnManager.rollbackTransaction())
        .doOnTerminate(this.txnManager::clearTransactionManager);
  }

  /**
   * Allows cleaning up resources.
   *
   * <p>Closes client library objects.
   *
   * @return reactive pipeline for closing the connection.
   */
  Mono<Void> close() {
    // TODO: if txn is committed/rolled back and then connection closed, clearTransactionManager
    // will run twice, causing trace span to be closed twice. Introduce `closed` field.
    return Mono.fromRunnable(() -> {
      this.txnManager.clearTransactionManager();
      this.executorService.shutdown();
    });
  }

  /**
   * Runs a health check query to determine if the connection is running.
   *
   * @return true if the connection is working, false if not.
   */
  Mono<Boolean> healthCheck() {
    return Mono.defer(() -> {
      if (this.executorService.isShutdown() || this.spannerClient.isClosed()) {
        return Mono.just(false);
      } else {
        return Flux.<SpannerClientLibraryRow>create(sink -> {
          com.google.cloud.spanner.Statement statement =
              Statement.newBuilder("SELECT 1").build();
          runSelectStatementAsFlux(this.dbClient.singleUse(), statement, sink);
        })
        .then(Mono.just(true))
        .onErrorResume(error -> {
          LOGGER.warn("Cloud Spanner healthcheck failed", error);
          return Mono.just(false);
        });
      }
    });
  }

  Mono<Boolean> localHealthcheck() {
    return Mono.fromSupplier(() -> !this.executorService.isShutdown());
  }

  boolean isAutoCommit() {
    return this.autoCommit;
  }

  Publisher<Void> setAutoCommit(boolean autoCommit) {
    return Mono.defer(() -> {
      Mono<Void> result = Mono.empty();
      if (this.autoCommit != autoCommit && this.txnManager.isInTransaction()) {
        // If autocommit is changed, commit the existing transaction.
        result = this.commitTransaction();
      }
      return result.doOnSuccess(empty -> this.autoCommit = autoCommit);
    });
  }

  /**
   * Allows running a DML statement.
   *
   * <p>If no transaction is active, a single-use transaction will be used.
   *
   * @return reactive pipeline for running a DML statement
   */
  Mono<Long> runDmlStatement(com.google.cloud.spanner.Statement statement) {
    return runBatchDmlInternal(ctx -> ctx.executeUpdateAsync(statement));
  }

  /**
   * Allows running DML statements in a batch.
   *
   * <p>If no transaction is active, a single-use transaction will be used.
   *
   * @return reactive pipeline for running the provided DML statements
   */
  Mono<long[]> runBatchDml(List<Statement> statements) {
    return runBatchDmlInternal(ctx -> ctx.batchUpdateAsync(statements));
  }

  private <T> Mono<T> runBatchDmlInternal(
      Function<TransactionContext, ApiFuture<T>> asyncOperation) {
    return Mono.defer(() -> {
      if (this.txnManager.isInReadonlyTransaction()) {
        return Mono.error(
            new IllegalAccessException("Cannot run DML statements in a readonly transaction."));
      } else if (!this.autoCommit && !this.txnManager.isInReadWriteTransaction()) {
        return Mono.error(new IllegalAccessException(
            "Cannot run DML statements outside of a transaction when autocommit is set to false."));
      }

      return convertFutureToMono(() -> {
        if (this.txnManager.isInReadWriteTransaction()) {
          return this.txnManager.runInTransaction(asyncOperation);
        } else {
          ApiFuture<T> rowCountFuture =
              this.dbClient
                  .runAsync()
                  .runAsync(txn -> asyncOperation.apply(txn), this.executorService);
          return rowCountFuture;
        }
      });
    });
  }

  Flux<SpannerClientLibraryRow> runSelectStatement(
      com.google.cloud.spanner.Statement statement) {
    return Flux.create(
        sink -> {
          if (this.txnManager.isInReadWriteTransaction()) {
            this.txnManager.runInTransaction(ctx -> runSelectStatementAsFlux(ctx, statement, sink));
          } else {
            runSelectStatementAsFlux(this.txnManager.getReadContext(), statement, sink);
          }
        });
  }

  Mono<Void> runDdlStatement(String query) {
    return convertFutureToMono(() -> this.dbAdminClient.updateDatabaseDdl(
        this.config.getInstanceName(),
        this.config.getDatabaseName(),
        Collections.singletonList(query),
        null));
  }

  /**
   * Runs SELECT query, adapting its output from {@link AsyncResultSet} to {@link FluxSink}.
   *
   * <p>Make sure that if run from a transactional context, this method is called after {@link
   * ApiFuture} returned by `transactionManager.beginAsync()` resolves. In practice, this means
   * always invoking this method in a chained `.then()` lambda * when running in transaction.
   *
   * @param readContext Cloud Spanner read context (plain or transactional)
   * @param statement query to run
   * @param sink output Flux sink
   * @return future suitable for transactional step chaining
   */
  private ApiFuture<Void> runSelectStatementAsFlux(
      ReadContext readContext,
      com.google.cloud.spanner.Statement statement,
      FluxSink<SpannerClientLibraryRow> sink) {
    AsyncResultSet ars = readContext.executeQueryAsync(statement);
    sink.onCancel(ars::cancel);

    return ars.setCallback(
        this.executorService,
        resultSet -> {
          // TODO: handle backpressure by asking callback to signal CallbackResponse.PAUSE
          try {
            switch (resultSet.tryNext()) {
              case DONE:
                sink.complete();
                return CallbackResponse.DONE;
              case NOT_READY:
              default:
                return CallbackResponse.CONTINUE;
              case OK:
                sink.next(new SpannerClientLibraryRow(resultSet.getCurrentRowAsStruct()));
                return CallbackResponse.CONTINUE;
            }
          } catch (Throwable t) {
            sink.error(t);
            return CallbackResponse.DONE;
          }
        });
  }

  private <T> Mono<T> convertFutureToMono(Supplier<ApiFuture<T>> futureSupplier) {
    return Mono.create(
        sink -> {
          ApiFuture future = futureSupplier.get();
          sink.onCancel(() -> future.cancel(true));

          ApiFutures.addCallback(future,
              new ApiFutureCallback<T>() {
                @Override
                public void onFailure(Throwable t) {
                  sink.error(t);
                }

                @Override
                public void onSuccess(T result) {
                  sink.success(result);
                }
              },
              this.executorService);
        });
  }

  QueryOptions getQueryOptions() {
    return this.queryOptions;
  }

  @VisibleForTesting
  void setTxnManager(DatabaseClientTransactionManager txnManager) {
    this.txnManager = txnManager;
  }
}

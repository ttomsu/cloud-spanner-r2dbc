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

package com.google.cloud.spanner.r2dbc;

import com.google.cloud.spanner.r2dbc.util.Assert;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.util.function.BiFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link Result} implementation for Cloud Spanner.
 *
 */
public class SpannerResult implements Result {

  private final Flux<SpannerRow> resultRows;

  private final Mono<Integer> rowsUpdated;

  /**
   * Constructor for query execution.
   *
   * @param resultRows the underlying result from Cloud Spanner.
   * @param rowsUpdated the number of rows affected by the operation.
   */
  public SpannerResult(Flux<SpannerRow> resultRows, Mono<Integer> rowsUpdated) {
    this.resultRows = Assert.requireNonNull(resultRows, "A non-null flux of rows is required.");
    this.rowsUpdated = Assert.requireNonNull(rowsUpdated,
        "A non-null mono of rows updated is required.").cache();
  }

  @Override
  public Mono<Integer> getRowsUpdated() {
    return this.rowsUpdated;
  }

  @Override
  public <T> Flux<T> map(BiFunction<Row, RowMetadata, ? extends T> f) {

    if (this.resultRows == null) {
      return Flux.empty();
    }

    return this.resultRows.map(row -> f.apply(row, row.getRowMetadata()));
  }
}

/*
 * Copyright (C) 2009 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.Pure;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.GwtCompatible;

import java.util.Map;

/**
 * An implementation of {@link ImmutableTable} that holds a single cell.
 *
 * @author Gregory Kick
 */
@GwtCompatible
class SingletonImmutableTable<R, C, V> extends ImmutableTable<R, C, V> {
  final R singleRowKey;
  final C singleColumnKey;
  final V singleValue;

  @Impure
  SingletonImmutableTable(R rowKey, C columnKey, V value) {
    this.singleRowKey = checkNotNull(rowKey);
    this.singleColumnKey = checkNotNull(columnKey);
    this.singleValue = checkNotNull(value);
  }

  @Impure
  SingletonImmutableTable(Cell<R, C, V> cell) {
    this(cell.getRowKey(), cell.getColumnKey(), cell.getValue());
  }

  @Impure
  @Override public ImmutableMap<R, V> column(C columnKey) {
    checkNotNull(columnKey);
    return containsColumn(columnKey)
        ? ImmutableMap.of(singleRowKey, singleValue)
        : ImmutableMap.<R, V>of();
  }

  @Impure
  @Override public ImmutableMap<C, Map<R, V>> columnMap() {
    return ImmutableMap.of(singleColumnKey,
        (Map<R, V>) ImmutableMap.of(singleRowKey, singleValue));
  }

  @Impure
  @Override public ImmutableMap<R, Map<C, V>> rowMap() {
    return ImmutableMap.of(singleRowKey,
        (Map<C, V>) ImmutableMap.of(singleColumnKey, singleValue));
  }

  @Pure
  @Override public int size() {
    return 1;
  }

  @Impure
  @Override
  ImmutableSet<Cell<R, C, V>> createCellSet() {
    return ImmutableSet.of(
        cellOf(singleRowKey, singleColumnKey, singleValue));
  }

  @Impure
  @Override ImmutableCollection<V> createValues() {
    return ImmutableSet.of(singleValue);
  }
}

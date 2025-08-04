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
import com.google.common.annotations.GwtCompatible;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * A table which forwards all its method calls to another table. Subclasses
 * should override one or more methods to modify the behavior of the backing
 * map as desired per the <a
 * href="http://en.wikipedia.org/wiki/Decorator_pattern">decorator pattern</a>.
 *
 * @author Gregory Kick
 * @since 7.0
 */
@GwtCompatible
public abstract class ForwardingTable<R, C, V> extends ForwardingObject
    implements Table<R, C, V> {
  /** Constructor for use by subclasses. */
  @Impure
  protected ForwardingTable() {}

  @Pure
  @Override protected abstract Table<R, C, V> delegate();

  @Impure
  @Override
  public Set<Cell<R, C, V>> cellSet() {
    return delegate().cellSet();
  }

  @Impure
  @Override
  public void clear() {
    delegate().clear();
  }

  @Impure
  @Override
  public Map<R, V> column(C columnKey) {
    return delegate().column(columnKey);
  }

  @Impure
  @Override
  public Set<C> columnKeySet() {
    return delegate().columnKeySet();
  }

  @Impure
  @Override
  public Map<C, Map<R, V>> columnMap() {
    return delegate().columnMap();
  }

  @Impure
  @Override
  public boolean contains(Object rowKey, Object columnKey) {
    return delegate().contains(rowKey, columnKey);
  }

  @Impure
  @Override
  public boolean containsColumn(Object columnKey) {
    return delegate().containsColumn(columnKey);
  }

  @Impure
  @Override
  public boolean containsRow(Object rowKey) {
    return delegate().containsRow(rowKey);
  }

  @Impure
  @Override
  public boolean containsValue(Object value) {
    return delegate().containsValue(value);
  }

  @Impure
  @Override
  public V get(Object rowKey, Object columnKey) {
    return delegate().get(rowKey, columnKey);
  }

  @Impure
  @Override
  public boolean isEmpty() {
    return delegate().isEmpty();
  }

  @Impure
  @Override
  public V put(R rowKey, C columnKey, V value) {
    return delegate().put(rowKey, columnKey, value);
  }

  @Impure
  @Override
  public void putAll(Table<? extends R, ? extends C, ? extends V> table) {
    delegate().putAll(table);
  }

  @Impure
  @Override
  public V remove(Object rowKey, Object columnKey) {
    return delegate().remove(rowKey, columnKey);
  }

  @Impure
  @Override
  public Map<C, V> row(R rowKey) {
    return delegate().row(rowKey);
  }

  @Impure
  @Override
  public Set<R> rowKeySet() {
    return delegate().rowKeySet();
  }

  @Impure
  @Override
  public Map<R, Map<C, V>> rowMap() {
    return delegate().rowMap();
  }

  @Impure
  @Override
  public int size() {
    return delegate().size();
  }

  @Impure
  @Override
  public Collection<V> values() {
    return delegate().values();
  }

  @Pure
  @Impure
  @Override public boolean equals(Object obj) {
    return (obj == this) || delegate().equals(obj);
  }

  @Pure
  @Impure
  @Override public int hashCode() {
    return delegate().hashCode();
  }
}

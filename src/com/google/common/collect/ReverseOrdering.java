/*
 * Copyright (C) 2007 The Guava Authors
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

import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.Impure;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.GwtCompatible;

import java.io.Serializable;
import java.util.Iterator;

import javax.annotation.Nullable;

/** An ordering that uses the reverse of a given order. */
@GwtCompatible(serializable = true)
final class ReverseOrdering<T> extends Ordering<T> implements Serializable {
  final Ordering<? super T> forwardOrder;

  @Impure
  ReverseOrdering(Ordering<? super T> forwardOrder) {
    this.forwardOrder = checkNotNull(forwardOrder);
  }

  @Impure
  @Override public int compare(T a, T b) {
    return forwardOrder.compare(b, a);
  }

  @Pure
  @SuppressWarnings("unchecked") // how to explain?
  @Override public <S extends T> Ordering<S> reverse() {
    return (Ordering<S>) forwardOrder;
  }

  // Override the min/max methods to "hoist" delegation outside loops

  @Impure
  @Override public <E extends T> E min(E a, E b) {
    return forwardOrder.max(a, b);
  }

  @Impure
  @Override public <E extends T> E min(E a, E b, E c, E... rest) {
    return forwardOrder.max(a, b, c, rest);
  }

  @Impure
  @Override public <E extends T> E min(Iterator<E> iterator) {
    return forwardOrder.max(iterator);
  }

  @Impure
  @Override public <E extends T> E min(Iterable<E> iterable) {
    return forwardOrder.max(iterable);
  }

  @Impure
  @Override public <E extends T> E max(E a, E b) {
    return forwardOrder.min(a, b);
  }

  @Impure
  @Override public <E extends T> E max(E a, E b, E c, E... rest) {
    return forwardOrder.min(a, b, c, rest);
  }

  @Impure
  @Override public <E extends T> E max(Iterator<E> iterator) {
    return forwardOrder.min(iterator);
  }

  @Impure
  @Override public <E extends T> E max(Iterable<E> iterable) {
    return forwardOrder.min(iterable);
  }

  @Pure
  @Override public int hashCode() {
    return -forwardOrder.hashCode();
  }

  @Pure
  @Override public boolean equals(@Nullable Object object) {
    if (object == this) {
      return true;
    }
    if (object instanceof ReverseOrdering) {
      ReverseOrdering<?> that = (ReverseOrdering<?>) object;
      return this.forwardOrder.equals(that.forwardOrder);
    }
    return false;
  }

  @Pure
  @Override public String toString() {
    return forwardOrder + ".reverse()";
  }

  private static final long serialVersionUID = 0;
}

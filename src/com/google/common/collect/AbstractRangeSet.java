/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import javax.annotation.Nullable;

/**
 * A skeletal implementation of {@code RangeSet}.
 *
 * @author Louis Wasserman
 */
abstract class AbstractRangeSet<C extends Comparable> implements RangeSet<C> {
  @SideEffectFree
  AbstractRangeSet() {}

  @Pure
  @Impure
  @Override
  public boolean contains(C value) {
    return rangeContaining(value) != null;
  }

  @Pure
  @Override
  public abstract Range<C> rangeContaining(C value);

  @Impure
  @Override
  public boolean isEmpty() {
    return asRanges().isEmpty();
  }

  @SideEffectFree
  @Override
  public void add(Range<C> range) {
    throw new UnsupportedOperationException();
  }

  @SideEffectFree
  @Override
  public void remove(Range<C> range) {
    throw new UnsupportedOperationException();
  }
  
  @Impure
  @Override
  public void clear() {
    remove(Range.<C>all());
  }

  @Impure
  @Override
  public boolean enclosesAll(RangeSet<C> other) {
    for (Range<C> range : other.asRanges()) {
      if (!encloses(range)) {
        return false;
      }
    }
    return true;
  }

  @Impure
  @Override
  public void addAll(RangeSet<C> other) {
    for (Range<C> range : other.asRanges()) {
      add(range);
    }
  }

  @Impure
  @Override
  public void removeAll(RangeSet<C> other) {
    for (Range<C> range : other.asRanges()) {
      remove(range);
    }
  }

  @Pure
  @Override
  public abstract boolean encloses(Range<C> otherRange);

  @Impure
  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == this) {
      return true;
    } else if (obj instanceof RangeSet) {
      RangeSet<?> other = (RangeSet<?>) obj;
      return this.asRanges().equals(other.asRanges());
    }
    return false;
  }

  @Impure
  @Override
  public final int hashCode() {
    return asRanges().hashCode();
  }

  @Impure
  @Override
  public final String toString() {
    return asRanges().toString();
  }
}

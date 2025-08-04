/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.collect;

import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.dataflow.qual.Pure;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.BoundType.CLOSED;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;

import java.io.Serializable;
import java.util.Collection;

import javax.annotation.Nullable;

/**
 * An implementation of {@link ContiguousSet} that contains one or more elements.
 *
 * @author Gregory Kick
 */
@GwtCompatible(emulated = true)
@SuppressWarnings("unchecked") // allow ungenerified Comparable types
final class RegularContiguousSet<C extends Comparable> extends ContiguousSet<C> {
  private final Range<C> range;

  @Impure
  RegularContiguousSet(Range<C> range, DiscreteDomain<C> domain) {
    super(domain);
    this.range = range;
  }

  @Impure
  private ContiguousSet<C> intersectionInCurrentDomain(Range<C> other) {
    return (range.isConnected(other))
        ? ContiguousSet.create(range.intersection(other), domain)
        : new EmptyContiguousSet<C>(domain);
  }

  @Impure
  @Override ContiguousSet<C> headSetImpl(C toElement, boolean inclusive) {
    return intersectionInCurrentDomain(Range.upTo(toElement, BoundType.forBoolean(inclusive)));
  }

  @Impure
  @Override ContiguousSet<C> subSetImpl(C fromElement, boolean fromInclusive, C toElement,
      boolean toInclusive) {
    if (fromElement.compareTo(toElement) == 0 && !fromInclusive && !toInclusive) {
      // Range would reject our attempt to create (x, x).
      return new EmptyContiguousSet<C>(domain);
    }
    return intersectionInCurrentDomain(Range.range(
        fromElement, BoundType.forBoolean(fromInclusive),
        toElement, BoundType.forBoolean(toInclusive)));
  }

  @Impure
  @Override ContiguousSet<C> tailSetImpl(C fromElement, boolean inclusive) {
    return intersectionInCurrentDomain(Range.downTo(fromElement, BoundType.forBoolean(inclusive)));
  }

  @Impure
  @GwtIncompatible("not used by GWT emulation")
  @Override int indexOf(Object target) {
    return contains(target) ? (int) domain.distance(first(), (C) target) : -1;
  }

  @Impure
  @Override public UnmodifiableIterator<C> iterator() {
    return new AbstractSequentialIterator<C>(first()) {
      final C last = last();

      @Impure
      @Override
      protected C computeNext(C previous) {
        return equalsOrThrow(previous, last) ? null : domain.next(previous);
      }
    };
  }

  @Impure
  @GwtIncompatible("NavigableSet")
  @Override public UnmodifiableIterator<C> descendingIterator() {
    return new AbstractSequentialIterator<C>(last()) {
      final C first = first();

      @Impure
      @Override
      protected C computeNext(C previous) {
        return equalsOrThrow(previous, first) ? null : domain.previous(previous);
      }
    };
  }

  @Pure
  @Impure
  private static boolean equalsOrThrow(Comparable<?> left, @Nullable Comparable<?> right) {
    return right != null && Range.compareOrThrow(left, right) == 0;
  }

  @Pure
  @Override boolean isPartialView() {
    return false;
  }

  @Impure
  @Override public C first() {
    return range.lowerBound.leastValueAbove(domain);
  }

  @Impure
  @Override public C last() {
    return range.upperBound.greatestValueBelow(domain);
  }

  @Impure
  @Override public int size() {
    long distance = domain.distance(first(), last());
    return (distance >= Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) distance + 1;
  }

  @Impure
  @Override public boolean contains(@Nullable Object object) {
    if (object == null) {
      return false;
    }
    try {
      return range.contains((C) object);
    } catch (ClassCastException e) {
      return false;
    }
  }

  @Impure
  @Override public boolean containsAll(Collection<?> targets) {
    return Collections2.containsAllImpl(this, targets);
  }

  @Pure
  @Override public boolean isEmpty() {
    return false;
  }

  @Impure
  @Override public ContiguousSet<C> intersection(ContiguousSet<C> other) {
    checkNotNull(other);
    checkArgument(this.domain.equals(other.domain));
    if (other.isEmpty()) {
      return other;
    } else {
      C lowerEndpoint = Ordering.natural().max(this.first(), other.first());
      C upperEndpoint = Ordering.natural().min(this.last(), other.last());
      return (lowerEndpoint.compareTo(upperEndpoint) < 0)
          ? ContiguousSet.create(Range.closed(lowerEndpoint, upperEndpoint), domain)
          : new EmptyContiguousSet<C>(domain);
    }
  }

  @Impure
  @Override public Range<C> range() {
    return range(CLOSED, CLOSED);
  }

  @Impure
  @Override public Range<C> range(BoundType lowerBoundType, BoundType upperBoundType) {
    return Range.create(range.lowerBound.withLowerBoundType(lowerBoundType, domain),
        range.upperBound.withUpperBoundType(upperBoundType, domain));
  }

  @SideEffectFree
  @Override public boolean equals(@Nullable Object object) {
    if (object == this) {
      return true;
    } else if (object instanceof RegularContiguousSet) {
      RegularContiguousSet<?> that = (RegularContiguousSet<?>) object;
      if (this.domain.equals(that.domain)) {
        return this.first().equals(that.first())
            && this.last().equals(that.last());
      }
    }
    return super.equals(object);
  }

  // copied to make sure not to use the GWT-emulated version
  @Impure
  @Override public int hashCode() {
    return Sets.hashCodeImpl(this);
  }

  @GwtIncompatible("serialization")
  private static final class SerializedForm<C extends Comparable> implements Serializable {
    final Range<C> range;
    final DiscreteDomain<C> domain;

    @SideEffectFree
    private SerializedForm(Range<C> range, DiscreteDomain<C> domain) {
      this.range = range;
      this.domain = domain;
    }

    @Impure
    private Object readResolve() {
      return new RegularContiguousSet<C>(range, domain);
    }
  }

  @SideEffectFree
  @Impure
  @GwtIncompatible("serialization")
  @Override Object writeReplace() {
    return new SerializedForm<C>(range, domain);
  }

  private static final long serialVersionUID = 0;
}

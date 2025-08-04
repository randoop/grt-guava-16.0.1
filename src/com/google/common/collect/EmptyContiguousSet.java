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
import org.checkerframework.dataflow.qual.Deterministic;
import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;

import java.io.Serializable;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * An empty contiguous set.
 *
 * @author Gregory Kick
 */
@GwtCompatible(emulated = true)
@SuppressWarnings("unchecked") // allow ungenerified Comparable types
final class EmptyContiguousSet<C extends Comparable> extends ContiguousSet<C> {
  @Impure
  EmptyContiguousSet(DiscreteDomain<C> domain) {
    super(domain);
  }

  @Deterministic
  @Override public C first() {
    throw new NoSuchElementException();
  }

  @Deterministic
  @Override public C last() {
    throw new NoSuchElementException();
  }

  @Pure
  @Override public int size() {
    return 0;
  }

  @Pure
  @Override public ContiguousSet<C> intersection(ContiguousSet<C> other) {
    return this;
  }

  @Deterministic
  @Override public Range<C> range() {
    throw new NoSuchElementException();
  }

  @Deterministic
  @Override public Range<C> range(BoundType lowerBoundType, BoundType upperBoundType) {
    throw new NoSuchElementException();
  }

  @Pure
  @Override ContiguousSet<C> headSetImpl(C toElement, boolean inclusive) {
    return this;
  }

  @Pure
  @Override ContiguousSet<C> subSetImpl(
      C fromElement, boolean fromInclusive, C toElement, boolean toInclusive) {
    return this;
  }

  @Pure
  @Override ContiguousSet<C> tailSetImpl(C fromElement, boolean fromInclusive) {
    return this;
  }

  @Pure
  @GwtIncompatible("not used by GWT emulation")
  @Override int indexOf(Object target) {
    return -1;
  }

  @Impure
  @Override public UnmodifiableIterator<C> iterator() {
    return Iterators.emptyIterator();
  }

  @Impure
  @GwtIncompatible("NavigableSet")
  @Override public UnmodifiableIterator<C> descendingIterator() {
    return Iterators.emptyIterator();
  }

  @Pure
  @Override boolean isPartialView() {
    return false;
  }

  @Pure
  @Override public boolean isEmpty() {
    return true;
  }

  @Impure
  @Override public ImmutableList<C> asList() {
    return ImmutableList.of();
  }

  @Pure
  @Override public String toString() {
    return "[]";
  }

  @Pure
  @Override public boolean equals(@Nullable Object object) {
    if (object instanceof Set) {
      Set<?> that = (Set<?>) object;
      return that.isEmpty();
    }
    return false;
  }

  @Pure
  @Override public int hashCode() {
    return 0;
  }

  @GwtIncompatible("serialization")
  private static final class SerializedForm<C extends Comparable> implements Serializable {
    private final DiscreteDomain<C> domain;

    @SideEffectFree
    private SerializedForm(DiscreteDomain<C> domain) {
      this.domain = domain;
    }

    @Impure
    private Object readResolve() {
      return new EmptyContiguousSet<C>(domain);
    }

    private static final long serialVersionUID = 0;
  }

  @SideEffectFree
  @Impure
  @GwtIncompatible("serialization")
  @Override
  Object writeReplace() {
    return new SerializedForm<C>(domain);
  }

  @Impure
  @GwtIncompatible("NavigableSet")
  ImmutableSortedSet<C> createDescendingSet() {
    return new EmptyImmutableSortedSet<C>(Ordering.natural().reverse());
  }
}

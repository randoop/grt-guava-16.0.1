/*
 * Copyright (C) 2008 The Guava Authors
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
import org.checkerframework.dataflow.qual.Deterministic;
import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;

import java.util.Collection;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * An empty immutable sorted set.
 *
 * @author Jared Levy
 */
@GwtCompatible(serializable = true, emulated = true)
@SuppressWarnings("serial") // uses writeReplace(), not default serialization
class EmptyImmutableSortedSet<E> extends ImmutableSortedSet<E> {
  @Impure
  EmptyImmutableSortedSet(Comparator<? super E> comparator) {
    super(comparator);
  }

  @Pure
  @Override
  public int size() {
    return 0;
  }

  @Pure
  @Override public boolean isEmpty() {
    return true;
  }

  @Pure
  @Override public boolean contains(@Nullable Object target) {
    return false;
  }

  @Pure
  @Override public boolean containsAll(Collection<?> targets) {
    return targets.isEmpty();
  }

  @Impure
  @Override public UnmodifiableIterator<E> iterator() {
    return Iterators.emptyIterator();
  }

  @Impure
  @GwtIncompatible("NavigableSet")
  @Override public UnmodifiableIterator<E> descendingIterator() {
    return Iterators.emptyIterator();
  }

  @Pure
  @Override boolean isPartialView() {
    return false;
  }

  @Impure
  @Override public ImmutableList<E> asList() {
    return ImmutableList.of();
  }

  @Pure
  @Override
  int copyIntoArray(Object[] dst, int offset) {
    return offset;
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

  @Pure
  @Override public String toString() {
    return "[]";
  }

  @Deterministic
  @Override
  public E first() {
    throw new NoSuchElementException();
  }

  @Deterministic
  @Override
  public E last() {
    throw new NoSuchElementException();
  }

  @Pure
  @Override
  ImmutableSortedSet<E> headSetImpl(E toElement, boolean inclusive) {
    return this;
  }

  @Pure
  @Override
  ImmutableSortedSet<E> subSetImpl(
      E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
    return this;
  }

  @Pure
  @Override
  ImmutableSortedSet<E> tailSetImpl(E fromElement, boolean inclusive) {
    return this;
  }

  @Pure
  @Override int indexOf(@Nullable Object target) {
    return -1;
  }

  @Impure
  @Override
  ImmutableSortedSet<E> createDescendingSet() {
    return new EmptyImmutableSortedSet<E>(Ordering.from(comparator).reverse());
  }
}

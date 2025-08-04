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
import org.checkerframework.dataflow.qual.Pure;
import javax.annotation.Nullable;

/**
 * A descending wrapper around an {@code ImmutableSortedMultiset}
 *
 * @author Louis Wasserman
 */
@SuppressWarnings("serial") // uses writeReplace, not default serialization
final class DescendingImmutableSortedMultiset<E> extends ImmutableSortedMultiset<E> {
  private final transient ImmutableSortedMultiset<E> forward;

  @Impure
  DescendingImmutableSortedMultiset(ImmutableSortedMultiset<E> forward) {
    this.forward = forward;
  }

  @Impure
  @Override
  public int count(@Nullable Object element) {
    return forward.count(element);
  }

  @Impure
  @Override
  public Entry<E> firstEntry() {
    return forward.lastEntry();
  }

  @Impure
  @Override
  public Entry<E> lastEntry() {
    return forward.firstEntry();
  }

  @Pure
  @Override
  public int size() {
    return forward.size();
  }

  @Impure
  @Override
  public ImmutableSortedSet<E> elementSet() {
    return forward.elementSet().descendingSet();
  }

  @Impure
  @Override
  Entry<E> getEntry(int index) {
    return forward.entrySet().asList().reverse().get(index);
  }

  @Pure
  @Override
  public ImmutableSortedMultiset<E> descendingMultiset() {
    return forward;
  }

  @Impure
  @Override
  public ImmutableSortedMultiset<E> headMultiset(E upperBound, BoundType boundType) {
    return forward.tailMultiset(upperBound, boundType).descendingMultiset();
  }

  @Impure
  @Override
  public ImmutableSortedMultiset<E> tailMultiset(E lowerBound, BoundType boundType) {
    return forward.headMultiset(lowerBound, boundType).descendingMultiset();
  }

  @Impure
  @Override
  boolean isPartialView() {
    return forward.isPartialView();
  }
}

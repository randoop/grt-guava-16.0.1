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
import com.google.common.base.Preconditions;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Implementation of {@link ImmutableList} with exactly one element.
 *
 * @author Hayward Chan
 */
@GwtCompatible(serializable = true, emulated = true)
@SuppressWarnings("serial") // uses writeReplace(), not default serialization
final class SingletonImmutableList<E> extends ImmutableList<E> {

  final transient E element;

  @Impure
  SingletonImmutableList(E element) {
    this.element = checkNotNull(element);
  }

  @Impure
  @Override
  public E get(int index) {
    Preconditions.checkElementIndex(index, 1);
    return element;
  }

  @Pure
  @Override public int indexOf(@Nullable Object object) {
    return element.equals(object) ? 0 : -1;
  }

  @Impure
  @Override public UnmodifiableIterator<E> iterator() {
    return Iterators.singletonIterator(element);
  }

  @Pure
  @Override public int lastIndexOf(@Nullable Object object) {
    return indexOf(object);
  }

  @Pure
  @Override
  public int size() {
    return 1;
  }

  @Impure
  @Override public ImmutableList<E> subList(int fromIndex, int toIndex) {
    Preconditions.checkPositionIndexes(fromIndex, toIndex, 1);
    return (fromIndex == toIndex) ? ImmutableList.<E>of() : this;
  }

  @Pure
  @Override public ImmutableList<E> reverse() {
    return this;
  }

  @Pure
  @Override public boolean contains(@Nullable Object object) {
    return element.equals(object);
  }

  @Pure
  @Override public boolean equals(@Nullable Object object) {
    if (object == this) {
      return true;
    }
    if (object instanceof List) {
      List<?> that = (List<?>) object;
      return that.size() == 1 && element.equals(that.get(0));
    }
    return false;
  }

  @Pure
  @Override public int hashCode() {
    // not caching hash code since it could change if the element is mutable
    // in a way that modifies its hash code.
    return 31 + element.hashCode();
  }

  @Impure
  @Override public String toString() {
    String elementToString = element.toString();
    return new StringBuilder(elementToString.length() + 2)
        .append('[')
        .append(elementToString)
        .append(']')
        .toString();
  }

  @Pure
  @Override public boolean isEmpty() {
    return false;
  }

  @Pure
  @Override boolean isPartialView() {
    return false;
  }

  @Impure
  @Override
  int copyIntoArray(Object[] dst, int offset) {
    dst[offset] = element;
    return offset + 1;
  }
}

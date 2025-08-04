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
import org.checkerframework.dataflow.qual.SideEffectFree;
import com.google.common.annotations.GwtCompatible;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * Implementation of {@link ImmutableList} with one or more elements.
 *
 * @author Kevin Bourrillion
 */
@GwtCompatible(serializable = true, emulated = true)
@SuppressWarnings("serial") // uses writeReplace(), not default serialization
class RegularImmutableList<E> extends ImmutableList<E> {
  private final transient int offset;
  private final transient int size;
  private final transient Object[] array;

  @SideEffectFree
  RegularImmutableList(Object[] array, int offset, int size) {
    this.offset = offset;
    this.size = size;
    this.array = array;
  }

  @Impure
  RegularImmutableList(Object[] array) {
    this(array, 0, array.length);
  }

  @Pure
  @Override
  public int size() {
    return size;
  }

  @Pure
  @Override boolean isPartialView() {
    return size != array.length;
  }

  @SideEffectFree
  @Override
  int copyIntoArray(Object[] dst, int dstOff) {
    System.arraycopy(array, offset, dst, dstOff, size);
    return dstOff + size;
  }

  // The fake cast to E is safe because the creation methods only allow E's
  @Impure
  @Override
  @SuppressWarnings("unchecked")
  public E get(int index) {
    Preconditions.checkElementIndex(index, size);
    return (E) array[index + offset];
  }

  @Pure
  @Override
  public int indexOf(@Nullable Object object) {
    if (object == null) {
      return -1;
    }
    for (int i = 0; i < size; i++) {
      if (array[offset + i].equals(object)) {
        return i;
      }
    }
    return -1;
  }

  @Pure
  @Override
  public int lastIndexOf(@Nullable Object object) {
    if (object == null) {
      return -1;
    }
    for (int i = size - 1; i >= 0; i--) {
      if (array[offset + i].equals(object)) {
        return i;
      }
    }
    return -1;
  }

  @Impure
  @Override
  ImmutableList<E> subListUnchecked(int fromIndex, int toIndex) {
    return new RegularImmutableList<E>(
        array, offset + fromIndex, toIndex - fromIndex);
  }

  @Impure
  @SuppressWarnings("unchecked")
  @Override
  public UnmodifiableListIterator<E> listIterator(int index) {
    // for performance
    // The fake cast to E is safe because the creation methods only allow E's
    return (UnmodifiableListIterator<E>)
        Iterators.forArray(array, offset, size, index);
  }

  // TODO(user): benchmark optimizations for equals() and see if they're worthwhile
}

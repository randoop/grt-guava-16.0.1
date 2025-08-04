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

import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.Pure;
import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;

import java.io.Serializable;
import java.util.Map.Entry;

import javax.annotation.Nullable;

/**
 * {@code values()} implementation for {@link ImmutableMap}.
 *
 * @author Jesse Wilson
 * @author Kevin Bourrillion
 */
@GwtCompatible(emulated = true)
final class ImmutableMapValues<K, V> extends ImmutableCollection<V> {
  private final ImmutableMap<K, V> map;
  
  @SideEffectFree
  ImmutableMapValues(ImmutableMap<K, V> map) {
    this.map = map;
  }

  @Pure
  @Override
  public int size() {
    return map.size();
  }

  @Impure
  @Override
  public UnmodifiableIterator<V> iterator() {
    return Maps.valueIterator(map.entrySet().iterator());
  }

  @Impure
  @Override
  public boolean contains(@Nullable Object object) {
    return object != null && Iterators.contains(iterator(), object);
  }

  @Pure
  @Override
  boolean isPartialView() {
    return true;
  }

  @Impure
  @Override
  ImmutableList<V> createAsList() {
    final ImmutableList<Entry<K, V>> entryList = map.entrySet().asList();
    return new ImmutableAsList<V>() {
      @Pure
      @Override
      public V get(int index) {
        return entryList.get(index).getValue();
      }

      @Pure
      @Override
      ImmutableCollection<V> delegateCollection() {
        return ImmutableMapValues.this;
      }
    };
  }

  @SideEffectFree
  @Impure
  @GwtIncompatible("serialization")
  @Override Object writeReplace() {
    return new SerializedForm<V>(map);
  }

  @GwtIncompatible("serialization")
  private static class SerializedForm<V> implements Serializable {
    final ImmutableMap<?, V> map;
    @SideEffectFree
    SerializedForm(ImmutableMap<?, V> map) {
      this.map = map;
    }
    @SideEffectFree
    Object readResolve() {
      return map.values();
    }
    private static final long serialVersionUID = 0;
  }
}

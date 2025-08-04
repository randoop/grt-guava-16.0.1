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
 * {@code keySet()} implementation for {@link ImmutableMap}.
 *
 * @author Jesse Wilson
 * @author Kevin Bourrillion
 */
@GwtCompatible(emulated = true)
final class ImmutableMapKeySet<K, V> extends ImmutableSet<K> {
  private final ImmutableMap<K, V> map;

  @SideEffectFree
  ImmutableMapKeySet(ImmutableMap<K, V> map) {
    this.map = map;
  }

  @Pure
  @Override
  public int size() {
    return map.size();
  }

  @Impure
  @Override
  public UnmodifiableIterator<K> iterator() {
    return asList().iterator();
  }

  @Pure
  @Override
  public boolean contains(@Nullable Object object) {
    return map.containsKey(object);
  }

  @Impure
  @Override
  ImmutableList<K> createAsList() {
    final ImmutableList<Entry<K, V>> entryList = map.entrySet().asList();
    return new ImmutableAsList<K>() {

      @Pure
      @Override
      public K get(int index) {
        return entryList.get(index).getKey();
      }

      @Pure
      @Override
      ImmutableCollection<K> delegateCollection() {
        return ImmutableMapKeySet.this;
      }

    };
  }

  @Pure
  @Override
  boolean isPartialView() {
    return true;
  }

  @SideEffectFree
  @Impure
  @GwtIncompatible("serialization")
  @Override Object writeReplace() {
    return new KeySetSerializedForm<K>(map);
  }

  @GwtIncompatible("serialization")
  private static class KeySetSerializedForm<K> implements Serializable {
    final ImmutableMap<K, ?> map;
    @SideEffectFree
    KeySetSerializedForm(ImmutableMap<K, ?> map) {
      this.map = map;
    }
    @SideEffectFree
    Object readResolve() {
      return map.keySet();
    }
    private static final long serialVersionUID = 0;
  }
}

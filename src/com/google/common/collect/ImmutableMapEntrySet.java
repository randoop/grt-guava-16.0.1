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
 * {@code entrySet()} implementation for {@link ImmutableMap}.
 *
 * @author Jesse Wilson
 * @author Kevin Bourrillion
 */
@GwtCompatible(emulated = true)
abstract class ImmutableMapEntrySet<K, V> extends ImmutableSet<Entry<K, V>> {
  @SideEffectFree
  @Impure
  ImmutableMapEntrySet() {}

  @Pure
  abstract ImmutableMap<K, V> map();

  @Pure
  @Impure
  @Override
  public int size() {
    return map().size();
  }

  @Pure
  @Impure
  @Override
  public boolean contains(@Nullable Object object) {
    if (object instanceof Entry) {
      Entry<?, ?> entry = (Entry<?, ?>) object;
      V value = map().get(entry.getKey());
      return value != null && value.equals(entry.getValue());
    }
    return false;
  }

  @Pure
  @Impure
  @Override
  boolean isPartialView() {
    return map().isPartialView();
  }

  @SideEffectFree
  @Impure
  @GwtIncompatible("serialization")
  @Override
  Object writeReplace() {
    return new EntrySetSerializedForm<K, V>(map());
  }

  @GwtIncompatible("serialization")
  private static class EntrySetSerializedForm<K, V> implements Serializable {
    final ImmutableMap<K, V> map;
    @SideEffectFree
    EntrySetSerializedForm(ImmutableMap<K, V> map) {
      this.map = map;
    }
    @SideEffectFree
    Object readResolve() {
      return map.entrySet();
    }
    private static final long serialVersionUID = 0;
  }
}

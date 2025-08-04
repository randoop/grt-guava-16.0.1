/*
 * Copyright (C) 2012 The Guava Authors
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

package com.google.common.reflect;

import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ForwardingMapEntry;
import com.google.common.collect.ForwardingSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A mutable type-to-instance map.
 * See also {@link ImmutableTypeToInstanceMap}.
 *
 * @author Ben Yu
 * @since 13.0
 */
@Beta
public final class MutableTypeToInstanceMap<B> extends ForwardingMap<TypeToken<? extends B>, B>
    implements TypeToInstanceMap<B> {

  private final Map<TypeToken<? extends B>, B> backingMap = Maps.newHashMap();

  @Impure
  @Nullable
  @Override
  public <T extends B> T getInstance(Class<T> type) {
    return trustedGet(TypeToken.of(type));
  }

  @Impure
  @Nullable
  @Override
  public <T extends B> T putInstance(Class<T> type, @Nullable T value) {
    return trustedPut(TypeToken.of(type), value);
  }

  @Impure
  @Nullable
  @Override
  public <T extends B> T getInstance(TypeToken<T> type) {
    return trustedGet(type.rejectTypeVariables());
  }

  @Impure
  @Nullable
  @Override
  public <T extends B> T putInstance(TypeToken<T> type, @Nullable T value) {
    return trustedPut(type.rejectTypeVariables(), value);
  }

  /** Not supported. Use {@link #putInstance} instead. */
  @Pure
  @Override public B put(TypeToken<? extends B> key, B value) {
    throw new UnsupportedOperationException("Please use putInstance() instead.");
  }

  /** Not supported. Use {@link #putInstance} instead. */
  @SideEffectFree
  @Override public void putAll(Map<? extends TypeToken<? extends B>, ? extends B> map) {
    throw new UnsupportedOperationException("Please use putInstance() instead.");
  }

  @Impure
  @Override public Set<Entry<TypeToken<? extends B>, B>> entrySet() {
    return UnmodifiableEntry.transformEntries(super.entrySet());
  }

  @Pure
  @Override protected Map<TypeToken<? extends B>, B> delegate() {
    return backingMap;
  }

  @Impure
  @SuppressWarnings("unchecked") // value could not get in if not a T
  @Nullable
  private <T extends B> T trustedPut(TypeToken<T> type, @Nullable T value) {
    return (T) backingMap.put(type, value);
  }

  @Pure
  @SuppressWarnings("unchecked") // value could not get in if not a T
  @Nullable
  private <T extends B> T trustedGet(TypeToken<T> type) {
    return (T) backingMap.get(type);
  }

  private static final class UnmodifiableEntry<K, V> extends ForwardingMapEntry<K, V> {

    private final Entry<K, V> delegate;

    @Impure
    static <K, V> Set<Entry<K, V>> transformEntries(final Set<Entry<K, V>> entries) {
      return new ForwardingSet<Map.Entry<K, V>>() {
        @Pure
        @Override protected Set<Entry<K, V>> delegate() {
          return entries;
        }
        @Impure
        @Override public Iterator<Entry<K, V>> iterator() {
          return UnmodifiableEntry.transformEntries(super.iterator());
        }
        @Impure
        @Override public Object[] toArray() {
          return standardToArray();
        }
        @Impure
        @Override public <T> T[] toArray(T[] array) {
          return standardToArray(array);
        }
      };
    }
  
    @Impure
    private static <K, V> Iterator<Entry<K, V>> transformEntries(Iterator<Entry<K, V>> entries) {
      return Iterators.transform(entries, new Function<Entry<K, V>, Entry<K, V>>() {
        @Impure
        @Override public Entry<K, V> apply(Entry<K, V> entry) {
          return new UnmodifiableEntry<K, V>(entry);
        }
      });
    }

    @Impure
    private UnmodifiableEntry(java.util.Map.Entry<K, V> delegate) {
      this.delegate = checkNotNull(delegate);
    }

    @Pure
    @Override protected Entry<K, V> delegate() {
      return delegate;
    }

    @Pure
    @Override public V setValue(V value) {
      throw new UnsupportedOperationException();
    }
  }
}

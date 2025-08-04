/*
 * Copyright (C) 2007 The Guava Authors
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
import com.google.common.annotations.GwtCompatible;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A multimap which forwards all its method calls to another multimap.
 * Subclasses should override one or more methods to modify the behavior of
 * the backing multimap as desired per the <a
 * href="http://en.wikipedia.org/wiki/Decorator_pattern">decorator pattern</a>.
 *
 * @author Robert Konigsberg
 * @since 2.0 (imported from Google Collections Library)
 */
@GwtCompatible
public abstract class ForwardingMultimap<K, V> extends ForwardingObject
    implements Multimap<K, V> {

  /** Constructor for use by subclasses. */
  @Impure
  protected ForwardingMultimap() {}

  @Pure
  @Override protected abstract Multimap<K, V> delegate();

  @Impure
  @Override
  public Map<K, Collection<V>> asMap() {
    return delegate().asMap();
  }

  @Impure
  @Override
  public void clear() {
    delegate().clear();
  }

  @Impure
  @Override
  public boolean containsEntry(@Nullable Object key, @Nullable Object value) {
    return delegate().containsEntry(key, value);
  }

  @Impure
  @Override
  public boolean containsKey(@Nullable Object key) {
    return delegate().containsKey(key);
  }

  @Impure
  @Override
  public boolean containsValue(@Nullable Object value) {
    return delegate().containsValue(value);
  }

  @Impure
  @Override
  public Collection<Entry<K, V>> entries() {
    return delegate().entries();
  }

  @Impure
  @Override
  public Collection<V> get(@Nullable K key) {
    return delegate().get(key);
  }

  @Impure
  @Override
  public boolean isEmpty() {
    return delegate().isEmpty();
  }

  @Impure
  @Override
  public Multiset<K> keys() {
    return delegate().keys();
  }

  @Impure
  @Override
  public Set<K> keySet() {
    return delegate().keySet();
  }

  @Impure
  @Override
  public boolean put(K key, V value) {
    return delegate().put(key, value);
  }

  @Impure
  @Override
  public boolean putAll(K key, Iterable<? extends V> values) {
    return delegate().putAll(key, values);
  }

  @Impure
  @Override
  public boolean putAll(Multimap<? extends K, ? extends V> multimap) {
    return delegate().putAll(multimap);
  }

  @Impure
  @Override
  public boolean remove(@Nullable Object key, @Nullable Object value) {
    return delegate().remove(key, value);
  }

  @Impure
  @Override
  public Collection<V> removeAll(@Nullable Object key) {
    return delegate().removeAll(key);
  }

  @Impure
  @Override
  public Collection<V> replaceValues(K key, Iterable<? extends V> values) {
    return delegate().replaceValues(key, values);
  }

  @Impure
  @Override
  public int size() {
    return delegate().size();
  }

  @Impure
  @Override
  public Collection<V> values() {
    return delegate().values();
  }

  @Pure
  @Impure
  @Override public boolean equals(@Nullable Object object) {
    return object == this || delegate().equals(object);
  }

  @Pure
  @Impure
  @Override public int hashCode() {
    return delegate().hashCode();
  }
}

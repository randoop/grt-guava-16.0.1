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
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.dataflow.qual.Pure;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.RandomAccess;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

import javax.annotation.Nullable;

/**
 * Synchronized collection views. The returned synchronized collection views are
 * serializable if the backing collection and the mutex are serializable.
 *
 * <p>If {@code null} is passed as the {@code mutex} parameter to any of this
 * class's top-level methods or inner class constructors, the created object
 * uses itself as the synchronization mutex.
 *
 * <p>This class should be used by other collection classes only.
 *
 * @author Mike Bostock
 * @author Jared Levy
 */
@GwtCompatible(emulated = true)
final class Synchronized {
  @SideEffectFree
  private Synchronized() {}

  static class SynchronizedObject implements Serializable {
    final Object delegate;
    final Object mutex;

    @Impure
    SynchronizedObject(Object delegate, @Nullable Object mutex) {
      this.delegate = checkNotNull(delegate);
      this.mutex = (mutex == null) ? this : mutex;
    }

    @Impure
    Object delegate() {
      return delegate;
    }

    // No equals and hashCode; see ForwardingObject for details.

    @SideEffectFree
    @Override public String toString() {
      synchronized (mutex) {
        return delegate.toString();
      }
    }

    // Serialization invokes writeObject only when it's private.
    // The SynchronizedObject subclasses don't need a writeObject method since
    // they don't contain any non-transient member variables, while the
    // following writeObject() handles the SynchronizedObject members.

    @Impure
    @GwtIncompatible("java.io.ObjectOutputStream")
    private void writeObject(ObjectOutputStream stream) throws IOException {
      synchronized (mutex) {
        stream.defaultWriteObject();
      }
    }

    @GwtIncompatible("not needed in emulated source")
    private static final long serialVersionUID = 0;
  }

  @Impure
  private static <E> Collection<E> collection(
      Collection<E> collection, @Nullable Object mutex) {
    return new SynchronizedCollection<E>(collection, mutex);
  }

  @VisibleForTesting static class SynchronizedCollection<E>
      extends SynchronizedObject implements Collection<E> {
    @Impure
    private SynchronizedCollection(
        Collection<E> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @SuppressWarnings("unchecked")
    @Override Collection<E> delegate() {
      return (Collection<E>) super.delegate();
    }

    @Impure
    @Override
    public boolean add(E e) {
      synchronized (mutex) {
        return delegate().add(e);
      }
    }

    @Impure
    @Override
    public boolean addAll(Collection<? extends E> c) {
      synchronized (mutex) {
        return delegate().addAll(c);
      }
    }

    @Impure
    @Override
    public void clear() {
      synchronized (mutex) {
        delegate().clear();
      }
    }

    @Impure
    @Override
    public boolean contains(Object o) {
      synchronized (mutex) {
        return delegate().contains(o);
      }
    }

    @Impure
    @Override
    public boolean containsAll(Collection<?> c) {
      synchronized (mutex) {
        return delegate().containsAll(c);
      }
    }

    @Impure
    @Override
    public boolean isEmpty() {
      synchronized (mutex) {
        return delegate().isEmpty();
      }
    }

    @Impure
    @Override
    public Iterator<E> iterator() {
      return delegate().iterator(); // manually synchronized
    }

    @Impure
    @Override
    public boolean remove(Object o) {
      synchronized (mutex) {
        return delegate().remove(o);
      }
    }

    @Impure
    @Override
    public boolean removeAll(Collection<?> c) {
      synchronized (mutex) {
        return delegate().removeAll(c);
      }
    }

    @Impure
    @Override
    public boolean retainAll(Collection<?> c) {
      synchronized (mutex) {
        return delegate().retainAll(c);
      }
    }

    @Impure
    @Override
    public int size() {
      synchronized (mutex) {
        return delegate().size();
      }
    }

    @Impure
    @Override
    public Object[] toArray() {
      synchronized (mutex) {
        return delegate().toArray();
      }
    }

    @Impure
    @Override
    public <T> T[] toArray(T[] a) {
      synchronized (mutex) {
        return delegate().toArray(a);
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  @VisibleForTesting static <E> Set<E> set(Set<E> set, @Nullable Object mutex) {
    return new SynchronizedSet<E>(set, mutex);
  }

  static class SynchronizedSet<E>
      extends SynchronizedCollection<E> implements Set<E> {

    @Impure
    SynchronizedSet(Set<E> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override Set<E> delegate() {
      return (Set<E>) super.delegate();
    }

    @Impure
    @Override public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      synchronized (mutex) {
        return delegate().equals(o);
      }
    }

    @Impure
    @Override public int hashCode() {
      synchronized (mutex) {
        return delegate().hashCode();
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  private static <E> SortedSet<E> sortedSet(
      SortedSet<E> set, @Nullable Object mutex) {
    return new SynchronizedSortedSet<E>(set, mutex);
  }

  static class SynchronizedSortedSet<E> extends SynchronizedSet<E>
      implements SortedSet<E> {
    @Impure
    SynchronizedSortedSet(SortedSet<E> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override SortedSet<E> delegate() {
      return (SortedSet<E>) super.delegate();
    }

    @Impure
    @Override
    public Comparator<? super E> comparator() {
      synchronized (mutex) {
        return delegate().comparator();
      }
    }

    @Impure
    @Override
    public SortedSet<E> subSet(E fromElement, E toElement) {
      synchronized (mutex) {
        return sortedSet(delegate().subSet(fromElement, toElement), mutex);
      }
    }

    @Impure
    @Override
    public SortedSet<E> headSet(E toElement) {
      synchronized (mutex) {
        return sortedSet(delegate().headSet(toElement), mutex);
      }
    }

    @Impure
    @Override
    public SortedSet<E> tailSet(E fromElement) {
      synchronized (mutex) {
        return sortedSet(delegate().tailSet(fromElement), mutex);
      }
    }

    @Impure
    @Override
    public E first() {
      synchronized (mutex) {
        return delegate().first();
      }
    }

    @Impure
    @Override
    public E last() {
      synchronized (mutex) {
        return delegate().last();
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  private static <E> List<E> list(List<E> list, @Nullable Object mutex) {
    return (list instanceof RandomAccess)
        ? new SynchronizedRandomAccessList<E>(list, mutex)
        : new SynchronizedList<E>(list, mutex);
  }

  private static class SynchronizedList<E> extends SynchronizedCollection<E>
      implements List<E> {
    @Impure
    SynchronizedList(List<E> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override List<E> delegate() {
      return (List<E>) super.delegate();
    }

    @Impure
    @Override
    public void add(int index, E element) {
      synchronized (mutex) {
        delegate().add(index, element);
      }
    }

    @Impure
    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
      synchronized (mutex) {
        return delegate().addAll(index, c);
      }
    }

    @Impure
    @Override
    public E get(int index) {
      synchronized (mutex) {
        return delegate().get(index);
      }
    }

    @Impure
    @Override
    public int indexOf(Object o) {
      synchronized (mutex) {
        return delegate().indexOf(o);
      }
    }

    @Impure
    @Override
    public int lastIndexOf(Object o) {
      synchronized (mutex) {
        return delegate().lastIndexOf(o);
      }
    }

    @Impure
    @Override
    public ListIterator<E> listIterator() {
      return delegate().listIterator(); // manually synchronized
    }

    @Impure
    @Override
    public ListIterator<E> listIterator(int index) {
      return delegate().listIterator(index); // manually synchronized
    }

    @Impure
    @Override
    public E remove(int index) {
      synchronized (mutex) {
        return delegate().remove(index);
      }
    }

    @Impure
    @Override
    public E set(int index, E element) {
      synchronized (mutex) {
        return delegate().set(index, element);
      }
    }

    @Impure
    @Override
    public List<E> subList(int fromIndex, int toIndex) {
      synchronized (mutex) {
        return list(delegate().subList(fromIndex, toIndex), mutex);
      }
    }

    @Impure
    @Override public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      synchronized (mutex) {
        return delegate().equals(o);
      }
    }

    @Impure
    @Override public int hashCode() {
      synchronized (mutex) {
        return delegate().hashCode();
      }
    }

    private static final long serialVersionUID = 0;
  }

  private static class SynchronizedRandomAccessList<E>
      extends SynchronizedList<E> implements RandomAccess {
    @Impure
    SynchronizedRandomAccessList(List<E> list, @Nullable Object mutex) {
      super(list, mutex);
    }
    private static final long serialVersionUID = 0;
  }

  @Impure
  static <E> Multiset<E> multiset(
      Multiset<E> multiset, @Nullable Object mutex) {
    if (multiset instanceof SynchronizedMultiset ||
        multiset instanceof ImmutableMultiset) {
      return multiset;
    }
    return new SynchronizedMultiset<E>(multiset, mutex);
  }

  private static class SynchronizedMultiset<E> extends SynchronizedCollection<E>
      implements Multiset<E> {
    transient Set<E> elementSet;
    transient Set<Entry<E>> entrySet;

    @Impure
    SynchronizedMultiset(Multiset<E> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override Multiset<E> delegate() {
      return (Multiset<E>) super.delegate();
    }

    @Impure
    @Override
    public int count(Object o) {
      synchronized (mutex) {
        return delegate().count(o);
      }
    }

    @Impure
    @Override
    public int add(E e, int n) {
      synchronized (mutex) {
        return delegate().add(e, n);
      }
    }

    @Impure
    @Override
    public int remove(Object o, int n) {
      synchronized (mutex) {
        return delegate().remove(o, n);
      }
    }

    @Impure
    @Override
    public int setCount(E element, int count) {
      synchronized (mutex) {
        return delegate().setCount(element, count);
      }
    }

    @Impure
    @Override
    public boolean setCount(E element, int oldCount, int newCount) {
      synchronized (mutex) {
        return delegate().setCount(element, oldCount, newCount);
      }
    }

    @Impure
    @Override
    public Set<E> elementSet() {
      synchronized (mutex) {
        if (elementSet == null) {
          elementSet = typePreservingSet(delegate().elementSet(), mutex);
        }
        return elementSet;
      }
    }

    @Impure
    @Override
    public Set<Entry<E>> entrySet() {
      synchronized (mutex) {
        if (entrySet == null) {
          entrySet = typePreservingSet(delegate().entrySet(), mutex);
        }
        return entrySet;
      }
    }

    @Impure
    @Override public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      synchronized (mutex) {
        return delegate().equals(o);
      }
    }

    @Impure
    @Override public int hashCode() {
      synchronized (mutex) {
        return delegate().hashCode();
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  static <K, V> Multimap<K, V> multimap(
      Multimap<K, V> multimap, @Nullable Object mutex) {
    if (multimap instanceof SynchronizedMultimap ||
        multimap instanceof ImmutableMultimap) {
      return multimap;
    }
    return new SynchronizedMultimap<K, V>(multimap, mutex);
  }

  private static class SynchronizedMultimap<K, V> extends SynchronizedObject
      implements Multimap<K, V> {
    transient Set<K> keySet;
    transient Collection<V> valuesCollection;
    transient Collection<Map.Entry<K, V>> entries;
    transient Map<K, Collection<V>> asMap;
    transient Multiset<K> keys;

    @Impure
    @SuppressWarnings("unchecked")
    @Override Multimap<K, V> delegate() {
      return (Multimap<K, V>) super.delegate();
    }

    @Impure
    SynchronizedMultimap(Multimap<K, V> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override
    public int size() {
      synchronized (mutex) {
        return delegate().size();
      }
    }

    @Impure
    @Override
    public boolean isEmpty() {
      synchronized (mutex) {
        return delegate().isEmpty();
      }
    }

    @Impure
    @Override
    public boolean containsKey(Object key) {
      synchronized (mutex) {
        return delegate().containsKey(key);
      }
    }

    @Impure
    @Override
    public boolean containsValue(Object value) {
      synchronized (mutex) {
        return delegate().containsValue(value);
      }
    }

    @Impure
    @Override
    public boolean containsEntry(Object key, Object value) {
      synchronized (mutex) {
        return delegate().containsEntry(key, value);
      }
    }

    @Impure
    @Override
    public Collection<V> get(K key) {
      synchronized (mutex) {
        return typePreservingCollection(delegate().get(key), mutex);
      }
    }

    @Impure
    @Override
    public boolean put(K key, V value) {
      synchronized (mutex) {
        return delegate().put(key, value);
      }
    }

    @Impure
    @Override
    public boolean putAll(K key, Iterable<? extends V> values) {
      synchronized (mutex) {
        return delegate().putAll(key, values);
      }
    }

    @Impure
    @Override
    public boolean putAll(Multimap<? extends K, ? extends V> multimap) {
      synchronized (mutex) {
        return delegate().putAll(multimap);
      }
    }

    @Impure
    @Override
    public Collection<V> replaceValues(K key, Iterable<? extends V> values) {
      synchronized (mutex) {
        return delegate().replaceValues(key, values); // copy not synchronized
      }
    }

    @Impure
    @Override
    public boolean remove(Object key, Object value) {
      synchronized (mutex) {
        return delegate().remove(key, value);
      }
    }

    @Impure
    @Override
    public Collection<V> removeAll(Object key) {
      synchronized (mutex) {
        return delegate().removeAll(key); // copy not synchronized
      }
    }

    @Impure
    @Override
    public void clear() {
      synchronized (mutex) {
        delegate().clear();
      }
    }

    @Impure
    @Override
    public Set<K> keySet() {
      synchronized (mutex) {
        if (keySet == null) {
          keySet = typePreservingSet(delegate().keySet(), mutex);
        }
        return keySet;
      }
    }

    @Impure
    @Override
    public Collection<V> values() {
      synchronized (mutex) {
        if (valuesCollection == null) {
          valuesCollection = collection(delegate().values(), mutex);
        }
        return valuesCollection;
      }
    }

    @Impure
    @Override
    public Collection<Map.Entry<K, V>> entries() {
      synchronized (mutex) {
        if (entries == null) {
          entries = typePreservingCollection(delegate().entries(), mutex);
        }
        return entries;
      }
    }

    @Impure
    @Override
    public Map<K, Collection<V>> asMap() {
      synchronized (mutex) {
        if (asMap == null) {
          asMap = new SynchronizedAsMap<K, V>(delegate().asMap(), mutex);
        }
        return asMap;
      }
    }

    @Impure
    @Override
    public Multiset<K> keys() {
      synchronized (mutex) {
        if (keys == null) {
          keys = multiset(delegate().keys(), mutex);
        }
        return keys;
      }
    }

    @Impure
    @Override public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      synchronized (mutex) {
        return delegate().equals(o);
      }
    }

    @Impure
    @Override public int hashCode() {
      synchronized (mutex) {
        return delegate().hashCode();
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  static <K, V> ListMultimap<K, V> listMultimap(
      ListMultimap<K, V> multimap, @Nullable Object mutex) {
    if (multimap instanceof SynchronizedListMultimap ||
        multimap instanceof ImmutableListMultimap) {
      return multimap;
    }
    return new SynchronizedListMultimap<K, V>(multimap, mutex);
  }

  private static class SynchronizedListMultimap<K, V>
      extends SynchronizedMultimap<K, V> implements ListMultimap<K, V> {
    @Impure
    SynchronizedListMultimap(
        ListMultimap<K, V> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }
    @Impure
    @Override ListMultimap<K, V> delegate() {
      return (ListMultimap<K, V>) super.delegate();
    }
    @Impure
    @Override public List<V> get(K key) {
      synchronized (mutex) {
        return list(delegate().get(key), mutex);
      }
    }
    @Impure
    @Override public List<V> removeAll(Object key) {
      synchronized (mutex) {
        return delegate().removeAll(key); // copy not synchronized
      }
    }
    @Impure
    @Override public List<V> replaceValues(
        K key, Iterable<? extends V> values) {
      synchronized (mutex) {
        return delegate().replaceValues(key, values); // copy not synchronized
      }
    }
    private static final long serialVersionUID = 0;
  }

  @Impure
  static <K, V> SetMultimap<K, V> setMultimap(
      SetMultimap<K, V> multimap, @Nullable Object mutex) {
    if (multimap instanceof SynchronizedSetMultimap ||
        multimap instanceof ImmutableSetMultimap) {
      return multimap;
    }
    return new SynchronizedSetMultimap<K, V>(multimap, mutex);
  }

  private static class SynchronizedSetMultimap<K, V>
      extends SynchronizedMultimap<K, V> implements SetMultimap<K, V> {
    transient Set<Map.Entry<K, V>> entrySet;

    @Impure
    SynchronizedSetMultimap(
        SetMultimap<K, V> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }
    @Impure
    @Override SetMultimap<K, V> delegate() {
      return (SetMultimap<K, V>) super.delegate();
    }
    @Impure
    @Override public Set<V> get(K key) {
      synchronized (mutex) {
        return set(delegate().get(key), mutex);
      }
    }
    @Impure
    @Override public Set<V> removeAll(Object key) {
      synchronized (mutex) {
        return delegate().removeAll(key); // copy not synchronized
      }
    }
    @Impure
    @Override public Set<V> replaceValues(
        K key, Iterable<? extends V> values) {
      synchronized (mutex) {
        return delegate().replaceValues(key, values); // copy not synchronized
      }
    }
    @Impure
    @Override public Set<Map.Entry<K, V>> entries() {
      synchronized (mutex) {
        if (entrySet == null) {
          entrySet = set(delegate().entries(), mutex);
        }
        return entrySet;
      }
    }
    private static final long serialVersionUID = 0;
  }

  @Impure
  static <K, V> SortedSetMultimap<K, V> sortedSetMultimap(
      SortedSetMultimap<K, V> multimap, @Nullable Object mutex) {
    if (multimap instanceof SynchronizedSortedSetMultimap) {
      return multimap;
    }
    return new SynchronizedSortedSetMultimap<K, V>(multimap, mutex);
  }

  private static class SynchronizedSortedSetMultimap<K, V>
      extends SynchronizedSetMultimap<K, V> implements SortedSetMultimap<K, V> {
    @Impure
    SynchronizedSortedSetMultimap(
        SortedSetMultimap<K, V> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }
    @Impure
    @Override SortedSetMultimap<K, V> delegate() {
      return (SortedSetMultimap<K, V>) super.delegate();
    }
    @Impure
    @Override public SortedSet<V> get(K key) {
      synchronized (mutex) {
        return sortedSet(delegate().get(key), mutex);
      }
    }
    @Impure
    @Override public SortedSet<V> removeAll(Object key) {
      synchronized (mutex) {
        return delegate().removeAll(key); // copy not synchronized
      }
    }
    @Impure
    @Override public SortedSet<V> replaceValues(
        K key, Iterable<? extends V> values) {
      synchronized (mutex) {
        return delegate().replaceValues(key, values); // copy not synchronized
      }
    }
    @Impure
    @Override
    public Comparator<? super V> valueComparator() {
      synchronized (mutex) {
        return delegate().valueComparator();
      }
    }
    private static final long serialVersionUID = 0;
  }

  @Impure
  private static <E> Collection<E> typePreservingCollection(
      Collection<E> collection, @Nullable Object mutex) {
    if (collection instanceof SortedSet) {
      return sortedSet((SortedSet<E>) collection, mutex);
    }
    if (collection instanceof Set) {
      return set((Set<E>) collection, mutex);
    }
    if (collection instanceof List) {
      return list((List<E>) collection, mutex);
    }
    return collection(collection, mutex);
  }

  @Impure
  private static <E> Set<E> typePreservingSet(
      Set<E> set, @Nullable Object mutex) {
    if (set instanceof SortedSet) {
      return sortedSet((SortedSet<E>) set, mutex);
    } else {
      return set(set, mutex);
    }
  }

  private static class SynchronizedAsMapEntries<K, V>
      extends SynchronizedSet<Map.Entry<K, Collection<V>>> {
    @Impure
    SynchronizedAsMapEntries(
        Set<Map.Entry<K, Collection<V>>> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override public Iterator<Map.Entry<K, Collection<V>>> iterator() {
      // Must be manually synchronized.
      final Iterator<Map.Entry<K, Collection<V>>> iterator = super.iterator();
      return new ForwardingIterator<Map.Entry<K, Collection<V>>>() {
        @Pure
        @Override protected Iterator<Map.Entry<K, Collection<V>>> delegate() {
          return iterator;
        }

        @Impure
        @Override public Map.Entry<K, Collection<V>> next() {
          final Map.Entry<K, Collection<V>> entry = super.next();
          return new ForwardingMapEntry<K, Collection<V>>() {
            @Pure
            @Override protected Map.Entry<K, Collection<V>> delegate() {
              return entry;
            }
            @Impure
            @Override public Collection<V> getValue() {
              return typePreservingCollection(entry.getValue(), mutex);
            }
          };
        }
      };
    }

    // See Collections.CheckedMap.CheckedEntrySet for details on attacks.

    @Impure
    @Override public Object[] toArray() {
      synchronized (mutex) {
        return ObjectArrays.toArrayImpl(delegate());
      }
    }
    @Impure
    @Override public <T> T[] toArray(T[] array) {
      synchronized (mutex) {
        return ObjectArrays.toArrayImpl(delegate(), array);
      }
    }
    @Impure
    @Override public boolean contains(Object o) {
      synchronized (mutex) {
        return Maps.containsEntryImpl(delegate(), o);
      }
    }
    @Impure
    @Override public boolean containsAll(Collection<?> c) {
      synchronized (mutex) {
        return Collections2.containsAllImpl(delegate(), c);
      }
    }
    @Impure
    @Override public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      synchronized (mutex) {
        return Sets.equalsImpl(delegate(), o);
      }
    }
    @Impure
    @Override public boolean remove(Object o) {
      synchronized (mutex) {
        return Maps.removeEntryImpl(delegate(), o);
      }
    }
    @Impure
    @Override public boolean removeAll(Collection<?> c) {
      synchronized (mutex) {
        return Iterators.removeAll(delegate().iterator(), c);
      }
    }
    @Impure
    @Override public boolean retainAll(Collection<?> c) {
      synchronized (mutex) {
        return Iterators.retainAll(delegate().iterator(), c);
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  @VisibleForTesting
  static <K, V> Map<K, V> map(Map<K, V> map, @Nullable Object mutex) {
    return new SynchronizedMap<K, V>(map, mutex);
  }

  private static class SynchronizedMap<K, V> extends SynchronizedObject
      implements Map<K, V> {
    transient Set<K> keySet;
    transient Collection<V> values;
    transient Set<Map.Entry<K, V>> entrySet;

    @Impure
    SynchronizedMap(Map<K, V> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @SuppressWarnings("unchecked")
    @Override Map<K, V> delegate() {
      return (Map<K, V>) super.delegate();
    }

    @Impure
    @Override
    public void clear() {
      synchronized (mutex) {
        delegate().clear();
      }
    }

    @Impure
    @Override
    public boolean containsKey(Object key) {
      synchronized (mutex) {
        return delegate().containsKey(key);
      }
    }

    @Impure
    @Override
    public boolean containsValue(Object value) {
      synchronized (mutex) {
        return delegate().containsValue(value);
      }
    }

    @Impure
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
      synchronized (mutex) {
        if (entrySet == null) {
          entrySet = set(delegate().entrySet(), mutex);
        }
        return entrySet;
      }
    }

    @Impure
    @Override
    public V get(Object key) {
      synchronized (mutex) {
        return delegate().get(key);
      }
    }

    @Impure
    @Override
    public boolean isEmpty() {
      synchronized (mutex) {
        return delegate().isEmpty();
      }
    }

    @Impure
    @Override
    public Set<K> keySet() {
      synchronized (mutex) {
        if (keySet == null) {
          keySet = set(delegate().keySet(), mutex);
        }
        return keySet;
      }
    }

    @Impure
    @Override
    public V put(K key, V value) {
      synchronized (mutex) {
        return delegate().put(key, value);
      }
    }

    @Impure
    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      synchronized (mutex) {
        delegate().putAll(map);
      }
    }

    @Impure
    @Override
    public V remove(Object key) {
      synchronized (mutex) {
        return delegate().remove(key);
      }
    }

    @Impure
    @Override
    public int size() {
      synchronized (mutex) {
        return delegate().size();
      }
    }

    @Impure
    @Override
    public Collection<V> values() {
      synchronized (mutex) {
        if (values == null) {
          values = collection(delegate().values(), mutex);
        }
        return values;
      }
    }

    @Impure
    @Override public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      synchronized (mutex) {
        return delegate().equals(o);
      }
    }

    @Impure
    @Override public int hashCode() {
      synchronized (mutex) {
        return delegate().hashCode();
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  static <K, V> SortedMap<K, V> sortedMap(
      SortedMap<K, V> sortedMap, @Nullable Object mutex) {
    return new SynchronizedSortedMap<K, V>(sortedMap, mutex);
  }

  static class SynchronizedSortedMap<K, V> extends SynchronizedMap<K, V>
      implements SortedMap<K, V> {

    @Impure
    SynchronizedSortedMap(SortedMap<K, V> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override SortedMap<K, V> delegate() {
      return (SortedMap<K, V>) super.delegate();
    }

    @Impure
    @Override public Comparator<? super K> comparator() {
      synchronized (mutex) {
        return delegate().comparator();
      }
    }

    @Impure
    @Override public K firstKey() {
      synchronized (mutex) {
        return delegate().firstKey();
      }
    }

    @Impure
    @Override public SortedMap<K, V> headMap(K toKey) {
      synchronized (mutex) {
        return sortedMap(delegate().headMap(toKey), mutex);
      }
    }

    @Impure
    @Override public K lastKey() {
      synchronized (mutex) {
        return delegate().lastKey();
      }
    }

    @Impure
    @Override public SortedMap<K, V> subMap(K fromKey, K toKey) {
      synchronized (mutex) {
        return sortedMap(delegate().subMap(fromKey, toKey), mutex);
      }
    }

    @Impure
    @Override public SortedMap<K, V> tailMap(K fromKey) {
      synchronized (mutex) {
        return sortedMap(delegate().tailMap(fromKey), mutex);
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  static <K, V> BiMap<K, V> biMap(BiMap<K, V> bimap, @Nullable Object mutex) {
    if (bimap instanceof SynchronizedBiMap ||
        bimap instanceof ImmutableBiMap) {
      return bimap;
    }
    return new SynchronizedBiMap<K, V>(bimap, mutex, null);
  }

  @VisibleForTesting static class SynchronizedBiMap<K, V>
      extends SynchronizedMap<K, V> implements BiMap<K, V>, Serializable {
    private transient Set<V> valueSet;
    private transient BiMap<V, K> inverse;

    @Impure
    private SynchronizedBiMap(BiMap<K, V> delegate, @Nullable Object mutex,
        @Nullable BiMap<V, K> inverse) {
      super(delegate, mutex);
      this.inverse = inverse;
    }

    @Impure
    @Override BiMap<K, V> delegate() {
      return (BiMap<K, V>) super.delegate();
    }

    @Impure
    @Override public Set<V> values() {
      synchronized (mutex) {
        if (valueSet == null) {
          valueSet = set(delegate().values(), mutex);
        }
        return valueSet;
      }
    }

    @Impure
    @Override
    public V forcePut(K key, V value) {
      synchronized (mutex) {
        return delegate().forcePut(key, value);
      }
    }

    @Impure
    @Override
    public BiMap<V, K> inverse() {
      synchronized (mutex) {
        if (inverse == null) {
          inverse
              = new SynchronizedBiMap<V, K>(delegate().inverse(), mutex, this);
        }
        return inverse;
      }
    }

    private static final long serialVersionUID = 0;
  }

  private static class SynchronizedAsMap<K, V>
      extends SynchronizedMap<K, Collection<V>> {
    transient Set<Map.Entry<K, Collection<V>>> asMapEntrySet;
    transient Collection<Collection<V>> asMapValues;

    @Impure
    SynchronizedAsMap(Map<K, Collection<V>> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override public Collection<V> get(Object key) {
      synchronized (mutex) {
        Collection<V> collection = super.get(key);
        return (collection == null) ? null
            : typePreservingCollection(collection, mutex);
      }
    }

    @Impure
    @Override public Set<Map.Entry<K, Collection<V>>> entrySet() {
      synchronized (mutex) {
        if (asMapEntrySet == null) {
          asMapEntrySet = new SynchronizedAsMapEntries<K, V>(
              delegate().entrySet(), mutex);
        }
        return asMapEntrySet;
      }
    }

    @Impure
    @Override public Collection<Collection<V>> values() {
      synchronized (mutex) {
        if (asMapValues == null) {
          asMapValues
              = new SynchronizedAsMapValues<V>(delegate().values(), mutex);
        }
        return asMapValues;
      }
    }

    @SideEffectFree
    @Override public boolean containsValue(Object o) {
      // values() and its contains() method are both synchronized.
      return values().contains(o);
    }

    private static final long serialVersionUID = 0;
  }

  private static class SynchronizedAsMapValues<V>
      extends SynchronizedCollection<Collection<V>> {
    @Impure
    SynchronizedAsMapValues(
        Collection<Collection<V>> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override public Iterator<Collection<V>> iterator() {
      // Must be manually synchronized.
      final Iterator<Collection<V>> iterator = super.iterator();
      return new ForwardingIterator<Collection<V>>() {
        @Pure
        @Override protected Iterator<Collection<V>> delegate() {
          return iterator;
        }
        @Impure
        @Override public Collection<V> next() {
          return typePreservingCollection(super.next(), mutex);
        }
      };
    }

    private static final long serialVersionUID = 0;
  }

  @GwtIncompatible("NavigableSet")
  @VisibleForTesting
  static class SynchronizedNavigableSet<E> extends SynchronizedSortedSet<E>
      implements NavigableSet<E> {
    @Impure
    SynchronizedNavigableSet(NavigableSet<E> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override NavigableSet<E> delegate() {
      return (NavigableSet<E>) super.delegate();
    }

    @Impure
    @Override public E ceiling(E e) {
      synchronized (mutex) {
        return delegate().ceiling(e);
      }
    }

    @Impure
    @Override public Iterator<E> descendingIterator() {
      return delegate().descendingIterator(); // manually synchronized
    }

    transient NavigableSet<E> descendingSet;

    @Impure
    @Override public NavigableSet<E> descendingSet() {
      synchronized (mutex) {
        if (descendingSet == null) {
          NavigableSet<E> dS =
              Synchronized.navigableSet(delegate().descendingSet(), mutex);
          descendingSet = dS;
          return dS;
        }
        return descendingSet;
      }
    }

    @Impure
    @Override public E floor(E e) {
      synchronized (mutex) {
        return delegate().floor(e);
      }
    }

    @Impure
    @Override public NavigableSet<E> headSet(E toElement, boolean inclusive) {
      synchronized (mutex) {
        return Synchronized.navigableSet(
            delegate().headSet(toElement, inclusive), mutex);
      }
    }

    @Impure
    @Override public E higher(E e) {
      synchronized (mutex) {
        return delegate().higher(e);
      }
    }

    @Impure
    @Override public E lower(E e) {
      synchronized (mutex) {
        return delegate().lower(e);
      }
    }

    @Impure
    @Override public E pollFirst() {
      synchronized (mutex) {
        return delegate().pollFirst();
      }
    }

    @Impure
    @Override public E pollLast() {
      synchronized (mutex) {
        return delegate().pollLast();
      }
    }

    @Impure
    @Override public NavigableSet<E> subSet(E fromElement,
        boolean fromInclusive, E toElement, boolean toInclusive) {
      synchronized (mutex) {
        return Synchronized.navigableSet(delegate().subSet(
            fromElement, fromInclusive, toElement, toInclusive), mutex);
      }
    }

    @Impure
    @Override public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
      synchronized (mutex) {
        return Synchronized.navigableSet(
            delegate().tailSet(fromElement, inclusive), mutex);
      }
    }

    @SideEffectFree
    @Override public SortedSet<E> headSet(E toElement) {
      return headSet(toElement, false);
    }

    @SideEffectFree
    @Override public SortedSet<E> subSet(E fromElement, E toElement) {
      return subSet(fromElement, true, toElement, false);
    }

    @SideEffectFree
    @Override public SortedSet<E> tailSet(E fromElement) {
      return tailSet(fromElement, true);
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  @GwtIncompatible("NavigableSet")
  static <E> NavigableSet<E> navigableSet(
      NavigableSet<E> navigableSet, @Nullable Object mutex) {
    return new SynchronizedNavigableSet<E>(navigableSet, mutex);
  }

  @Impure
  @GwtIncompatible("NavigableSet")
  static <E> NavigableSet<E> navigableSet(NavigableSet<E> navigableSet) {
    return navigableSet(navigableSet, null);
  }

  @Impure
  @GwtIncompatible("NavigableMap")
  static <K, V> NavigableMap<K, V> navigableMap(
      NavigableMap<K, V> navigableMap) {
    return navigableMap(navigableMap, null);
  }

  @Impure
  @GwtIncompatible("NavigableMap")
  static <K, V> NavigableMap<K, V> navigableMap(
      NavigableMap<K, V> navigableMap, @Nullable Object mutex) {
    return new SynchronizedNavigableMap<K, V>(navigableMap, mutex);
  }

  @GwtIncompatible("NavigableMap")
  @VisibleForTesting static class SynchronizedNavigableMap<K, V>
      extends SynchronizedSortedMap<K, V> implements NavigableMap<K, V> {

    @Impure
    SynchronizedNavigableMap(
        NavigableMap<K, V> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override NavigableMap<K, V> delegate() {
      return (NavigableMap<K, V>) super.delegate();
    }

    @Impure
    @Override public Entry<K, V> ceilingEntry(K key) {
      synchronized (mutex) {
        return nullableSynchronizedEntry(delegate().ceilingEntry(key), mutex);
      }
    }

    @Impure
    @Override public K ceilingKey(K key) {
      synchronized (mutex) {
        return delegate().ceilingKey(key);
      }
    }

    transient NavigableSet<K> descendingKeySet;

    @Impure
    @Override public NavigableSet<K> descendingKeySet() {
      synchronized (mutex) {
        if (descendingKeySet == null) {
          return descendingKeySet =
              Synchronized.navigableSet(delegate().descendingKeySet(), mutex);
        }
        return descendingKeySet;
      }
    }

    transient NavigableMap<K, V> descendingMap;

    @Impure
    @Override public NavigableMap<K, V> descendingMap() {
      synchronized (mutex) {
        if (descendingMap == null) {
          return descendingMap =
              navigableMap(delegate().descendingMap(), mutex);
        }
        return descendingMap;
      }
    }

    @Impure
    @Override public Entry<K, V> firstEntry() {
      synchronized (mutex) {
        return nullableSynchronizedEntry(delegate().firstEntry(), mutex);
      }
    }

    @Impure
    @Override public Entry<K, V> floorEntry(K key) {
      synchronized (mutex) {
        return nullableSynchronizedEntry(delegate().floorEntry(key), mutex);
      }
    }

    @Impure
    @Override public K floorKey(K key) {
      synchronized (mutex) {
        return delegate().floorKey(key);
      }
    }

    @Impure
    @Override public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
      synchronized (mutex) {
        return navigableMap(
            delegate().headMap(toKey, inclusive), mutex);
      }
    }

    @Impure
    @Override public Entry<K, V> higherEntry(K key) {
      synchronized (mutex) {
        return nullableSynchronizedEntry(delegate().higherEntry(key), mutex);
      }
    }

    @Impure
    @Override public K higherKey(K key) {
      synchronized (mutex) {
        return delegate().higherKey(key);
      }
    }

    @Impure
    @Override public Entry<K, V> lastEntry() {
      synchronized (mutex) {
        return nullableSynchronizedEntry(delegate().lastEntry(), mutex);
      }
    }

    @Impure
    @Override public Entry<K, V> lowerEntry(K key) {
      synchronized (mutex) {
        return nullableSynchronizedEntry(delegate().lowerEntry(key), mutex);
      }
    }

    @Impure
    @Override public K lowerKey(K key) {
      synchronized (mutex) {
        return delegate().lowerKey(key);
      }
    }

    @SideEffectFree
    @Override public Set<K> keySet() {
      return navigableKeySet();
    }

    transient NavigableSet<K> navigableKeySet;

    @Impure
    @Override public NavigableSet<K> navigableKeySet() {
      synchronized (mutex) {
        if (navigableKeySet == null) {
          return navigableKeySet =
              Synchronized.navigableSet(delegate().navigableKeySet(), mutex);
        }
        return navigableKeySet;
      }
    }

    @Impure
    @Override public Entry<K, V> pollFirstEntry() {
      synchronized (mutex) {
        return nullableSynchronizedEntry(delegate().pollFirstEntry(), mutex);
      }
    }

    @Impure
    @Override public Entry<K, V> pollLastEntry() {
      synchronized (mutex) {
        return nullableSynchronizedEntry(delegate().pollLastEntry(), mutex);
      }
    }

    @Impure
    @Override public NavigableMap<K, V> subMap(
        K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
      synchronized (mutex) {
        return navigableMap(
            delegate().subMap(fromKey, fromInclusive, toKey, toInclusive),
            mutex);
      }
    }

    @Impure
    @Override public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
      synchronized (mutex) {
        return navigableMap(
            delegate().tailMap(fromKey, inclusive), mutex);
      }
    }

    @SideEffectFree
    @Override public SortedMap<K, V> headMap(K toKey) {
      return headMap(toKey, false);
    }

    @SideEffectFree
    @Override public SortedMap<K, V> subMap(K fromKey, K toKey) {
      return subMap(fromKey, true, toKey, false);
    }

    @SideEffectFree
    @Override public SortedMap<K, V> tailMap(K fromKey) {
      return tailMap(fromKey, true);
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  @GwtIncompatible("works but is needed only for NavigableMap")
  private static <K, V> Entry<K, V> nullableSynchronizedEntry(
      @Nullable Entry<K, V> entry, @Nullable Object mutex) {
    if (entry == null) {
      return null;
    }
    return new SynchronizedEntry<K, V>(entry, mutex);
  }

  @GwtIncompatible("works but is needed only for NavigableMap")
  private static class SynchronizedEntry<K, V> extends SynchronizedObject
      implements Entry<K, V> {

    @Impure
    SynchronizedEntry(Entry<K, V> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @SuppressWarnings("unchecked") // guaranteed by the constructor
    @Override Entry<K, V> delegate() {
      return (Entry<K, V>) super.delegate();
    }

    @Impure
    @Override public boolean equals(Object obj) {
      synchronized (mutex) {
        return delegate().equals(obj);
      }
    }

    @Impure
    @Override public int hashCode() {
      synchronized (mutex) {
        return delegate().hashCode();
      }
    }

    @Impure
    @Override public K getKey() {
      synchronized (mutex) {
        return delegate().getKey();
      }
    }

    @Impure
    @Override public V getValue() {
      synchronized (mutex) {
        return delegate().getValue();
      }
    }

    @Impure
    @Override public V setValue(V value) {
      synchronized (mutex) {
        return delegate().setValue(value);
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  static <E> Queue<E> queue(Queue<E> queue, @Nullable Object mutex) {
    return (queue instanceof SynchronizedQueue)
        ? queue
        : new SynchronizedQueue<E>(queue, mutex);
  }

  private static class SynchronizedQueue<E> extends SynchronizedCollection<E>
      implements Queue<E> {

    @Impure
    SynchronizedQueue(Queue<E> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override Queue<E> delegate() {
      return (Queue<E>) super.delegate();
    }

    @Impure
    @Override
    public E element() {
      synchronized (mutex) {
        return delegate().element();
      }
    }

    @Impure
    @Override
    public boolean offer(E e) {
      synchronized (mutex) {
        return delegate().offer(e);
      }
    }

    @Impure
    @Override
    public E peek() {
      synchronized (mutex) {
        return delegate().peek();
      }
    }

    @Impure
    @Override
    public E poll() {
      synchronized (mutex) {
        return delegate().poll();
      }
    }

    @Impure
    @Override
    public E remove() {
      synchronized (mutex) {
        return delegate().remove();
      }
    }

    private static final long serialVersionUID = 0;
  }

  @Impure
  @GwtIncompatible("Deque")
  static <E> Deque<E> deque(Deque<E> deque, @Nullable Object mutex) {
    return new SynchronizedDeque<E>(deque, mutex);
  }

  @GwtIncompatible("Deque")
  private static final class SynchronizedDeque<E>
      extends SynchronizedQueue<E> implements Deque<E> {

    @Impure
    SynchronizedDeque(Deque<E> delegate, @Nullable Object mutex) {
      super(delegate, mutex);
    }

    @Impure
    @Override Deque<E> delegate() {
      return (Deque<E>) super.delegate();
    }

    @Impure
    @Override
    public void addFirst(E e) {
      synchronized (mutex) {
        delegate().addFirst(e);
      }
    }

    @Impure
    @Override
    public void addLast(E e) {
      synchronized (mutex) {
        delegate().addLast(e);
      }
    }

    @Impure
    @Override
    public boolean offerFirst(E e) {
      synchronized (mutex) {
        return delegate().offerFirst(e);
      }
    }

    @Impure
    @Override
    public boolean offerLast(E e) {
      synchronized (mutex) {
        return delegate().offerLast(e);
      }
    }

    @Impure
    @Override
    public E removeFirst() {
      synchronized (mutex) {
        return delegate().removeFirst();
      }
    }

    @Impure
    @Override
    public E removeLast() {
      synchronized (mutex) {
        return delegate().removeLast();
      }
    }

    @Impure
    @Override
    public E pollFirst() {
      synchronized (mutex) {
        return delegate().pollFirst();
      }
    }

    @Impure
    @Override
    public E pollLast() {
      synchronized (mutex) {
        return delegate().pollLast();
      }
    }

    @Impure
    @Override
    public E getFirst() {
      synchronized (mutex) {
        return delegate().getFirst();
      }
    }

    @Impure
    @Override
    public E getLast() {
      synchronized (mutex) {
        return delegate().getLast();
      }
    }

    @Impure
    @Override
    public E peekFirst() {
      synchronized (mutex) {
        return delegate().peekFirst();
      }
    }

    @Impure
    @Override
    public E peekLast() {
      synchronized (mutex) {
        return delegate().peekLast();
      }
    }

    @Impure
    @Override
    public boolean removeFirstOccurrence(Object o) {
      synchronized (mutex) {
        return delegate().removeFirstOccurrence(o);
      }
    }

    @Impure
    @Override
    public boolean removeLastOccurrence(Object o) {
      synchronized (mutex) {
        return delegate().removeLastOccurrence(o);
      }
    }

    @Impure
    @Override
    public void push(E e) {
      synchronized (mutex) {
        delegate().push(e);
      }
    }

    @Impure
    @Override
    public E pop() {
      synchronized (mutex) {
        return delegate().pop();
      }
    }

    @Impure
    @Override
    public Iterator<E> descendingIterator() {
      synchronized (mutex) {
        return delegate().descendingIterator();
      }
    }

    private static final long serialVersionUID = 0;
  }
}

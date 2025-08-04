/*
 * Copyright (C) 2009 The Guava Authors
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

import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.CollectPreconditions.checkRemove;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Ticker;
import com.google.common.collect.GenericMapMaker.NullListener;
import com.google.common.collect.MapMaker.RemovalCause;
import com.google.common.collect.MapMaker.RemovalListener;
import com.google.common.collect.MapMaker.RemovalNotification;
import com.google.common.primitives.Ints;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * The concurrent hash map implementation built by {@link MapMaker}.
 *
 * <p>This implementation is heavily derived from revision 1.96 of <a
 * href="http://tinyurl.com/ConcurrentHashMap">ConcurrentHashMap.java</a>.
 *
 * @author Bob Lee
 * @author Charles Fry
 * @author Doug Lea ({@code ConcurrentHashMap})
 */
class MapMakerInternalMap<K, V>
    extends AbstractMap<K, V> implements ConcurrentMap<K, V>, Serializable {

  /*
   * The basic strategy is to subdivide the table among Segments, each of which itself is a
   * concurrently readable hash table. The map supports non-blocking reads and concurrent writes
   * across different segments.
   *
   * If a maximum size is specified, a best-effort bounding is performed per segment, using a
   * page-replacement algorithm to determine which entries to evict when the capacity has been
   * exceeded.
   *
   * The page replacement algorithm's data structures are kept casually consistent with the map. The
   * ordering of writes to a segment is sequentially consistent. An update to the map and recording
   * of reads may not be immediately reflected on the algorithm's data structures. These structures
   * are guarded by a lock and operations are applied in batches to avoid lock contention. The
   * penalty of applying the batches is spread across threads so that the amortized cost is slightly
   * higher than performing just the operation without enforcing the capacity constraint.
   *
   * This implementation uses a per-segment queue to record a memento of the additions, removals,
   * and accesses that were performed on the map. The queue is drained on writes and when it exceeds
   * its capacity threshold.
   *
   * The Least Recently Used page replacement algorithm was chosen due to its simplicity, high hit
   * rate, and ability to be implemented with O(1) time complexity. The initial LRU implementation
   * operates per-segment rather than globally for increased implementation simplicity. We expect
   * the cache hit rate to be similar to that of a global LRU algorithm.
   */

  // Constants

  /**
   * The maximum capacity, used if a higher value is implicitly specified by either of the
   * constructors with arguments. MUST be a power of two <= 1<<30 to ensure that entries are
   * indexable using ints.
   */
  static final int MAXIMUM_CAPACITY = Ints.MAX_POWER_OF_TWO;

  /** The maximum number of segments to allow; used to bound constructor arguments. */
  static final int MAX_SEGMENTS = 1 << 16; // slightly conservative

  /** Number of (unsynchronized) retries in the containsValue method. */
  static final int CONTAINS_VALUE_RETRIES = 3;

  /**
   * Number of cache access operations that can be buffered per segment before the cache's recency
   * ordering information is updated. This is used to avoid lock contention by recording a memento
   * of reads and delaying a lock acquisition until the threshold is crossed or a mutation occurs.
   *
   * <p>This must be a (2^n)-1 as it is used as a mask.
   */
  static final int DRAIN_THRESHOLD = 0x3F;

  /**
   * Maximum number of entries to be drained in a single cleanup run. This applies independently to
   * the cleanup queue and both reference queues.
   */
  // TODO(fry): empirically optimize this
  static final int DRAIN_MAX = 16;

  static final long CLEANUP_EXECUTOR_DELAY_SECS = 60;

  // Fields

  private static final Logger logger = Logger.getLogger(MapMakerInternalMap.class.getName());

  /**
   * Mask value for indexing into segments. The upper bits of a key's hash code are used to choose
   * the segment.
   */
  final transient int segmentMask;

  /**
   * Shift value for indexing within segments. Helps prevent entries that end up in the same segment
   * from also ending up in the same bucket.
   */
  final transient int segmentShift;

  /** The segments, each of which is a specialized hash table. */
  final transient Segment<K, V>[] segments;

  /** The concurrency level. */
  final int concurrencyLevel;

  /** Strategy for comparing keys. */
  final Equivalence<Object> keyEquivalence;

  /** Strategy for comparing values. */
  final Equivalence<Object> valueEquivalence;

  /** Strategy for referencing keys. */
  final Strength keyStrength;

  /** Strategy for referencing values. */
  final Strength valueStrength;

  /** The maximum size of this map. MapMaker.UNSET_INT if there is no maximum. */
  final int maximumSize;

  /** How long after the last access to an entry the map will retain that entry. */
  final long expireAfterAccessNanos;

  /** How long after the last write to an entry the map will retain that entry. */
  final long expireAfterWriteNanos;

  /** Entries waiting to be consumed by the removal listener. */
  // TODO(fry): define a new type which creates event objects and automates the clear logic
  final Queue<RemovalNotification<K, V>> removalNotificationQueue;

  /**
   * A listener that is invoked when an entry is removed due to expiration or garbage collection of
   * soft/weak entries.
   */
  final RemovalListener<K, V> removalListener;

  /** Factory used to create new entries. */
  final transient EntryFactory entryFactory;

  /** Measures time in a testable way. */
  final Ticker ticker;

  /**
   * Creates a new, empty map with the specified strategy, initial capacity and concurrency level.
   */
  @Impure
  MapMakerInternalMap(MapMaker builder) {
    concurrencyLevel = Math.min(builder.getConcurrencyLevel(), MAX_SEGMENTS);

    keyStrength = builder.getKeyStrength();
    valueStrength = builder.getValueStrength();

    keyEquivalence = builder.getKeyEquivalence();
    valueEquivalence = valueStrength.defaultEquivalence();

    maximumSize = builder.maximumSize;
    expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
    expireAfterWriteNanos = builder.getExpireAfterWriteNanos();

    entryFactory = EntryFactory.getFactory(keyStrength, expires(), evictsBySize());
    ticker = builder.getTicker();

    removalListener = builder.getRemovalListener();
    removalNotificationQueue = (removalListener == NullListener.INSTANCE)
        ? MapMakerInternalMap.<RemovalNotification<K, V>>discardingQueue()
        : new ConcurrentLinkedQueue<RemovalNotification<K, V>>();

    int initialCapacity = Math.min(builder.getInitialCapacity(), MAXIMUM_CAPACITY);
    if (evictsBySize()) {
      initialCapacity = Math.min(initialCapacity, maximumSize);
    }

    // Find power-of-two sizes best matching arguments. Constraints:
    // (segmentCount <= maximumSize)
    // && (concurrencyLevel > maximumSize || segmentCount > concurrencyLevel)
    int segmentShift = 0;
    int segmentCount = 1;
    while (segmentCount < concurrencyLevel
        && (!evictsBySize() || segmentCount * 2 <= maximumSize)) {
      ++segmentShift;
      segmentCount <<= 1;
    }
    this.segmentShift = 32 - segmentShift;
    segmentMask = segmentCount - 1;

    this.segments = newSegmentArray(segmentCount);

    int segmentCapacity = initialCapacity / segmentCount;
    if (segmentCapacity * segmentCount < initialCapacity) {
      ++segmentCapacity;
    }

    int segmentSize = 1;
    while (segmentSize < segmentCapacity) {
      segmentSize <<= 1;
    }

    if (evictsBySize()) {
      // Ensure sum of segment max sizes = overall max size
      int maximumSegmentSize = maximumSize / segmentCount + 1;
      int remainder = maximumSize % segmentCount;
      for (int i = 0; i < this.segments.length; ++i) {
        if (i == remainder) {
          maximumSegmentSize--;
        }
        this.segments[i] =
            createSegment(segmentSize, maximumSegmentSize);
      }
    } else {
      for (int i = 0; i < this.segments.length; ++i) {
        this.segments[i] =
            createSegment(segmentSize, MapMaker.UNSET_INT);
      }
    }
  }

  @Pure
  boolean evictsBySize() {
    return maximumSize != MapMaker.UNSET_INT;
  }

  @Pure
  @Impure
  boolean expires() {
    return expiresAfterWrite() || expiresAfterAccess();
  }

  @Pure
  boolean expiresAfterWrite() {
    return expireAfterWriteNanos > 0;
  }

  @Pure
  boolean expiresAfterAccess() {
    return expireAfterAccessNanos > 0;
  }

  @Pure
  boolean usesKeyReferences() {
    return keyStrength != Strength.STRONG;
  }

  @Pure
  boolean usesValueReferences() {
    return valueStrength != Strength.STRONG;
  }

  enum Strength {
    /*
     * TODO(kevinb): If we strongly reference the value and aren't computing, we needn't wrap the
     * value. This could save ~8 bytes per entry.
     */

    STRONG {
      @SideEffectFree
      @Impure
      @Override
      <K, V> ValueReference<K, V> referenceValue(
          Segment<K, V> segment, ReferenceEntry<K, V> entry, V value) {
        return new StrongValueReference<K, V>(value);
      }

      @Impure
      @Override
      Equivalence<Object> defaultEquivalence() {
        return Equivalence.equals();
      }
    },

    SOFT {
      @Impure
      @Override
      <K, V> ValueReference<K, V> referenceValue(
          Segment<K, V> segment, ReferenceEntry<K, V> entry, V value) {
        return new SoftValueReference<K, V>(segment.valueReferenceQueue, value, entry);
      }

      @Impure
      @Override
      Equivalence<Object> defaultEquivalence() {
        return Equivalence.identity();
      }
    },

    WEAK {
      @Impure
      @Override
      <K, V> ValueReference<K, V> referenceValue(
          Segment<K, V> segment, ReferenceEntry<K, V> entry, V value) {
        return new WeakValueReference<K, V>(segment.valueReferenceQueue, value, entry);
      }

      @Impure
      @Override
      Equivalence<Object> defaultEquivalence() {
        return Equivalence.identity();
      }
    };

    /**
     * Creates a reference for the given value according to this value strength.
     */
    @Impure
    abstract <K, V> ValueReference<K, V> referenceValue(
        Segment<K, V> segment, ReferenceEntry<K, V> entry, V value);

    /**
     * Returns the default equivalence strategy used to compare and hash keys or values referenced
     * at this strength. This strategy will be used unless the user explicitly specifies an
     * alternate strategy.
     */
    @Impure
    abstract Equivalence<Object> defaultEquivalence();
  }

  /**
   * Creates new entries.
   */
  enum EntryFactory {
    STRONG {
      @SideEffectFree
      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return new StrongEntry<K, V>(key, hash, next);
      }
    },
    STRONG_EXPIRABLE {
      @SideEffectFree
      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return new StrongExpirableEntry<K, V>(key, hash, next);
      }

      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
        copyExpirableEntry(original, newEntry);
        return newEntry;
      }
    },
    STRONG_EVICTABLE {
      @SideEffectFree
      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return new StrongEvictableEntry<K, V>(key, hash, next);
      }

      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
        copyEvictableEntry(original, newEntry);
        return newEntry;
      }
    },
    STRONG_EXPIRABLE_EVICTABLE {
      @SideEffectFree
      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return new StrongExpirableEvictableEntry<K, V>(key, hash, next);
      }

      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
        copyExpirableEntry(original, newEntry);
        copyEvictableEntry(original, newEntry);
        return newEntry;
      }
    },

    WEAK {
      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return new WeakEntry<K, V>(segment.keyReferenceQueue, key, hash, next);
      }
    },
    WEAK_EXPIRABLE {
      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return new WeakExpirableEntry<K, V>(segment.keyReferenceQueue, key, hash, next);
      }

      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
        copyExpirableEntry(original, newEntry);
        return newEntry;
      }
    },
    WEAK_EVICTABLE {
      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return new WeakEvictableEntry<K, V>(segment.keyReferenceQueue, key, hash, next);
      }

      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
        copyEvictableEntry(original, newEntry);
        return newEntry;
      }
    },
    WEAK_EXPIRABLE_EVICTABLE {
      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> newEntry(
          Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return new WeakExpirableEvictableEntry<K, V>(segment.keyReferenceQueue, key, hash, next);
      }

      @Impure
      @Override
      <K, V> ReferenceEntry<K, V> copyEntry(
          Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
        copyExpirableEntry(original, newEntry);
        copyEvictableEntry(original, newEntry);
        return newEntry;
      }
    };

    /**
     * Masks used to compute indices in the following table.
     */
    static final int EXPIRABLE_MASK = 1;
    static final int EVICTABLE_MASK = 2;

    /**
     * Look-up table for factories. First dimension is the reference type. The second dimension is
     * the result of OR-ing the feature masks.
     */
    static final EntryFactory[][] factories = {
      { STRONG, STRONG_EXPIRABLE, STRONG_EVICTABLE, STRONG_EXPIRABLE_EVICTABLE },
      {}, // no support for SOFT keys
      { WEAK, WEAK_EXPIRABLE, WEAK_EVICTABLE, WEAK_EXPIRABLE_EVICTABLE }
    };

    @Impure
    static EntryFactory getFactory(Strength keyStrength, boolean expireAfterWrite,
        boolean evictsBySize) {
      int flags = (expireAfterWrite ? EXPIRABLE_MASK : 0) | (evictsBySize ? EVICTABLE_MASK : 0);
      return factories[keyStrength.ordinal()][flags];
    }

    /**
     * Creates a new entry.
     *
     * @param segment to create the entry for
     * @param key of the entry
     * @param hash of the key
     * @param next entry in the same bucket
     */
    @Impure
    abstract <K, V> ReferenceEntry<K, V> newEntry(
        Segment<K, V> segment, K key, int hash, @Nullable ReferenceEntry<K, V> next);

    /**
     * Copies an entry, assigning it a new {@code next} entry.
     *
     * @param original the entry to copy
     * @param newNext entry in the same bucket
     */
    @Impure
    @GuardedBy("Segment.this")
    <K, V> ReferenceEntry<K, V> copyEntry(
        Segment<K, V> segment, ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
      return newEntry(segment, original.getKey(), original.getHash(), newNext);
    }

    @Impure
    @GuardedBy("Segment.this")
    <K, V> void copyExpirableEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newEntry) {
      // TODO(fry): when we link values instead of entries this method can go
      // away, as can connectExpirables, nullifyExpirable.
      newEntry.setExpirationTime(original.getExpirationTime());

      connectExpirables(original.getPreviousExpirable(), newEntry);
      connectExpirables(newEntry, original.getNextExpirable());

      nullifyExpirable(original);
    }

    @Impure
    @GuardedBy("Segment.this")
    <K, V> void copyEvictableEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newEntry) {
      // TODO(fry): when we link values instead of entries this method can go
      // away, as can connectEvictables, nullifyEvictable.
      connectEvictables(original.getPreviousEvictable(), newEntry);
      connectEvictables(newEntry, original.getNextEvictable());

      nullifyEvictable(original);
    }
  }

  /**
   * A reference to a value.
   */
  interface ValueReference<K, V> {
    /**
     * Gets the value. Does not block or throw exceptions.
     */
    @Pure
    V get();

    /**
     * Waits for a value that may still be computing. Unlike get(), this method can block (in the
     * case of FutureValueReference).
     *
     * @throws ExecutionException if the computing thread throws an exception
     */
    @SideEffectFree
    @Impure
    V waitForValue() throws ExecutionException;

    /**
     * Returns the entry associated with this value reference, or {@code null} if this value
     * reference is independent of any entry.
     */
    @Pure
    ReferenceEntry<K, V> getEntry();

    /**
     * Creates a copy of this reference for the given entry.
     *
     * <p>{@code value} may be null only for a loading reference.
     */
    @Impure
    ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, @Nullable V value, ReferenceEntry<K, V> entry);

    /**
     * Clears this reference object.
     *
     * @param newValue the new value reference which will replace this one; this is only used during
     *     computation to immediately notify blocked threads of the new value
     */
    @Impure
    void clear(@Nullable ValueReference<K, V> newValue);

    /**
     * Returns {@code true} if the value type is a computing reference (regardless of whether or not
     * computation has completed). This is necessary to distiguish between partially-collected
     * entries and computing entries, which need to be cleaned up differently.
     */
    @Pure
    boolean isComputingReference();
  }

  /**
   * Placeholder. Indicates that the value hasn't been set yet.
   */
  static final ValueReference<Object, Object> UNSET = new ValueReference<Object, Object>() {
    @Override
    public Object get() {
      return null;
    }

    @Override
    public ReferenceEntry<Object, Object> getEntry() {
      return null;
    }

    @Override
    public ValueReference<Object, Object> copyFor(ReferenceQueue<Object> queue,
        @Nullable Object value, ReferenceEntry<Object, Object> entry) {
      return this;
    }

    @Override
    public boolean isComputingReference() {
      return false;
    }

    @Override
    public Object waitForValue() {
      return null;
    }

    @Override
    public void clear(ValueReference<Object, Object> newValue) {}
  };

  /**
   * Singleton placeholder that indicates a value is being computed.
   */
  @Pure
  @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
  static <K, V> ValueReference<K, V> unset() {
    return (ValueReference<K, V>) UNSET;
  }

  /**
   * An entry in a reference map.
   *
   * Entries in the map can be in the following states:
   *
   * Valid:
   * - Live: valid key/value are set
   * - Computing: computation is pending
   *
   * Invalid:
   * - Expired: time expired (key/value may still be set)
   * - Collected: key/value was partially collected, but not yet cleaned up
   */
  interface ReferenceEntry<K, V> {
    /**
     * Gets the value reference from this entry.
     */
    @Pure
    ValueReference<K, V> getValueReference();

    /**
     * Sets the value reference for this entry.
     */
    @Impure
    void setValueReference(ValueReference<K, V> valueReference);

    /**
     * Gets the next entry in the chain.
     */
    @Pure
    ReferenceEntry<K, V> getNext();

    /**
     * Gets the entry's hash.
     */
    @Pure
    int getHash();

    /**
     * Gets the key for this entry.
     */
    @SideEffectFree
    K getKey();

    /*
     * Used by entries that are expirable. Expirable entries are maintained in a doubly-linked list.
     * New entries are added at the tail of the list at write time; stale entries are expired from
     * the head of the list.
     */

    /**
     * Gets the entry expiration time in ns.
     */
    @Pure
    long getExpirationTime();

    /**
     * Sets the entry expiration time in ns.
     */
    @Impure
    void setExpirationTime(long time);

    /**
     * Gets the next entry in the recency list.
     */
    @Pure
    ReferenceEntry<K, V> getNextExpirable();

    /**
     * Sets the next entry in the recency list.
     */
    @Impure
    void setNextExpirable(ReferenceEntry<K, V> next);

    /**
     * Gets the previous entry in the recency list.
     */
    @Pure
    ReferenceEntry<K, V> getPreviousExpirable();

    /**
     * Sets the previous entry in the recency list.
     */
    @Impure
    void setPreviousExpirable(ReferenceEntry<K, V> previous);

    /*
     * Implemented by entries that are evictable. Evictable entries are maintained in a
     * doubly-linked list. New entries are added at the tail of the list at write time and stale
     * entries are expired from the head of the list.
     */

    /**
     * Gets the next entry in the recency list.
     */
    @Pure
    ReferenceEntry<K, V> getNextEvictable();

    /**
     * Sets the next entry in the recency list.
     */
    @Impure
    void setNextEvictable(ReferenceEntry<K, V> next);

    /**
     * Gets the previous entry in the recency list.
     */
    @Pure
    ReferenceEntry<K, V> getPreviousEvictable();

    /**
     * Sets the previous entry in the recency list.
     */
    @Impure
    void setPreviousEvictable(ReferenceEntry<K, V> previous);
  }

  private enum NullEntry implements ReferenceEntry<Object, Object> {
    INSTANCE;

    @Pure
    @Override
    public ValueReference<Object, Object> getValueReference() {
      return null;
    }

    @SideEffectFree
    @Override
    public void setValueReference(ValueReference<Object, Object> valueReference) {}

    @Pure
    @Override
    public ReferenceEntry<Object, Object> getNext() {
      return null;
    }

    @Pure
    @Override
    public int getHash() {
      return 0;
    }

    @Pure
    @Override
    public Object getKey() {
      return null;
    }

    @Pure
    @Override
    public long getExpirationTime() {
      return 0;
    }

    @SideEffectFree
    @Override
    public void setExpirationTime(long time) {}

    @Pure
    @Override
    public ReferenceEntry<Object, Object> getNextExpirable() {
      return this;
    }

    @SideEffectFree
    @Override
    public void setNextExpirable(ReferenceEntry<Object, Object> next) {}

    @Pure
    @Override
    public ReferenceEntry<Object, Object> getPreviousExpirable() {
      return this;
    }

    @SideEffectFree
    @Override
    public void setPreviousExpirable(ReferenceEntry<Object, Object> previous) {}

    @Pure
    @Override
    public ReferenceEntry<Object, Object> getNextEvictable() {
      return this;
    }

    @SideEffectFree
    @Override
    public void setNextEvictable(ReferenceEntry<Object, Object> next) {}

    @Pure
    @Override
    public ReferenceEntry<Object, Object> getPreviousEvictable() {
      return this;
    }

    @SideEffectFree
    @Override
    public void setPreviousEvictable(ReferenceEntry<Object, Object> previous) {}
  }

  abstract static class AbstractReferenceEntry<K, V> implements ReferenceEntry<K, V> {
    @Pure
    @Override
    public ValueReference<K, V> getValueReference() {
      throw new UnsupportedOperationException();
    }

    @SideEffectFree
    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNext() {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public int getHash() {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public K getKey() {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public long getExpirationTime() {
      throw new UnsupportedOperationException();
    }

    @SideEffectFree
    @Override
    public void setExpirationTime(long time) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }
  }

  @Pure
  @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
  static <K, V> ReferenceEntry<K, V> nullEntry() {
    return (ReferenceEntry<K, V>) NullEntry.INSTANCE;
  }

  static final Queue<? extends Object> DISCARDING_QUEUE = new AbstractQueue<Object>() {
    @Override
    public boolean offer(Object o) {
      return true;
    }

    @Override
    public Object peek() {
      return null;
    }

    @Override
    public Object poll() {
      return null;
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public Iterator<Object> iterator() {
      return Iterators.emptyIterator();
    }
  };

  /**
   * Queue that discards all elements.
   */
  @Pure
  @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
  static <E> Queue<E> discardingQueue() {
    return (Queue) DISCARDING_QUEUE;
  }

  /*
   * Note: All of this duplicate code sucks, but it saves a lot of memory. If only Java had mixins!
   * To maintain this code, make a change for the strong reference type. Then, cut and paste, and
   * replace "Strong" with "Soft" or "Weak" within the pasted text. The primary difference is that
   * strong entries store the key reference directly while soft and weak entries delegate to their
   * respective superclasses.
   */

  /**
   * Used for strongly-referenced keys.
   */
  static class StrongEntry<K, V> implements ReferenceEntry<K, V> {
    final K key;

    @SideEffectFree
    StrongEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      this.key = key;
      this.hash = hash;
      this.next = next;
    }

    @Pure
    @Override
    public K getKey() {
      return this.key;
    }

    // null expiration

    @Pure
    @Override
    public long getExpirationTime() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    // null eviction

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    // The code below is exactly the same for each entry type.

    final int hash;
    final ReferenceEntry<K, V> next;
    volatile ValueReference<K, V> valueReference = unset();

    @Pure
    @Override
    public ValueReference<K, V> getValueReference() {
      return valueReference;
    }

    @Impure
    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
      ValueReference<K, V> previous = this.valueReference;
      this.valueReference = valueReference;
      previous.clear(valueReference);
    }

    @Pure
    @Override
    public int getHash() {
      return hash;
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNext() {
      return next;
    }
  }

  static final class StrongExpirableEntry<K, V> extends StrongEntry<K, V>
      implements ReferenceEntry<K, V> {
    @SideEffectFree
    @Impure
    StrongExpirableEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(key, hash, next);
    }

    // The code below is exactly the same for each expirable entry type.

    volatile long time = Long.MAX_VALUE;

    @Pure
    @Override
    public long getExpirationTime() {
      return time;
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      this.time = time;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      return nextExpirable;
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      this.nextExpirable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      return previousExpirable;
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      this.previousExpirable = previous;
    }
  }

  static final class StrongEvictableEntry<K, V>
      extends StrongEntry<K, V> implements ReferenceEntry<K, V> {
    @SideEffectFree
    @Impure
    StrongEvictableEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(key, hash, next);
    }

    // The code below is exactly the same for each evictable entry type.

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      return nextEvictable;
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      this.nextEvictable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      return previousEvictable;
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      this.previousEvictable = previous;
    }
  }

  static final class StrongExpirableEvictableEntry<K, V>
      extends StrongEntry<K, V> implements ReferenceEntry<K, V> {
    @SideEffectFree
    @Impure
    StrongExpirableEvictableEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(key, hash, next);
    }

    // The code below is exactly the same for each expirable entry type.

    volatile long time = Long.MAX_VALUE;

    @Pure
    @Override
    public long getExpirationTime() {
      return time;
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      this.time = time;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      return nextExpirable;
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      this.nextExpirable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      return previousExpirable;
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      this.previousExpirable = previous;
    }

    // The code below is exactly the same for each evictable entry type.

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      return nextEvictable;
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      this.nextEvictable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      return previousEvictable;
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      this.previousEvictable = previous;
    }
  }

  /**
   * Used for softly-referenced keys.
   */
  static class SoftEntry<K, V> extends SoftReference<K> implements ReferenceEntry<K, V> {
    @Impure
    SoftEntry(ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(key, queue);
      this.hash = hash;
      this.next = next;
    }

    @SideEffectFree
    @Override
    public K getKey() {
      return get();
    }

    // null expiration
    @Pure
    @Override
    public long getExpirationTime() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    // null eviction

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    // The code below is exactly the same for each entry type.

    final int hash;
    final ReferenceEntry<K, V> next;
    volatile ValueReference<K, V> valueReference = unset();

    @Pure
    @Override
    public ValueReference<K, V> getValueReference() {
      return valueReference;
    }

    @Impure
    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
      ValueReference<K, V> previous = this.valueReference;
      this.valueReference = valueReference;
      previous.clear(valueReference);
    }

    @Pure
    @Override
    public int getHash() {
      return hash;
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNext() {
      return next;
    }
  }

  static final class SoftExpirableEntry<K, V>
      extends SoftEntry<K, V> implements ReferenceEntry<K, V> {
    @Impure
    SoftExpirableEntry(
        ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each expirable entry type.

    volatile long time = Long.MAX_VALUE;

    @Pure
    @Override
    public long getExpirationTime() {
      return time;
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      this.time = time;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      return nextExpirable;
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      this.nextExpirable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      return previousExpirable;
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      this.previousExpirable = previous;
    }
  }

  static final class SoftEvictableEntry<K, V>
      extends SoftEntry<K, V> implements ReferenceEntry<K, V> {
    @Impure
    SoftEvictableEntry(
        ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each evictable entry type.

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      return nextEvictable;
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      this.nextEvictable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      return previousEvictable;
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      this.previousEvictable = previous;
    }
  }

  static final class SoftExpirableEvictableEntry<K, V>
      extends SoftEntry<K, V> implements ReferenceEntry<K, V> {
    @Impure
    SoftExpirableEvictableEntry(
        ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each expirable entry type.

    volatile long time = Long.MAX_VALUE;

    @Pure
    @Override
    public long getExpirationTime() {
      return time;
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      this.time = time;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      return nextExpirable;
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      this.nextExpirable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      return previousExpirable;
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      this.previousExpirable = previous;
    }

    // The code below is exactly the same for each evictable entry type.

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      return nextEvictable;
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      this.nextEvictable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      return previousEvictable;
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      this.previousEvictable = previous;
    }
  }

  /**
   * Used for weakly-referenced keys.
   */
  static class WeakEntry<K, V> extends WeakReference<K> implements ReferenceEntry<K, V> {
    @Impure
    WeakEntry(ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(key, queue);
      this.hash = hash;
      this.next = next;
    }

    @SideEffectFree
    @Override
    public K getKey() {
      return get();
    }

    // null expiration

    @Pure
    @Override
    public long getExpirationTime() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    // null eviction

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      throw new UnsupportedOperationException();
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      throw new UnsupportedOperationException();
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      throw new UnsupportedOperationException();
    }

    // The code below is exactly the same for each entry type.

    final int hash;
    final ReferenceEntry<K, V> next;
    volatile ValueReference<K, V> valueReference = unset();

    @Pure
    @Override
    public ValueReference<K, V> getValueReference() {
      return valueReference;
    }

    @Impure
    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
      ValueReference<K, V> previous = this.valueReference;
      this.valueReference = valueReference;
      previous.clear(valueReference);
    }

    @Pure
    @Override
    public int getHash() {
      return hash;
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getNext() {
      return next;
    }
  }

  static final class WeakExpirableEntry<K, V>
      extends WeakEntry<K, V> implements ReferenceEntry<K, V> {
    @Impure
    WeakExpirableEntry(
        ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each expirable entry type.

    volatile long time = Long.MAX_VALUE;

    @Pure
    @Override
    public long getExpirationTime() {
      return time;
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      this.time = time;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      return nextExpirable;
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      this.nextExpirable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      return previousExpirable;
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      this.previousExpirable = previous;
    }
  }

  static final class WeakEvictableEntry<K, V>
      extends WeakEntry<K, V> implements ReferenceEntry<K, V> {
    @Impure
    WeakEvictableEntry(
        ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each evictable entry type.

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      return nextEvictable;
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      this.nextEvictable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      return previousEvictable;
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      this.previousEvictable = previous;
    }
  }

  static final class WeakExpirableEvictableEntry<K, V>
      extends WeakEntry<K, V> implements ReferenceEntry<K, V> {
    @Impure
    WeakExpirableEvictableEntry(
        ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      super(queue, key, hash, next);
    }

    // The code below is exactly the same for each expirable entry type.

    volatile long time = Long.MAX_VALUE;

    @Pure
    @Override
    public long getExpirationTime() {
      return time;
    }

    @Impure
    @Override
    public void setExpirationTime(long time) {
      this.time = time;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextExpirable() {
      return nextExpirable;
    }

    @Impure
    @Override
    public void setNextExpirable(ReferenceEntry<K, V> next) {
      this.nextExpirable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousExpirable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousExpirable() {
      return previousExpirable;
    }

    @Impure
    @Override
    public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
      this.previousExpirable = previous;
    }

    // The code below is exactly the same for each evictable entry type.

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> nextEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getNextEvictable() {
      return nextEvictable;
    }

    @Impure
    @Override
    public void setNextEvictable(ReferenceEntry<K, V> next) {
      this.nextEvictable = next;
    }

    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> previousEvictable = nullEntry();

    @Pure
    @Override
    public ReferenceEntry<K, V> getPreviousEvictable() {
      return previousEvictable;
    }

    @Impure
    @Override
    public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
      this.previousEvictable = previous;
    }
  }

  /**
   * References a weak value.
   */
  static final class WeakValueReference<K, V>
      extends WeakReference<V> implements ValueReference<K, V> {
    final ReferenceEntry<K, V> entry;

    @Impure
    WeakValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
      super(referent, queue);
      this.entry = entry;
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getEntry() {
      return entry;
    }

    @Impure
    @Override
    public void clear(ValueReference<K, V> newValue) {
      clear();
    }

    @Impure
    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
      return new WeakValueReference<K, V>(queue, value, entry);
    }

    @Pure
    @Override
    public boolean isComputingReference() {
      return false;
    }

    @SideEffectFree
    @Override
    public V waitForValue() {
      return get();
    }
  }

  /**
   * References a soft value.
   */
  static final class SoftValueReference<K, V>
      extends SoftReference<V> implements ValueReference<K, V> {
    final ReferenceEntry<K, V> entry;

    @Impure
    SoftValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
      super(referent, queue);
      this.entry = entry;
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getEntry() {
      return entry;
    }

    @Impure
    @Override
    public void clear(ValueReference<K, V> newValue) {
      clear();
    }

    @Impure
    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
      return new SoftValueReference<K, V>(queue, value, entry);
    }

    @Pure
    @Override
    public boolean isComputingReference() {
      return false;
    }

    @SideEffectFree
    @Override
    public V waitForValue() {
      return get();
    }
  }

  /**
   * References a strong value.
   */
  static final class StrongValueReference<K, V> implements ValueReference<K, V> {
    final V referent;

    @SideEffectFree
    StrongValueReference(V referent) {
      this.referent = referent;
    }

    @Pure
    @Override
    public V get() {
      return referent;
    }

    @Pure
    @Override
    public ReferenceEntry<K, V> getEntry() {
      return null;
    }

    @Pure
    @Override
    public ValueReference<K, V> copyFor(
        ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
      return this;
    }

    @Pure
    @Override
    public boolean isComputingReference() {
      return false;
    }

    @Pure
    @Impure
    @Override
    public V waitForValue() {
      return get();
    }

    @SideEffectFree
    @Override
    public void clear(ValueReference<K, V> newValue) {}
  }

  /**
   * Applies a supplemental hash function to a given hash code, which defends against poor quality
   * hash functions. This is critical when the concurrent hash map uses power-of-two length hash
   * tables, that otherwise encounter collisions for hash codes that do not differ in lower or
   * upper bits.
   *
   * @param h hash code
   */
  @Pure
  static int rehash(int h) {
    // Spread bits to regularize both segment and index locations,
    // using variant of single-word Wang/Jenkins hash.
    // TODO(kevinb): use Hashing/move this to Hashing?
    h += (h << 15) ^ 0xffffcd7d;
    h ^= (h >>> 10);
    h += (h << 3);
    h ^= (h >>> 6);
    h += (h << 2) + (h << 14);
    return h ^ (h >>> 16);
  }

  /**
   * This method is a convenience for testing. Code should call {@link Segment#newEntry} directly.
   */
  @Impure
  @GuardedBy("Segment.this")
  @VisibleForTesting
  ReferenceEntry<K, V> newEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
    return segmentFor(hash).newEntry(key, hash, next);
  }

  /**
   * This method is a convenience for testing. Code should call {@link Segment#copyEntry} directly.
   */
  @Impure
  @GuardedBy("Segment.this")
  @VisibleForTesting
  ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
    int hash = original.getHash();
    return segmentFor(hash).copyEntry(original, newNext);
  }

  /**
   * This method is a convenience for testing. Code should call {@link Segment#setValue} instead.
   */
  @Impure
  @GuardedBy("Segment.this")
  @VisibleForTesting
  ValueReference<K, V> newValueReference(ReferenceEntry<K, V> entry, V value) {
    int hash = entry.getHash();
    return valueStrength.referenceValue(segmentFor(hash), entry, value);
  }

  @Impure
  int hash(Object key) {
    int h = keyEquivalence.hash(key);
    return rehash(h);
  }

  @Impure
  void reclaimValue(ValueReference<K, V> valueReference) {
    ReferenceEntry<K, V> entry = valueReference.getEntry();
    int hash = entry.getHash();
    segmentFor(hash).reclaimValue(entry.getKey(), hash, valueReference);
  }

  @Impure
  void reclaimKey(ReferenceEntry<K, V> entry) {
    int hash = entry.getHash();
    segmentFor(hash).reclaimKey(entry, hash);
  }

  /**
   * This method is a convenience for testing. Code should call {@link Segment#getLiveValue}
   * instead.
   */
  @Impure
  @VisibleForTesting
  boolean isLive(ReferenceEntry<K, V> entry) {
    return segmentFor(entry.getHash()).getLiveValue(entry) != null;
  }

  /**
   * Returns the segment that should be used for a key with the given hash.
   *
   * @param hash the hash code for the key
   * @return the segment
   */
  @Pure
  Segment<K, V> segmentFor(int hash) {
    // TODO(fry): Lazily create segments?
    return segments[(hash >>> segmentShift) & segmentMask];
  }

  @Impure
  Segment<K, V> createSegment(int initialCapacity, int maxSegmentSize) {
    return new Segment<K, V>(this, initialCapacity, maxSegmentSize);
  }

  /**
   * Gets the value from an entry. Returns {@code null} if the entry is invalid,
   * partially-collected, computing, or expired. Unlike {@link Segment#getLiveValue} this method
   * does not attempt to clean up stale entries.
   */
  @Impure
  V getLiveValue(ReferenceEntry<K, V> entry) {
    if (entry.getKey() == null) {
      return null;
    }
    V value = entry.getValueReference().get();
    if (value == null) {
      return null;
    }

    if (expires() && isExpired(entry)) {
      return null;
    }
    return value;
  }

  // expiration

  /**
   * Returns {@code true} if the entry has expired.
   */
  @Impure
  boolean isExpired(ReferenceEntry<K, V> entry) {
    return isExpired(entry, ticker.read());
  }

  /**
   * Returns {@code true} if the entry has expired.
   */
  @Pure
  @Impure
  boolean isExpired(ReferenceEntry<K, V> entry, long now) {
    // if the expiration time had overflowed, this "undoes" the overflow
    return now - entry.getExpirationTime() > 0;
  }

  @Impure
  @GuardedBy("Segment.this")
  static <K, V> void connectExpirables(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
    previous.setNextExpirable(next);
    next.setPreviousExpirable(previous);
  }

  @Impure
  @GuardedBy("Segment.this")
  static <K, V> void nullifyExpirable(ReferenceEntry<K, V> nulled) {
    ReferenceEntry<K, V> nullEntry = nullEntry();
    nulled.setNextExpirable(nullEntry);
    nulled.setPreviousExpirable(nullEntry);
  }

  // eviction

  /**
   * Notifies listeners that an entry has been automatically removed due to expiration, eviction,
   * or eligibility for garbage collection. This should be called every time expireEntries or
   * evictEntry is called (once the lock is released).
   */
  @Impure
  void processPendingNotifications() {
    RemovalNotification<K, V> notification;
    while ((notification = removalNotificationQueue.poll()) != null) {
      try {
        removalListener.onRemoval(notification);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Exception thrown by removal listener", e);
      }
    }
  }

  /** Links the evitables together. */
  @Impure
  @GuardedBy("Segment.this")
  static <K, V> void connectEvictables(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
    previous.setNextEvictable(next);
    next.setPreviousEvictable(previous);
  }

  @Impure
  @GuardedBy("Segment.this")
  static <K, V> void nullifyEvictable(ReferenceEntry<K, V> nulled) {
    ReferenceEntry<K, V> nullEntry = nullEntry();
    nulled.setNextEvictable(nullEntry);
    nulled.setPreviousEvictable(nullEntry);
  }

  @Pure
  @SuppressWarnings("unchecked")
  final Segment<K, V>[] newSegmentArray(int ssize) {
    return new Segment[ssize];
  }

  // Inner Classes

  /**
   * Segments are specialized versions of hash tables. This subclass inherits from ReentrantLock
   * opportunistically, just to simplify some locking and avoid separate construction.
   */
  @SuppressWarnings("serial") // This class is never serialized.
  static class Segment<K, V> extends ReentrantLock {

    /*
     * TODO(fry): Consider copying variables (like evictsBySize) from outer class into this class.
     * It will require more memory but will reduce indirection.
     */

    /*
     * Segments maintain a table of entry lists that are ALWAYS kept in a consistent state, so can
     * be read without locking. Next fields of nodes are immutable (final). All list additions are
     * performed at the front of each bin. This makes it easy to check changes, and also fast to
     * traverse. When nodes would otherwise be changed, new nodes are created to replace them. This
     * works well for hash tables since the bin lists tend to be short. (The average length is less
     * than two.)
     *
     * Read operations can thus proceed without locking, but rely on selected uses of volatiles to
     * ensure that completed write operations performed by other threads are noticed. For most
     * purposes, the "count" field, tracking the number of elements, serves as that volatile
     * variable ensuring visibility. This is convenient because this field needs to be read in many
     * read operations anyway:
     *
     * - All (unsynchronized) read operations must first read the "count" field, and should not
     * look at table entries if it is 0.
     *
     * - All (synchronized) write operations should write to the "count" field after structurally
     * changing any bin. The operations must not take any action that could even momentarily
     * cause a concurrent read operation to see inconsistent data. This is made easier by the
     * nature of the read operations in Map. For example, no operation can reveal that the table
     * has grown but the threshold has not yet been updated, so there are no atomicity requirements
     * for this with respect to reads.
     *
     * As a guide, all critical volatile reads and writes to the count field are marked in code
     * comments.
     */

    final MapMakerInternalMap<K, V> map;

    /**
     * The number of live elements in this segment's region. This does not include unset elements
     * which are awaiting cleanup.
     */
    volatile int count;

    /**
     * Number of updates that alter the size of the table. This is used during bulk-read methods to
     * make sure they see a consistent snapshot: If modCounts change during a traversal of segments
     * computing size or checking containsValue, then we might have an inconsistent view of state
     * so (usually) must retry.
     */
    int modCount;

    /**
     * The table is expanded when its size exceeds this threshold. (The value of this field is
     * always {@code (int) (capacity * 0.75)}.)
     */
    int threshold;

    /**
     * The per-segment table.
     */
    volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;

    /**
     * The maximum size of this map. MapMaker.UNSET_INT if there is no maximum.
     */
    final int maxSegmentSize;

    /**
     * The key reference queue contains entries whose keys have been garbage collected, and which
     * need to be cleaned up internally.
     */
    final ReferenceQueue<K> keyReferenceQueue;

    /**
     * The value reference queue contains value references whose values have been garbage collected,
     * and which need to be cleaned up internally.
     */
    final ReferenceQueue<V> valueReferenceQueue;

    /**
     * The recency queue is used to record which entries were accessed for updating the eviction
     * list's ordering. It is drained as a batch operation when either the DRAIN_THRESHOLD is
     * crossed or a write occurs on the segment.
     */
    final Queue<ReferenceEntry<K, V>> recencyQueue;

    /**
     * A counter of the number of reads since the last write, used to drain queues on a small
     * fraction of read operations.
     */
    final AtomicInteger readCount = new AtomicInteger();

    /**
     * A queue of elements currently in the map, ordered by access time. Elements are added to the
     * tail of the queue on access/write.
     */
    @GuardedBy("Segment.this")
    final Queue<ReferenceEntry<K, V>> evictionQueue;

    /**
     * A queue of elements currently in the map, ordered by expiration time (either access or write
     * time). Elements are added to the tail of the queue on access/write.
     */
    @GuardedBy("Segment.this")
    final Queue<ReferenceEntry<K, V>> expirationQueue;

    @Impure
    Segment(MapMakerInternalMap<K, V> map, int initialCapacity, int maxSegmentSize) {
      this.map = map;
      this.maxSegmentSize = maxSegmentSize;
      initTable(newEntryArray(initialCapacity));

      keyReferenceQueue = map.usesKeyReferences()
           ? new ReferenceQueue<K>() : null;

      valueReferenceQueue = map.usesValueReferences()
           ? new ReferenceQueue<V>() : null;

      recencyQueue = (map.evictsBySize() || map.expiresAfterAccess())
          ? new ConcurrentLinkedQueue<ReferenceEntry<K, V>>()
          : MapMakerInternalMap.<ReferenceEntry<K, V>>discardingQueue();

      evictionQueue = map.evictsBySize()
          ? new EvictionQueue<K, V>()
          : MapMakerInternalMap.<ReferenceEntry<K, V>>discardingQueue();

      expirationQueue = map.expires()
          ? new ExpirationQueue<K, V>()
          : MapMakerInternalMap.<ReferenceEntry<K, V>>discardingQueue();
    }

    @Impure
    AtomicReferenceArray<ReferenceEntry<K, V>> newEntryArray(int size) {
      return new AtomicReferenceArray<ReferenceEntry<K, V>>(size);
    }

    @Impure
    void initTable(AtomicReferenceArray<ReferenceEntry<K, V>> newTable) {
      this.threshold = newTable.length() * 3 / 4; // 0.75
      if (this.threshold == maxSegmentSize) {
        // prevent spurious expansion before eviction
        this.threshold++;
      }
      this.table = newTable;
    }

    @Impure
    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> newEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
      return map.entryFactory.newEntry(this, key, hash, next);
    }

    /**
     * Copies {@code original} into a new entry chained to {@code newNext}. Returns the new entry,
     * or {@code null} if {@code original} was already garbage collected.
     */
    @Impure
    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
      if (original.getKey() == null) {
        // key collected
        return null;
      }

      ValueReference<K, V> valueReference = original.getValueReference();
      V value = valueReference.get();
      if ((value == null) && !valueReference.isComputingReference()) {
        // value collected
        return null;
      }

      ReferenceEntry<K, V> newEntry = map.entryFactory.copyEntry(this, original, newNext);
      newEntry.setValueReference(valueReference.copyFor(this.valueReferenceQueue, value, newEntry));
      return newEntry;
    }

    /**
     * Sets a new value of an entry. Adds newly created entries at the end of the expiration queue.
     */
    @Impure
    @GuardedBy("Segment.this")
    void setValue(ReferenceEntry<K, V> entry, V value) {
      ValueReference<K, V> valueReference = map.valueStrength.referenceValue(this, entry, value);
      entry.setValueReference(valueReference);
      recordWrite(entry);
    }

    // reference queues, for garbage collection cleanup

    /**
     * Cleanup collected entries when the lock is available.
     */
    @Impure
    void tryDrainReferenceQueues() {
      if (tryLock()) {
        try {
          drainReferenceQueues();
        } finally {
          unlock();
        }
      }
    }

    /**
     * Drain the key and value reference queues, cleaning up internal entries containing garbage
     * collected keys or values.
     */
    @Impure
    @GuardedBy("Segment.this")
    void drainReferenceQueues() {
      if (map.usesKeyReferences()) {
        drainKeyReferenceQueue();
      }
      if (map.usesValueReferences()) {
        drainValueReferenceQueue();
      }
    }

    @Impure
    @GuardedBy("Segment.this")
    void drainKeyReferenceQueue() {
      Reference<? extends K> ref;
      int i = 0;
      while ((ref = keyReferenceQueue.poll()) != null) {
        @SuppressWarnings("unchecked")
        ReferenceEntry<K, V> entry = (ReferenceEntry<K, V>) ref;
        map.reclaimKey(entry);
        if (++i == DRAIN_MAX) {
          break;
        }
      }
    }

    @Impure
    @GuardedBy("Segment.this")
    void drainValueReferenceQueue() {
      Reference<? extends V> ref;
      int i = 0;
      while ((ref = valueReferenceQueue.poll()) != null) {
        @SuppressWarnings("unchecked")
        ValueReference<K, V> valueReference = (ValueReference<K, V>) ref;
        map.reclaimValue(valueReference);
        if (++i == DRAIN_MAX) {
          break;
        }
      }
    }

    /**
     * Clears all entries from the key and value reference queues.
     */
    @Impure
    void clearReferenceQueues() {
      if (map.usesKeyReferences()) {
        clearKeyReferenceQueue();
      }
      if (map.usesValueReferences()) {
        clearValueReferenceQueue();
      }
    }

    @Impure
    void clearKeyReferenceQueue() {
      while (keyReferenceQueue.poll() != null) {}
    }

    @Impure
    void clearValueReferenceQueue() {
      while (valueReferenceQueue.poll() != null) {}
    }

    // recency queue, shared by expiration and eviction

    /**
     * Records the relative order in which this read was performed by adding {@code entry} to the
     * recency queue. At write-time, or when the queue is full past the threshold, the queue will
     * be drained and the entries therein processed.
     *
     * <p>Note: locked reads should use {@link #recordLockedRead}.
     */
    @Impure
    void recordRead(ReferenceEntry<K, V> entry) {
      if (map.expiresAfterAccess()) {
        recordExpirationTime(entry, map.expireAfterAccessNanos);
      }
      recencyQueue.add(entry);
    }

    /**
     * Updates the eviction metadata that {@code entry} was just read. This currently amounts to
     * adding {@code entry} to relevant eviction lists.
     *
     * <p>Note: this method should only be called under lock, as it directly manipulates the
     * eviction queues. Unlocked reads should use {@link #recordRead}.
     */
    @Impure
    @GuardedBy("Segment.this")
    void recordLockedRead(ReferenceEntry<K, V> entry) {
      evictionQueue.add(entry);
      if (map.expiresAfterAccess()) {
        recordExpirationTime(entry, map.expireAfterAccessNanos);
        expirationQueue.add(entry);
      }
    }

    /**
     * Updates eviction metadata that {@code entry} was just written. This currently amounts to
     * adding {@code entry} to relevant eviction lists.
     */
    @Impure
    @GuardedBy("Segment.this")
    void recordWrite(ReferenceEntry<K, V> entry) {
      // we are already under lock, so drain the recency queue immediately
      drainRecencyQueue();
      evictionQueue.add(entry);
      if (map.expires()) {
        // currently MapMaker ensures that expireAfterWrite and
        // expireAfterAccess are mutually exclusive
        long expiration = map.expiresAfterAccess()
            ? map.expireAfterAccessNanos
            : map.expireAfterWriteNanos;
        recordExpirationTime(entry, expiration);
        expirationQueue.add(entry);
      }
    }

    /**
     * Drains the recency queue, updating eviction metadata that the entries therein were read in
     * the specified relative order. This currently amounts to adding them to relevant eviction
     * lists (accounting for the fact that they could have been removed from the map since being
     * added to the recency queue).
     */
    @Impure
    @GuardedBy("Segment.this")
    void drainRecencyQueue() {
      ReferenceEntry<K, V> e;
      while ((e = recencyQueue.poll()) != null) {
        // An entry may be in the recency queue despite it being removed from
        // the map . This can occur when the entry was concurrently read while a
        // writer is removing it from the segment or after a clear has removed
        // all of the segment's entries.
        if (evictionQueue.contains(e)) {
          evictionQueue.add(e);
        }
        if (map.expiresAfterAccess() && expirationQueue.contains(e)) {
          expirationQueue.add(e);
        }
      }
    }

    // expiration

    @Impure
    void recordExpirationTime(ReferenceEntry<K, V> entry, long expirationNanos) {
      // might overflow, but that's okay (see isExpired())
      entry.setExpirationTime(map.ticker.read() + expirationNanos);
    }

    /**
     * Cleanup expired entries when the lock is available.
     */
    @Impure
    void tryExpireEntries() {
      if (tryLock()) {
        try {
          expireEntries();
        } finally {
          unlock();
          // don't call postWriteCleanup as we're in a read
        }
      }
    }

    @Impure
    @GuardedBy("Segment.this")
    void expireEntries() {
      drainRecencyQueue();

      if (expirationQueue.isEmpty()) {
        // There's no point in calling nanoTime() if we have no entries to
        // expire.
        return;
      }
      long now = map.ticker.read();
      ReferenceEntry<K, V> e;
      while ((e = expirationQueue.peek()) != null && map.isExpired(e, now)) {
        if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
          throw new AssertionError();
        }
      }
    }

    // eviction

    @Impure
    void enqueueNotification(ReferenceEntry<K, V> entry, RemovalCause cause) {
      enqueueNotification(entry.getKey(), entry.getHash(), entry.getValueReference().get(), cause);
    }

    @Impure
    void enqueueNotification(@Nullable K key, int hash, @Nullable V value, RemovalCause cause) {
      if (map.removalNotificationQueue != DISCARDING_QUEUE) {
        RemovalNotification<K, V> notification = new RemovalNotification<K, V>(key, value, cause);
        map.removalNotificationQueue.offer(notification);
      }
    }

    /**
     * Performs eviction if the segment is full. This should only be called prior to adding a new
     * entry and increasing {@code count}.
     *
     * @return {@code true} if eviction occurred
     */
    @Impure
    @GuardedBy("Segment.this")
    boolean evictEntries() {
      if (map.evictsBySize() && count >= maxSegmentSize) {
        drainRecencyQueue();

        ReferenceEntry<K, V> e = evictionQueue.remove();
        if (!removeEntry(e, e.getHash(), RemovalCause.SIZE)) {
          throw new AssertionError();
        }
        return true;
      }
      return false;
    }

    /**
     * Returns first entry of bin for given hash.
     */
    @Impure
    ReferenceEntry<K, V> getFirst(int hash) {
      // read this volatile field only once
      AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
      return table.get(hash & (table.length() - 1));
    }

    // Specialized implementations of map methods

    @Impure
    ReferenceEntry<K, V> getEntry(Object key, int hash) {
      if (count != 0) { // read-volatile
        for (ReferenceEntry<K, V> e = getFirst(hash); e != null; e = e.getNext()) {
          if (e.getHash() != hash) {
            continue;
          }

          K entryKey = e.getKey();
          if (entryKey == null) {
            tryDrainReferenceQueues();
            continue;
          }

          if (map.keyEquivalence.equivalent(key, entryKey)) {
            return e;
          }
        }
      }

      return null;
    }

    @Impure
    ReferenceEntry<K, V> getLiveEntry(Object key, int hash) {
      ReferenceEntry<K, V> e = getEntry(key, hash);
      if (e == null) {
        return null;
      } else if (map.expires() && map.isExpired(e)) {
        tryExpireEntries();
        return null;
      }
      return e;
    }

    @Impure
    V get(Object key, int hash) {
      try {
        ReferenceEntry<K, V> e = getLiveEntry(key, hash);
        if (e == null) {
          return null;
        }

        V value = e.getValueReference().get();
        if (value != null) {
          recordRead(e);
        } else {
          tryDrainReferenceQueues();
        }
        return value;
      } finally {
        postReadCleanup();
      }
    }

    @Impure
    boolean containsKey(Object key, int hash) {
      try {
        if (count != 0) { // read-volatile
          ReferenceEntry<K, V> e = getLiveEntry(key, hash);
          if (e == null) {
            return false;
          }
          return e.getValueReference().get() != null;
        }

        return false;
      } finally {
        postReadCleanup();
      }
    }

    /**
     * This method is a convenience for testing. Code should call {@link
     * MapMakerInternalMap#containsValue} directly.
     */
    @Impure
    @VisibleForTesting
    boolean containsValue(Object value) {
      try {
        if (count != 0) { // read-volatile
          AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
          int length = table.length();
          for (int i = 0; i < length; ++i) {
            for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
              V entryValue = getLiveValue(e);
              if (entryValue == null) {
                continue;
              }
              if (map.valueEquivalence.equivalent(value, entryValue)) {
                return true;
              }
            }
          }
        }

        return false;
      } finally {
        postReadCleanup();
      }
    }

    @Impure
    V put(K key, int hash, V value, boolean onlyIfAbsent) {
      lock();
      try {
        preWriteCleanup();

        int newCount = this.count + 1;
        if (newCount > this.threshold) { // ensure capacity
          expand();
          newCount = this.count + 1;
        }

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        // Look for an existing entry.
        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            // We found an existing entry.

            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();

            if (entryValue == null) {
              ++modCount;
              setValue(e, value);
              if (!valueReference.isComputingReference()) {
                enqueueNotification(key, hash, entryValue, RemovalCause.COLLECTED);
                newCount = this.count; // count remains unchanged
              } else if (evictEntries()) { // evictEntries after setting new value
                newCount = this.count + 1;
              }
              this.count = newCount; // write-volatile
              return null;
            } else if (onlyIfAbsent) {
              // Mimic
              // "if (!map.containsKey(key)) ...
              // else return map.get(key);
              recordLockedRead(e);
              return entryValue;
            } else {
              // clobber existing entry, count remains unchanged
              ++modCount;
              enqueueNotification(key, hash, entryValue, RemovalCause.REPLACED);
              setValue(e, value);
              return entryValue;
            }
          }
        }

        // Create a new entry.
        ++modCount;
        ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
        setValue(newEntry, value);
        table.set(index, newEntry);
        if (evictEntries()) { // evictEntries after setting new value
          newCount = this.count + 1;
        }
        this.count = newCount; // write-volatile
        return null;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    /**
     * Expands the table if possible.
     */
    @Impure
    @GuardedBy("Segment.this")
    void expand() {
      AtomicReferenceArray<ReferenceEntry<K, V>> oldTable = table;
      int oldCapacity = oldTable.length();
      if (oldCapacity >= MAXIMUM_CAPACITY) {
        return;
      }

      /*
       * Reclassify nodes in each list to new Map. Because we are using power-of-two expansion, the
       * elements from each bin must either stay at same index, or move with a power of two offset.
       * We eliminate unnecessary node creation by catching cases where old nodes can be reused
       * because their next fields won't change. Statistically, at the default threshold, only
       * about one-sixth of them need cloning when a table doubles. The nodes they replace will be
       * garbage collectable as soon as they are no longer referenced by any reader thread that may
       * be in the midst of traversing table right now.
       */

      int newCount = count;
      AtomicReferenceArray<ReferenceEntry<K, V>> newTable = newEntryArray(oldCapacity << 1);
      threshold = newTable.length() * 3 / 4;
      int newMask = newTable.length() - 1;
      for (int oldIndex = 0; oldIndex < oldCapacity; ++oldIndex) {
        // We need to guarantee that any existing reads of old Map can
        // proceed. So we cannot yet null out each bin.
        ReferenceEntry<K, V> head = oldTable.get(oldIndex);

        if (head != null) {
          ReferenceEntry<K, V> next = head.getNext();
          int headIndex = head.getHash() & newMask;

          // Single node on list
          if (next == null) {
            newTable.set(headIndex, head);
          } else {
            // Reuse the consecutive sequence of nodes with the same target
            // index from the end of the list. tail points to the first
            // entry in the reusable list.
            ReferenceEntry<K, V> tail = head;
            int tailIndex = headIndex;
            for (ReferenceEntry<K, V> e = next; e != null; e = e.getNext()) {
              int newIndex = e.getHash() & newMask;
              if (newIndex != tailIndex) {
                // The index changed. We'll need to copy the previous entry.
                tailIndex = newIndex;
                tail = e;
              }
            }
            newTable.set(tailIndex, tail);

            // Clone nodes leading up to the tail.
            for (ReferenceEntry<K, V> e = head; e != tail; e = e.getNext()) {
              int newIndex = e.getHash() & newMask;
              ReferenceEntry<K, V> newNext = newTable.get(newIndex);
              ReferenceEntry<K, V> newFirst = copyEntry(e, newNext);
              if (newFirst != null) {
                newTable.set(newIndex, newFirst);
              } else {
                removeCollectedEntry(e);
                newCount--;
              }
            }
          }
        }
      }
      table = newTable;
      this.count = newCount;
    }

    @Impure
    boolean replace(K key, int hash, V oldValue, V newValue) {
      lock();
      try {
        preWriteCleanup();

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            // If the value disappeared, this entry is partially collected,
            // and we should pretend like it doesn't exist.
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();
            if (entryValue == null) {
              if (isCollected(valueReference)) {
                int newCount = this.count - 1;
                ++modCount;
                enqueueNotification(entryKey, hash, entryValue, RemovalCause.COLLECTED);
                ReferenceEntry<K, V> newFirst = removeFromChain(first, e);
                newCount = this.count - 1;
                table.set(index, newFirst);
                this.count = newCount; // write-volatile
              }
              return false;
            }

            if (map.valueEquivalence.equivalent(oldValue, entryValue)) {
              ++modCount;
              enqueueNotification(key, hash, entryValue, RemovalCause.REPLACED);
              setValue(e, newValue);
              return true;
            } else {
              // Mimic
              // "if (map.containsKey(key) && map.get(key).equals(oldValue))..."
              recordLockedRead(e);
              return false;
            }
          }
        }

        return false;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    @Impure
    V replace(K key, int hash, V newValue) {
      lock();
      try {
        preWriteCleanup();

        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            // If the value disappeared, this entry is partially collected,
            // and we should pretend like it doesn't exist.
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();
            if (entryValue == null) {
              if (isCollected(valueReference)) {
                int newCount = this.count - 1;
                ++modCount;
                enqueueNotification(entryKey, hash, entryValue, RemovalCause.COLLECTED);
                ReferenceEntry<K, V> newFirst = removeFromChain(first, e);
                newCount = this.count - 1;
                table.set(index, newFirst);
                this.count = newCount; // write-volatile
              }
              return null;
            }

            ++modCount;
            enqueueNotification(key, hash, entryValue, RemovalCause.REPLACED);
            setValue(e, newValue);
            return entryValue;
          }
        }

        return null;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    @Impure
    V remove(Object key, int hash) {
      lock();
      try {
        preWriteCleanup();

        int newCount = this.count - 1;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();

            RemovalCause cause;
            if (entryValue != null) {
              cause = RemovalCause.EXPLICIT;
            } else if (isCollected(valueReference)) {
              cause = RemovalCause.COLLECTED;
            } else {
              return null;
            }

            ++modCount;
            enqueueNotification(entryKey, hash, entryValue, cause);
            ReferenceEntry<K, V> newFirst = removeFromChain(first, e);
            newCount = this.count - 1;
            table.set(index, newFirst);
            this.count = newCount; // write-volatile
            return entryValue;
          }
        }

        return null;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    @Impure
    boolean remove(Object key, int hash, Object value) {
      lock();
      try {
        preWriteCleanup();

        int newCount = this.count - 1;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> valueReference = e.getValueReference();
            V entryValue = valueReference.get();

            RemovalCause cause;
            if (map.valueEquivalence.equivalent(value, entryValue)) {
              cause = RemovalCause.EXPLICIT;
            } else if (isCollected(valueReference)) {
              cause = RemovalCause.COLLECTED;
            } else {
              return false;
            }

            ++modCount;
            enqueueNotification(entryKey, hash, entryValue, cause);
            ReferenceEntry<K, V> newFirst = removeFromChain(first, e);
            newCount = this.count - 1;
            table.set(index, newFirst);
            this.count = newCount; // write-volatile
            return (cause == RemovalCause.EXPLICIT);
          }
        }

        return false;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    @Impure
    void clear() {
      if (count != 0) {
        lock();
        try {
          AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
          if (map.removalNotificationQueue != DISCARDING_QUEUE) {
            for (int i = 0; i < table.length(); ++i) {
              for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
                // Computing references aren't actually in the map yet.
                if (!e.getValueReference().isComputingReference()) {
                  enqueueNotification(e, RemovalCause.EXPLICIT);
                }
              }
            }
          }
          for (int i = 0; i < table.length(); ++i) {
            table.set(i, null);
          }
          clearReferenceQueues();
          evictionQueue.clear();
          expirationQueue.clear();
          readCount.set(0);

          ++modCount;
          count = 0; // write-volatile
        } finally {
          unlock();
          postWriteCleanup();
        }
      }
    }

    /**
     * Removes an entry from within a table. All entries following the removed node can stay, but
     * all preceding ones need to be cloned.
     *
     * <p>This method does not decrement count for the removed entry, but does decrement count for
     * all partially collected entries which are skipped. As such callers which are modifying count
     * must re-read it after calling removeFromChain.
     *
     * @param first the first entry of the table
     * @param entry the entry being removed from the table
     * @return the new first entry for the table
     */
    @Impure
    @GuardedBy("Segment.this")
    ReferenceEntry<K, V> removeFromChain(ReferenceEntry<K, V> first, ReferenceEntry<K, V> entry) {
      evictionQueue.remove(entry);
      expirationQueue.remove(entry);

      int newCount = count;
      ReferenceEntry<K, V> newFirst = entry.getNext();
      for (ReferenceEntry<K, V> e = first; e != entry; e = e.getNext()) {
        ReferenceEntry<K, V> next = copyEntry(e, newFirst);
        if (next != null) {
          newFirst = next;
        } else {
          removeCollectedEntry(e);
          newCount--;
        }
      }
      this.count = newCount;
      return newFirst;
    }

    @Impure
    void removeCollectedEntry(ReferenceEntry<K, V> entry) {
      enqueueNotification(entry, RemovalCause.COLLECTED);
      evictionQueue.remove(entry);
      expirationQueue.remove(entry);
    }

    /**
     * Removes an entry whose key has been garbage collected.
     */
    @Impure
    boolean reclaimKey(ReferenceEntry<K, V> entry, int hash) {
      lock();
      try {
        int newCount = count - 1;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          if (e == entry) {
            ++modCount;
            enqueueNotification(
                e.getKey(), hash, e.getValueReference().get(), RemovalCause.COLLECTED);
            ReferenceEntry<K, V> newFirst = removeFromChain(first, e);
            newCount = this.count - 1;
            table.set(index, newFirst);
            this.count = newCount; // write-volatile
            return true;
          }
        }

        return false;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    /**
     * Removes an entry whose value has been garbage collected.
     */
    @Impure
    boolean reclaimValue(K key, int hash, ValueReference<K, V> valueReference) {
      lock();
      try {
        int newCount = this.count - 1;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> v = e.getValueReference();
            if (v == valueReference) {
              ++modCount;
              enqueueNotification(key, hash, valueReference.get(), RemovalCause.COLLECTED);
              ReferenceEntry<K, V> newFirst = removeFromChain(first, e);
              newCount = this.count - 1;
              table.set(index, newFirst);
              this.count = newCount; // write-volatile
              return true;
            }
            return false;
          }
        }

        return false;
      } finally {
        unlock();
        if (!isHeldByCurrentThread()) { // don't cleanup inside of put
          postWriteCleanup();
        }
      }
    }

    /**
     * Clears a value that has not yet been set, and thus does not require count to be modified.
     */
    @Impure
    boolean clearValue(K key, int hash, ValueReference<K, V> valueReference) {
      lock();
      try {
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
          K entryKey = e.getKey();
          if (e.getHash() == hash && entryKey != null
              && map.keyEquivalence.equivalent(key, entryKey)) {
            ValueReference<K, V> v = e.getValueReference();
            if (v == valueReference) {
              ReferenceEntry<K, V> newFirst = removeFromChain(first, e);
              table.set(index, newFirst);
              return true;
            }
            return false;
          }
        }

        return false;
      } finally {
        unlock();
        postWriteCleanup();
      }
    }

    @Impure
    @GuardedBy("Segment.this")
    boolean removeEntry(ReferenceEntry<K, V> entry, int hash, RemovalCause cause) {
      int newCount = this.count - 1;
      AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
      int index = hash & (table.length() - 1);
      ReferenceEntry<K, V> first = table.get(index);

      for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
        if (e == entry) {
          ++modCount;
          enqueueNotification(e.getKey(), hash, e.getValueReference().get(), cause);
          ReferenceEntry<K, V> newFirst = removeFromChain(first, e);
          newCount = this.count - 1;
          table.set(index, newFirst);
          this.count = newCount; // write-volatile
          return true;
        }
      }

      return false;
    }

    /**
     * Returns {@code true} if the value has been partially collected, meaning that the value is
     * null and it is not computing.
     */
    @Pure
    @Impure
    boolean isCollected(ValueReference<K, V> valueReference) {
      if (valueReference.isComputingReference()) {
        return false;
      }
      return (valueReference.get() == null);
    }

    /**
     * Gets the value from an entry. Returns {@code null} if the entry is invalid,
     * partially-collected, computing, or expired.
     */
    @Impure
    V getLiveValue(ReferenceEntry<K, V> entry) {
      if (entry.getKey() == null) {
        tryDrainReferenceQueues();
        return null;
      }
      V value = entry.getValueReference().get();
      if (value == null) {
        tryDrainReferenceQueues();
        return null;
      }

      if (map.expires() && map.isExpired(entry)) {
        tryExpireEntries();
        return null;
      }
      return value;
    }

    /**
     * Performs routine cleanup following a read. Normally cleanup happens during writes, or from
     * the cleanupExecutor. If cleanup is not observed after a sufficient number of reads, try
     * cleaning up from the read thread.
     */
    @Impure
    void postReadCleanup() {
      if ((readCount.incrementAndGet() & DRAIN_THRESHOLD) == 0) {
        runCleanup();
      }
    }

    /**
     * Performs routine cleanup prior to executing a write. This should be called every time a
     * write thread acquires the segment lock, immediately after acquiring the lock.
     *
     * <p>Post-condition: expireEntries has been run.
     */
    @Impure
    @GuardedBy("Segment.this")
    void preWriteCleanup() {
      runLockedCleanup();
    }

    /**
     * Performs routine cleanup following a write.
     */
    @Impure
    void postWriteCleanup() {
      runUnlockedCleanup();
    }

    @Impure
    void runCleanup() {
      runLockedCleanup();
      runUnlockedCleanup();
    }

    @Impure
    void runLockedCleanup() {
      if (tryLock()) {
        try {
          drainReferenceQueues();
          expireEntries(); // calls drainRecencyQueue
          readCount.set(0);
        } finally {
          unlock();
        }
      }
    }

    @Impure
    void runUnlockedCleanup() {
      // locked cleanup may generate notifications we can send unlocked
      if (!isHeldByCurrentThread()) {
        map.processPendingNotifications();
      }
    }

  }

  // Queues

  /**
   * A custom queue for managing eviction order. Note that this is tightly integrated with {@code
   * ReferenceEntry}, upon which it relies to perform its linking.
   *
   * <p>Note that this entire implementation makes the assumption that all elements which are in
   * the map are also in this queue, and that all elements not in the queue are not in the map.
   *
   * <p>The benefits of creating our own queue are that (1) we can replace elements in the middle
   * of the queue as part of copyEvictableEntry, and (2) the contains method is highly optimized
   * for the current model.
   */
  static final class EvictionQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
    final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {

      ReferenceEntry<K, V> nextEvictable = this;

      @Pure
      @Override
      public ReferenceEntry<K, V> getNextEvictable() {
        return nextEvictable;
      }

      @Impure
      @Override
      public void setNextEvictable(ReferenceEntry<K, V> next) {
        this.nextEvictable = next;
      }

      ReferenceEntry<K, V> previousEvictable = this;

      @Pure
      @Override
      public ReferenceEntry<K, V> getPreviousEvictable() {
        return previousEvictable;
      }

      @Impure
      @Override
      public void setPreviousEvictable(ReferenceEntry<K, V> previous) {
        this.previousEvictable = previous;
      }
    };

    // implements Queue

    @Impure
    @Override
    public boolean offer(ReferenceEntry<K, V> entry) {
      // unlink
      connectEvictables(entry.getPreviousEvictable(), entry.getNextEvictable());

      // add to tail
      connectEvictables(head.getPreviousEvictable(), entry);
      connectEvictables(entry, head);

      return true;
    }

    @Pure
    @Impure
    @Override
    public ReferenceEntry<K, V> peek() {
      ReferenceEntry<K, V> next = head.getNextEvictable();
      return (next == head) ? null : next;
    }

    @Impure
    @Override
    public ReferenceEntry<K, V> poll() {
      ReferenceEntry<K, V> next = head.getNextEvictable();
      if (next == head) {
        return null;
      }

      remove(next);
      return next;
    }

    @Impure
    @Override
    @SuppressWarnings("unchecked")
    public boolean remove(Object o) {
      ReferenceEntry<K, V> e = (ReferenceEntry) o;
      ReferenceEntry<K, V> previous = e.getPreviousEvictable();
      ReferenceEntry<K, V> next = e.getNextEvictable();
      connectEvictables(previous, next);
      nullifyEvictable(e);

      return next != NullEntry.INSTANCE;
    }

    @Pure
    @Impure
    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
      ReferenceEntry<K, V> e = (ReferenceEntry) o;
      return e.getNextEvictable() != NullEntry.INSTANCE;
    }

    @Pure
    @Impure
    @Override
    public boolean isEmpty() {
      return head.getNextEvictable() == head;
    }

    @Pure
    @Impure
    @Override
    public int size() {
      int size = 0;
      for (ReferenceEntry<K, V> e = head.getNextEvictable(); e != head; e = e.getNextEvictable()) {
        size++;
      }
      return size;
    }

    @Impure
    @Override
    public void clear() {
      ReferenceEntry<K, V> e = head.getNextEvictable();
      while (e != head) {
        ReferenceEntry<K, V> next = e.getNextEvictable();
        nullifyEvictable(e);
        e = next;
      }

      head.setNextEvictable(head);
      head.setPreviousEvictable(head);
    }

    @Impure
    @Override
    public Iterator<ReferenceEntry<K, V>> iterator() {
      return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
        @Pure
        @Impure
        @Override
        protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
          ReferenceEntry<K, V> next = previous.getNextEvictable();
          return (next == head) ? null : next;
        }
      };
    }
  }

  /**
   * A custom queue for managing expiration order. Note that this is tightly integrated with
   * {@code ReferenceEntry}, upon which it reliese to perform its linking.
   *
   * <p>Note that this entire implementation makes the assumption that all elements which are in
   * the map are also in this queue, and that all elements not in the queue are not in the map.
   *
   * <p>The benefits of creating our own queue are that (1) we can replace elements in the middle
   * of the queue as part of copyEvictableEntry, and (2) the contains method is highly optimized
   * for the current model.
   */
  static final class ExpirationQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
    final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {

      @Pure
      @Override
      public long getExpirationTime() {
        return Long.MAX_VALUE;
      }

      @SideEffectFree
      @Override
      public void setExpirationTime(long time) {}

      ReferenceEntry<K, V> nextExpirable = this;

      @Pure
      @Override
      public ReferenceEntry<K, V> getNextExpirable() {
        return nextExpirable;
      }

      @Impure
      @Override
      public void setNextExpirable(ReferenceEntry<K, V> next) {
        this.nextExpirable = next;
      }

      ReferenceEntry<K, V> previousExpirable = this;

      @Pure
      @Override
      public ReferenceEntry<K, V> getPreviousExpirable() {
        return previousExpirable;
      }

      @Impure
      @Override
      public void setPreviousExpirable(ReferenceEntry<K, V> previous) {
        this.previousExpirable = previous;
      }
    };

    // implements Queue

    @Impure
    @Override
    public boolean offer(ReferenceEntry<K, V> entry) {
      // unlink
      connectExpirables(entry.getPreviousExpirable(), entry.getNextExpirable());

      // add to tail
      connectExpirables(head.getPreviousExpirable(), entry);
      connectExpirables(entry, head);

      return true;
    }

    @Pure
    @Impure
    @Override
    public ReferenceEntry<K, V> peek() {
      ReferenceEntry<K, V> next = head.getNextExpirable();
      return (next == head) ? null : next;
    }

    @Impure
    @Override
    public ReferenceEntry<K, V> poll() {
      ReferenceEntry<K, V> next = head.getNextExpirable();
      if (next == head) {
        return null;
      }

      remove(next);
      return next;
    }

    @Impure
    @Override
    @SuppressWarnings("unchecked")
    public boolean remove(Object o) {
      ReferenceEntry<K, V> e = (ReferenceEntry) o;
      ReferenceEntry<K, V> previous = e.getPreviousExpirable();
      ReferenceEntry<K, V> next = e.getNextExpirable();
      connectExpirables(previous, next);
      nullifyExpirable(e);

      return next != NullEntry.INSTANCE;
    }

    @Pure
    @Impure
    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
      ReferenceEntry<K, V> e = (ReferenceEntry) o;
      return e.getNextExpirable() != NullEntry.INSTANCE;
    }

    @Pure
    @Impure
    @Override
    public boolean isEmpty() {
      return head.getNextExpirable() == head;
    }

    @Pure
    @Impure
    @Override
    public int size() {
      int size = 0;
      for (ReferenceEntry<K, V> e = head.getNextExpirable(); e != head; e = e.getNextExpirable()) {
        size++;
      }
      return size;
    }

    @Impure
    @Override
    public void clear() {
      ReferenceEntry<K, V> e = head.getNextExpirable();
      while (e != head) {
        ReferenceEntry<K, V> next = e.getNextExpirable();
        nullifyExpirable(e);
        e = next;
      }

      head.setNextExpirable(head);
      head.setPreviousExpirable(head);
    }

    @Impure
    @Override
    public Iterator<ReferenceEntry<K, V>> iterator() {
      return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
        @Pure
        @Impure
        @Override
        protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
          ReferenceEntry<K, V> next = previous.getNextExpirable();
          return (next == head) ? null : next;
        }
      };
    }
  }

  static final class CleanupMapTask implements Runnable {
    final WeakReference<MapMakerInternalMap<?, ?>> mapReference;

    @Impure
    public CleanupMapTask(MapMakerInternalMap<?, ?> map) {
      this.mapReference = new WeakReference<MapMakerInternalMap<?, ?>>(map);
    }

    @Impure
    @Override
    public void run() {
      MapMakerInternalMap<?, ?> map = mapReference.get();
      if (map == null) {
        throw new CancellationException();
      }

      for (Segment<?, ?> segment : map.segments) {
        segment.runCleanup();
      }
    }
  }

  // ConcurrentMap methods

  @Pure
  @Override
  public boolean isEmpty() {
    /*
     * Sum per-segment modCounts to avoid mis-reporting when elements are concurrently added and
     * removed in one segment while checking another, in which case the table was never actually
     * empty at any point. (The sum ensures accuracy up through at least 1<<31 per-segment
     * modifications before recheck.)  Method containsValue() uses similar constructions for
     * stability checks.
     */
    long sum = 0L;
    Segment<K, V>[] segments = this.segments;
    for (int i = 0; i < segments.length; ++i) {
      if (segments[i].count != 0) {
        return false;
      }
      sum += segments[i].modCount;
    }

    if (sum != 0L) { // recheck unless no modifications
      for (int i = 0; i < segments.length; ++i) {
        if (segments[i].count != 0) {
          return false;
        }
        sum -= segments[i].modCount;
      }
      if (sum != 0L) {
        return false;
      }
    }
    return true;
  }

  @Impure
  @Override
  public int size() {
    Segment<K, V>[] segments = this.segments;
    long sum = 0;
    for (int i = 0; i < segments.length; ++i) {
      sum += segments[i].count;
    }
    return Ints.saturatedCast(sum);
  }

  @Impure
  @Override
  public V get(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    int hash = hash(key);
    return segmentFor(hash).get(key, hash);
  }

  /**
   * Returns the internal entry for the specified key. The entry may be computing, expired, or
   * partially collected. Does not impact recency ordering.
   */
  @Impure
  ReferenceEntry<K, V> getEntry(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    int hash = hash(key);
    return segmentFor(hash).getEntry(key, hash);
  }

  @Impure
  @Override
  public boolean containsKey(@Nullable Object key) {
    if (key == null) {
      return false;
    }
    int hash = hash(key);
    return segmentFor(hash).containsKey(key, hash);
  }

  @Impure
  @Override
  public boolean containsValue(@Nullable Object value) {
    if (value == null) {
      return false;
    }

    // This implementation is patterned after ConcurrentHashMap, but without the locking. The only
    // way for it to return a false negative would be for the target value to jump around in the map
    // such that none of the subsequent iterations observed it, despite the fact that at every point
    // in time it was present somewhere int the map. This becomes increasingly unlikely as
    // CONTAINS_VALUE_RETRIES increases, though without locking it is theoretically possible.
    final Segment<K, V>[] segments = this.segments;
    long last = -1L;
    for (int i = 0; i < CONTAINS_VALUE_RETRIES; i++) {
      long sum = 0L;
      for (Segment<K, V> segment : segments) {
        // ensure visibility of most recent completed write
        @SuppressWarnings({"UnusedDeclaration", "unused"})
        int c = segment.count; // read-volatile

        AtomicReferenceArray<ReferenceEntry<K, V>> table = segment.table;
        for (int j = 0; j < table.length(); j++) {
          for (ReferenceEntry<K, V> e = table.get(j); e != null; e = e.getNext()) {
            V v = segment.getLiveValue(e);
            if (v != null && valueEquivalence.equivalent(value, v)) {
              return true;
            }
          }
        }
        sum += segment.modCount;
      }
      if (sum == last) {
        break;
      }
      last = sum;
    }
    return false;
  }

  @Impure
  @Override
  public V put(K key, V value) {
    checkNotNull(key);
    checkNotNull(value);
    int hash = hash(key);
    return segmentFor(hash).put(key, hash, value, false);
  }

  @Impure
  @Override
  public V putIfAbsent(K key, V value) {
    checkNotNull(key);
    checkNotNull(value);
    int hash = hash(key);
    return segmentFor(hash).put(key, hash, value, true);
  }

  @Impure
  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    for (Entry<? extends K, ? extends V> e : m.entrySet()) {
      put(e.getKey(), e.getValue());
    }
  }

  @Impure
  @Override
  public V remove(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    int hash = hash(key);
    return segmentFor(hash).remove(key, hash);
  }

  @Impure
  @Override
  public boolean remove(@Nullable Object key, @Nullable Object value) {
    if (key == null || value == null) {
      return false;
    }
    int hash = hash(key);
    return segmentFor(hash).remove(key, hash, value);
  }

  @Impure
  @Override
  public boolean replace(K key, @Nullable V oldValue, V newValue) {
    checkNotNull(key);
    checkNotNull(newValue);
    if (oldValue == null) {
      return false;
    }
    int hash = hash(key);
    return segmentFor(hash).replace(key, hash, oldValue, newValue);
  }

  @Impure
  @Override
  public V replace(K key, V value) {
    checkNotNull(key);
    checkNotNull(value);
    int hash = hash(key);
    return segmentFor(hash).replace(key, hash, value);
  }

  @Impure
  @Override
  public void clear() {
    for (Segment<K, V> segment : segments) {
      segment.clear();
    }
  }

  transient Set<K> keySet;

  @Impure
  @Override
  public Set<K> keySet() {
    Set<K> ks = keySet;
    return (ks != null) ? ks : (keySet = new KeySet());
  }

  transient Collection<V> values;

  @Impure
  @Override
  public Collection<V> values() {
    Collection<V> vs = values;
    return (vs != null) ? vs : (values = new Values());
  }

  transient Set<Entry<K, V>> entrySet;

  @Impure
  @Override
  public Set<Entry<K, V>> entrySet() {
    Set<Entry<K, V>> es = entrySet;
    return (es != null) ? es : (entrySet = new EntrySet());
  }

  // Iterator Support

  abstract class HashIterator<E> implements Iterator<E> {

    int nextSegmentIndex;
    int nextTableIndex;
    Segment<K, V> currentSegment;
    AtomicReferenceArray<ReferenceEntry<K, V>> currentTable;
    ReferenceEntry<K, V> nextEntry;
    WriteThroughEntry nextExternal;
    WriteThroughEntry lastReturned;

    @Impure
    HashIterator() {
      nextSegmentIndex = segments.length - 1;
      nextTableIndex = -1;
      advance();
    }

    @Impure
    @Override
    public abstract E next();

    @Impure
    final void advance() {
      nextExternal = null;

      if (nextInChain()) {
        return;
      }

      if (nextInTable()) {
        return;
      }

      while (nextSegmentIndex >= 0) {
        currentSegment = segments[nextSegmentIndex--];
        if (currentSegment.count != 0) {
          currentTable = currentSegment.table;
          nextTableIndex = currentTable.length() - 1;
          if (nextInTable()) {
            return;
          }
        }
      }
    }

    /**
     * Finds the next entry in the current chain. Returns {@code true} if an entry was found.
     */
    @Impure
    boolean nextInChain() {
      if (nextEntry != null) {
        for (nextEntry = nextEntry.getNext(); nextEntry != null; nextEntry = nextEntry.getNext()) {
          if (advanceTo(nextEntry)) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Finds the next entry in the current table. Returns {@code true} if an entry was found.
     */
    @Impure
    boolean nextInTable() {
      while (nextTableIndex >= 0) {
        if ((nextEntry = currentTable.get(nextTableIndex--)) != null) {
          if (advanceTo(nextEntry) || nextInChain()) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Advances to the given entry. Returns {@code true} if the entry was valid, {@code false} if it
     * should be skipped.
     */
    @Impure
    boolean advanceTo(ReferenceEntry<K, V> entry) {
      try {
        K key = entry.getKey();
        V value = getLiveValue(entry);
        if (value != null) {
          nextExternal = new WriteThroughEntry(key, value);
          return true;
        } else {
          // Skip stale entry.
          return false;
        }
      } finally {
        currentSegment.postReadCleanup();
      }
    }

    @Pure
    @Override
    public boolean hasNext() {
      return nextExternal != null;
    }

    @Impure
    WriteThroughEntry nextEntry() {
      if (nextExternal == null) {
        throw new NoSuchElementException();
      }
      lastReturned = nextExternal;
      advance();
      return lastReturned;
    }

    @Impure
    @Override
    public void remove() {
      checkRemove(lastReturned != null);
      MapMakerInternalMap.this.remove(lastReturned.getKey());
      lastReturned = null;
    }
  }

  final class KeyIterator extends HashIterator<K> {

    @Impure
    @Override
    public K next() {
      return nextEntry().getKey();
    }
  }

  final class ValueIterator extends HashIterator<V> {

    @Impure
    @Override
    public V next() {
      return nextEntry().getValue();
    }
  }

  /**
   * Custom Entry class used by EntryIterator.next(), that relays setValue changes to the
   * underlying map.
   */
  final class WriteThroughEntry extends AbstractMapEntry<K, V> {
    final K key; // non-null
    V value; // non-null

    @Impure
    WriteThroughEntry(K key, V value) {
      this.key = key;
      this.value = value;
    }

    @Pure
    @Override
    public K getKey() {
      return key;
    }

    @Pure
    @Override
    public V getValue() {
      return value;
    }

    @Pure
    @Override
    public boolean equals(@Nullable Object object) {
      // Cannot use key and value equivalence
      if (object instanceof Entry) {
        Entry<?, ?> that = (Entry<?, ?>) object;
        return key.equals(that.getKey()) && value.equals(that.getValue());
      }
      return false;
    }

    @Pure
    @Override
    public int hashCode() {
      // Cannot use key and value equivalence
      return key.hashCode() ^ value.hashCode();
    }

    @Impure
    @Override
    public V setValue(V newValue) {
      V oldValue = put(key, newValue);
      value = newValue; // only if put succeeds
      return oldValue;
    }
  }

  final class EntryIterator extends HashIterator<Entry<K, V>> {

    @Impure
    @Override
    public Entry<K, V> next() {
      return nextEntry();
    }
  }

  final class KeySet extends AbstractSet<K> {

    @Impure
    @Override
    public Iterator<K> iterator() {
      return new KeyIterator();
    }

    @Pure
    @Override
    public int size() {
      return MapMakerInternalMap.this.size();
    }

    @Pure
    @Override
    public boolean isEmpty() {
      return MapMakerInternalMap.this.isEmpty();
    }

    @Pure
    @Override
    public boolean contains(Object o) {
      return MapMakerInternalMap.this.containsKey(o);
    }

    @Impure
    @Override
    public boolean remove(Object o) {
      return MapMakerInternalMap.this.remove(o) != null;
    }

    @Impure
    @Override
    public void clear() {
      MapMakerInternalMap.this.clear();
    }
  }

  final class Values extends AbstractCollection<V> {

    @Impure
    @Override
    public Iterator<V> iterator() {
      return new ValueIterator();
    }

    @Pure
    @Override
    public int size() {
      return MapMakerInternalMap.this.size();
    }

    @Pure
    @Override
    public boolean isEmpty() {
      return MapMakerInternalMap.this.isEmpty();
    }

    @Pure
    @Override
    public boolean contains(Object o) {
      return MapMakerInternalMap.this.containsValue(o);
    }

    @Impure
    @Override
    public void clear() {
      MapMakerInternalMap.this.clear();
    }
  }

  final class EntrySet extends AbstractSet<Entry<K, V>> {

    @Impure
    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator();
    }

    @Impure
    @Override
    public boolean contains(Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }
      Entry<?, ?> e = (Entry<?, ?>) o;
      Object key = e.getKey();
      if (key == null) {
        return false;
      }
      V v = MapMakerInternalMap.this.get(key);

      return v != null && valueEquivalence.equivalent(e.getValue(), v);
    }

    @Impure
    @Override
    public boolean remove(Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }
      Entry<?, ?> e = (Entry<?, ?>) o;
      Object key = e.getKey();
      return key != null && MapMakerInternalMap.this.remove(key, e.getValue());
    }

    @Pure
    @Override
    public int size() {
      return MapMakerInternalMap.this.size();
    }

    @Pure
    @Override
    public boolean isEmpty() {
      return MapMakerInternalMap.this.isEmpty();
    }

    @Impure
    @Override
    public void clear() {
      MapMakerInternalMap.this.clear();
    }
  }

  // Serialization Support

  private static final long serialVersionUID = 5;

  @Impure
  Object writeReplace() {
    return new SerializationProxy<K, V>(keyStrength, valueStrength, keyEquivalence,
        valueEquivalence, expireAfterWriteNanos, expireAfterAccessNanos, maximumSize,
        concurrencyLevel, removalListener, this);
  }

  /**
   * The actual object that gets serialized. Unfortunately, readResolve() doesn't get called when a
   * circular dependency is present, so the proxy must be able to behave as the map itself.
   */
  abstract static class AbstractSerializationProxy<K, V>
      extends ForwardingConcurrentMap<K, V> implements Serializable {
    private static final long serialVersionUID = 3;

    final Strength keyStrength;
    final Strength valueStrength;
    final Equivalence<Object> keyEquivalence;
    final Equivalence<Object> valueEquivalence;
    final long expireAfterWriteNanos;
    final long expireAfterAccessNanos;
    final int maximumSize;
    final int concurrencyLevel;
    final RemovalListener<? super K, ? super V> removalListener;

    transient ConcurrentMap<K, V> delegate;

    @Impure
    AbstractSerializationProxy(Strength keyStrength, Strength valueStrength,
        Equivalence<Object> keyEquivalence, Equivalence<Object> valueEquivalence,
        long expireAfterWriteNanos, long expireAfterAccessNanos, int maximumSize,
        int concurrencyLevel, RemovalListener<? super K, ? super V> removalListener,
        ConcurrentMap<K, V> delegate) {
      this.keyStrength = keyStrength;
      this.valueStrength = valueStrength;
      this.keyEquivalence = keyEquivalence;
      this.valueEquivalence = valueEquivalence;
      this.expireAfterWriteNanos = expireAfterWriteNanos;
      this.expireAfterAccessNanos = expireAfterAccessNanos;
      this.maximumSize = maximumSize;
      this.concurrencyLevel = concurrencyLevel;
      this.removalListener = removalListener;
      this.delegate = delegate;
    }

    @Pure
    @Override
    protected ConcurrentMap<K, V> delegate() {
      return delegate;
    }

    @Impure
    void writeMapTo(ObjectOutputStream out) throws IOException {
      out.writeInt(delegate.size());
      for (Entry<K, V> entry : delegate.entrySet()) {
        out.writeObject(entry.getKey());
        out.writeObject(entry.getValue());
      }
      out.writeObject(null); // terminate entries
    }

    @Impure
    @SuppressWarnings("deprecation") // serialization of deprecated feature
    MapMaker readMapMaker(ObjectInputStream in) throws IOException {
      int size = in.readInt();
      MapMaker mapMaker = new MapMaker()
          .initialCapacity(size)
          .setKeyStrength(keyStrength)
          .setValueStrength(valueStrength)
          .keyEquivalence(keyEquivalence)
          .concurrencyLevel(concurrencyLevel);
      mapMaker.removalListener(removalListener);
      if (expireAfterWriteNanos > 0) {
        mapMaker.expireAfterWrite(expireAfterWriteNanos, TimeUnit.NANOSECONDS);
      }
      if (expireAfterAccessNanos > 0) {
        mapMaker.expireAfterAccess(expireAfterAccessNanos, TimeUnit.NANOSECONDS);
      }
      if (maximumSize != MapMaker.UNSET_INT) {
        mapMaker.maximumSize(maximumSize);
      }
      return mapMaker;
    }

    @Impure
    @SuppressWarnings("unchecked")
    void readEntries(ObjectInputStream in) throws IOException, ClassNotFoundException {
      while (true) {
        K key = (K) in.readObject();
        if (key == null) {
          break; // terminator
        }
        V value = (V) in.readObject();
        delegate.put(key, value);
      }
    }
  }

  /**
   * The actual object that gets serialized. Unfortunately, readResolve() doesn't get called when a
   * circular dependency is present, so the proxy must be able to behave as the map itself.
   */
  private static final class SerializationProxy<K, V> extends AbstractSerializationProxy<K, V> {
    private static final long serialVersionUID = 3;

    @Impure
    SerializationProxy(Strength keyStrength, Strength valueStrength,
        Equivalence<Object> keyEquivalence, Equivalence<Object> valueEquivalence,
        long expireAfterWriteNanos, long expireAfterAccessNanos, int maximumSize,
        int concurrencyLevel, RemovalListener<? super K, ? super V> removalListener,
        ConcurrentMap<K, V> delegate) {
      super(keyStrength, valueStrength, keyEquivalence, valueEquivalence, expireAfterWriteNanos,
          expireAfterAccessNanos, maximumSize, concurrencyLevel, removalListener, delegate);
    }

    @Impure
    private void writeObject(ObjectOutputStream out) throws IOException {
      out.defaultWriteObject();
      writeMapTo(out);
    }

    @Impure
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
      in.defaultReadObject();
      MapMaker mapMaker = readMapMaker(in);
      delegate = mapMaker.makeMap();
      readEntries(in);
    }

    @Pure
    private Object readResolve() {
      return delegate;
    }
  }
}

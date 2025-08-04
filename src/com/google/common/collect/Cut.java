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

import org.checkerframework.dataflow.qual.Deterministic;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.GwtCompatible;
import com.google.common.primitives.Booleans;

import java.io.Serializable;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

/**
 * Implementation detail for the internal structure of {@link Range} instances. Represents
 * a unique way of "cutting" a "number line" (actually of instances of type {@code C}, not
 * necessarily "numbers") into two sections; this can be done below a certain value, above
 * a certain value, below all values or above all values. With this object defined in this
 * way, an interval can always be represented by a pair of {@code Cut} instances.
 *
 * @author Kevin Bourrillion
 */
@GwtCompatible
abstract class Cut<C extends Comparable> implements Comparable<Cut<C>>, Serializable {
  final C endpoint;

  @SideEffectFree
  Cut(@Nullable C endpoint) {
    this.endpoint = endpoint;
  }

  @Impure
  abstract boolean isLessThan(C value);

  @Pure
  abstract BoundType typeAsLowerBound();
  @Pure
  abstract BoundType typeAsUpperBound();

  @Impure
  abstract Cut<C> withLowerBoundType(BoundType boundType, DiscreteDomain<C> domain);
  @Impure
  abstract Cut<C> withUpperBoundType(BoundType boundType, DiscreteDomain<C> domain);

  @Impure
  abstract void describeAsLowerBound(StringBuilder sb);
  @Impure
  abstract void describeAsUpperBound(StringBuilder sb);

  @Impure
  abstract C leastValueAbove(DiscreteDomain<C> domain);
  @Impure
  abstract C greatestValueBelow(DiscreteDomain<C> domain);

  /*
   * The canonical form is a BelowValue cut whenever possible, otherwise ABOVE_ALL, or
   * (only in the case of types that are unbounded below) BELOW_ALL.
   */
  @Impure
  Cut<C> canonical(DiscreteDomain<C> domain) {
    return this;
  }

  // note: overriden by {BELOW,ABOVE}_ALL
  @Impure
  @Override
  public int compareTo(Cut<C> that) {
    if (that == belowAll()) {
      return 1;
    }
    if (that == aboveAll()) {
      return -1;
    }
    int result = Range.compareOrThrow(endpoint, that.endpoint);
    if (result != 0) {
      return result;
    }
    // same value. below comes before above
    return Booleans.compare(
        this instanceof AboveValue, that instanceof AboveValue);
  }

  @Pure
  C endpoint() {
    return endpoint;
  }

  @SideEffectFree
  @SuppressWarnings("unchecked") // catching CCE
  @Override public boolean equals(Object obj) {
    if (obj instanceof Cut) {
      // It might not really be a Cut<C>, but we'll catch a CCE if it's not
      Cut<C> that = (Cut<C>) obj;
      try {
        int compareResult = compareTo(that);
        return compareResult == 0;
      } catch (ClassCastException ignored) {
      }
    }
    return false;
  }

  /*
   * The implementation neither produces nor consumes any non-null instance of type C, so
   * casting the type parameter is safe.
   */
  @Pure
  @SuppressWarnings("unchecked")
  static <C extends Comparable> Cut<C> belowAll() {
    return (Cut<C>) BelowAll.INSTANCE;
  }

  private static final long serialVersionUID = 0;

  private static final class BelowAll extends Cut<Comparable<?>> {
    private static final BelowAll INSTANCE = new BelowAll();

    @SideEffectFree
    @Impure
    private BelowAll() {
      super(null);
    }
    @Pure
    @Override Comparable<?> endpoint() {
      throw new IllegalStateException("range unbounded on this side");
    }
    @Pure
    @Override boolean isLessThan(Comparable<?> value) {
      return true;
    }
    @Pure
    @Override BoundType typeAsLowerBound() {
      throw new IllegalStateException();
    }
    @Pure
    @Override BoundType typeAsUpperBound() {
      throw new AssertionError("this statement should be unreachable");
    }
    @Pure
    @Override Cut<Comparable<?>> withLowerBoundType(BoundType boundType,
        DiscreteDomain<Comparable<?>> domain) {
      throw new IllegalStateException();
    }
    @Pure
    @Override Cut<Comparable<?>> withUpperBoundType(BoundType boundType,
        DiscreteDomain<Comparable<?>> domain) {
      throw new AssertionError("this statement should be unreachable");
    }
    @Impure
    @Override void describeAsLowerBound(StringBuilder sb) {
      sb.append("(-\u221e");
    }
    @SideEffectFree
    @Override void describeAsUpperBound(StringBuilder sb) {
      throw new AssertionError();
    }
    @Deterministic
    @Impure
    @Override Comparable<?> leastValueAbove(
        DiscreteDomain<Comparable<?>> domain) {
      return domain.minValue();
    }
    @Pure
    @Override Comparable<?> greatestValueBelow(
        DiscreteDomain<Comparable<?>> domain) {
      throw new AssertionError();
    }
    @Impure
    @Override Cut<Comparable<?>> canonical(
        DiscreteDomain<Comparable<?>> domain) {
      try {
        return Cut.<Comparable<?>>belowValue(domain.minValue());
      } catch (NoSuchElementException e) {
        return this;
      }
    }
    @Pure
    @Override public int compareTo(Cut<Comparable<?>> o) {
      return (o == this) ? 0 : -1;
    }
    @Pure
    @Override public String toString() {
      return "-\u221e";
    }
    @Pure
    private Object readResolve() {
      return INSTANCE;
    }
    private static final long serialVersionUID = 0;
  }

  /*
   * The implementation neither produces nor consumes any non-null instance of
   * type C, so casting the type parameter is safe.
   */
  @Pure
  @SuppressWarnings("unchecked")
  static <C extends Comparable> Cut<C> aboveAll() {
    return (Cut<C>) AboveAll.INSTANCE;
  }

  private static final class AboveAll extends Cut<Comparable<?>> {
    private static final AboveAll INSTANCE = new AboveAll();

    @SideEffectFree
    @Impure
    private AboveAll() {
      super(null);
    }
    @Pure
    @Override Comparable<?> endpoint() {
      throw new IllegalStateException("range unbounded on this side");
    }
    @Pure
    @Override boolean isLessThan(Comparable<?> value) {
      return false;
    }
    @Pure
    @Override BoundType typeAsLowerBound() {
      throw new AssertionError("this statement should be unreachable");
    }
    @Pure
    @Override BoundType typeAsUpperBound() {
      throw new IllegalStateException();
    }
    @Pure
    @Override Cut<Comparable<?>> withLowerBoundType(BoundType boundType,
        DiscreteDomain<Comparable<?>> domain) {
      throw new AssertionError("this statement should be unreachable");
    }
    @Pure
    @Override Cut<Comparable<?>> withUpperBoundType(BoundType boundType,
        DiscreteDomain<Comparable<?>> domain) {
      throw new IllegalStateException();
    }
    @SideEffectFree
    @Override void describeAsLowerBound(StringBuilder sb) {
      throw new AssertionError();
    }
    @Impure
    @Override void describeAsUpperBound(StringBuilder sb) {
      sb.append("+\u221e)");
    }
    @Pure
    @Override Comparable<?> leastValueAbove(
        DiscreteDomain<Comparable<?>> domain) {
      throw new AssertionError();
    }
    @Deterministic
    @Impure
    @Override Comparable<?> greatestValueBelow(
        DiscreteDomain<Comparable<?>> domain) {
      return domain.maxValue();
    }
    @Pure
    @Override public int compareTo(Cut<Comparable<?>> o) {
      return (o == this) ? 0 : 1;
    }
    @Pure
    @Override public String toString() {
      return "+\u221e";
    }
    @Pure
    private Object readResolve() {
      return INSTANCE;
    }
    private static final long serialVersionUID = 0;
  }

  @Impure
  static <C extends Comparable> Cut<C> belowValue(C endpoint) {
    return new BelowValue<C>(endpoint);
  }

  private static final class BelowValue<C extends Comparable> extends Cut<C> {
    @Impure
    BelowValue(C endpoint) {
      super(checkNotNull(endpoint));
    }

    @Impure
    @Override boolean isLessThan(C value) {
      return Range.compareOrThrow(endpoint, value) <= 0;
    }
    @Pure
    @Override BoundType typeAsLowerBound() {
      return BoundType.CLOSED;
    }
    @Pure
    @Override BoundType typeAsUpperBound() {
      return BoundType.OPEN;
    }
    @Impure
    @Override Cut<C> withLowerBoundType(BoundType boundType, DiscreteDomain<C> domain) {
      switch (boundType) {
        case CLOSED:
          return this;
        case OPEN:
          @Nullable C previous = domain.previous(endpoint);
          return (previous == null) ? Cut.<C>belowAll() : new AboveValue<C>(previous);
        default:
          throw new AssertionError();
      }
    }
    @Impure
    @Override Cut<C> withUpperBoundType(BoundType boundType, DiscreteDomain<C> domain) {
      switch (boundType) {
        case CLOSED:
          @Nullable C previous = domain.previous(endpoint);
          return (previous == null) ? Cut.<C>aboveAll() : new AboveValue<C>(previous);
        case OPEN:
          return this;
        default:
          throw new AssertionError();
      }
    }
    @Impure
    @Override void describeAsLowerBound(StringBuilder sb) {
      sb.append('[').append(endpoint);
    }
    @Impure
    @Override void describeAsUpperBound(StringBuilder sb) {
      sb.append(endpoint).append(')');
    }
    @Pure
    @Override C leastValueAbove(DiscreteDomain<C> domain) {
      return endpoint;
    }
    @Impure
    @Override C greatestValueBelow(DiscreteDomain<C> domain) {
      return domain.previous(endpoint);
    }
    @Pure
    @Override public int hashCode() {
      return endpoint.hashCode();
    }
    @Pure
    @Override public String toString() {
      return "\\" + endpoint + "/";
    }
    private static final long serialVersionUID = 0;
  }

  @Impure
  static <C extends Comparable> Cut<C> aboveValue(C endpoint) {
    return new AboveValue<C>(endpoint);
  }

  private static final class AboveValue<C extends Comparable> extends Cut<C> {
    @Impure
    AboveValue(C endpoint) {
      super(checkNotNull(endpoint));
    }

    @Impure
    @Override boolean isLessThan(C value) {
      return Range.compareOrThrow(endpoint, value) < 0;
    }
    @Pure
    @Override BoundType typeAsLowerBound() {
      return BoundType.OPEN;
    }
    @Pure
    @Override BoundType typeAsUpperBound() {
      return BoundType.CLOSED;
    }
    @Impure
    @Override Cut<C> withLowerBoundType(BoundType boundType, DiscreteDomain<C> domain) {
      switch (boundType) {
        case OPEN:
          return this;
        case CLOSED:
          @Nullable C next = domain.next(endpoint);
          return (next == null) ? Cut.<C>belowAll() : belowValue(next);
        default:
          throw new AssertionError();
      }
    }
    @Impure
    @Override Cut<C> withUpperBoundType(BoundType boundType, DiscreteDomain<C> domain) {
      switch (boundType) {
        case OPEN:
          @Nullable C next = domain.next(endpoint);
          return (next == null) ? Cut.<C>aboveAll() : belowValue(next);
        case CLOSED:
          return this;
        default:
          throw new AssertionError();
      }
    }
    @Impure
    @Override void describeAsLowerBound(StringBuilder sb) {
      sb.append('(').append(endpoint);
    }
    @Impure
    @Override void describeAsUpperBound(StringBuilder sb) {
      sb.append(endpoint).append(']');
    }
    @Impure
    @Override C leastValueAbove(DiscreteDomain<C> domain) {
      return domain.next(endpoint);
    }
    @Pure
    @Override C greatestValueBelow(DiscreteDomain<C> domain) {
      return endpoint;
    }
    @Impure
    @Override Cut<C> canonical(DiscreteDomain<C> domain) {
      C next = leastValueAbove(domain);
      return (next != null) ? belowValue(next) : Cut.<C>aboveAll();
    }
    @Pure
    @Override public int hashCode() {
      return ~endpoint.hashCode();
    }
    @Pure
    @Override public String toString() {
      return "/" + endpoint + "\\";
    }
    private static final long serialVersionUID = 0;
  }
}

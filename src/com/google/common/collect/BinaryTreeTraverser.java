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

package com.google.common.collect;

import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.Pure;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.Beta;
import com.google.common.annotations.GwtCompatible;
import com.google.common.base.Optional;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.Iterator;

/**
 * A variant of {@link TreeTraverser} for binary trees, providing additional traversals specific to
 * binary trees.
 *
 * @author Louis Wasserman
 * @since 15.0
 */
@Beta
@GwtCompatible(emulated = true)
public abstract class BinaryTreeTraverser<T> extends TreeTraverser<T> {
  // TODO(user): make this GWT-compatible when we've checked in ArrayDeque and BitSet emulation

  /**
   * Returns the left child of the specified node, or {@link Optional#absent()} if the specified
   * node has no left child.
   */
  @Pure
  public abstract Optional<T> leftChild(T root);

  /**
   * Returns the right child of the specified node, or {@link Optional#absent()} if the specified
   * node has no right child.
   */
  @Pure
  public abstract Optional<T> rightChild(T root);

  /**
   * Returns the children of this node, in left-to-right order.
   */
  @Impure
  @Override
  public final Iterable<T> children(final T root) {
    checkNotNull(root);
    return new FluentIterable<T>() {
      @Impure
      @Override
      public Iterator<T> iterator() {
        return new AbstractIterator<T>() {
          boolean doneLeft;
          boolean doneRight;

          @Impure
          @Override
          protected T computeNext() {
            if (!doneLeft) {
              doneLeft = true;
              Optional<T> left = leftChild(root);
              if (left.isPresent()) {
                return left.get();
              }
            }
            if (!doneRight) {
              doneRight = true;
              Optional<T> right = rightChild(root);
              if (right.isPresent()) {
                return right.get();
              }
            }
            return endOfData();
          }
        };
      }
    };
  }

  @Impure
  @Override
  UnmodifiableIterator<T> preOrderIterator(T root) {
    return new PreOrderIterator(root);
  }

  /*
   * Optimized implementation of preOrderIterator for binary trees.
   */
  private final class PreOrderIterator extends UnmodifiableIterator<T>
      implements PeekingIterator<T> {
    private final Deque<T> stack;

    @Impure
    PreOrderIterator(T root) {
      this.stack = new ArrayDeque<T>();
      stack.addLast(root);
    }

    @Pure
    @Override
    public boolean hasNext() {
      return !stack.isEmpty();
    }

    @Impure
    @Override
    public T next() {
      T result = stack.removeLast();
      pushIfPresent(stack, rightChild(result));
      pushIfPresent(stack, leftChild(result));
      return result;
    }

    @Impure
    @Override
    public T peek() {
      return stack.getLast();
    }
  }

  @Impure
  @Override
  UnmodifiableIterator<T> postOrderIterator(T root) {
    return new PostOrderIterator(root);
  }

  /*
   * Optimized implementation of postOrderIterator for binary trees.
   */
  private final class PostOrderIterator extends UnmodifiableIterator<T> {
    private final Deque<T> stack;
    private final BitSet hasExpanded;

    @Impure
    PostOrderIterator(T root) {
      this.stack = new ArrayDeque<T>();
      stack.addLast(root);
      this.hasExpanded = new BitSet();
    }

    @Pure
    @Override
    public boolean hasNext() {
      return !stack.isEmpty();
    }

    @Impure
    @Override
    public T next() {
      while (true) {
        T node = stack.getLast();
        boolean expandedNode = hasExpanded.get(stack.size() - 1);
        if (expandedNode) {
          stack.removeLast();
          hasExpanded.clear(stack.size());
          return node;
        } else {
          hasExpanded.set(stack.size() - 1);
          pushIfPresent(stack, rightChild(node));
          pushIfPresent(stack, leftChild(node));
        }
      }
    }
  }

  // TODO(user): see if any significant optimizations are possible for breadthFirstIterator

  @Impure
  public final FluentIterable<T> inOrderTraversal(final T root) {
    checkNotNull(root);
    return new FluentIterable<T>() {
      @Impure
      @Override
      public UnmodifiableIterator<T> iterator() {
        return new InOrderIterator(root);
      }
    };
  }

  private final class InOrderIterator extends AbstractIterator<T> {
    private final Deque<T> stack;
    private final BitSet hasExpandedLeft;

    @Impure
    InOrderIterator(T root) {
      this.stack = new ArrayDeque<T>();
      this.hasExpandedLeft = new BitSet();
      stack.addLast(root);
    }

    @Impure
    @Override
    protected T computeNext() {
      while (!stack.isEmpty()) {
        T node = stack.getLast();
        if (hasExpandedLeft.get(stack.size() - 1)) {
          stack.removeLast();
          hasExpandedLeft.clear(stack.size());
          pushIfPresent(stack, rightChild(node));
          return node;
        } else {
          hasExpandedLeft.set(stack.size() - 1);
          pushIfPresent(stack, leftChild(node));
        }
      }
      return endOfData();
    }
  }

  @Impure
  private static <T> void pushIfPresent(Deque<T> stack, Optional<T> node) {
    if (node.isPresent()) {
      stack.addLast(node.get());
    }
  }
}

/*
 * Copyright (C) 2011 The Guava Authors
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

package com.google.common.util.concurrent;

import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.Pure;
import com.google.common.collect.ForwardingObject;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An executor service which forwards all its method calls to another executor
 * service. Subclasses should override one or more methods to modify the
 * behavior of the backing executor service as desired per the <a
 * href="http://en.wikipedia.org/wiki/Decorator_pattern">decorator pattern</a>.
 *
 * @author Kurt Alfred Kluever
 * @since 10.0
 */
public abstract class ForwardingExecutorService extends ForwardingObject
    implements ExecutorService {
  /** Constructor for use by subclasses. */
  @Impure
  protected ForwardingExecutorService() {}
  
  @Pure
  @Override
  protected abstract ExecutorService delegate();

  @Impure
  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit)
      throws InterruptedException {
    return delegate().awaitTermination(timeout, unit);
  }

  @Impure
  @Override
  public <T> List<Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks) throws InterruptedException {
    return delegate().invokeAll(tasks);
  }

  @Impure
  @Override
  public <T> List<Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException {
    return delegate().invokeAll(tasks, timeout, unit);
  }

  @Impure
  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException {
    return delegate().invokeAny(tasks);
  }

  @Impure
  @Override
  public <T> T invokeAny(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return delegate().invokeAny(tasks, timeout, unit);
  }

  @Impure
  @Override
  public boolean isShutdown() {
    return delegate().isShutdown();
  }

  @Impure
  @Override
  public boolean isTerminated() {
    return delegate().isTerminated();
  }

  @Impure
  @Override
  public void shutdown() {
    delegate().shutdown();
  }

  @Impure
  @Override
  public List<Runnable> shutdownNow() {
    return delegate().shutdownNow();
  }

  @Impure
  @Override
  public void execute(Runnable command) {
    delegate().execute(command);
  }

  @Impure
  public <T> Future<T> submit(Callable<T> task) {
    return delegate().submit(task);
  }

  @Impure
  @Override
  public Future<?> submit(Runnable task) {
    return delegate().submit(task);
  }

  @Impure
  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    return delegate().submit(task, result);
  }
}

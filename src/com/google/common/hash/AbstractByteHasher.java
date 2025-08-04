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

package com.google.common.hash;

import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.dataflow.qual.Impure;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndexes;

import com.google.common.primitives.Chars;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.common.primitives.Shorts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Abstract {@link Hasher} that handles converting primitives to bytes using a scratch {@code
 * ByteBuffer} and streams all bytes to a sink to compute the hash.
 *
 * @author Colin Decker
 */
abstract class AbstractByteHasher extends AbstractHasher {

  private final ByteBuffer scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

  /**
   * Updates this hasher with the given byte.
   */
  @Impure
  protected abstract void update(byte b);

  /**
   * Updates this hasher with the given bytes.
   */
  @Impure
  protected void update(byte[] b) {
    update(b, 0, b.length);
  }

  /**
   * Updates this hasher with {@code len} bytes starting at {@code off} in the given buffer.
   */
  @Impure
  protected void update(byte[] b, int off, int len) {
    for (int i = off; i < off + len; i++) {
      update(b[i]);
    }
  }

  @Impure
  @Override
  public Hasher putByte(byte b) {
    update(b);
    return this;
  }

  @Impure
  @Override
  public Hasher putBytes(byte[] bytes) {
    checkNotNull(bytes);
    update(bytes);
    return this;
  }

  @Impure
  @Override
  public Hasher putBytes(byte[] bytes, int off, int len) {
    checkPositionIndexes(off, off + len, bytes.length);
    update(bytes, off, len);
    return this;
  }

  /**
   * Updates the sink with the given number of bytes from the buffer.
   */
  @Impure
  private Hasher update(int bytes) {
    try {
      update(scratch.array(), 0, bytes);
    } finally {
      scratch.clear();
    }
    return this;
  }

  @Impure
  @Override
  public Hasher putShort(short s) {
    scratch.putShort(s);
    return update(Shorts.BYTES);
  }

  @Impure
  @Override
  public Hasher putInt(int i) {
    scratch.putInt(i);
    return update(Ints.BYTES);
  }

  @Impure
  @Override
  public Hasher putLong(long l) {
    scratch.putLong(l);
    return update(Longs.BYTES);
  }

  @Impure
  @Override
  public Hasher putChar(char c) {
    scratch.putChar(c);
    return update(Chars.BYTES);
  }

  @SideEffectFree
  @Override
  public <T> Hasher putObject(T instance, Funnel<? super T> funnel) {
    funnel.funnel(instance, this);
    return this;
  }
}

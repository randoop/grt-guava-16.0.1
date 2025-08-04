/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing permissions and limitations
 * under the License.
 */

package com.google.common.math;

import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.Impure;
import com.google.common.annotations.GwtCompatible;

import java.math.BigInteger;

import javax.annotation.Nullable;

/**
 * A collection of preconditions for math functions.
 *
 * @author Louis Wasserman
 */
@GwtCompatible
final class MathPreconditions {
  @Pure
  static int checkPositive(@Nullable String role, int x) {
    if (x <= 0) {
      throw new IllegalArgumentException(role + " (" + x + ") must be > 0");
    }
    return x;
  }

  @Pure
  static long checkPositive(@Nullable String role, long x) {
    if (x <= 0) {
      throw new IllegalArgumentException(role + " (" + x + ") must be > 0");
    }
    return x;
  }

  @Impure
  static BigInteger checkPositive(@Nullable String role, BigInteger x) {
    if (x.signum() <= 0) {
      throw new IllegalArgumentException(role + " (" + x + ") must be > 0");
    }
    return x;
  }

  @Pure
  static int checkNonNegative(@Nullable String role, int x) {
    if (x < 0) {
      throw new IllegalArgumentException(role + " (" + x + ") must be >= 0");
    }
    return x;
  }

  @Pure
  static long checkNonNegative(@Nullable String role, long x) {
    if (x < 0) {
      throw new IllegalArgumentException(role + " (" + x + ") must be >= 0");
    }
    return x;
  }

  @Impure
  static BigInteger checkNonNegative(@Nullable String role, BigInteger x) {
    if (x.signum() < 0) {
      throw new IllegalArgumentException(role + " (" + x + ") must be >= 0");
    }
    return x;
  }

  @Pure
  static double checkNonNegative(@Nullable String role, double x) {
    if (!(x >= 0)) { // not x < 0, to work with NaN.
      throw new IllegalArgumentException(role + " (" + x + ") must be >= 0");
    }
    return x;
  }

  @SideEffectFree
  static void checkRoundingUnnecessary(boolean condition) {
    if (!condition) {
      throw new ArithmeticException("mode was UNNECESSARY, but rounding was necessary");
    }
  }

  @SideEffectFree
  static void checkInRange(boolean condition) {
    if (!condition) {
      throw new ArithmeticException("not in range");
    }
  }

  @SideEffectFree
  static void checkNoOverflow(boolean condition) {
    if (!condition) {
      throw new ArithmeticException("overflow");
    }
  }

  @SideEffectFree
  private MathPreconditions() {}
}

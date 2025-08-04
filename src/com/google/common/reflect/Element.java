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

import org.checkerframework.dataflow.qual.Impure;
import org.checkerframework.dataflow.qual.Pure;
import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.annotation.Nullable;

/**
 * Represents either a {@link Field}, a {@link Method} or a {@link Constructor}.
 * Provides convenience methods such as {@link #isPublic} and {@link #isPackagePrivate}.
 * 
 * @author Ben Yu
 */
class Element extends AccessibleObject implements Member {

  private final AccessibleObject accessibleObject;
  private final Member member;

  @Impure
  <M extends AccessibleObject & Member> Element(M member) {
    checkNotNull(member);
    this.accessibleObject = member;
    this.member = member;
  }

  @Impure
  public TypeToken<?> getOwnerType() {
    return TypeToken.of(getDeclaringClass());
  }

  @Pure
  @Override public final boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
    return accessibleObject.isAnnotationPresent(annotationClass);
  }

  @Impure
  @Override public final <A extends Annotation> A getAnnotation(Class<A> annotationClass) {
    return accessibleObject.getAnnotation(annotationClass);
  }

  @Impure
  @Override public final Annotation[] getAnnotations() {
    return accessibleObject.getAnnotations();
  }

  @Impure
  @Override public final Annotation[] getDeclaredAnnotations() {
    return accessibleObject.getDeclaredAnnotations();
  }

  @Impure
  @Override public final void setAccessible(boolean flag) throws SecurityException {
    accessibleObject.setAccessible(flag);
  }

  @Impure
  @Override public final boolean isAccessible() {
    return accessibleObject.isAccessible();
  }

  @Impure
  @Override public Class<?> getDeclaringClass() {
    return member.getDeclaringClass();
  }

  @Impure
  @Override public final String getName() {
    return member.getName();
  }

  @Impure
  @Override public final int getModifiers() {
    return member.getModifiers();
  }

  @Impure
  @Override public final boolean isSynthetic() {
    return member.isSynthetic();
  }

  /** Returns true if the element is public. */
  @Impure
  public final boolean isPublic() {
    return Modifier.isPublic(getModifiers());
  }

  /** Returns true if the element is protected. */
  @Impure
  public final boolean isProtected() {
    return Modifier.isProtected(getModifiers());
  }

  /** Returns true if the element is package-private. */
  @Impure
  public final boolean isPackagePrivate() {
    return !isPrivate() && !isPublic() && !isProtected();
  }

  /** Returns true if the element is private. */
  @Impure
  public final boolean isPrivate() {
    return Modifier.isPrivate(getModifiers());
  }

  /** Returns true if the element is static. */
  @Impure
  public final boolean isStatic() {
    return Modifier.isStatic(getModifiers());
  }

  /**
   * Returns {@code true} if this method is final, per {@code Modifier.isFinal(getModifiers())}.
   * 
   * <p>Note that a method may still be effectively "final", or non-overridable when it has no
   * {@code final} keyword. For example, it could be private, or it could be declared by a final
   * class. To tell whether a method is overridable, use {@link Invokable#isOverridable}.
   */
  @Impure
  public final boolean isFinal() {
    return Modifier.isFinal(getModifiers());
  }

  /** Returns true if the method is abstract. */
  @Impure
  public final boolean isAbstract() {
    return Modifier.isAbstract(getModifiers());
  }

  /** Returns true if the element is native. */
  @Impure
  public final boolean isNative() {
    return Modifier.isNative(getModifiers());
  }

  /** Returns true if the method is synchronized. */
  @Impure
  public final boolean isSynchronized() {
    return Modifier.isSynchronized(getModifiers());
  }

  /** Returns true if the field is volatile. */
  @Impure
  final boolean isVolatile() {
    return Modifier.isVolatile(getModifiers());
  }

  /** Returns true if the field is transient. */
  @Impure
  final boolean isTransient() {
    return Modifier.isTransient(getModifiers());
  }

  @Impure
  @Override public boolean equals(@Nullable Object obj) {
    if (obj instanceof Element) {
      Element that = (Element) obj;
      return getOwnerType().equals(that.getOwnerType()) && member.equals(that.member);
    }
    return false;
  }

  @Pure
  @Override public int hashCode() {
    return member.hashCode();
  }

  @Impure
  @Override public String toString() {
    return member.toString();
  }
}

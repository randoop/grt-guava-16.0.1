/*
 * Copyright (C) 2009 The Guava Authors
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

package com.google.common.io;

import org.checkerframework.dataflow.qual.Impure;
import java.io.DataInput;
import java.io.IOException;

/**
 * An extension of {@code DataInput} for reading from in-memory byte arrays; its
 * methods offer identical functionality but do not throw {@link IOException}.
 *
 * <p><b>Warning:<b> The caller is responsible for not attempting to read past
 * the end of the array. If any method encounters the end of the array
 * prematurely, it throws {@link IllegalStateException} to signify <i>programmer
 * error</i>. This behavior is a technical violation of the supertype's
 * contract, which specifies a checked exception.
 *
 * @author Kevin Bourrillion
 * @since 1.0
 */
public interface ByteArrayDataInput extends DataInput {
  @Impure
  @Override void readFully(byte b[]);

  @Impure
  @Override void readFully(byte b[], int off, int len);

  @Impure
  @Override int skipBytes(int n);

  @Impure
  @Override boolean readBoolean();

  @Impure
  @Override byte readByte();

  @Impure
  @Override int readUnsignedByte();

  @Impure
  @Override short readShort();

  @Impure
  @Override int readUnsignedShort();

  @Impure
  @Override char readChar();

  @Impure
  @Override int readInt();

  @Impure
  @Override long readLong();

  @Impure
  @Override float readFloat();

  @Impure
  @Override double readDouble();

  @Impure
  @Override String readLine();

  @Impure
  @Override String readUTF();
}

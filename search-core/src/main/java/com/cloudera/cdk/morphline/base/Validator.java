/**
 * Copyright 2013 Cloudera Inc.
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
package com.cloudera.cdk.morphline.base;

import java.util.Arrays;

import com.cloudera.cdk.morphline.api.MorphlineParsingException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.typesafe.config.Config;

/**
 * Simple helper to validate user configurable parameters.
 */
public final class Validator<T> {
  
  /**
   * Validates that the given value is contained in the range [min, max]
   */
  public void validateRange(Config config, T value, Comparable<T> min, Comparable<T>  max) {
    boolean isValid = min.compareTo(value) <= 0 && 0 <= max.compareTo(value);
    if (!isValid) {
      fail(config, String.format("Invalid choice: '%s' (choose from {%s..%s})", value, min, max));
    }
  }
  
  /**
   * Validates that an enum of the given type with the given value exists, and that this enum is
   * contained in the given list of permitted choices; finally returns that enum object.
   */
  public <T extends Enum<T>> T  validateEnum(Config config, String value, Class<T> type, T... choices) {
    if (choices.length == 0) {
      choices = type.getEnumConstants();
    }
    Preconditions.checkArgument(choices.length > 0);
    try {
      T result = Enum.valueOf(type, value);
      if (!Arrays.asList(choices).contains(result)) {
        throw new IllegalArgumentException();
      }
      return result;
    } catch (IllegalArgumentException e) {
      fail(config, String.format("Invalid choice: '%s' (choose from {%s})", value, Joiner.on(",").join(choices)));
      return null; // keep compiler happy
    }
  }
  
  private void fail(Config config, String msg) {
    throw new MorphlineParsingException(msg, config);
  }
  
//  public T validateChoice(Config config, String value, T... choices) {
//    Preconditions.checkArgument(choices.length > 0);
//    int i = Arrays.asList(choices).indexOf(value);
//    if (i >= 0) {
//      return choices[i];    
//    }
//    fail(config, String.format("Invalid choice: '%s' (choose from {%s})", value, Joiner.on(",").join(choices)));
//    return null; // keep compiler happy
//  }

}

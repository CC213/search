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
package com.cloudera.cdk.morphline.cmd;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.AbstractCommand;
import com.typesafe.config.Config;

/**
 * A Not command consists of one nested command, the boolean return value of which is inverted.
 */
public final class NotBuilder implements CommandBuilder {

  @Override
  public String getName() {
    return "not";
  }
  
  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new Not(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private static final class Not extends AbstractCommand {

    private Command realChild;
    
    public Not(Config config, Command parent, Command child, MorphlineContext context) {
      super(config, parent, child, context);
      realChild = buildCommand(config, this, child, context.getCommandBuilders());
    }

    @Override
    protected Command getChild() {
      return realChild;
    }

    @Override
    public boolean process(Record record) {
      return !super.process(record);
    }
  }
  
}

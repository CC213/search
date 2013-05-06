/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloudera.cdk.morphline.stdlib;

import java.util.Collections;
import java.util.List;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.Configs;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.AbstractCommand;
import com.cloudera.cdk.morphline.base.FieldExpression;
import com.typesafe.config.Config;

/**
 * Base class for commands that do slf4j logging; each log level is a subclass.
 */
abstract class LogCommand extends AbstractCommand {
  
  private String format;
  private String[] args;  
  
  public LogCommand(Config config, Command parent, Command child, MorphlineContext context) {
    super(config, parent, child, context);
    this.format = Configs.getString(config, "format");
    List<String> argList = Configs.getStringList(config, "args", Collections.EMPTY_LIST);
    this.args = argList.toArray(new String[argList.size()]);      
  }

  @Override
  public boolean process(Record record) {
    Object[] resolvedArgs = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      resolvedArgs[i] = new FieldExpression(args[i], getConfig()).evaluate(record);
    }
    log(format, resolvedArgs);
    return super.process(record);
  }
  
  protected abstract void log(String format, Object[] args);
     
}

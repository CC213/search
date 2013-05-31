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
package com.cloudera.cdk.morphline.stdlib;

import java.util.Collection;
import java.util.Collections;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.Record;
import com.typesafe.config.Config;

/**
 * Command that logs to slf4j at INFO level.
 */
public final class LogInfoBuilder implements CommandBuilder {

  @Override
  public Collection<String> getNames() {
    return Collections.singletonList("logInfo");
  }
  
  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new LogInfo(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private static final class LogInfo extends LogCommand {
    public LogInfo(Config config, Command parent, Command child, MorphlineContext context) {
      super(config, parent, child, context);
    }
    
    @Override
    public boolean process(Record record) {
      if (LOG.isInfoEnabled()) {
        return super.process(record);
      } else {
        return getChild().process(record);
      }
    }

    @Override
    protected void log(String format, Object[] args) {
      LOG.info(format, args);
    }       
  }    

}

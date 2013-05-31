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

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.Configs;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.AbstractCommand;
import com.cloudera.cdk.morphline.base.Fields;
import com.typesafe.config.Config;

/**
 * A command that sets a universally unique identifier on all records that are intercepted. By
 * default this event header is named "id".
 */
public final class GenerateUUIDBuilder implements CommandBuilder {

  public static final String HEADER_NAME = "headerName";
  public static final String PRESERVE_EXISTING_NAME = "preserveExisting";
  public static final String PREFIX_NAME = "prefix";
  
  @Override
  public Collection<String> getNames() {
    return Collections.singletonList("generateUUID");
  }

  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new GenerateUUID(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private static final class GenerateUUID extends AbstractCommand {
    
    private String headerName;
    private boolean preserveExisting;
    private String prefix;

    public GenerateUUID(Config config, Command parent, Command child, MorphlineContext context) { 
      super(config, parent, child, context);
      this.headerName = Configs.getString(config, HEADER_NAME, Fields.ID);
      this.preserveExisting = Configs.getBoolean(config, PRESERVE_EXISTING_NAME, true);
      this.prefix = Configs.getString(config, PREFIX_NAME, "");
    }

    @Override
    public boolean process(Record record) {      
      if (preserveExisting && record.getFields().containsKey(headerName)) {
        // we must preserve the existing id
      } else if (isMatch(record)) {
        record.replaceValues(headerName, generateUUID());
      }
      return super.process(record);
    }

    protected String getPrefix() {
      return prefix;
    }

    protected String generateUUID() {
      return getPrefix() + UUID.randomUUID().toString();
    }

    protected boolean isMatch(Record event) {
      return true;
    }

  }

}

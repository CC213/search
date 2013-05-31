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
package com.cloudera.cdk.morphline.avro;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Parser;
import org.apache.avro.Schema.Type;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.file.FileReader;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.util.Utf8;
import org.junit.Test;

import com.cloudera.cdk.morphline.api.AbstractMorphlineTest;
import com.cloudera.cdk.morphline.api.MorphlineCompilationException;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.Fields;
import com.cloudera.cdk.morphline.parser.AbstractParser;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

public class AvroMorphlineTest extends AbstractMorphlineTest {
  
  @Test
  public void testAvroArrayUnionDocument() throws Exception {
    Schema documentSchema = Schema.createRecord("Doc", "adoc", null, false);
    List<Field> docFields = new ArrayList<Field>();   
    Schema intArraySchema = Schema.createArray(Schema.create(Type.INT));
    Schema intArrayUnionSchema = Schema.createUnion(Arrays.asList(intArraySchema, Schema.create(Type.NULL)));
    Schema itemListSchema = Schema.createArray(intArrayUnionSchema);
    docFields.add(new Field("price", itemListSchema, null, null));
    documentSchema.setFields(docFields);        
//    System.out.println(documentSchema.toString(true));
    
//    // create record0
    GenericData.Record document0 = new GenericData.Record(documentSchema);
      document0.put("price", new GenericData.Array(itemListSchema, Arrays.asList(
          new GenericData.Array(intArraySchema, Arrays.asList(1, 2, 3, 4, 5)),
          new GenericData.Array(intArraySchema, Arrays.asList(10, 20)),
          null,
          null,
//          new GenericData.Array(intArraySchema, Arrays.asList()),
          new GenericData.Array(intArraySchema, Arrays.asList(100, 200)),
          null
//          new GenericData.Array(intArraySchema, Arrays.asList(1000))
      )));    

    GenericData.Record document1 = new GenericData.Record(documentSchema);
    document1.put("price", new GenericData.Array(itemListSchema, Arrays.asList(
        new GenericData.Array(intArraySchema, Arrays.asList(1000))
    )));    
    
    morphline = createMorphline("test-morphlines/extractAvroPaths");        
    {
      deleteAllDocuments();
      Record record = new Record();
      record.put(Fields.ATTACHMENT_BODY, document0);
      startSession();
  //    System.out.println(documentSchema.toString(true));
  //    System.out.println(document0.toString());
      assertTrue(morphline.process(record));
      assertEquals(1, collector.getRecords().size());
      List expected = Arrays.asList(Arrays.asList(Arrays.asList(1, 2, 3, 4, 5), Arrays.asList(10, 20), null, null, Arrays.asList(100, 200), null)); 
      //List expected2 = Arrays.asList(1, 2, 3, 4, 5, 10, 20, 100, 200); 
      assertEquals(expected, collector.getFirstRecord().get("/price"));
      assertEquals(expected, collector.getFirstRecord().get("/price/[]"));
//      assertEquals(expected, collector.getFirstRecord().get("/*"));
//      assertEquals(expected2, collector.getFirstRecord().get("/*/*"));
      assertEquals(Arrays.asList(), collector.getFirstRecord().get("/unknownField"));
    }
    
    {
      deleteAllDocuments();
      Record record = new Record();
      record.put(Fields.ATTACHMENT_BODY, document1);
      startSession();
  //    System.out.println(documentSchema.toString(true));
  //    System.out.println(document1.toString());
      assertTrue(morphline.process(record));
      assertEquals(1, collector.getRecords().size());
      List expected = Arrays.asList(Arrays.asList(Arrays.asList(1000)));
      assertEquals(expected, collector.getFirstRecord().get("/price"));
      assertEquals(expected, collector.getFirstRecord().get("/price/[]"));
      assertEquals(Arrays.asList(), collector.getFirstRecord().get("/unknownField"));
    }
    
    morphline = createMorphline("test-morphlines/extractAvroPathsFlattened");        
    {
      deleteAllDocuments();
      Record record = new Record();
      record.put(Fields.ATTACHMENT_BODY, document0);
      startSession();
//      System.out.println(documentSchema.toString(true));
//      System.out.println(document0.toString());
      assertTrue(morphline.process(record));
      assertEquals(1, collector.getRecords().size());
      List expected = Arrays.asList(1, 2, 3, 4, 5, 10, 20, 100, 200);
      assertEquals(expected, collector.getFirstRecord().get("/price"));
      assertEquals(expected, collector.getFirstRecord().get("/price/[]"));
      assertEquals(Arrays.asList(), collector.getFirstRecord().get("/unknownField"));
    }
    
    ingestAndVerifyAvro(documentSchema, document0);
    ingestAndVerifyAvro(documentSchema, document0, document1);
    
    Record event = new Record();
    event.getFields().put(Fields.ATTACHMENT_BODY, document0);
    morphline = createMorphline("test-morphlines/extractAvroTree");
    deleteAllDocuments();
    System.out.println(document0);
    assertTrue(load(event));
    assertEquals(1, queryResultSetSize("*:*"));
    Record first = collector.getFirstRecord();    
    AbstractParser.removeAttachments(first);
    assertEquals(Arrays.asList(1, 2, 3, 4, 5, 10, 20, 100, 200), first.get("/price"));
    assertEquals(1, first.getFields().asMap().size());
  }
  
  @Test
  public void testAvroComplexDocuments() throws Exception {
    Schema documentSchema = Schema.createRecord("Document", "adoc", null, false);
    List<Field> docFields = new ArrayList<Field>();
    docFields.add(new Field("docId", Schema.create(Type.INT), null, null));
    
      Schema linksSchema = Schema.createRecord("Links", "alink", null, false);
      List<Field> linkFields = new ArrayList<Field>();
      linkFields.add(new Field("backward", Schema.createArray(Schema.create(Type.INT)), null, null));
      linkFields.add(new Field("forward", Schema.createArray(Schema.create(Type.INT)), null, null));
      linksSchema.setFields(linkFields);
      
      docFields.add(new Field("links", Schema.createUnion(Arrays.asList(linksSchema, Schema.create(Type.NULL))), null, null));
//      docFields.add(new Field("links", linksSchema, null, null));
      
      Schema nameSchema = Schema.createRecord("Name", "aname", null, false);
      List<Field> nameFields = new ArrayList<Field>();
      
        Schema languageSchema = Schema.createRecord("Language", "alanguage", null, false);
        List<Field> languageFields = new ArrayList<Field>();
        languageFields.add(new Field("code", Schema.create(Type.STRING), null, null));
//        docFields.add(new Field("links", Schema.createUnion(Arrays.asList(linksSchema, Schema.create(Type.NULL))), null, null));
        languageFields.add(new Field("country", Schema.createUnion(Arrays.asList(Schema.create(Type.STRING), Schema.create(Type.NULL))), null, null));
        languageSchema.setFields(languageFields);
        
      nameFields.add(new Field("language", Schema.createArray(languageSchema), null, null));
      nameFields.add(new Field("url", Schema.createUnion(Arrays.asList(Schema.create(Type.STRING), Schema.create(Type.NULL))), null, null));              
//      nameFields.add(new Field("url", Schema.create(Type.STRING), null, null));             
      nameSchema.setFields(nameFields);
      
    docFields.add(new Field("name", Schema.createArray(nameSchema), null, null));         
    documentSchema.setFields(docFields);    
    
//    System.out.println(documentSchema.toString(true));
    
    
    
    // create record0
    GenericData.Record document0 = new GenericData.Record(documentSchema);
    document0.put("docId", 10);
    
    GenericData.Record links = new GenericData.Record(linksSchema);
      links.put("forward", new GenericData.Array(linksSchema.getField("forward").schema(), Arrays.asList(20, 40, 60)));
      links.put("backward", new GenericData.Array(linksSchema.getField("backward").schema(), Arrays.asList()));

    document0.put("links", links);
      
      GenericData.Record name0 = new GenericData.Record(nameSchema);
      
        GenericData.Record language0 = new GenericData.Record(languageSchema);
        language0.put("code", "en-us");
        language0.put("country", "us");
        
        GenericData.Record language1 = new GenericData.Record(languageSchema);
        language1.put("code", "en");
        
      name0.put("language", new GenericData.Array(nameSchema.getField("language").schema(), Arrays.asList(language0, language1)));
      name0.put("url", "http://A");
        
      GenericData.Record name1 = new GenericData.Record(nameSchema);
      name1.put("language", new GenericData.Array(nameSchema.getField("language").schema(), Arrays.asList()));
      name1.put("url", "http://B");
      
      GenericData.Record name2 = new GenericData.Record(nameSchema);
      
      GenericData.Record language2 = new GenericData.Record(languageSchema);
      language2.put("code", "en-gb");
      language2.put("country", "gb");
            
      name2.put("language", new GenericData.Array(nameSchema.getField("language").schema(), Arrays.asList(language2)));     
      
    document0.put("name", new GenericData.Array(documentSchema.getField("name").schema(), Arrays.asList(name0, name1, name2)));     
//    System.out.println(document0.toString());

    
    // create record1
    GenericData.Record document1 = new GenericData.Record(documentSchema);
    document1.put("docId", 20);
    
      GenericData.Record links1 = new GenericData.Record(linksSchema);
      links1.put("backward", new GenericData.Array(linksSchema.getField("backward").schema(), Arrays.asList(10, 30)));
      links1.put("forward", new GenericData.Array(linksSchema.getField("forward").schema(), Arrays.asList(80)));

    document1.put("links", links1);
      
      GenericData.Record name4 = new GenericData.Record(nameSchema);      
      name4.put("language", new GenericData.Array(nameSchema.getField("language").schema(), Arrays.asList()));
      name4.put("url", "http://C");
        
    document1.put("name", new GenericData.Array(documentSchema.getField("name").schema(), Arrays.asList(name4)));     
    
    morphline = createMorphline("test-morphlines/extractAvroPaths");        
    {
      deleteAllDocuments();
      Record record = new Record();
      record.put(Fields.ATTACHMENT_BODY, document0);
      startSession();
//      System.out.println(documentSchema.toString(true));
//      System.out.println(document0.toString());
      assertTrue(morphline.process(record));
      assertEquals(1, collector.getRecords().size());
      assertEquals(Arrays.asList(10), collector.getFirstRecord().get("/docId"));
      assertEquals(Arrays.asList(Arrays.asList()), collector.getFirstRecord().get("/links/backward"));
      List expected = Arrays.asList(Arrays.asList(20, 40, 60));
      assertEquals(expected, collector.getFirstRecord().get("/links/forward"));
      assertEquals(expected, collector.getFirstRecord().get("/links/forward/[]"));
      assertEquals(expected, collector.getFirstRecord().get("/links/forward[]"));
      assertEquals(Arrays.asList("en-us", "en", "en-gb"), collector.getFirstRecord().get("/name/[]/language/[]/code"));
      assertEquals(Arrays.asList("en-us", "en", "en-gb"), collector.getFirstRecord().get("/name[]/language[]/code"));
      assertEquals(Arrays.asList("us", "gb"), collector.getFirstRecord().get("/name/[]/language/[]/country"));
      assertEquals(Arrays.asList("us", "gb"), collector.getFirstRecord().get("/name[]/language[]/country"));
      assertEquals(Arrays.asList(), collector.getFirstRecord().get("/unknownField"));
    }

    morphline = createMorphline("test-morphlines/extractAvroPathsFlattened");        
    {
      deleteAllDocuments();
      Record record = new Record();
      record.put(Fields.ATTACHMENT_BODY, document0);
      startSession();
//      System.out.println(documentSchema.toString(true));
//      System.out.println(document0.toString());
      assertTrue(morphline.process(record));
      assertEquals(1, collector.getRecords().size());
      assertEquals(Arrays.asList(10), collector.getFirstRecord().get("/docId"));
      assertEquals(Arrays.asList(), collector.getFirstRecord().get("/links/backward"));
      List expected = Arrays.asList(20, 40, 60);
      assertEquals(expected, collector.getFirstRecord().get("/links/forward"));
      assertEquals(expected, collector.getFirstRecord().get("/links/forward/[]"));
      assertEquals(expected, collector.getFirstRecord().get("/links/forward[]"));
      assertEquals(Arrays.asList("en-us", "en", "en-gb"), collector.getFirstRecord().get("/name/[]/language/[]/code"));
      assertEquals(Arrays.asList("en-us", "en", "en-gb"), collector.getFirstRecord().get("/name[]/language[]/code"));
      assertEquals(Arrays.asList("us", "gb"), collector.getFirstRecord().get("/name/[]/language/[]/country"));
      assertEquals(Arrays.asList("us", "gb"), collector.getFirstRecord().get("/name[]/language[]/country"));
      assertEquals(Arrays.asList(), collector.getFirstRecord().get("/unknownField"));
    }
    
    ingestAndVerifyAvro(documentSchema, document0);
    ingestAndVerifyAvro(documentSchema, document0, document1);
    
    Record event = new Record();
    event.getFields().put(Fields.ATTACHMENT_BODY, document0);
    morphline = createMorphline("test-morphlines/extractAvroTree");
    deleteAllDocuments();
//    System.out.println(document0);
    assertTrue(load(event));
    assertEquals(1, queryResultSetSize("*:*"));
    Record first = collector.getFirstRecord();
    assertEquals(Arrays.asList("us", "gb"), first.get("/name/language/country"));
    assertEquals(Arrays.asList("en-us", "en", "en-gb"), first.get("/name/language/code"));
    assertEquals(Arrays.asList(20, 40, 60), first.get("/links/forward"));
    assertEquals(Arrays.asList("http://A", "http://B"), first.get("/name/url"));
    assertEquals(Arrays.asList(10), first.get("/docId"));
    AbstractParser.removeAttachments(first);
    assertEquals(5, first.getFields().asMap().size());
  }
  
  @Test
  public void testMap() throws Exception {
    Schema schema = new Parser().parse(new File("src/test/resources/test-avro-schemas/intero1.avsc"));
    GenericData.Record document0 = new GenericData.Record(schema);
    Map map = new LinkedHashMap();
    Schema mapRecordSchema = schema.getField("mapField").schema().getValueType();
    GenericData.Record mapRecord = new GenericData.Record(mapRecordSchema); 
    mapRecord.put("label", "nadja");
    map.put(utf8("foo"), mapRecord);
    document0.put("mapField", map);

    morphline = createMorphline("test-morphlines/extractAvroPaths");        
    deleteAllDocuments();
    Record record = new Record();
    record.put(Fields.ATTACHMENT_BODY, document0);
    startSession();
//    System.out.println(schema.toString(true));
//    System.out.println(document0.toString());
    assertTrue(morphline.process(record));
    assertEquals(1, collector.getRecords().size());
    assertEquals(Arrays.asList("nadja"), collector.getFirstRecord().get("/mapField/foo/label"));
    assertEquals(Arrays.asList(), collector.getFirstRecord().get("/unknownField"));

    morphline = createMorphline("test-morphlines/extractAvroPathsFlattened");        
    deleteAllDocuments();
    record = new Record();
    record.put(Fields.ATTACHMENT_BODY, document0);
    startSession();
//      System.out.println(documentSchema.toString(true));
//      System.out.println(document0.toString());
    assertTrue(morphline.process(record));
    assertEquals(1, collector.getRecords().size());
    assertEquals(Arrays.asList("nadja"), collector.getFirstRecord().get("/mapField/foo/label"));
    assertEquals(Arrays.asList(), collector.getFirstRecord().get("/unknownField"));
    
    ingestAndVerifyAvro(schema, document0);
    
    Record event = new Record();
    event.getFields().put(Fields.ATTACHMENT_BODY, document0);
    morphline = createMorphline("test-morphlines/extractAvroTree");
    deleteAllDocuments();
    System.out.println(document0);
    assertTrue(load(event));
    assertEquals(1, queryResultSetSize("*:*"));
    Record first = collector.getFirstRecord();
    assertEquals(Arrays.asList("nadja"), first.get("/mapField/foo/label"));
    AbstractParser.removeAttachments(first);
    assertEquals(1, first.getFields().asMap().size());
  }

  private void ingestAndVerifyAvro(Schema schema, GenericData.Record... records) throws IOException {
    deleteAllDocuments();
    
    GenericDatumWriter datum = new GenericDatumWriter(schema);
    DataFileWriter writer = new DataFileWriter(datum);
    writer.setMeta("Meta-Key0", "Meta-Value0");
    writer.setMeta("Meta-Key1", "Meta-Value1");
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    writer.create(schema, bout);
    for (GenericData.Record record : records) {
      writer.append(record);
    }
    writer.flush();
    writer.close();

    FileReader<GenericData.Record> reader = new DataFileReader(new ReadAvroContainerBuilder.ForwardOnlySeekableInputStream(new ByteArrayInputStream(bout.toByteArray())), new GenericDatumReader());
    Schema schema2 = reader.getSchema();
    assertEquals(schema, schema2);
    GenericData.Record record2 = new GenericData.Record(schema2);    
    for (GenericData.Record record : records) {
      assertTrue(reader.hasNext());
      reader.next(record2);
      assertEquals(record, record2);
    }

    Record event = new Record();
    event.getFields().put(Fields.ATTACHMENT_BODY, new ByteArrayInputStream(bout.toByteArray()));
    morphline = createMorphline("test-morphlines/readAvroContainer");
    deleteAllDocuments();
    assertTrue(load(event));
    assertEquals(records.length, queryResultSetSize("*:*"));

    
    GenericDatumWriter datumWriter = new GenericDatumWriter(schema);
    bout = new ByteArrayOutputStream();
    Encoder encoder = EncoderFactory.get().binaryEncoder(bout, null);
    for (GenericData.Record record : records) {
      datumWriter.write(record, encoder);
    }
    encoder.flush();

    Decoder decoder = DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(bout.toByteArray()), null);
    DatumReader<GenericData.Record> datumReader = new GenericDatumReader<GenericData.Record>(schema);
    for (int i = 0; i < records.length; i++) {
      GenericData.Record record3 = datumReader.read(null, decoder);
      assertEquals(records[i], record3);
    }
    
    event = new Record();
    event.getFields().put(Fields.ATTACHMENT_BODY, new ByteArrayInputStream(bout.toByteArray()));
    File tmp = new File("target/tmp-test-schema.avsc");
    try {
      tmp.deleteOnExit();
      Files.write(schema.toString(true), tmp, Charsets.UTF_8);
      morphline = createMorphline("test-morphlines/readAvroWithExternalSchema");
      deleteAllDocuments();    
      assertTrue(load(event));
      assertEquals(records.length, queryResultSetSize("*:*"));
    } finally {
      tmp.delete();
    }
        
    for (GenericData.Record record : records) {
      event = new Record();
      event.getFields().put(Fields.ATTACHMENT_BODY, record);
      morphline = createMorphline("test-morphlines/extractAvroTree");
      deleteAllDocuments();
      assertTrue(load(event));
      assertEquals(1, queryResultSetSize("*:*"));
    }
  }

  @Test
  public void testReadAvroWithMissingExternalSchema() throws Exception {
    try {
      morphline = createMorphline("test-morphlines/readAvroWithMissingExternalSchema");
      fail();
    } catch (MorphlineCompilationException e) {
      assertTrue(e.getMessage().startsWith(
          "You must specify an external Avro schema because this is required to read containerless Avro"));
    }
  }

  private boolean load(Record record) {
    startSession();
    return morphline.process(record);
  }
  
  private int queryResultSetSize(String query) {
    return collector.getRecords().size();
  }
  
  private static Utf8 utf8(String str) {
    return new Utf8(str);
  }

}

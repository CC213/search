/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.hadoop.tika;


import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.impl.action.HelpArgumentAction;
import net.sourceforge.argparse4j.impl.choice.RangeArgumentChoice;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.FeatureControl;
import net.sourceforge.argparse4j.inf.Namespace;

import org.apache.flume.sink.solr.indexer.TikaIndexer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.solr.hadoop.LineRandomizerMapper;
import org.apache.solr.hadoop.LineRandomizerReducer;
import org.apache.solr.hadoop.SolrInputDocumentWritable;
import org.apache.solr.hadoop.SolrOutputFormat;
import org.apache.solr.hadoop.SolrReducer;
import org.apache.solr.hadoop.TreeMergeMapper;
import org.apache.solr.hadoop.TreeMergeOutputFormat;
import org.apache.solr.hadoop.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Public API for a MapReduce batch job driver that creates a set of Solr index
 * shards from a list of input files and writes the indexes into HDFS, in a
 * flexible, scalable and fault-tolerant manner.
 */
public class TikaIndexerTool extends Configured implements Tool {
  
  Job job; // visible for testing only
  
  public static final String RESULTS_DIR = "results";

  /** A list of input file URLs. Used as input to the Mapper */
  private static final String FULL_INPUT_LIST = "full-input-list.txt";

  private static final Logger LOG = LoggerFactory.getLogger(TikaIndexerTool.class);

  
  /**
   * See http://argparse4j.sourceforge.net and for details see http://argparse4j.sourceforge.net/usage.html
   */
  static final class MyArgumentParser {
    
    /**
     * Parses the given command line arguments.
     * 
     * @return exitCode null indicates the caller shall proceed with processing,
     *         non-null indicates the caller shall exit the program with the
     *         given exit status code.
     */
    public Integer parseArgs(String[] args, FileSystem fs, Options opts) {
      assert args != null;
      assert fs != null;
      assert opts != null;

      if (args.length == 0) {
        args = new String[] { "--help" };
      }
      
      ArgumentParser parser = ArgumentParsers
        .newArgumentParser("hadoop [GenericOptions]... jar search-mr-*-job.jar ", false)
        .defaultHelp(true)
        .description(
          "MapReduce batch job driver that creates a set of Solr index shards from a list of input files " +
          "and writes the indexes into HDFS, in a flexible, scalable and fault-tolerant manner. " +
          "The program proceeds in several consecutive MapReduce based phases, as follows:" +
          "\n\n" +
          "1) Randomization phase: This (parallel) phase randomizes the list of input files in order to spread " +
          "indexing load more evenly among the mappers of the subsequent phase." +  
          "\n\n" +
          "2) Mapper phase: This (parallel) phase takes the input files, extracts the relevant content, transforms it " +
          "and hands SolrInputDocuments to a set of reducers. The ETL functionality is flexible and " +
          "customizable. Parsers for a set of standard data formats such as Avro, CSV, Text, HTML, XML, " +
          "PDF, Word, Excel, etc. are provided out of the box, and additional custom parsers for additional " +
          "file or data formats can be added as Apache Tika plugins. Any kind of data can be detected and indexed - " +
          "a file is an InputStream of any format and parsers for any data format and any custom ETL logic " +
          "can be registered. " +
          "\n\n" +
          "3) Reducer phase: This (parallel) phase loads the mapper's SolrInputDocuments into one EmbeddedSolrServer per reducer. " +
          "Each such reducer and Solr server can be seen as a (micro) shard. The Solr servers store their " +
          "data in HDFS." + 
          "\n\n" +
          "4) Mapper-only merge phase: This (parallel) phase merges the set of reducer shards into the number of solr " +
          "shards expected by the user, using a mapper-only job. This phase is omitted if the number " +
          "of shards is already equal to the number of shards expected by the user. " +
          "\n\n" +
          "5) Go-live phase: This optional (parallel) phase merges the output shards of the previous phase into a set of " +
          "live customer facing Solr servers, typically a SolrCloud.");

      parser.addArgument("--help", "-h")
        .help("Show this help message and exit")
        .action(new HelpArgumentAction() {
          @Override
          public void run(ArgumentParser parser, Argument arg, Map<String, Object> attrs, String flag, Object value) throws ArgumentParserException {
            parser.printHelp(new PrintWriter(System.out));  
            System.out.println();
            System.out.print(ToolRunnerHelpFormatter.getGenericCommandUsage());
            //ToolRunner.printGenericCommandUsage(System.out);
            System.out.println(
              "Examples: \n\n" + 
              "  # Prepare a config jar file containing org/apache/tika/mime/custom-mimetypes.xml and custom mylog4j.properties:\n" +
              "  rm -fr myconfig; mkdir myconfig\n" + 
              "  cp src/test/resources/log4j.properties myconfig/mylog4j.properties\n" + 
              "  cp -r src/test/resources/org myconfig/\n" + 
              "  jar -cMvf myconfig.jar -C myconfig .\n" + 
              "\n" +              
              "  # (Re)index an Avro based Twitter tweet file:\n" +
              "  sudo -u hdfs hadoop \\\n" + 
              "    --config /etc/hadoop/conf.cloudera.mapreduce1 \\\n" +
              "    jar search-mr-*-job.jar \\\n" +
              "    --files src/test/resources/tika-config.xml \\\n" + 
              "    --libjars myconfig.jar \\\n" + 
              "    -D 'mapred.child.java.opts=-Xmx500m -Dlog4j.configuration=mylog4j.properties' \\\n" + 
              "    -D 'mapreduce.child.java.opts=-Xmx500m -Dlog4j.configuration=mylog4j.properties' \\\n" + 
              "    --solrhomedir src/test/resources/solr/minimr \\\n" +
              "    --outputdir hdfs://c2202.halxg.cloudera.com/user/whoschek/test \\\n" + 
              "    --shards 1 \\\n" + 
              "    hdfs:///user/whoschek/test-documents/sample-statuses-20120906-141433.avro\n" +
              "\n" +
              "  # (Re)index all files that match all of the following conditions:\n" +
              "  # 1) File is contained somewhere in the directory tree rooted at hdfs:///user/whoschek/solrloadtest/twitter/tweets\n" +
              "  # 2) file name matches the glob pattern 'sample-statuses*.gz'\n" +
              "  # 3) file was last modified less than 100000 minutes ago\n" +
              "  # 4) file size is between 1 MB and 1 GB\n" +
              "  # Also include extra library jar file containing JSON tweet Java parser:\n" +
              "  hadoop jar target/search-mr-*-job.jar org.apache.solr.hadoop.tika.HdfsFindTool \\\n" + 
              "    -find hdfs:///user/whoschek/solrloadtest/twitter/tweets \\\n" + 
              "    -type f \\\n" + 
              "    -name 'sample-statuses*.gz' \\\n" + 
              "    -mmin -1000000 \\\n" + 
              "    -size -100000000c \\\n" + 
              "    -size +1000000c \\\n" + 
              "  | sudo -u hdfs hadoop \\\n" + 
              "    --config /etc/hadoop/conf.cloudera.mapreduce1 \\\n" + 
              "    jar target/search-mr-*-job.jar \\\n" + 
              "    --files src/test/resources/tika-config.xml \\\n" + 
              "    --libjars myconfig.jar,../search-contrib/target/search-contrib-*-SNAPSHOT.jar \\\n" + 
              "    -D 'mapred.child.java.opts=-Xmx500m -Dlog4j.configuration=mylog4j.properties' \\\n" + 
              "    -D 'mapreduce.child.java.opts=-Xmx500m -Dlog4j.configuration=mylog4j.properties' \\\n" + 
              "    --solrhomedir src/test/resources/solr/minimr \\\n" + 
              "    --outputdir hdfs://c2202.halxg.cloudera.com/user/whoschek/test \\\n" + 
              "    --shards 100 \\\n" + 
              "    --inputlist -"
            );
            throw new FoundHelpArgument(); // Trick to prevent processing of any remaining arguments
          }
        });
      
      Argument inputListArg = parser.addArgument("--inputlist")
        .action(Arguments.append())
        .metavar("URI")
  //      .type(new ArgumentTypes.PathArgumentType(fs).verifyExists().verifyCanRead())
        .type(Path.class)
        .help("Local URI or HDFS URI of a file containing a list of HDFS URIs to index, one URI per line. " + 
              "If '-' is specified, URIs are read from the standard input. " + 
              "Multiple --inputlist arguments can be specified");
      
      Argument outputDirArg = parser.addArgument("--outputdir")
        .metavar("HDFS_URI")
        .type(new ArgumentTypes.PathArgumentType(fs) {
          @Override
          public Path convert(ArgumentParser parser, Argument arg, String value) throws ArgumentParserException {
            Path path = super.convert(parser, arg, value);
            if ("hdfs".equals(path.toUri().getScheme()) && path.toUri().getAuthority() == null) {
              // TODO: consider defaulting to hadoop's fs.default.name here or in SolrRecordWriter.createEmbeddedSolrServer()
              throw new ArgumentParserException("Missing authority in path URI: " + path, parser); 
            }
            return path;
          }
        }.verifyScheme(fs.getScheme()).verifyIsAbsolute().verifyCanWriteParent())
        .required(true)
        .help("HDFS directory to write Solr indexes to");
      
      Argument solrHomeDirArg = parser.addArgument("--solrhomedir")
        .metavar("DIR")
        .type(new ArgumentTypes.FileArgumentType().verifyIsDirectory().verifyCanRead())
        .required(true)
        .help("Local dir containing Solr conf/ and lib/");
  
      Argument mappersArg = parser.addArgument("--mappers")
        .metavar("INTEGER")
        .type(Integer.class)
        .choices(new RangeArgumentChoice(-1, Integer.MAX_VALUE)) // TODO: also support X% syntax where X is an integer
        .setDefault(-1)
        .help("Maximum number of MR mapper tasks to use. -1 indicates use all map slots available on the cluster");
  
      Argument reducersArg = parser.addArgument("--reducers")
        .metavar("INTEGER")
        .type(Integer.class)
        .choices(new RangeArgumentChoice(-1, Integer.MAX_VALUE)) // TODO: also support X% syntax where X is an integer
        .setDefault(-1)
        .help("Number of reducers to index into. -1 indicates use all reduce slots available on the cluster. " +
            "0 indicates use one reducer per output shard, which disables the mtree merge MR algorithm. " +
            "The mtree merge MR algorithm improves scalability by spreading load " +
            "(in particular CPU load) among a number of parallel reducers that can be much larger than the number " +
            "of solr shards expected by the user. It can be seen as an extension of concurrent lucene merges " +
            "and tiered lucene merges to the clustered case. The subsequent mapper-only phase " +
            "merges the output of said large number of reducers to the number of shards expected by the user, " +
            "again by utilizing more available parallelism on the cluster.");
      
      Argument fanoutArg = parser.addArgument("--fanout")
        .metavar("INTEGER")
        .type(Integer.class)
        .choices(new RangeArgumentChoice(2, Integer.MAX_VALUE))
        .setDefault(Integer.MAX_VALUE)
        .help(FeatureControl.SUPPRESS);
      
      Argument shardsArg = parser.addArgument("--shards")
        .metavar("INTEGER")
        .type(Integer.class)
        .choices(new RangeArgumentChoice(1, Integer.MAX_VALUE))
        .setDefault(1)
        .help("Number of output shards to use");
  
      Argument maxSegmentsArg = parser.addArgument("--maxsegments")
        .metavar("INTEGER")
        .type(Integer.class)
        .choices(new RangeArgumentChoice(1, Integer.MAX_VALUE))
        .setDefault(1)
        .help("Maximum number of segments to be contained on output in the index of each reducer shard. " +
            "After a reducer has built its output index it applies a merge policy to merge segments " +
            "until there are <= maxSegments lucene segments left in this index. " + 
            "Merging segments involves reading and rewriting all data in all these segment files, " + 
            "potentially multiple times, which is very I/O intensive and time consuming. " + 
            "However, an index with fewer segments can later be merged faster, " +
            "and it can later be queried faster once deployed to a live Solr serving shard. " + 
            "Set maxSegments to 1 to optimize the index for low query latency. " + 
            "In a nutshell, a small maxSegments value trades indexing latency for subsequently improved query latency. " + 
            "This can be a reasonable trade-off for batch indexing systems.");
      
      Argument fairSchedulerPoolArg = parser.addArgument("--fairschedulerpool")
        .metavar("STRING")
        .help("Name of MR fair scheduler pool to submit jobs to");
  
      Argument verboseArg = parser.addArgument("--verbose", "-v")
        .action(Arguments.storeTrue())
        .help("Turn on verbose output");
  
      Argument noRandomizeArg = parser.addArgument("--norandomize")
        .action(Arguments.storeTrue())
        .help(FeatureControl.SUPPRESS);
  
      // trailing positional arguments
      Argument inputFilesArg = parser.addArgument("inputfiles")
        .metavar("HDFS_URI")
        .type(new ArgumentTypes.PathArgumentType(fs).verifyScheme(fs.getScheme()).verifyExists().verifyCanRead())
        .nargs("*")
        .setDefault()
        .help("HDFS URI of file or dir to index");
          
      Namespace ns;
      try {
        ns = parser.parseArgs(args);
      } catch (FoundHelpArgument e) {
        return 0;
      } catch (ArgumentParserException e) {
        parser.handleError(e);
        return 1;
      }
      LOG.debug("Parsed command line args: {}", ns);
      
      opts.inputLists = ns.getList(inputListArg.getDest());
      if (opts.inputLists == null) {
        opts.inputLists = Collections.EMPTY_LIST;
      }
      opts.inputFiles = ns.getList(inputFilesArg.getDest());
      opts.outputDir = (Path) ns.get(outputDirArg.getDest());
      opts.mappers = ns.getInt(mappersArg.getDest());
      opts.reducers = ns.getInt(reducersArg.getDest());
      opts.fanout = ns.getInt(fanoutArg.getDest());
      opts.shards = ns.getInt(shardsArg.getDest());
      opts.maxSegments = ns.getInt(maxSegmentsArg.getDest());
      opts.solrHomeDir = (File) ns.get(solrHomeDirArg.getDest());
      opts.fairSchedulerPool = (String) ns.get(fairSchedulerPoolArg.getDest());
      opts.isRandomize = !ns.getBoolean(noRandomizeArg.getDest());
      opts.isVerbose = ns.getBoolean(verboseArg.getDest());
      
      if (opts.inputLists.isEmpty() && opts.inputFiles.isEmpty()) {
        LOG.info("No input files specified - nothing to process");
        return 0; // nothing to process
      }
      return null;     
    }
    
    /** Marker trick to prevent processing of any remaining arguments once --help option has been parsed */
    private static final class FoundHelpArgument extends RuntimeException {      
    }
  }
  // END OF INNER CLASS  

  
  static final class Options {    
    List<Path> inputLists;
    List<Path> inputFiles;
    Path outputDir;
    int mappers;
    int reducers;
    int fanout;
    int shards;
    int maxSegments;
    File solrHomeDir;
    String fairSchedulerPool;
    boolean isRandomize;
    boolean isVerbose;
    String zkHost;
    String collection;
    List<String> solrUrls;
  }
  // END OF INNER CLASS  

  
  /** API for command line clients */
  public static void main(String[] args) throws Exception  {
    int res = ToolRunner.run(new Configuration(), new TikaIndexerTool(), args);
    System.exit(res);
  }

  public TikaIndexerTool() {}

  @Override
  public int run(String[] args) throws Exception {
    FileSystem fs = FileSystem.get(getConf());
    Options opts = new Options();
    Integer exitCode = new MyArgumentParser().parseArgs(args, fs, opts);
    if (exitCode != null) {
      return exitCode;
    }
    return run(opts);
  }
  
  /** API for Java clients; visible for testing; may become a public API eventually */
  int run(Options options) throws Exception {
    long programStartTime = System.currentTimeMillis();
    if (options.fairSchedulerPool != null) {
      getConf().set("mapred.fairscheduler.pool", options.fairSchedulerPool);
    }
    getConf().setInt(SolrOutputFormat.SOLR_RECORD_WRITER_MAX_SEGMENTS, options.maxSegments);
    
    // switch off a false warning about allegedly not implementing Tool
    // also see http://hadoop.6.n7.nabble.com/GenericOptionsParser-warning-td8103.html
    getConf().setBoolean("mapred.used.genericoptionsparser", true); 

    job = Job.getInstance(getConf());
    job.setJarByClass(getClass());
    job.setJobName(getClass().getName() + "/" + Utils.getShortClassName(TikaMapper.class));

    int mappers = new JobClient(job.getConfiguration()).getClusterStatus().getMaxMapTasks(); // MR1
    //mappers = job.getCluster().getClusterStatus().getMapSlotCapacity(); // Yarn only
    LOG.info("Cluster reports {} mapper slots", mappers);
    
    if (options.mappers == -1) { 
      mappers = 8 * mappers; // better accomodate stragglers
    } else {
      mappers = options.mappers;
    }
    if (mappers <= 0) {
      throw new IllegalStateException("Illegal number of mappers: " + mappers);
    }
    options.mappers = mappers;
    
    FileSystem fs = options.outputDir.getFileSystem(job.getConfiguration());
    if (fs.exists(options.outputDir) && !delete(options.outputDir, true, fs)) {
      return -1;
    }
    Path outputResultsDir = new Path(options.outputDir, RESULTS_DIR);
    Path outputReduceDir = new Path(options.outputDir, "reducers");
    Path outputStep1Dir = new Path(options.outputDir, "tmp1");    
    Path outputStep2Dir = new Path(options.outputDir, "tmp2");    
    Path outputTreeMergeStep = new Path(options.outputDir, "mtree-merge-output");
    Path fullInputList = new Path(outputStep1Dir, FULL_INPUT_LIST);
    
    LOG.debug("Creating list of input files for mappers: {}", fullInputList);
    long numFiles = addInputFiles(options.inputFiles, options.inputLists, fullInputList, job.getConfiguration());
    if (numFiles == 0) {
      LOG.info("No input files found - nothing to process");
      return 0;
    }
    int numLinesPerSplit = (int) ceilDivide(numFiles, mappers);
    if (numLinesPerSplit < 0) { // numeric overflow from downcasting long to int?
      numLinesPerSplit = Integer.MAX_VALUE;
    }
    numLinesPerSplit = Math.max(1, numLinesPerSplit);

    int realMappers = Math.min(mappers, (int) ceilDivide(numFiles, numLinesPerSplit));
    calculateNumReducers(options, realMappers);
    int reducers = options.reducers;
    LOG.info("Using these parameters: numFiles: {}, mappers: {}, realMappers: {}, reducers: {}, shards: {}, fanout: {}, maxSegments: {}",
        numFiles, mappers, realMappers, reducers, options.shards, options.fanout, options.maxSegments);
        
    if (options.isRandomize) { 
      // there's no benefit in using many parallel mapper tasks just to randomize the order of a few lines;
      // use sequential algorithm below a certain threshold
      // TODO: if there are less than a million lines, reduce latency even more by running main memory sort right away instead of launching a high latency MR job      
      int numLinesPerRandomizerSplit = Math.max(1000 * 1000, numLinesPerSplit);

      long startTime = System.currentTimeMillis();
      Job randomizerJob = randomizeInputFiles(getConf(), fullInputList, outputStep2Dir, numLinesPerRandomizerSplit);
      LOG.info("Randomizing list of {} input files to spread indexing load more evenly among mappers: {}", numFiles, fullInputList);
      if (!waitForCompletion(randomizerJob, options.isVerbose)) {
        return -1; // job failed
      }
      float secs = (System.currentTimeMillis() - startTime) / 1000.0f;
      LOG.info("Done. Randomizing list of {} input files took {} secs", numFiles, secs);
    } else {
      outputStep2Dir = outputStep1Dir;
    }
    
    job.setInputFormatClass(NLineInputFormat.class);
    NLineInputFormat.addInputPath(job, outputStep2Dir);
    NLineInputFormat.setNumLinesPerSplit(job, numLinesPerSplit);    
    FileOutputFormat.setOutputPath(job, outputReduceDir);
    job.setMapperClass(TikaMapper.class);
    job.setReducerClass(SolrReducer.class); 
    if (reducers != options.shards) {
//      job.setPartitionerClass(MyPartitionerX.class); FIXME
    }
    job.setOutputFormatClass(SolrOutputFormat.class);
    SolrOutputFormat.setupSolrHomeCache(options.solrHomeDir, job);      
    job.setNumReduceTasks(reducers);  
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(SolrInputDocumentWritable.class);
    job.getConfiguration().set(TikaIndexer.TIKA_CONFIG_LOCATION, "tika-config.xml");
    LOG.info("Indexing {} files using {} real mappers into {} reducers", numFiles, realMappers, reducers);
    long startTime = System.currentTimeMillis();
    if (!waitForCompletion(job, options.isVerbose)) {
      return -1; // job failed
    }
    float secs = (System.currentTimeMillis() - startTime) / 1000.0f;
    LOG.info("Done. Indexing {} files using {} real mappers into {} reducers took {} secs", numFiles, realMappers, reducers, secs);

    int mtreeMergeIterations = 0;
    if (reducers > options.shards) {
      mtreeMergeIterations = (int) Math.round(log(options.fanout, reducers / options.shards));
    }
    LOG.debug("MTree merge iterations to do: {}", mtreeMergeIterations);
    int mtreeMergeIteration = 1;
    while (reducers > options.shards) { // run a mtree merge iteration
      job = Job.getInstance(getConf());
      job.setJarByClass(getClass());
      job.setJobName(getClass().getName() + "/" + Utils.getShortClassName(TreeMergeMapper.class));
      job.setMapperClass(TreeMergeMapper.class);
      job.setOutputFormatClass(TreeMergeOutputFormat.class);
      job.setNumReduceTasks(0);  
      job.setOutputKeyClass(Text.class);
      job.setOutputValueClass(NullWritable.class);    
      job.setInputFormatClass(NLineInputFormat.class);
      
      Path inputStepDir = new Path(options.outputDir, "mtree-merge-input-iteration" + mtreeMergeIteration);
      fullInputList = new Path(inputStepDir, FULL_INPUT_LIST);    
      LOG.debug("MTree merge iteration {}/{}: Creating input list file for mappers {}", mtreeMergeIteration, mtreeMergeIterations, fullInputList);
      numFiles = createTreeMergeInputDirList(outputReduceDir, fs, fullInputList);    
      if (numFiles != reducers) {
        throw new IllegalStateException("Not same reducers: " + reducers + ", numFiles: " + numFiles);
      }
      NLineInputFormat.addInputPath(job, fullInputList);
      NLineInputFormat.setNumLinesPerSplit(job, options.fanout);    
      FileOutputFormat.setOutputPath(job, outputTreeMergeStep);
      
      LOG.info("MTree merge iteration {}/{}: Merging {} shards into {} shards using fanout {}", mtreeMergeIteration,
          mtreeMergeIterations, reducers, (reducers / options.fanout), options.fanout);
      startTime = System.currentTimeMillis();
      if (!waitForCompletion(job, options.isVerbose)) {
        return -1; // job failed
      }
      secs = (System.currentTimeMillis() - startTime) / 1000.0f;
      LOG.info("MTree merge iteration {}/{}: Done. Merging {} shards into {} shards using fanout {} took {} secs",
          mtreeMergeIteration, mtreeMergeIterations, reducers, (reducers / options.fanout), options.fanout, secs);
      
      if (!delete(outputReduceDir, true, fs)) {
        return -1;
      }
      if (!rename(outputTreeMergeStep, outputReduceDir, fs)) {
        return -1;
      }
      assert reducers % options.fanout == 0;
      reducers = reducers / options.fanout;
      mtreeMergeIteration++;
    }
    assert reducers == options.shards;
    
    // publish results dir
    if (!rename(outputReduceDir, outputResultsDir, fs)) {
      return -1;
    }
    goodbye(job, programStartTime);
    return 0;
  }

  private void calculateNumReducers(Options options, int realMappers) throws IOException {
    if (options.shards <= 0) {
      throw new IllegalStateException("Illegal number of shards: " + options.shards);
    }
    if (options.fanout <= 1) {
      throw new IllegalStateException("Illegal fanout: " + options.fanout);
    }
    if (realMappers <= 0) {
      throw new IllegalStateException("Illegal realMappers: " + realMappers);
    }
    
    int reducers = new JobClient(job.getConfiguration()).getClusterStatus().getMaxReduceTasks(); // MR1
    //reducers = job.getCluster().getClusterStatus().getReduceSlotCapacity(); // Yarn only      
    LOG.info("Cluster reports {} reduce slots", reducers);

    if (options.reducers == 0) {
      reducers = options.shards;
    } else if (options.reducers == -1) {
      reducers = Math.min(reducers, realMappers); // no need to use many reducers when using few mappers
    } else {
      reducers = options.reducers;
    }
    reducers = Math.max(reducers, options.shards);
    
    if (reducers != options.shards) {
      // Ensure fanout isn't misconfigured. fanout can't meaningfully be larger than what would be 
      // required to merge all leaf shards in one single tree merge iteration into root shards
      options.fanout = Math.min(options.fanout, (int) ceilDivide(reducers, options.shards));
      
      // Ensure invariant reducers == options.shards * (fanout ^ N) where N is an integer >= 1.
      // N is the number of mtree merge iterations.
      // This helps to evenly spread docs among root shards and simplifies the impl of the mtree merge algorithm.
      int s = options.shards;
      while (s < reducers) { 
        s = s * options.fanout;
      }
      reducers = s;
      assert reducers % options.fanout == 0;
    }
    options.reducers = reducers;
  }

  /**
   * To uniformly spread load across all mappers we randomize fullInputList
   * with a separate small Mapper & Reducer preprocessing step. This way
   * each input line ends up on a random position in the output file list.
   * Each mapper indexes a disjoint consecutive set of files such that each
   * set has roughly the same size, at least from a probabilistic
   * perspective.
   * 
   * For example an input file with the following input list of URLs:
   * 
   * A
   * B
   * C
   * D
   * 
   * might be randomized into the following output list of URLs:
   * 
   * C
   * A
   * D
   * B
   * 
   * The implementation sorts the list of lines by randomly generated numbers.
   */
  private Job randomizeInputFiles(Configuration baseConfig, Path fullInputList, Path outputStep2Dir, int numLinesPerSplit) throws IOException {
    Job job2 = Job.getInstance(baseConfig);
    job2.setJarByClass(getClass());
    job2.setJobName(getClass().getName() + "/" + Utils.getShortClassName(LineRandomizerMapper.class));
    job2.setInputFormatClass(NLineInputFormat.class);
    NLineInputFormat.addInputPath(job2, fullInputList);
    NLineInputFormat.setNumLinesPerSplit(job2, numLinesPerSplit);          
    job2.setMapperClass(LineRandomizerMapper.class);
    job2.setReducerClass(LineRandomizerReducer.class);
    job2.setOutputFormatClass(TextOutputFormat.class);
    FileOutputFormat.setOutputPath(job2, outputStep2Dir);
    job2.setNumReduceTasks(1);
    job2.setOutputKeyClass(LongWritable.class);
    job2.setOutputValueClass(Text.class);
    return job2;
  }

  private long addInputFiles(List<Path> inputFiles, List<Path> inputLists, Path fullInputList, Configuration conf) throws IOException {
    long numFiles = 0;
    FileSystem fs = fullInputList.getFileSystem(conf);
    FSDataOutputStream out = fs.create(fullInputList);
    try {
      Writer writer = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
      
      for (Path inputFile : inputFiles) {
        FileSystem inputFileFs = inputFile.getFileSystem(conf);
        if (inputFileFs.exists(inputFile)) {
          PathFilter pathFilter = new PathFilter() {      
            @Override
            public boolean accept(Path path) {
              return !path.getName().startsWith("."); // ignore "hidden" files and dirs
            }
          };
          numFiles += addInputFilesRecursively(inputFile, writer, inputFileFs, pathFilter);
        }
      }

      for (Path inputList : inputLists) {
        InputStream in;
        if (inputList.toString().equals("-")) {
          in = System.in;
        } else if (inputList.isAbsoluteAndSchemeAuthorityNull()) {
          in = new BufferedInputStream(new FileInputStream(inputList.toString()));
        } else {
          in = fs.open(inputList);
        }
        try {
          BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
          String line;
          while ((line = reader.readLine()) != null) {
            writer.write(line + "\n");
            numFiles++;
          }
          reader.close();
        } finally {
          in.close();
        }
      }
      
      writer.close();
    } finally {
      out.close();
    }    
    return numFiles;
  }
  
  /**
   * Add the specified file to the input set, if path is a directory then
   * add the files contained therein.
   */
  private long addInputFilesRecursively(Path path, Writer writer, FileSystem fs, PathFilter pathFilter) throws IOException {
    long numFiles = 0;
    for (FileStatus stat : fs.listStatus(path, pathFilter)) {
      LOG.debug("Adding path {}", stat.getPath());
      if (stat.isDirectory()) {
        numFiles += addInputFilesRecursively(stat.getPath(), writer, fs, pathFilter);
      } else {
        writer.write(stat.getPath().toString() + "\n");
        numFiles++;
      }
    }
    return numFiles;
  }
  
  private int createTreeMergeInputDirList(Path outputReduceDir, FileSystem fs, Path fullInputList)
      throws FileNotFoundException, IOException, UnsupportedEncodingException {
    
    FileStatus[] dirs = fs.listStatus(outputReduceDir, new PathFilter() {      
      @Override
      public boolean accept(Path path) {
        return path.getName().startsWith(SolrOutputFormat.getOutputName(job));
      }
    });    
    Arrays.sort(dirs); // FIXME: handle more than 99999 shards (need numeric sort rather than lexicographical sort)
    int numFiles = 0;
    FSDataOutputStream out = fs.create(fullInputList);
    try {
      Writer writer = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
      for (FileStatus stat : dirs) {
        LOG.debug("Adding path {}", stat.getPath());
        if (!stat.isDirectory()) {
          throw new IllegalStateException("Not a directory: " + stat.getPath());
        }
        Path dir = new Path(stat.getPath(), "data/index");
        if (!fs.isDirectory(dir)) {
          throw new IllegalStateException("Not a directory: " + dir);
        }
        writer.write(dir.toString() + "\n");
        numFiles++;
      }
      writer.close();
    } finally {
      out.close();
    }
    return numFiles;
  }

  private boolean waitForCompletion(Job job, boolean isVerbose) throws IOException, InterruptedException, ClassNotFoundException {
    LOG.trace("Running job: " + getJobInfo(job));
    boolean success = job.waitForCompletion(isVerbose);
    if (!success) {
      LOG.error("Job failed! " + getJobInfo(job));
    }
    return success;
  }

  private void goodbye(Job job, long startTime) {
    float secs = (System.currentTimeMillis() - startTime) / 1000.0f;
    LOG.info("Succeeded with job: " + getJobInfo(job));
    LOG.info("Success. Done. Program took {} secs. Goodbye.", secs);
  }

  private String getJobInfo(Job job) {
    return "jobName: " + job.getJobName() + ", jobId: " + job.getJobID();
  }
  
  private boolean rename(Path src, Path dst, FileSystem fs) throws IOException {
    boolean success = fs.rename(src, dst);
    if (!success) {
      LOG.error("Cannot rename " + src + " to " + dst);
    }
    return success;
  }
  
  private boolean delete(Path path, boolean recursive, FileSystem fs) throws IOException {
    boolean success = fs.delete(path, recursive);
    if (!success) {
      LOG.error("Cannot delete " + path);
    }
    return success;
  }

  // same as IntMath.divide(p, q, RoundingMode.CEILING)
  private long ceilDivide(long p, long q) {
    long result = p / q;
    if (p % q != 0) {
      result++;
    }
    return result;
  }
  
  /**
   * Returns <tt>log<sub>base</sub>value</tt>.
   */
  private double log(double base, double value) {
    return Math.log(value) / Math.log(base);
  }

}
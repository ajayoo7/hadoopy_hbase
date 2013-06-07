/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dappervision.hbase.mapred;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.mapred.TableSplit;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.typedbytes.TypedBytesWritable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import com.dappervision.hbase.mapred.TypedBytesTableRecordReader;

/**
 * A Base for {@link TableInputFormat}s. Receives a {@link HTable}, a
 * byte[] of input columns and optionally a {@link Filter}.
 * Subclasses may use other TableRecordReader implementations.
 * <p>
 * An example of a subclass:
 * <pre>
 *   class ExampleTIF extends TableInputFormatBase implements JobConfigurable {
 *
 *     public void configure(JobConf job) {
 *       HTable exampleTable = new HTable(HBaseConfiguration.create(job),
 *         Bytes.toBytes("exampleTable"));
 *       // mandatory
 *       setHTable(exampleTable);
 *       Text[] inputColumns = new byte [][] { Bytes.toBytes("columnA"),
 *         Bytes.toBytes("columnB") };
 *       // mandatory
 *       setInputColumns(inputColumns);
 *       RowFilterInterface exampleFilter = new RegExpRowFilter("keyPrefix.*");
 *       // optional
 *       setRowFilter(exampleFilter);
 *     }
 *
 *     public void validateInput(JobConf job) throws IOException {
 *     }
 *  }
 * </pre>
 */

@Deprecated
public abstract class TypedBytesTableInputFormatBase
implements InputFormat<TypedBytesWritable, TypedBytesWritable> {
  final Log LOG = LogFactory.getLog(TypedBytesTableInputFormatBase.class);
  private byte [][] inputColumns;
  private HTable table;
  private TypedBytesTableRecordReader tableRecordReader;
  private Filter rowFilter;
  private ByteBuffer startRow;
  private ByteBuffer stopRow;

  /**
   * Builds a TableRecordReader. If no TableRecordReader was provided, uses
   * the default.
   *
   * @see org.apache.hadoop.mapred.InputFormat#getRecordReader(InputSplit,
   *      JobConf, Reporter)
   */
  public RecordReader<TypedBytesWritable, TypedBytesWritable> getRecordReader(
      InputSplit split, JobConf job, Reporter reporter)
  throws IOException {
    TableSplit tSplit = (TableSplit) split;
    TypedBytesTableRecordReader trr = this.tableRecordReader;
    // if no table record reader was provided use default
    if (trr == null) {
      trr = new TypedBytesTableRecordReader();
    }
    trr.setStartRow(tSplit.getStartRow());
    trr.setEndRow(tSplit.getEndRow());
    trr.setHTable(this.table);
    trr.setInputColumns(this.inputColumns);
    trr.setRowFilter(this.rowFilter);
    trr.init();
    return trr;
  }

  private String stringFromByteBuffer(ByteBuffer s) {
      byte[] bytes = s.array();
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) {
          sb.append(String.format("%02X ", b));
      }
      return sb.toString();
  }

  // Taken from org/apache/cassandra/utils/ByteBufferUtil.java
  public static int compareUnsigned(ByteBuffer o1, ByteBuffer o2)
  {
      assert o1 != null;
      assert o2 != null;
      if (o1 == o2)
          return 0;
      int end1 = o1.position() + o1.remaining();
      int end2 = o2.position() + o2.remaining();
      for (int i = o1.position(), j = o2.position(); i < end1 && j < end2; i++, j++)
      {
          int a = (o1.get(i) & 0xff);
          int b = (o2.get(j) & 0xff);
          if (a != b)
              return a - b;
      }
      return o1.remaining() - o2.remaining();
  }

  /**
   * Calculates the splits that will serve as input for the map tasks.
   * <ul>
   * Splits are created in number equal to the smallest between numSplits and
   * the number of {@link HRegion}s in the table. If the number of splits is
   * smaller than the number of {@link HRegion}s then splits are spanned across
   * multiple {@link HRegion}s and are grouped the most evenly possible. In the
   * case splits are uneven the bigger splits are placed first in the
   * {@link InputSplit} array.
   *
   * @param job the map task {@link JobConf}
   * @param numSplits a hint to calculate the number of splits (mapred.map.tasks).
   *
   * @return the input splits
   *
   * @see org.apache.hadoop.mapred.InputFormat#getSplits(org.apache.hadoop.mapred.JobConf, int)
   */
  public InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {
    if (this.table == null) {
      throw new IOException("No table was provided");
    }
    byte [][] startKeys = this.table.getStartKeys();
    // NOTE(brandyn): Here we remove regions that are entirely outside of our start/stop rows
    ByteBuffer emptyStartRow = ByteBuffer.wrap(HConstants.EMPTY_START_ROW);
    ArrayList<byte []> startKeysList = new ArrayList<byte []>();
    LOG.info("Target Split: [" + stringFromByteBuffer(startRow) + ", " + stringFromByteBuffer(stopRow) + ")");
    for (int i = 0; i < startKeys.length; i++) {
        ByteBuffer curStartKey = ByteBuffer.wrap(startKeys[i]);
        ByteBuffer curEndKey = ByteBuffer.wrap(((i + 1) < startKeys.length) ? startKeys[i + 1]: HConstants.EMPTY_START_ROW);
        // if cur end row <= start row: This is entirely before the slice we care about
        if (startRow != null && compareUnsigned(curEndKey, startRow) <= 0 && compareUnsigned(curEndKey, emptyStartRow) != 0) {
            //LOG.info("Skipping split ( < start)...");
            continue;
        }
        // if stop row <= cur start row: Slice we care about is entirely before this
        if (stopRow != null && compareUnsigned(stopRow, curStartKey) <= 0) {
            //LOG.info("Skipping split ( > stop)...");
            continue;
        }
        LOG.info("Kept split: [" + stringFromByteBuffer(curStartKey) + ", " + stringFromByteBuffer(curEndKey) + ")");
        startKeysList.add(startKeys[i]);
    }
    startKeys = startKeysList.toArray(new byte[startKeysList.size()][]);

    if (startKeys == null || startKeys.length == 0) {
      throw new IOException("Expecting at least one region");
    }
    if (this.inputColumns == null || this.inputColumns.length == 0) {
      throw new IOException("Expecting at least one column");
    }
    int realNumSplits = numSplits > startKeys.length? startKeys.length:
      numSplits;
    InputSplit[] splits = new InputSplit[realNumSplits];
    int middle = startKeys.length / realNumSplits;
    int startPos = 0;
    int curSplit = 0;
    for (int i = 0; i < realNumSplits; i++) {
      int lastPos = startPos + middle;
      lastPos = startKeys.length % realNumSplits > i ? lastPos + 1 : lastPos;
      String regionLocation = table.getRegionLocation(startKeys[startPos]).
        getServerAddress().getHostname();
      ByteBuffer curStartKey = ByteBuffer.wrap(startKeys[startPos]);
      ByteBuffer curEndKey = ByteBuffer.wrap(((i + 1) < realNumSplits) ? startKeys[lastPos]: HConstants.EMPTY_START_ROW);
      startPos = lastPos;
      // NOTE(brandyn): Truncate splits that overlap start/end row
      if (startRow != null && compareUnsigned(curStartKey, startRow) < 0) {
          LOG.info("Truncating split...");
          curStartKey = startRow;
      }
      if (stopRow != null && (compareUnsigned(curEndKey, stopRow) > 0 || compareUnsigned(curEndKey, emptyStartRow) == 0)) {
          LOG.info("Truncating split...");
          curEndKey = stopRow;
      }
      splits[curSplit] = new TableSplit(this.table.getTableName(), curStartKey.array(), curEndKey.array(), regionLocation);
      LOG.info("split: " + i + "->" + splits[curSplit]);
      curSplit += 1;
    }
    return Arrays.copyOf(splits, curSplit);
  }

  /**
   * @param inputColumns to be passed in {@link Result} to the map task.
   */
  protected void setInputColumns(byte [][] inputColumns) {
    this.inputColumns = inputColumns;
  }

  /**
   * Allows subclasses to get the {@link HTable}.
   */
  protected HTable getHTable() {
    return this.table;
  }

  /**
   * Allows subclasses to set the {@link HTable}.
   *
   * @param table to get the data from
   */
  protected void setHTable(HTable table) {
    this.table = table;
  }

  /**
   * Allows subclasses to set the {@link TableRecordReader}.
   *
   * @param tableRecordReader
   *                to provide other {@link TableRecordReader} implementations.
   */
  protected void setTableRecordReader(TypedBytesTableRecordReader tableRecordReader) {
    this.tableRecordReader = tableRecordReader;
  }

  /**
   * Allows subclasses to set the {@link Filter} to be used.
   *
   * @param rowFilter
   */
  protected void setRowFilter(Filter rowFilter) {
    this.rowFilter = rowFilter;
  }

  protected void setStartRow(byte [] startRow) {
      this.startRow = ByteBuffer.wrap(startRow);
  }

  protected void setStopRow(byte [] stopRow) {
      this.stopRow = ByteBuffer.wrap(stopRow);
  }

}
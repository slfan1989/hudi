/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.hive.ddl;

import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.fs.StorageSchemes;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.hive.HiveSyncConfig;
import org.apache.hudi.hive.HoodieHiveSyncException;
import org.apache.hudi.hive.util.HivePartitionUtil;
import org.apache.hudi.hive.util.HiveSchemaUtil;
import org.apache.hudi.sync.common.model.PartitionValueExtractor;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.parquet.schema.MessageType;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.hudi.hive.HiveSyncConfigHolder.HIVE_SUPPORT_TIMESTAMP_TYPE;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_BASE_PATH;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_DATABASE_NAME;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_PARTITION_EXTRACTOR_CLASS;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_PARTITION_FIELDS;

/**
 * DDLExecutor impl based on HMS which use HMS apis directly for all DDL tasks.
 */
public class HMSDDLExecutor implements DDLExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(HMSDDLExecutor.class);

  private final HiveSyncConfig syncConfig;
  private final String databaseName;
  private final IMetaStoreClient client;
  private final PartitionValueExtractor partitionValueExtractor;

  public HMSDDLExecutor(HiveSyncConfig syncConfig, IMetaStoreClient metaStoreClient) {
    this.syncConfig = syncConfig;
    this.databaseName = syncConfig.getStringOrDefault(META_SYNC_DATABASE_NAME);
    this.client = metaStoreClient;
    String className = syncConfig.getStringOrDefault(META_SYNC_PARTITION_EXTRACTOR_CLASS);
    try {
      this.partitionValueExtractor =
          (PartitionValueExtractor) Class.forName(className).newInstance();
    } catch (Exception e) {
      throw new HoodieHiveSyncException(
          "Failed to initialize PartitionValueExtractor class " + className, e);
    }
  }

  @Override
  public void createDatabase(String databaseName) {
    try {
      Database database = new Database(databaseName, "automatically created by hoodie", null, null);
      client.createDatabase(database);
    } catch (Exception e) {
      LOG.error("Failed to create database {}", databaseName, e);
      throw new HoodieHiveSyncException("Failed to create database " + databaseName, e);
    }
  }

  @Override
  public void createTable(String tableName, MessageType storageSchema, String inputFormatClass, String outputFormatClass,
      String serdeClass, Map<String, String> serdeProperties, Map<String, String> tableProperties) {
    try {
      LinkedHashMap<String, String> mapSchema =
          HiveSchemaUtil.parquetSchemaToMapSchema(storageSchema, syncConfig.getBoolean(HIVE_SUPPORT_TIMESTAMP_TYPE), false);

      List<FieldSchema> fieldSchema = HiveSchemaUtil.convertMapSchemaToHiveFieldSchema(mapSchema, syncConfig);

      List<FieldSchema> partitionSchema = syncConfig.getMetaSyncPartitionFields().stream().map(partitionKey -> {
        String partitionKeyType = HiveSchemaUtil.getPartitionKeyType(mapSchema, partitionKey);
        return new FieldSchema(partitionKey, partitionKeyType.toLowerCase(), "");
      }).collect(Collectors.toList());

      Table newTb = new Table();
      newTb.setDbName(databaseName);
      newTb.setTableName(tableName);
      newTb.setOwner(UserGroupInformation.getCurrentUser().getShortUserName());
      newTb.setCreateTime((int) System.currentTimeMillis());
      StorageDescriptor storageDescriptor = new StorageDescriptor();
      storageDescriptor.setCols(fieldSchema);
      storageDescriptor.setInputFormat(inputFormatClass);
      storageDescriptor.setOutputFormat(outputFormatClass);
      storageDescriptor.setLocation(syncConfig.getMetaSyncBasePath());
      serdeProperties.put("serialization.format", "1");
      storageDescriptor.setSerdeInfo(new SerDeInfo(null, serdeClass, serdeProperties));
      newTb.setSd(storageDescriptor);
      newTb.setPartitionKeys(partitionSchema);

      if (syncConfig.getHiveCreateExternalTable()) {
        newTb.putToParameters("EXTERNAL", "TRUE");
        newTb.setTableType(TableType.EXTERNAL_TABLE.toString());
      }

      for (Map.Entry<String, String> entry : tableProperties.entrySet()) {
        newTb.putToParameters(entry.getKey(), entry.getValue());
      }
      client.createTable(newTb);
    } catch (Exception e) {
      LOG.error("failed to create table {}.", tableName, e);
      throw new HoodieHiveSyncException("failed to create table " + tableName, e);
    }
  }

  @Override
  public void updateTableDefinition(String tableName, MessageType newSchema) {
    try {
      boolean cascade = syncConfig.getSplitStrings(META_SYNC_PARTITION_FIELDS).size() > 0;
      List<FieldSchema> fieldSchema = HiveSchemaUtil.convertParquetSchemaToHiveFieldSchema(newSchema, syncConfig);
      Table table = client.getTable(databaseName, tableName);
      StorageDescriptor sd = table.getSd();
      sd.setCols(fieldSchema);
      table.setSd(sd);
      EnvironmentContext environmentContext = new EnvironmentContext();
      if (cascade) {
        LOG.info("partition table, need cascade");
        environmentContext.putToProperties(StatsSetupConst.CASCADE, StatsSetupConst.TRUE);
      }
      client.alter_table_with_environmentContext(databaseName, tableName, table, environmentContext);
    } catch (Exception e) {
      LOG.error("Failed to update table for " + tableName, e);
      throw new HoodieHiveSyncException("Failed to update table for " + tableName, e);
    }
  }

  @Override
  public Map<String, String> getTableSchema(String tableName) {
    try {
      // HiveMetastoreClient returns partition keys separate from Columns, hence get both and merge to
      // get the Schema of the table.
      final long start = System.currentTimeMillis();
      Table table = this.client.getTable(databaseName, tableName);
      List<FieldSchema> allColumns = new ArrayList<>();
      allColumns.addAll(table.getPartitionKeys());
      allColumns.addAll(table.getSd().getCols());
      Map<String, String> schema = allColumns.stream().collect(
          Collectors.toMap(FieldSchema::getName, f -> f.getType().toUpperCase()));
      final long end = System.currentTimeMillis();
      LOG.info("Time taken to getTableSchema: {} ms.", (end - start));
      return schema;
    } catch (Exception e) {
      LOG.error("{}.{} get table schema failed.", databaseName, tableName);
      throw new HoodieHiveSyncException(databaseName + "." + tableName + " get table schema failed", e);
    }
  }

  @Override
  public void addPartitionsToTable(String tableName, List<String> addPartitions) {
    if (addPartitions.isEmpty()) {
      LOG.info("No partitions to add for {}.", tableName);
      return;
    }
    LOG.info("Adding partitions {} to table {}.", addPartitions.size(), tableName);
    try {
      StorageDescriptor sd = client.getTable(databaseName, tableName).getSd();
      int batchSyncPartitionNum = syncConfig.getHiveBatchSyncPartitionNum();
      for (List<String> batch : CollectionUtils.batches(addPartitions, batchSyncPartitionNum)) {
        List<Partition> partitionList = new ArrayList<>();
        batch.forEach(partition -> {
          StorageDescriptor partitionSd = new StorageDescriptor();
          partitionSd.setCols(sd.getCols());
          partitionSd.setInputFormat(sd.getInputFormat());
          partitionSd.setOutputFormat(sd.getOutputFormat());
          partitionSd.setSerdeInfo(sd.getSerdeInfo());
          String fullPartitionPath = FSUtils.getPartitionPath(syncConfig.getString(META_SYNC_BASE_PATH), partition).toString();
          List<String> partitionValues = partitionValueExtractor.extractPartitionValuesInPath(partition);
          partitionSd.setLocation(fullPartitionPath);
          partitionList.add(new Partition(partitionValues, databaseName, tableName, 0, 0, partitionSd, null));
        });
        client.add_partitions(partitionList, true, false);
        LOG.info("HMSDDLExecutor add a batch partitions done: {}.", partitionList.size());
      }
    } catch (TException e) {
      LOG.error("{}.{} add partition failed.", databaseName, tableName);
      throw new HoodieHiveSyncException(databaseName + "." + tableName + " add partition failed", e);
    }
  }

  @Override
  public void updatePartitionsToTable(String tableName, List<String> changedPartitions) {
    if (changedPartitions.isEmpty()) {
      LOG.info("No partitions to change for {}.", tableName);
      return;
    }
    LOG.info("Changing partitions {} on {}.", changedPartitions.size(), tableName);
    try {
      StorageDescriptor sd = client.getTable(databaseName, tableName).getSd();
      List<Partition> partitionList = changedPartitions.stream().map(partition -> {
        Path partitionPath = FSUtils.getPartitionPath(syncConfig.getString(META_SYNC_BASE_PATH), partition);
        String partitionScheme = partitionPath.toUri().getScheme();
        String fullPartitionPath = StorageSchemes.HDFS.getScheme().equals(partitionScheme)
            ? FSUtils.getDFSFullPartitionPath(syncConfig.getHadoopFileSystem(), partitionPath) : partitionPath.toString();
        List<String> partitionValues = partitionValueExtractor.extractPartitionValuesInPath(partition);
        StorageDescriptor partitionSd = sd.deepCopy();
        partitionSd.setLocation(fullPartitionPath);
        return new Partition(partitionValues, databaseName, tableName, 0, 0, partitionSd, null);
      }).collect(Collectors.toList());
      client.alter_partitions(databaseName, tableName, partitionList, null);
    } catch (TException e) {
      LOG.error("{}.{} update partition failed.", databaseName, tableName, e);
      throw new HoodieHiveSyncException(databaseName + "." + tableName + " update partition failed", e);
    }
  }

  @Override
  public void dropPartitionsToTable(String tableName, List<String> dropPartitions) {
    if (dropPartitions.isEmpty()) {
      LOG.info("No partitions to drop for {}.", tableName);
      return;
    }
    LOG.info("Dropping partitions {} on {}.", dropPartitions.size(), tableName);
    try {
      for (String dropPartition : dropPartitions) {
        HivePartitionUtil.dropPartition(databaseName, tableName, dropPartition,
            partitionValueExtractor, syncConfig, client);
        LOG.info("Drop partition {} on {}.", dropPartition, tableName);
      }
    } catch (TException e) {
      LOG.error("{}.{} drop partition failed.", databaseName, tableName, e);
      throw new HoodieHiveSyncException(databaseName + "." + tableName + " drop partition failed", e);
    }
  }

  @Override
  public void updateTableComments(String tableName, Map<String, Pair<String, String>> alterSchema) {
    try {
      Table table = client.getTable(databaseName, tableName);
      StorageDescriptor sd = new StorageDescriptor(table.getSd());
      for (FieldSchema fieldSchema : sd.getCols()) {
        if (alterSchema.containsKey(fieldSchema.getName())) {
          String comment = alterSchema.get(fieldSchema.getName()).getRight();
          fieldSchema.setComment(comment);
        }
      }
      table.setSd(sd);
      EnvironmentContext environmentContext = new EnvironmentContext();
      client.alter_table_with_environmentContext(databaseName, tableName, table, environmentContext);
      sd.clear();
    } catch (Exception e) {
      LOG.error("{}.{} update table comments failed.", databaseName, tableName, e);
      throw new HoodieHiveSyncException(databaseName + "." + tableName + " update table comments failed", e);
    }
  }

  @Override
  public void close() {
    if (client != null) {
      Hive.closeCurrent();
    }
  }
}

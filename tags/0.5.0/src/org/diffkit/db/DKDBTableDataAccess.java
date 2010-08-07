/**
 * Copyright 2010 Joseph Panico
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.diffkit.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.diffkit.common.DKMapKeyValueComparator;
import org.diffkit.common.DKValidate;
import org.diffkit.util.DKNumberUtil;
import org.diffkit.util.DKSqlUtil;
import org.diffkit.util.DKStringUtil;

/**
 * @author jpanico
 */
public class DKDBTableDataAccess {

   private static final String TABLE_CATALOG_KEY = "TABLE_CATALOG";
   private static final String TABLE_SCHEMA_KEY = "TABLE_SCHEMA";
   private static final String TABLE_NAME_KEY = "TABLE_NAME";

   private final DKDBConnectionSource _connectionSource;
   private final Logger _log = LoggerFactory.getLogger(this.getClass());

   public DKDBTableDataAccess(DKDBConnectionSource connectionSource_) {
      _connectionSource = connectionSource_;
      DKValidate.notNull(_connectionSource);
   }

   /**
    * convenience method that calls getTable(String,String,String)
    * 
    * @throws SQLException
    */
   public DKDBTable getTable(String tableName_) throws SQLException {
      if (tableName_ == null)
         return null;
      String[] elems = tableName_.split("\\.");
      if (elems.length > 2)
         throw new IllegalArgumentException(String.format(
            "to many dot separated in tableName_->%s", tableName_));
      String schemaName = null;
      if (elems.length == 2) {
         schemaName = elems[0];
         tableName_ = elems[1];
      }
      return this.getTable(null, schemaName, tableName_);
   }

   /**
    * assumes that only one Table matches parameters; will throw Exception
    * otherwise
    * 
    * @throws ClassNotFoundException
    * @throws SQLException
    */
   public DKDBTable getTable(String catalog_, String schema_, String tableName_)
      throws SQLException {
      List<DKDBTable> tables = this.getTables(catalog_, schema_, tableName_);
      if ((tables == null) || (tables.isEmpty()))
         return null;
      if (tables.size() > 1)
         throw new RuntimeException(
            String.format(
               "For catalog_->%s schema_->%s tableName_->%s found more than one matchien table->%s",
               catalog_, schema_, tableName_, tables));
      return tables.get(0);
   }

   @SuppressWarnings("unchecked")
   public List<DKDBTable> getTables(String catalog_, String schema_, String tableName_)
      throws SQLException {
      Connection connection = this.getConnection();
      DatabaseMetaData dbMeta = connection.getMetaData();
      List<Map> tableMaps = this.getTableMaps(catalog_, schema_, tableName_, dbMeta);
      if ((tableMaps == null) || (tableMaps.isEmpty()))
         return null;
      List<DKDBTable> tables = new ArrayList<DKDBTable>(tableMaps.size());
      for (Map<String, ?> tableMap : tableMaps) {
         _log.debug("tableMap->{}", tableMap);
         List<Map> columMaps = this.getColumnMaps(tableMap, dbMeta);
         _log.debug("columMaps->{}", columMaps);
         List<Map> pkMaps = this.getPKMaps(tableMap, dbMeta);
         _log.debug("pkMaps->{}", pkMaps);
         DKDBTable table = this.constructTable(tableMap, columMaps, pkMaps);
         _log.debug("table->{}", table);
         tables.add(table);
      }
      DKSqlUtil.close(connection);
      return tables;
   }

   @SuppressWarnings("unchecked")
   private DKDBTable constructTable(Map<String, ?> tableMap_, List<Map> columnMaps_,
                                    List<Map> pkMaps_) {
      String catalogName = (String) tableMap_.get(TABLE_CATALOG_KEY);
      String schemaName = (String) tableMap_.get(TABLE_SCHEMA_KEY);
      String tableName = (String) tableMap_.get(TABLE_NAME_KEY);
      _log.debug("catalogName->{}", catalogName);
      _log.debug("schemaName->{}", schemaName);
      _log.debug("tableName->{}", tableName);
      _log.debug("columnMaps_->{}", columnMaps_);
      _log.debug("pkMaps_->{}", pkMaps_);
      DKDBColumn[] columns = ((columnMaps_ == null) || (columnMaps_.isEmpty())) ? null
         : new DKDBColumn[columnMaps_.size()];
      for (int i = 0; i < columnMaps_.size(); i++) {
         columns[i] = this.constructColumn(columnMaps_.get(i));
         _log.debug("i->{} columns[i]->{}", i, columns[i]);
      }
      DKDBPrimaryKey primaryKey = this.constructPrimaryKey((List<Map>) pkMaps_, columns);

      return new DKDBTable(catalogName, schemaName, tableName, columns, primaryKey);
   }

   private DKDBColumn constructColumn(Map<String, ?> columnMap_) {
      _log.debug("columnMap_->{}", columnMap_);
      String tableName = (String) columnMap_.get("COLUMN_NAME");
      Integer ordinalPosition = (Integer) columnMap_.get("ORDINAL_POSITION");
      String dataTypeName = (String) columnMap_.get("TYPE_NAME");
      Integer columnSize = (Integer) columnMap_.get("COLUMN_SIZE");
      Boolean isNullable = DKStringUtil.parseBoolean((String) columnMap_.get("IS_NULLABLE"));

      return new DKDBColumn(tableName, DKNumberUtil.getInt(ordinalPosition, -1),
         dataTypeName, DKNumberUtil.getInt(columnSize, -1), isNullable);
   }

   @SuppressWarnings("unchecked")
   private DKDBPrimaryKey constructPrimaryKey(List<Map> pkMaps_, DKDBColumn[] columns_) {
      if ((pkMaps_ == null || (pkMaps_.isEmpty())))
         return null;
      List<Map> pkMaps = new ArrayList<Map>(pkMaps_);
      Comparator<Map> ordinalComparator = (Comparator<Map>) new DKMapKeyValueComparator(
         "KEY_SEQ");
      Collections.sort(pkMaps, ordinalComparator);
      String pkName = (String) pkMaps.get(0).get("PK_NAME");
      _log.debug("pkName->{}", pkName);
      String[] keyColumnNames = new String[pkMaps.size()];
      for (int i = 0; i < pkMaps.size(); i++) {
         Map pkMap = pkMaps.get(i);
         String mapName = (String) pkMap.get("PK_NAME");
         if (!mapName.equals(pkName))
            throw new RuntimeException(String.format("more than one pkName->%s, %s",
               pkName, mapName));
         keyColumnNames[i] = (String) pkMap.get("COLUMN_NAME");
      }
      return new DKDBPrimaryKey(pkName, keyColumnNames);
   }

   @SuppressWarnings("unchecked")
   private List<Map> getColumnMaps(Map<String, ?> tableMap_, DatabaseMetaData dbMeta_)
      throws SQLException {
      String catalogName = (String) tableMap_.get(TABLE_CATALOG_KEY);
      String schemaName = (String) tableMap_.get(TABLE_SCHEMA_KEY);
      String tableName = (String) tableMap_.get(TABLE_NAME_KEY);
      _log.debug("catalogName->{}", catalogName);
      _log.debug("schemaName->{}", schemaName);
      _log.debug("tableName->{}", tableName);

      ResultSet columnsRS = dbMeta_.getColumns(catalogName, schemaName, tableName, null);
      List<Map> columnMaps = DKSqlUtil.readRows(columnsRS);
      _log.debug("columnMaps->{}", columnMaps);
      DKSqlUtil.close(columnsRS);
      return columnMaps;
   }

   @SuppressWarnings("unchecked")
   private List<Map> getTableMaps(String catalog_, String schema_, String tableName_,
                                  DatabaseMetaData dbMeta_) throws SQLException {
      _log.debug("catalog_->{}", catalog_);
      _log.debug("schema_->{}", schema_);
      _log.debug("tableName_->{}", tableName_);
      ResultSet tablesRS = dbMeta_.getTables(catalog_, schema_, tableName_, null);
      _log.debug("tablesRS->{}", tablesRS);
      if (tablesRS == null) {
         _log.warn("no tablesRS for catalog_->{} schema_->{} tableName_->{}");
         return null;
      }
      List<Map> tableMaps = DKSqlUtil.readRows(tablesRS);
      _log.debug("tableMaps->{}", tableMaps);
      DKSqlUtil.close(tablesRS);
      return tableMaps;
   }

   @SuppressWarnings("unchecked")
   private List<Map> getPKMaps(Map<String, ?> tableMap_, DatabaseMetaData dbMeta_)
      throws SQLException {
      String catalogName = (String) tableMap_.get(TABLE_CATALOG_KEY);
      String schemaName = (String) tableMap_.get(TABLE_SCHEMA_KEY);
      String tableName = (String) tableMap_.get(TABLE_NAME_KEY);
      _log.debug("catalogName->{}", catalogName);
      _log.debug("schemaName->{}", schemaName);
      _log.debug("tableName->{}", tableName);
      ResultSet primaryKeyRS = dbMeta_.getPrimaryKeys(catalogName, schemaName, tableName);
      _log.debug("primaryKeyRS->{}", primaryKeyRS);
      if (primaryKeyRS == null) {
         _log.warn("no primaryKeyRS for catalog_->{} schema_->{} tableName_->{}");
         return null;
      }
      List<Map> pkMaps = DKSqlUtil.readRows(primaryKeyRS);
      _log.debug("pkMaps->{}", pkMaps);
      DKSqlUtil.close(primaryKeyRS);
      return pkMaps;
   }

   private Connection getConnection() throws SQLException {
      return _connectionSource.getConnection();
   }
}

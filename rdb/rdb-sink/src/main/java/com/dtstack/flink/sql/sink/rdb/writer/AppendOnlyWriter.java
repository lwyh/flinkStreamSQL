/*
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

package com.dtstack.flink.sql.sink.rdb.writer;

import com.dtstack.flink.sql.sink.MetricOutputFormat;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static com.dtstack.flink.sql.sink.rdb.JDBCTypeConvertUtils.setRecordToStatement;

/**
 * Just append record to jdbc, can not receive retract/delete message.
 */
public class AppendOnlyWriter implements JDBCWriter {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(AppendOnlyWriter.class);

    private final String insertSQL;
    private final int[] fieldTypes;

    private transient PreparedStatement statement;
    private transient List<Row> rows;
    // only use metric
    private transient MetricOutputFormat metricOutputFormat;

    public AppendOnlyWriter(String insertSQL, int[] fieldTypes, MetricOutputFormat metricOutputFormat) {
        this.insertSQL = insertSQL;
        this.fieldTypes = fieldTypes;
        this.metricOutputFormat = metricOutputFormat;
    }

    @Override
    public void open(Connection connection) throws SQLException {
        this.rows = new ArrayList();
        this.statement = connection.prepareStatement(insertSQL);
    }

    /**
     *   Append mode retract/delete message will not execute
     * @param record
     * @throws SQLException
     */
    @Override
    public void addRecord(Tuple2<Boolean, Row> record) throws SQLException {
        if (!record.f0) {
            return;
        }
        rows.add(record.f1);
    }

    @Override
    public void executeBatch(Connection connection) throws SQLException {
        try {
            rows.forEach(row -> {
                try {
                    setRecordToStatement(statement, fieldTypes, row);
                    statement.addBatch();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            statement.executeBatch();
            connection.commit();
            rows.clear();
        } catch (Exception e) {
            LOG.debug("AppendOnlyWriter executeBatch error ", e);
            cleanBatchWhenError();
            executeUpdate(connection);
        }
    }


    @Override
    public void executeUpdate(Connection connection) {
        rows.forEach(row -> {
            try {
                setRecordToStatement(statement, fieldTypes, row);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                metricOutputFormat.outDirtyRecords.inc();
                if (metricOutputFormat.outDirtyRecords.getCount() % DIRTYDATA_PRINT_FREQUENTY == 0 || LOG.isDebugEnabled()) {
                    LOG.error("record insert failed ,this row is {}", row.toString());
                    LOG.error("", e);
                }
            }
        });
        rows.clear();
    }


    @Override
    public void cleanBatchWhenError() throws SQLException {
        statement.clearBatch();
    }

    @Override
    public void close() throws SQLException {
        if (statement != null) {
            statement.close();
            statement = null;
        }
    }

}

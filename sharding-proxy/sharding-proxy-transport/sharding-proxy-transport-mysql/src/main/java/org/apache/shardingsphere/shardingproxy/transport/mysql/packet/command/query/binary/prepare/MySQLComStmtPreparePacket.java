/*
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

package org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.binary.prepare;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.core.parsing.SQLParsingEngine;
import org.apache.shardingsphere.core.parsing.parser.sql.SQLStatement;
import org.apache.shardingsphere.core.parsing.parser.sql.dml.insert.InsertStatement;
import org.apache.shardingsphere.core.parsing.parser.sql.dql.select.SelectStatement;
import org.apache.shardingsphere.core.rule.ShardingRule;
import org.apache.shardingsphere.shardingproxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.shardingproxy.backend.schema.LogicSchema;
import org.apache.shardingsphere.shardingproxy.backend.schema.LogicSchemas;
import org.apache.shardingsphere.shardingproxy.backend.schema.MasterSlaveSchema;
import org.apache.shardingsphere.shardingproxy.backend.schema.ShardingSchema;
import org.apache.shardingsphere.shardingproxy.transport.mysql.constant.MySQLColumnType;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.MySQLPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.MySQLCommandPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.MySQLColumnDefinition41Packet;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.binary.MySQLBinaryStatementRegistry;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.generic.MySQLEofPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.payload.MySQLPacketPayload;

import java.util.Collection;
import java.util.LinkedList;

/**
 * MySQL COM_STMT_PREPARE command packet.
 * 
 * @see <a href="https://dev.mysql.com/doc/internals/en/com-stmt-prepare.html">COM_STMT_PREPARE</a>
 *
 * @author zhangliang
 */
@Slf4j
public final class MySQLComStmtPreparePacket implements MySQLCommandPacket {
    
    private static final MySQLBinaryStatementRegistry PREPARED_STATEMENT_REGISTRY = MySQLBinaryStatementRegistry.getInstance();
    
    private final String schemaName;
    
    private final String sql;
    
    private final SQLParsingEngine sqlParsingEngine;
    
    public MySQLComStmtPreparePacket(final BackendConnection backendConnection, final MySQLPacketPayload payload) {
        sql = payload.readStringEOF();
        schemaName = backendConnection.getSchemaName();
        LogicSchema logicSchema = backendConnection.getLogicSchema();
        // TODO we should use none-sharding parsing engine in future.
        sqlParsingEngine = new SQLParsingEngine(LogicSchemas.getInstance().getDatabaseType(), sql, getShardingRule(logicSchema), logicSchema.getMetaData().getTable());
    }
    
    private ShardingRule getShardingRule(final LogicSchema logicSchema) {
        return logicSchema instanceof MasterSlaveSchema ? ((MasterSlaveSchema) logicSchema).getDefaultShardingRule() : ((ShardingSchema) logicSchema).getShardingRule();
    }
    
    @Override
    public void write(final MySQLPacketPayload payload) {
        payload.writeStringEOF(sql);
    }
    
    @Override
    public Collection<MySQLPacket> execute() {
        log.debug("COM_STMT_PREPARE received for Sharding-Proxy: {}", sql);
        Collection<MySQLPacket> result = new LinkedList<>();
        int currentSequenceId = 0;
        SQLStatement sqlStatement = sqlParsingEngine.parse(true);
        int parametersIndex = sqlStatement.getParametersIndex();
        result.add(new MySQLComStmtPrepareOKPacket(++currentSequenceId, PREPARED_STATEMENT_REGISTRY.register(sql, parametersIndex), getNumColumns(sqlStatement), parametersIndex, 0));
        for (int i = 0; i < parametersIndex; i++) {
            // TODO add column name
            result.add(new MySQLColumnDefinition41Packet(++currentSequenceId, schemaName,
                    sqlStatement.getTables().isSingleTable() ? sqlStatement.getTables().getSingleTableName() : "", "", "", "", 100, MySQLColumnType.MYSQL_TYPE_VARCHAR, 0));
        }
        if (parametersIndex > 0) {
            result.add(new MySQLEofPacket(++currentSequenceId));
        }
        // TODO add If numColumns > 0
        return result;
    }
    
    private int getNumColumns(final SQLStatement sqlStatement) {
        if (sqlStatement instanceof SelectStatement) {
            return ((SelectStatement) sqlStatement).getItems().size();
        }
        if (sqlStatement instanceof InsertStatement) {
            return ((InsertStatement) sqlStatement).getColumns().size();
        }
        return 0;
    }
    
    @Override
    public int getSequenceId() {
        return 0;
    }
}

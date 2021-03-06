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

import com.google.common.base.Optional;
import lombok.SneakyThrows;
import org.apache.shardingsphere.core.constant.ShardingConstant;
import org.apache.shardingsphere.core.metadata.ShardingMetaData;
import org.apache.shardingsphere.core.parsing.SQLParsingEngine;
import org.apache.shardingsphere.core.parsing.cache.ParsingResultCache;
import org.apache.shardingsphere.core.parsing.parser.context.selectitem.CommonSelectItem;
import org.apache.shardingsphere.core.parsing.parser.context.table.Table;
import org.apache.shardingsphere.core.parsing.parser.dialect.mysql.statement.ShowTablesStatement;
import org.apache.shardingsphere.core.parsing.parser.sql.SQLStatement;
import org.apache.shardingsphere.core.parsing.parser.sql.dml.insert.InsertStatement;
import org.apache.shardingsphere.core.parsing.parser.sql.dql.select.SelectStatement;
import org.apache.shardingsphere.core.rule.ShardingRule;
import org.apache.shardingsphere.shardingproxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.shardingproxy.backend.schema.LogicSchemas;
import org.apache.shardingsphere.shardingproxy.backend.schema.ShardingSchema;
import org.apache.shardingsphere.shardingproxy.transport.mysql.constant.MySQLColumnType;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.MySQLPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.MySQLColumnDefinition41Packet;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.binary.fixture.BinaryStatementRegistryUtil;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.generic.MySQLEofPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.payload.MySQLPacketPayload;
import org.apache.shardingsphere.transaction.core.TransactionType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class MySQLComStmtPreparePacketTest {
    
    @Mock
    private MySQLPacketPayload payload;
    
    private BackendConnection backendConnection = new BackendConnection(TransactionType.LOCAL);
    
    @Before
    public void setUp() {
        setShardingSchemaMap();
        backendConnection.setCurrentSchema(ShardingConstant.LOGIC_SCHEMA_NAME);
        
    }
    
    @Before
    @After
    public void reset() {
        BinaryStatementRegistryUtil.reset();
    }
    
    @SneakyThrows
    private void setShardingSchemaMap() {
        ShardingSchema shardingSchema = mock(ShardingSchema.class);
        ShardingMetaData metaData = mock(ShardingMetaData.class);
        when(shardingSchema.getMetaData()).thenReturn(metaData);
        ShardingRule shardingRule = mock(ShardingRule.class);
        ParsingResultCache parsingResultCache = mock(ParsingResultCache.class);
        when(shardingRule.getParsingResultCache()).thenReturn(parsingResultCache);
        when(shardingSchema.getShardingRule()).thenReturn(shardingRule);
        Map<String, ShardingSchema> shardingSchemas = new HashMap<>();
        shardingSchemas.put(ShardingConstant.LOGIC_SCHEMA_NAME, shardingSchema);
        Field field = LogicSchemas.class.getDeclaredField("logicSchemas");
        field.setAccessible(true);
        field.set(LogicSchemas.getInstance(), shardingSchemas);
    }
    
    @Test
    public void assertWrite() {
        when(payload.readStringEOF()).thenReturn("SELECT id FROM tbl WHERE id=?");
        MySQLComStmtPreparePacket actual = new MySQLComStmtPreparePacket(backendConnection, payload);
        actual.write(payload);
        verify(payload).writeStringEOF("SELECT id FROM tbl WHERE id=?");
    }
    
    @Test
    public void assertExecuteForQueryWithParameters() {
        SelectStatement selectStatement = new SelectStatement();
        selectStatement.setParametersIndex(1);
        selectStatement.getTables().add(new Table("tbl", Optional.<String>absent()));
        selectStatement.getItems().addAll(Collections.singletonList(new CommonSelectItem("id", Optional.<String>absent())));
        Collection<MySQLPacket> actual = getComStmtPreparePacketWithMockedSQLParsingEngine("SELECT id FROM tbl WHERE id=?", selectStatement).execute();
        assertThat(actual.size(), is(3));
        Iterator<MySQLPacket> packets = actual.iterator();
        MySQLComStmtPrepareOKPacket comStmtPrepareOKPacket = (MySQLComStmtPrepareOKPacket) packets.next();
        assertThat(comStmtPrepareOKPacket.getSequenceId(), is(1));
        MySQLColumnDefinition41Packet columnDefinition41Packet = (MySQLColumnDefinition41Packet) packets.next();
        assertThat(columnDefinition41Packet.getSequenceId(), is(2));
        assertThat(columnDefinition41Packet.getName(), is(""));
        assertThat(columnDefinition41Packet.getColumnType(), is(MySQLColumnType.MYSQL_TYPE_VARCHAR));
        MySQLEofPacket eofPacket = (MySQLEofPacket) packets.next();
        assertThat(eofPacket.getSequenceId(), is(3));
    }
    
    @Test
    public void assertExecuteForQueryWithoutParameters() {
        SelectStatement selectStatement = new SelectStatement();
        selectStatement.getTables().add(new Table("tbl", Optional.<String>absent()));
        selectStatement.getItems().addAll(Collections.singletonList(new CommonSelectItem("1", Optional.<String>absent())));
        Collection<MySQLPacket> actual = getComStmtPreparePacketWithMockedSQLParsingEngine("SELECT 1", selectStatement).execute();
        assertThat(actual.size(), is(1));
        MySQLPacket mysqlPacket = actual.iterator().next();
        assertThat(mysqlPacket, instanceOf(MySQLComStmtPrepareOKPacket.class));
        assertThat(mysqlPacket.getSequenceId(), is(1));
    }
    
    @Test
    public void assertExecuteForInsertWithoutParameters() {
        InsertStatement insertStatement = new InsertStatement();
        insertStatement.getTables().add(new Table("tbl", Optional.<String>absent()));
        Collection<MySQLPacket> actual = getComStmtPreparePacketWithMockedSQLParsingEngine("INSERT INTO tbl VALUES(1)", insertStatement).execute();
        assertThat(actual.size(), is(1));
        MySQLPacket mysqlPacket = actual.iterator().next();
        assertThat(mysqlPacket, instanceOf(MySQLComStmtPrepareOKPacket.class));
        assertThat(mysqlPacket.getSequenceId(), is(1));
    }
    
    @Test
    public void assertExecuteForDALWithoutParameters() {
        ShowTablesStatement showTablesStatement = new ShowTablesStatement();
        Collection<MySQLPacket> actual = getComStmtPreparePacketWithMockedSQLParsingEngine("SHOW TABLES", showTablesStatement).execute();
        assertThat(actual.size(), is(1));
        MySQLPacket mysqlPacket = actual.iterator().next();
        assertThat(mysqlPacket, instanceOf(MySQLComStmtPrepareOKPacket.class));
        assertThat(mysqlPacket.getSequenceId(), is(1));
    }
    
    @SneakyThrows
    private MySQLComStmtPreparePacket getComStmtPreparePacketWithMockedSQLParsingEngine(final String sql, final SQLStatement sqlStatement) {
        when(payload.readStringEOF()).thenReturn(sql);
        MySQLComStmtPreparePacket result = new MySQLComStmtPreparePacket(backendConnection, payload);
        SQLParsingEngine sqlParsingEngine = mock(SQLParsingEngine.class);
        when(sqlParsingEngine.parse(true)).thenReturn(sqlStatement);
        Field field = MySQLComStmtPreparePacket.class.getDeclaredField("sqlParsingEngine");
        field.setAccessible(true);
        field.set(result, sqlParsingEngine);
        return result;
    }
}

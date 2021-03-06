/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.eagle.storage.jdbc.entity.impl;

import org.apache.eagle.log.base.taggedlog.TaggedLogAPIEntity;
import org.apache.eagle.storage.jdbc.conn.ConnectionManager;
import org.apache.eagle.storage.jdbc.conn.ConnectionManagerFactory;
import org.apache.eagle.storage.jdbc.conn.impl.TorqueStatementPeerImpl;
import org.apache.eagle.storage.jdbc.criteria.impl.PrimaryKeyCriteriaBuilder;
import org.apache.eagle.storage.jdbc.entity.JdbcEntitySerDeserHelper;
import org.apache.eagle.storage.jdbc.entity.JdbcEntityWriter;
import org.apache.eagle.storage.jdbc.schema.JdbcEntityDefinition;
import org.apache.commons.lang.time.StopWatch;
import org.apache.torque.ConstraintViolationException;
import org.apache.torque.criteria.Criteria;
import org.apache.torque.om.ObjectKey;
import org.apache.torque.sql.SqlBuilder;
import org.apache.torque.util.ColumnValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.sql.Savepoint;

/**
 * @since 3/27/15
 */
public class JdbcEntityWriterImpl<E extends TaggedLogAPIEntity> implements JdbcEntityWriter<E> {
    private final static Logger LOG = LoggerFactory.getLogger(JdbcEntityWriterImpl.class);

    private ConnectionManager connectionManager;
    private JdbcEntityDefinition jdbcEntityDefinition;

    public JdbcEntityWriterImpl(JdbcEntityDefinition jdbcEntityDefinition) {
        this.jdbcEntityDefinition = jdbcEntityDefinition;
        try {
            this.connectionManager = ConnectionManagerFactory.getInstance();
        } catch (Exception e) {
            LOG.error(e.getMessage(),e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> write(List<E> entities) throws Exception {
        List<String> keys = new ArrayList<String>();
        if(LOG.isDebugEnabled()) LOG.debug("Writing "+entities.size()+" entities");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        Connection connection = null;
        Savepoint insertDup = null;
        int i = 0 ;
        try {
            connection = ConnectionManagerFactory.getInstance().getConnection();
            // set auto commit false and commit by hands for 3x~5x better performance
            connection.setAutoCommit(false);
            TorqueStatementPeerImpl<E> peer = connectionManager.getStatementExecutor(this.jdbcEntityDefinition.getJdbcTableName());
            for (E entity : entities) {
                entity.setEncodedRowkey(peer.getPrimaryKeyBuilder().build(entity));
                ColumnValues columnValues = JdbcEntitySerDeserHelper.buildColumnValues(entity, this.jdbcEntityDefinition);

                ObjectKey key = null;
                try {
                    //save point , so that we can roll back just current entry, if required.
                    i++ ;
                    insertDup = connection.setSavepoint("insertEntity"+i);
                    // TODO: implement batch insert for better performance
                    key = peer.delegate().doInsert(columnValues,connection);

                    if (key != null) {
                        keys.add((String) key.getValue());
                    } else {
                        keys.add(entity.getEncodedRowkey());
                    }
                } catch (ClassCastException ex) {
                    assert key != null;
                    throw new RuntimeException("Key is not in type of String (VARCHAR) , but JdbcType (java.sql.Types): " + key.getJdbcType() + ", value: " + key.getValue(), ex);
                } catch (ConstraintViolationException e){
                    //this message will be different in each DB type ...using duplicate keyword to catch for broader set of DBs. moreover we are already inside ConstraintViolationException exception, do we even need this check?
                    if(e.getMessage().toLowerCase().contains("duplicate")){
                        connection.rollback(insertDup); // need to rollback current Insert entity, as it is duplicate record, need to update. Postgresql is strict in transaction handling(need rollback) as compared to MySql
                        String primaryKey = entity.getEncodedRowkey();
                        if(primaryKey==null) {
                            primaryKey = ConnectionManagerFactory.getInstance().getStatementExecutor().getPrimaryKeyBuilder().build(entity);
                            entity.setEncodedRowkey(primaryKey);
                        }
                        PrimaryKeyCriteriaBuilder pkBuilder = new PrimaryKeyCriteriaBuilder(Collections.singletonList(primaryKey), this.jdbcEntityDefinition.getJdbcTableName());
                        Criteria selectCriteria = pkBuilder.build();
                        if(LOG.isDebugEnabled()) LOG.debug("Updating by query: "+ SqlBuilder.buildQuery(selectCriteria).getDisplayString());
                        peer.delegate().doUpdate(selectCriteria, columnValues, connection);
                        keys.add(primaryKey);
                    }
                }
            }

            // Why not commit in finally: give up all if any single entity throws exception to make sure consistency guarantee
            if(LOG.isDebugEnabled()){
                LOG.debug("Committing writing");
            }
            connection.commit();
        }catch (Exception ex) {
            LOG.error("Failed to write records, rolling back",ex);
            if(connection!=null)
                connection.rollback();
            throw ex;
        }finally {
            stopWatch.stop();
            if(LOG.isDebugEnabled()) LOG.debug("Closing connection");
            if(connection!=null)
                connection.close();
        }

        LOG.info(String.format("Wrote %s records in %s ms (table: %s)",keys.size(),stopWatch.getTime(),this.jdbcEntityDefinition.getJdbcTableName()));
        return keys;
    }
}
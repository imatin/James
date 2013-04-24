/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.user.jdbc;

import java.util.Iterator;

import javax.sql.DataSource;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.DefaultConfigurationBuilder;
import org.apache.commons.dbcp.BasicDataSource;
import org.apache.derby.jdbc.EmbeddedDriver;
import org.apache.james.filesystem.api.mock.MockFileSystem;
import org.apache.james.lifecycle.api.LifecycleUtil;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.api.UsersRepositoryException;
import org.slf4j.LoggerFactory;

/**
 * Test basic behaviors of UsersFileRepository
 */
public class JamesUsersJdbcRepositoryTest extends AbstractUsersJdbcRepositoryTest {

    /**
     * Create the repository to be tested.
     * 
     * @return the user repository
     * @throws Exception
     */
    protected UsersRepository getUsersRepository() throws Exception {
        JamesUsersJdbcRepository res = new JamesUsersJdbcRepository();
        String tableString = "jamesusers";
        configureAbstractJdbcUsersRepository(res, tableString);
        return res;
    }

    /**
     * @param res
     * @param tableString
     * @throws Exception
     * @throws ConfigurationException
     */
    protected void configureAbstractJdbcUsersRepository(AbstractJdbcUsersRepository res, String tableString) throws Exception, ConfigurationException {
        res.setFileSystem(new MockFileSystem());
        DataSource dataSource = getDataSource();
        res.setDatasource(dataSource);
        DefaultConfigurationBuilder configuration = new DefaultConfigurationBuilder();
        configuration.addProperty("[@destinationURL]", "db://maildb/" + tableString);
        configuration.addProperty("sqlFile", "file://conf/sqlResources.xml");
        res.setLog(LoggerFactory.getLogger("MockLog"));
        res.configure(configuration);
        res.init();
    }

    private BasicDataSource getDataSource() {
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName(EmbeddedDriver.class.getName());
        ds.setUrl("jdbc:derby:memory:testdb;create=true");
        ds.setUsername("james");
        ds.setPassword("james");
        return ds;
    }

    /**
     * @return
     */
    protected boolean getCheckCase() {
        return true;
    }

    protected void disposeUsersRepository() throws UsersRepositoryException {
        Iterator<String> i = this.usersRepository.list();
        while (i.hasNext()) {
            this.usersRepository.removeUser((String) i.next());
        }
        LifecycleUtil.dispose(this.usersRepository);
    }

}

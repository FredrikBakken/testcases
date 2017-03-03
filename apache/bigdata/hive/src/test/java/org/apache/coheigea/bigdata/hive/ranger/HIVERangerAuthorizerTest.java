/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.coheigea.bigdata.hive.ranger;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hive.service.server.HiveServer2;
import org.junit.Assert;

/**
 * A custom RangerAdminClient is plugged into Ranger in turn, which loads security policies from a local file. These policies were 
 * generated in the Ranger Admin UI for a service called "cl1_hive":
 * 
 * a) A user "bob" can do a select/update on the table "words"
 * b) A group called "IT" can do a select only on the "count" column in "words"
 * c) "bob" can create any database
 * d) "dave" can do a select on the table "words" but only if the "count" column is >= 80
 * e) "jane" can do a select on the table "words", but only get a "hash" of the word, and not the word itself.
 * 
 * In addition we have some TAG based policies created in Atlas and synced into Ranger:
 * 
 *  a) The tag "HiveTableTag" is associated with "select" permission to the "dev" group to the "words" table in the "hivetable" database.
 *  b) The tag "HiveDatabaseTag" is associated with "create" permission to the "dev" group to the "hivetable" database.
 *  c) The tag "HiveColumnTag" is associated with "select" permission to the "frank" user to the "word" column of the "words" table.
 * 
 * Policies available from admin via:
 * 
 * http://localhost:6080/service/plugins/policies/download/cl1_hive
 */
public class HIVERangerAuthorizerTest {
    
    private static final File hdfsBaseDir = new File("./target/hdfs/").getAbsoluteFile();
    private static HiveServer2 hiveServer;
    private static int port;
    
    @org.junit.BeforeClass
    public static void setup() throws Exception {
        // Get a random port
        ServerSocket serverSocket = new ServerSocket(0);
        port = serverSocket.getLocalPort();
        serverSocket.close();
        
        HiveConf conf = new HiveConf();
        
        // Warehouse
        File warehouseDir = new File("./target/hdfs/warehouse").getAbsoluteFile();
        conf.set(HiveConf.ConfVars.METASTOREWAREHOUSE.varname, warehouseDir.getPath());
        
        // Scratchdir
        File scratchDir = new File("./target/hdfs/scratchdir").getAbsoluteFile();
        conf.set("hive.exec.scratchdir", scratchDir.getPath());
     
        // Create a temporary directory for the Hive metastore
        File metastoreDir = new File("./target/rangerauthzmetastore/").getAbsoluteFile();
        conf.set(HiveConf.ConfVars.METASTORECONNECTURLKEY.varname,
                 String.format("jdbc:derby:;databaseName=%s;create=true",  metastoreDir.getPath()));
        
        conf.set(HiveConf.ConfVars.METASTORE_AUTO_CREATE_ALL.varname, "true");
        conf.set(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_PORT.varname, "" + port);
        
        hiveServer = new HiveServer2();
        hiveServer.init(conf);
        hiveServer.start();
        
        Class.forName("org.apache.hive.jdbc.HiveDriver");
        
        // Create database
        String initialUrl = "jdbc:hive2://localhost:" + port;
        Connection connection = DriverManager.getConnection(initialUrl, "admin", "admin");
        Statement statement = connection.createStatement();
        
        statement.execute("CREATE DATABASE rangerauthz");
        
        statement.close();
        connection.close();
        
        // Load data into HIVE
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        connection = DriverManager.getConnection(url, "admin", "admin");
        statement = connection.createStatement();
        // statement.execute("CREATE TABLE WORDS (word STRING, count INT)");
        statement.execute("create table words (word STRING, count INT) row format delimited fields terminated by '\t' stored as textfile");
        
        // Copy "wordcount.txt" to "target" to avoid overwriting it during load
        java.io.File inputFile = new java.io.File(HIVERangerAuthorizerTest.class.getResource("../../../../../../wordcount.txt").toURI());
        Path outputPath = Paths.get(inputFile.toPath().getParent().getParent().toString() + java.io.File.separator + "wordcountout.txt");
        Files.copy(inputFile.toPath(), outputPath);
        
        statement.execute("LOAD DATA INPATH '" + outputPath + "' OVERWRITE INTO TABLE words");
        
        // Just test to make sure it's working
        ResultSet resultSet = statement.executeQuery("SELECT * FROM words where count == '100'");
        resultSet.next();
        Assert.assertEquals("Mr.", resultSet.getString(1));
        
        statement.close();
        connection.close();
        
        // Enable authorization
        conf.set(HiveConf.ConfVars.HIVE_AUTHORIZATION_ENABLED.varname, "true");
        conf.set(HiveConf.ConfVars.HIVE_SERVER2_ENABLE_DOAS.varname, "true");
        conf.set(HiveConf.ConfVars.HIVE_AUTHORIZATION_MANAGER.varname, 
                 "org.apache.ranger.authorization.hive.authorizer.RangerHiveAuthorizerFactory");
    }
    
    @org.junit.AfterClass
    public static void cleanup() throws Exception {
        hiveServer.stop();
        FileUtil.fullyDelete(hdfsBaseDir);
        File metastoreDir = new File("./target/rangerauthzmetastore/").getAbsoluteFile();
        FileUtil.fullyDelete(metastoreDir);
    }
    
    // this should be allowed (by the policy - user)
    @org.junit.Test
    public void testHiveSelectAllAsBob() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        Connection connection = DriverManager.getConnection(url, "bob", "bob");
        Statement statement = connection.createStatement();

        ResultSet resultSet = statement.executeQuery("SELECT * FROM words where count == '100'");
        resultSet.next();
        Assert.assertEquals("Mr.", resultSet.getString(1));
        Assert.assertEquals(100, resultSet.getInt(2));

        statement.close();
        connection.close();
    }
    
    // the "IT" group doesn't have permission to select all
    @org.junit.Test
    public void testHiveSelectAllAsAlice() throws Exception {
        
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting("alice", new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
                Connection connection = DriverManager.getConnection(url, "alice", "alice");
                Statement statement = connection.createStatement();
        
                try {
                    statement.executeQuery("SELECT * FROM words where count == '100'");
                    Assert.fail("Failure expected on an unauthorized call");
                } catch (SQLException ex) {
                    // expected
                }
        
                statement.close();
                connection.close();
                return null;
            }
        });
    }
    
    // this should be allowed (by the policy - user)
    @org.junit.Test
    public void testHiveSelectSpecificColumnAsBob() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        Connection connection = DriverManager.getConnection(url, "bob", "bob");
        Statement statement = connection.createStatement();

        ResultSet resultSet = statement.executeQuery("SELECT count FROM words where count == '100'");
        resultSet.next();
        Assert.assertEquals(100, resultSet.getInt(1));

        statement.close();
        connection.close();
    }
    
    // this should be allowed (by the policy - group)
    @org.junit.Test
    public void testHiveSelectSpecificColumnAsAlice() throws Exception {
        
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting("alice", new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {

            public Void run() throws Exception {
                String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
                Connection connection = DriverManager.getConnection(url, "alice", "alice");
                Statement statement = connection.createStatement();

                ResultSet resultSet = statement.executeQuery("SELECT count FROM words where count == '100'");
                resultSet.next();
                Assert.assertEquals(100, resultSet.getInt(1));

                statement.close();
                connection.close();
                return null;
            }
        });
    }
    
    // An unknown user shouldn't be allowed
    @org.junit.Test
    public void testHiveSelectSpecificColumnAsEve() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        Connection connection = DriverManager.getConnection(url, "eve", "eve");
        Statement statement = connection.createStatement();

        try {
            statement.executeQuery("SELECT count FROM words where count == '100'");
            Assert.fail("Failure expected on an unauthorized call");
        } catch (SQLException ex) {
            // expected
        }

        statement.close();
        connection.close();
    }
    
    // test "alice", but in the wrong group
    @org.junit.Test
    public void testHiveSelectSpecificColumnAsAliceWrongGroup() throws Exception {
        
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting("alice", new String[] {"DevOps"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {

            public Void run() throws Exception {
                String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
                Connection connection = DriverManager.getConnection(url, "alice", "alice");
                Statement statement = connection.createStatement();

                try {
                    statement.executeQuery("SELECT count FROM words where count == '100'");
                    Assert.fail("Failure expected on an unauthorized call");
                } catch (SQLException ex) {
                    // expected
                }

                statement.close();
                connection.close();
                return null;
            }
        });
    }
    
    // this should be allowed (by the policy - user)
    @org.junit.Test
    public void testHiveUpdateAllAsBob() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        Connection connection = DriverManager.getConnection(url, "bob", "bob");
        Statement statement = connection.createStatement();

        statement.execute("insert into words (word, count) values ('newword', 5)");
        
        ResultSet resultSet = statement.executeQuery("SELECT * FROM words where word == 'newword'");
        resultSet.next();
        Assert.assertEquals("newword", resultSet.getString(1));
        Assert.assertEquals(5, resultSet.getInt(2));

        statement.close();
        connection.close();
    }
    
    // this should not be allowed as "alice" can't insert into the table
    @org.junit.Test
    public void testHiveUpdateAllAsAlice() throws Exception {
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting("alice", new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
                Connection connection = DriverManager.getConnection(url, "alice", "alice");
                Statement statement = connection.createStatement();
        
                try {
                    statement.execute("insert into words (word, count) values ('newword2', 5)");
                    Assert.fail("Failure expected on an unauthorized call");
                } catch (SQLException ex) {
                    // expected
                }
                
        
                statement.close();
                connection.close();
                return null;
            }
        });
    }
    
    @org.junit.Test
    public void testHiveCreateDropDatabase() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port;
        
        // Try to create a database as "bob" - this should be allowed
        Connection connection = DriverManager.getConnection(url, "bob", "bob");
        Statement statement = connection.createStatement();

        statement.execute("CREATE DATABASE bobtemp");

        statement.close();
        connection.close();
        
        // Try to create a database as "alice" - this should not be allowed
        connection = DriverManager.getConnection(url, "alice", "alice");
        statement = connection.createStatement();

        try {
            statement.execute("CREATE DATABASE alicetemp");
            Assert.fail("Failure expected on an unauthorized call");
        } catch (SQLException ex) {
            // expected
        }
        
        // Try to drop a database as "bob" - this should not be allowed
        connection = DriverManager.getConnection(url, "bob", "bob");
        statement = connection.createStatement();

        try {
            statement.execute("drop DATABASE bobtemp");
            Assert.fail("Failure expected on an unauthorized call");
        } catch (SQLException ex) {
            // expected
        }
        
        // Try to drop a database as "admin" - this should be allowed
        connection = DriverManager.getConnection(url, "admin", "admin");
        statement = connection.createStatement();

        statement.execute("drop DATABASE bobtemp");

        statement.close();
        connection.close();
    }
    
    @org.junit.Test
    public void testBobSelectOnDifferentDatabase() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port;
        
        // Create a database as "admin"
        Connection connection = DriverManager.getConnection(url, "admin", "admin");
        Statement statement = connection.createStatement();

        statement.execute("CREATE DATABASE admintemp");

        statement.close();
        connection.close();
        
        // Create a "words" table in "admintemp"
        url = "jdbc:hive2://localhost:" + port + "/admintemp";
        connection = DriverManager.getConnection(url, "admin", "admin");
        statement = connection.createStatement();
        statement.execute("CREATE TABLE WORDS (word STRING, count INT)");
        
        statement.close();
        connection.close();
        
        // Now try to read it as "bob"
        connection = DriverManager.getConnection(url, "bob", "bob");
        statement = connection.createStatement();

        try {
            statement.executeQuery("SELECT count FROM words where count == '100'");
            Assert.fail("Failure expected on an unauthorized call");
        } catch (SQLException ex) {
            // expected
        }

        statement.close();
        connection.close();
        
        // Drop the table and database as "admin"
        connection = DriverManager.getConnection(url, "admin", "admin");
        statement = connection.createStatement();

        statement.execute("drop TABLE words");
        statement.execute("drop DATABASE admintemp");

        statement.close();
        connection.close();
    }
    
    @org.junit.Test
    public void testBobSelectOnDifferentTables() throws Exception {
        
        // Create a "words2" table in "rangerauthz"
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        Connection connection = DriverManager.getConnection(url, "admin", "admin");
        Statement statement = connection.createStatement();
        statement.execute("CREATE TABLE WORDS2 (word STRING, count INT)");
        
        statement.close();
        connection.close();
        
        // Now try to read it as "bob"
        connection = DriverManager.getConnection(url, "bob", "bob");
        statement = connection.createStatement();

        try {
            statement.executeQuery("SELECT count FROM words2 where count == '100'");
            Assert.fail("Failure expected on an unauthorized call");
        } catch (SQLException ex) {
            // expected
        }

        statement.close();
        connection.close();
        
        // Drop the table as "admin"
        connection = DriverManager.getConnection(url, "admin", "admin");
        statement = connection.createStatement();

        statement.execute("drop TABLE words2");

        statement.close();
        connection.close();
    }
    
    @org.junit.Test
    public void testBobAlter() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        
        // Create a new table as admin
        Connection connection = DriverManager.getConnection(url, "admin", "admin");
        Statement statement = connection.createStatement();
        statement.execute("CREATE TABLE WORDS2 (word STRING, count INT)");
        
        statement.close();
        connection.close();
        
        // Try to add a new column in words as "bob" - this should fail
        url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        connection = DriverManager.getConnection(url, "bob", "bob");
        statement = connection.createStatement();
        
        try {
            statement.execute("ALTER TABLE WORDS2 ADD COLUMNS (newcol STRING)");
            Assert.fail("Failure expected on an unauthorized call");
        } catch (SQLException ex) {
            // expected
        }
        
        statement.close();
        connection.close();
        
        // Now add it as "admin"
        connection = DriverManager.getConnection(url, "admin", "admin");
        statement = connection.createStatement();
        
        statement.execute("ALTER TABLE WORDS2 ADD COLUMNS (newcol STRING)");
        
        statement.close();
        connection.close();
        
        // Try to alter it as "bob" - this should fail
        connection = DriverManager.getConnection(url, "bob", "bob");
        statement = connection.createStatement();
        
        try {
            statement.execute("ALTER TABLE WORDS2 REPLACE COLUMNS (word STRING, count INT)");
            Assert.fail("Failure expected on an unauthorized call");
        } catch (SQLException ex) {
            // expected
        }
        
        statement.close();
        connection.close();
        
        // Now alter it as "admin"
        connection = DriverManager.getConnection(url, "admin", "admin");
        statement = connection.createStatement();
        
        statement.execute("ALTER TABLE WORDS2 REPLACE COLUMNS (word STRING, count INT)");
        
        statement.close();
        connection.close();
        
        // alter the table as "admin"
        connection = DriverManager.getConnection(url, "admin", "admin");
        statement = connection.createStatement();

        statement.execute("drop TABLE words2");

        statement.close();
        connection.close();
    }
    
    @org.junit.Test
    public void testHiveRowFilter() throws Exception {
        
        // dave can do a select where the count is >= 80
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        Connection connection = DriverManager.getConnection(url, "dave", "dave");
        Statement statement = connection.createStatement();

        // "dave" can select where count >= 80
        ResultSet resultSet = statement.executeQuery("SELECT * FROM words where count == '100'");
        resultSet.next();
        Assert.assertEquals("Mr.", resultSet.getString(1));
        Assert.assertEquals(100, resultSet.getInt(2));
        
        resultSet = statement.executeQuery("SELECT * FROM words where count == '79'");
        Assert.assertFalse(resultSet.next());
        
        statement.close();
        connection.close();
        
        // "bob" should be able to read a count of "79" as the filter doesn't apply to him
        connection = DriverManager.getConnection(url, "bob", "bob");
        statement = connection.createStatement();
        
        resultSet = statement.executeQuery("SELECT * FROM words where count == '79'");
        Assert.assertTrue(resultSet.next());
        
        statement.close();
        connection.close();
    }
    
    @org.junit.Test
    public void testHiveDataMasking() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port + "/rangerauthz";
        Connection connection = DriverManager.getConnection(url, "jane", "jane");
        Statement statement = connection.createStatement();

        // "jane" can only set a hash of the word, and not the word itself
        ResultSet resultSet = statement.executeQuery("SELECT * FROM words where count == '100'");
        resultSet.next();
        Assert.assertEquals("127469a6b4253ebb77adccc0dd48461e", resultSet.getString(1));
        Assert.assertEquals(100, resultSet.getInt(2));
        
        statement.close();
        connection.close();
    }
    
    @org.junit.Test
    public void testTagBasedPolicyForTable() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port;
        
        // Create a database as "admin"
        Connection connection = DriverManager.getConnection(url, "admin", "admin");
        Statement statement = connection.createStatement();

        statement.execute("CREATE DATABASE hivetable");

        statement.close();
        connection.close();
        
        // Create a "words" table in "hivetable"
        final String tableUrl = "jdbc:hive2://localhost:" + port + "/hivetable";
        connection = DriverManager.getConnection(tableUrl, "admin", "admin");
        statement = connection.createStatement();
        statement.execute("CREATE TABLE WORDS (word STRING, count INT)");
        statement.execute("CREATE TABLE WORDS2 (word STRING, count INT)");
        
        statement.close();
        connection.close();
        
        // Now try to read it as the "public" group
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting("alice", new String[] {"dev"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection connection = DriverManager.getConnection(tableUrl, "alice", "alice");
                Statement statement = connection.createStatement();
        
                // "words" should work
                ResultSet resultSet = statement.executeQuery("SELECT * FROM words");
                Assert.assertNotNull(resultSet);
        
                statement.close();
               
                statement = connection.createStatement();
                try {
                    // "words2" should not
                    statement.executeQuery("SELECT * FROM words2");
                    Assert.fail("Failure expected on an unauthorized call");
                } catch (SQLException ex) {
                    // expected
                }
                
                statement.close();
                connection.close();
                return null;
            }
        });
        
        // Drop the table and database as "admin"
        connection = DriverManager.getConnection(tableUrl, "admin", "admin");
        statement = connection.createStatement();

        statement.execute("drop TABLE words");
        statement.execute("drop TABLE words2");
        statement.execute("drop DATABASE hivetable");

        statement.close();
        connection.close();
    }
    
    @org.junit.Test
    public void testTagBasedPolicyForDatabase() throws Exception {
        
        final String url = "jdbc:hive2://localhost:" + port;
        
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting("alice", new String[] {"dev"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                // Create a database
                Connection connection = DriverManager.getConnection(url, "alice", "alice");
                Statement statement = connection.createStatement();

                statement.execute("CREATE DATABASE hivetable");
                statement.close();
                
                statement = connection.createStatement();
                try {
                    // "hivetable2" should not be allowed to be created by the "dev" group
                    statement.execute("CREATE DATABASE hivetable2");
                    Assert.fail("Failure expected on an unauthorized call");
                } catch (SQLException ex) {
                    // expected
                }
                
                statement.close();
                connection.close();
                return null;
            }
        });
        
        // Drop the database as "admin"
        Connection connection = DriverManager.getConnection(url, "admin", "admin");
        Statement statement = connection.createStatement();

        statement.execute("drop DATABASE hivetable");

        statement.close();
        connection.close();
    }
    
    @org.junit.Test
    public void testTagBasedPolicyForColumn() throws Exception {
        
        String url = "jdbc:hive2://localhost:" + port;
        
        // Create a database as "admin"
        Connection connection = DriverManager.getConnection(url, "admin", "admin");
        Statement statement = connection.createStatement();

        statement.execute("CREATE DATABASE hivetable");

        statement.close();
        connection.close();
        
        // Create a "words" table in "hivetable"
        final String tableUrl = "jdbc:hive2://localhost:" + port + "/hivetable";
        connection = DriverManager.getConnection(tableUrl, "admin", "admin");
        statement = connection.createStatement();
        statement.execute("CREATE TABLE WORDS (word STRING, count INT)");
        statement.execute("CREATE TABLE WORDS2 (word STRING, count INT)");
        
        statement.close();
        connection.close();
        
        // Now try to read it as the user "frank"
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting("frank", new String[] {"unknown"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection connection = DriverManager.getConnection(tableUrl, "frank", "frank");
               
                // we can select "word" from "words"
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT word FROM words");
                Assert.assertNotNull(resultSet);
                statement.close();
                
                try {
                    // we can't select "word" from "words2" as "frank"
                    statement.executeQuery("SELECT word FROM words2");
                    Assert.fail("Failure expected on an unauthorized call");
                } catch (SQLException ex) {
                    // expected
                }
                
                statement.close();
                connection.close();
                return null;
            }
        });
        
        // Drop the table and database as "admin"
        connection = DriverManager.getConnection(tableUrl, "admin", "admin");
        statement = connection.createStatement();

        statement.execute("drop TABLE words");
        statement.execute("drop TABLE words2");
        statement.execute("drop DATABASE hivetable");

        statement.close();
        connection.close();
    }
    
}

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

package org.apache.coheigea.bigdata.hbase.ranger;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.Assert;

/**
 * A custom RangerAdminClient is plugged into Ranger in turn, which loads security policies from a local file. These policies were 
 * generated in the Ranger Admin UI for a service called "HBaseTest":
 * 
 * a) The "logged in" user can do anything
 * b) The IT group can read and write to the "temp" table, but only the "colfam1" column family.
 * 
 * In addition we have some TAG based policies created in Atlas and synced into Ranger:
 * 
 *  a) The tag "HbaseTableTag" is associated with "create" permission to the "dev" group to the "temp3" table
 *  b) The tag "HbaseColFamTag" is associated with "read" permission to the "dev" group to the "colfam1" column family of the "temp3" table.
 *  c) The tag "HbaseColTag" is associated with "write" permission to the "dev" group to the "col1" column of the "colfam1" column family of 
 *  the "temp3" table.
 * 
 * Policies available from admin via:
 * 
 * http://localhost:6080/service/plugins/policies/download/cl1_hbase
 */
public class HBaseRangerAuthorizationTest {
    
    private static int port;
    private static HBaseTestingUtility utility;
    
    
    @org.junit.BeforeClass
    public static void setup() throws Exception {
        port = getFreePort();
        
        utility = new HBaseTestingUtility();
        utility.getConfiguration().set("test.hbase.zookeeper.property.clientPort", "" + port);
        utility.getConfiguration().set("hbase.master.port", "" + getFreePort());
        utility.getConfiguration().set("hbase.master.info.port", "" + getFreePort());
        utility.getConfiguration().set("hbase.regionserver.port", "" + getFreePort());
        utility.getConfiguration().set("hbase.regionserver.info.port", "" + getFreePort());
        utility.getConfiguration().set("zookeeper.znode.parent", "/hbase-unsecure");

        // Enable authorization
        utility.getConfiguration().set("hbase.security.authorization", "true");
        utility.getConfiguration().set("hbase.coprocessor.master.classes", 
            "org.apache.ranger.authorization.hbase.RangerAuthorizationCoprocessor");
        utility.getConfiguration().set("hbase.coprocessor.region.classes", 
            "org.apache.ranger.authorization.hbase.RangerAuthorizationCoprocessor");

        utility.startMiniCluster();

        // Create a table as "admin"
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");

        // Create a table
        Connection conn = ConnectionFactory.createConnection(conf);
        Admin admin = conn.getAdmin();

        // Create a table
        if (!admin.tableExists(TableName.valueOf("temp"))) {
            HTableDescriptor tableDescriptor = new HTableDescriptor(TableName.valueOf("temp"));

            // Adding column families to table descriptor
            tableDescriptor.addFamily(new HColumnDescriptor("colfam1"));
            tableDescriptor.addFamily(new HColumnDescriptor("colfam2"));

            admin.createTable(tableDescriptor);
        }

        // Add a new row
        Put put = new Put(Bytes.toBytes("row1"));
        put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val1"));
        Table table = conn.getTable(TableName.valueOf("temp"));
        table.put(put);
        
        put = new Put(Bytes.toBytes("row1"));
        put.addColumn(Bytes.toBytes("colfam2"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
        table.put(put);

        conn.close();
    }
    
    @org.junit.AfterClass
    public static void cleanup() throws Exception {
        utility.shutdownMiniCluster();
    }
    
    @org.junit.Test
    public void testReadTablesAsProcessOwner() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Admin admin = conn.getAdmin();

        HTableDescriptor[] tableDescriptors = admin.listTables();
        Assert.assertEquals(1, tableDescriptors.length);

        conn.close();
    }
    
    // This should fail, as the "IT" group only has read privileges, not admin privileges, on the table "temp"
    @org.junit.Test
    public void testReadTablesAsGroupIT() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Admin admin = conn.getAdmin();
                
                HTableDescriptor[] tableDescriptors = admin.listTables();
                Assert.assertEquals(0, tableDescriptors.length);
        
                conn.close();
                return null;
            }
        });
    }
    
    @org.junit.Test
    public void testCreateAndDropTables() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Admin admin = conn.getAdmin();

        // Create a new table as process owner
        HTableDescriptor tableDescriptor = new HTableDescriptor(TableName.valueOf("temp2"));

        // Adding column families to table descriptor
        tableDescriptor.addFamily(new HColumnDescriptor("colfam1"));
        tableDescriptor.addFamily(new HColumnDescriptor("colfam2"));

        admin.createTable(tableDescriptor);

        conn.close();
        
        // Try to disable + delete the table as the "IT" group
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Admin admin = conn.getAdmin();
                
                try {
                    admin.disableTable(TableName.valueOf("temp2"));
                    admin.deleteTable(TableName.valueOf("temp2"));
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }
                
                conn.close();
                return null;
            }
        });
        
        // Now disable and delete as process owner
        conn = ConnectionFactory.createConnection(conf);
        admin = conn.getAdmin();
        admin.disableTable(TableName.valueOf("temp2"));
        admin.deleteTable(TableName.valueOf("temp2"));
        
        conn.close();
    }
    
    @org.junit.Test
    public void testReadRowAsProcessOwner() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Table table = conn.getTable(TableName.valueOf("temp"));
        
        // Read a row
        Get get = new Get(Bytes.toBytes("row1"));
        Result result = table.get(get);
        byte[] valResult = result.getValue(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"));
        Assert.assertTrue(Arrays.equals(valResult, Bytes.toBytes("val1")));
        
        conn.close();
    }
    
    @org.junit.Test
    public void testReadRowAsGroupIT() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp"));
                
                // Read a row
                Get get = new Get(Bytes.toBytes("row1"));
                Result result = table.get(get);
                byte[] valResult = result.getValue(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"));
                Assert.assertTrue(Arrays.equals(valResult, Bytes.toBytes("val1")));

                conn.close();
                return null;
            }
        });
    }
    
    // This should fail as "public" doesn't have the right to read the table
    @org.junit.Test
    public void testReadRowAsGroupPublic() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"public"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp"));
                
                // Read a row
                try {
                    Get get = new Get(Bytes.toBytes("row1"));
                    table.get(get);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }

                conn.close();
                return null;
            }
        });
    }
    
    @org.junit.Test
    public void testReadRowFromColFam2AsProcessOwner() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Table table = conn.getTable(TableName.valueOf("temp"));

        // Read a row
        Get get = new Get(Bytes.toBytes("row1"));
        Result result = table.get(get);
        byte[] valResult = result.getValue(Bytes.toBytes("colfam2"), Bytes.toBytes("col1"));
        Assert.assertTrue(Arrays.equals(valResult, Bytes.toBytes("val2")));

        conn.close();
    }
    
    @org.junit.Test
    public void testReadRowFromColFam2AsGroupIT() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp"));
                
                // Read a row
                Get get = new Get(Bytes.toBytes("row1"));
                Result result = table.get(get);
                byte[] valResult = result.getValue(Bytes.toBytes("colfam2"), Bytes.toBytes("col1"));
                Assert.assertNull(valResult);

                conn.close();
                return null;
            }
        });
    }
    
    @org.junit.Test
    public void testWriteRowAsProcessOwner() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Table table = conn.getTable(TableName.valueOf("temp"));
        
        // Add a new row
        Put put = new Put(Bytes.toBytes("row2"));
        put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
        table.put(put);
        
        conn.close();
    }
    
    @org.junit.Test
    public void testWriteRowAsGroupIT() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp"));
                
                // Add a new row
                Put put = new Put(Bytes.toBytes("row3"));
                put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
                table.put(put);
                
                conn.close();
                return null;
            }
        });
    }
    
    @org.junit.Test
    public void testWriteRowAsGroupPublic() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"public"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp"));
                
                // Add a new row
                try {
                    Put put = new Put(Bytes.toBytes("row3"));
                    put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
                    table.put(put);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }
                
                conn.close();
                return null;
            }
        });
    }
    
    @org.junit.Test
    public void testWriteRowInColFam2AsGroupIT() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp"));
                
                // Add a new row
                try {
                    Put put = new Put(Bytes.toBytes("row3"));
                    put.addColumn(Bytes.toBytes("colfam2"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
                    table.put(put);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }
                
                conn.close();
                return null;
            }
        });
    }
    
    @org.junit.Test
    public void testReadRowInAnotherTable() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Admin admin = conn.getAdmin();

        // Create a new table as process owner
        HTableDescriptor tableDescriptor = new HTableDescriptor(TableName.valueOf("temp4"));

        // Adding column families to table descriptor
        tableDescriptor.addFamily(new HColumnDescriptor("colfam1"));
        tableDescriptor.addFamily(new HColumnDescriptor("colfam2"));

        admin.createTable(tableDescriptor);

        // Write a value
        Put put = new Put(Bytes.toBytes("row1"));
        put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val1"));
        Table table = conn.getTable(TableName.valueOf("temp4"));
        table.put(put);
        
        // Read a row
        Get get = new Get(Bytes.toBytes("row1"));
        Result result = table.get(get);
        byte[] valResult = result.getValue(Bytes.toBytes("colfam2"), Bytes.toBytes("col1"));
        Assert.assertNull(valResult);
        
        conn.close();
        
        // Now try to read the row as group "IT" - it should fail as "IT" can only read from table "temp"
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp4"));
                
                // Read a row
                try {
                    Get get = new Get(Bytes.toBytes("row1"));
                    table.get(get);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }

                conn.close();
                return null;
            }
        });
        
        // Now disable and delete as process owner
        conn = ConnectionFactory.createConnection(conf);
        admin = conn.getAdmin();
        admin.disableTable(TableName.valueOf("temp4"));
        admin.deleteTable(TableName.valueOf("temp4"));
        
        conn.close();
    }
    
    @org.junit.Test
    public void testDeleteRowAsProcessOwner() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Table table = conn.getTable(TableName.valueOf("temp"));
        
        // Add a new row
        Put put = new Put(Bytes.toBytes("row4"));
        put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
        table.put(put);
        
        // Delete the new row
        Delete delete = new Delete(Bytes.toBytes("row4"));
        table.delete(delete);
        
        conn.close();
    }
    
    @org.junit.Test
    public void testDeleteRowAsGroupIT() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Table table = conn.getTable(TableName.valueOf("temp"));
        
        // Add a new row (as process owner)
        Put put = new Put(Bytes.toBytes("row5"));
        put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
        table.put(put);
        
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp"));
                
                try {
                    // Delete the new row
                    Delete delete = new Delete(Bytes.toBytes("row5"));
                    table.delete(delete);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }
                
                conn.close();
                return null;
            }
        });
        
        // Delete the new row (as process owner)
        Delete delete = new Delete(Bytes.toBytes("row5"));
        table.delete(delete);
        
        conn.close();
    }
    
    @org.junit.Test
    public void testTagBasedTablePolicy() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        final HTableDescriptor tableDescriptor = new HTableDescriptor(TableName.valueOf("temp3"));

        // Adding column families to table descriptor
        tableDescriptor.addFamily(new HColumnDescriptor("colfam1"));
        tableDescriptor.addFamily(new HColumnDescriptor("colfam2"));

        // Try to create a "temp3" table as the "IT" group - this should fail
        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        
        // Try to create the table as the "IT" group - this should fail
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Admin admin = conn.getAdmin();
                
                try {
                    admin.createTable(tableDescriptor);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }
                
                conn.close();
                return null;
            }
        });

        // Now try to create the table as the "dev" group - this should work
        ugi = UserGroupInformation.createUserForTesting(user, new String[] {"dev"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Admin admin = conn.getAdmin();
                
                admin.createTable(tableDescriptor);
                
                conn.close();
                return null;
            }
        });
        
        // Drop the table
        Connection conn = ConnectionFactory.createConnection(conf);
        Admin admin = conn.getAdmin();
        
        admin.disableTable(TableName.valueOf("temp3"));
        admin.deleteTable(TableName.valueOf("temp3"));
        
        conn.close();
    }
    
    @org.junit.Test
    public void testTagBasedColumnFamilyPolicy() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        // Create a new table as process owner
        final HTableDescriptor tableDescriptor = new HTableDescriptor(TableName.valueOf("temp3"));

        // Adding column families to table descriptor
        tableDescriptor.addFamily(new HColumnDescriptor("colfam1"));
        tableDescriptor.addFamily(new HColumnDescriptor("colfam2"));
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Admin admin = conn.getAdmin();
        
        admin.createTable(tableDescriptor);
        
        // Add a new row
        Put put = new Put(Bytes.toBytes("row1"));
        put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val1"));
        Table table = conn.getTable(TableName.valueOf("temp3"));
        table.put(put);
        
        put = new Put(Bytes.toBytes("row1"));
        put.addColumn(Bytes.toBytes("colfam2"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
        table.put(put);
        
        conn.close();

        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"dev"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp3"));
                
                // Try to read the "colfam1" of the "temp3" table as the "dev" group - this should work
                Get get = new Get(Bytes.toBytes("row1"));
                Result result = table.get(get);
                byte[] valResult = result.getValue(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"));
                Assert.assertTrue(Arrays.equals(valResult, Bytes.toBytes("val1")));
                
                // Now try to read the "colfam2" column family of the temp3 table - this should fail
                get = new Get(Bytes.toBytes("row1"));
                result = table.get(get);
                valResult = result.getValue(Bytes.toBytes("colfam2"), Bytes.toBytes("col1"));
                Assert.assertNull(valResult);
                
                conn.close();
                return null;
            }
        });
        
        // Now try to read colfam1 as the "IT" group - this should fail
        ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp3"));
                
                Get get = new Get(Bytes.toBytes("row1"));
                try {
                    table.get(get);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }
                
                return null;
            }
        });
        
        // Drop the table
        conn = ConnectionFactory.createConnection(conf);
        admin = conn.getAdmin();
        
        admin.disableTable(TableName.valueOf("temp3"));
        admin.deleteTable(TableName.valueOf("temp3"));
        
        conn.close();
    }
    
    @org.junit.Test
    public void testTagBasedColumnPolicy() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "" + port);
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        
        // Create a new table as process owner
        final HTableDescriptor tableDescriptor = new HTableDescriptor(TableName.valueOf("temp3"));

        // Adding column families to table descriptor
        tableDescriptor.addFamily(new HColumnDescriptor("colfam1"));
        tableDescriptor.addFamily(new HColumnDescriptor("colfam2"));
        
        Connection conn = ConnectionFactory.createConnection(conf);
        Admin admin = conn.getAdmin();
        
        admin.createTable(tableDescriptor);
        
        // Add a new row
        Put put = new Put(Bytes.toBytes("row1"));
        put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val1"));
        Table table = conn.getTable(TableName.valueOf("temp3"));
        table.put(put);
        
        put = new Put(Bytes.toBytes("row1"));
        put.addColumn(Bytes.toBytes("colfam2"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
        table.put(put);
        
        conn.close();

        String user = "bob";
        if ("bob".equals(System.getProperty("user.name"))) {
            user = "alice";
        }
        UserGroupInformation ugi = UserGroupInformation.createUserForTesting(user, new String[] {"dev"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp3"));
                
                // Try to write something to the "col1" column of the "colfam1" of the "temp3" table as the "dev" group 
                // - this should work
                Put put = new Put(Bytes.toBytes("row3"));
                put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
                table.put(put);
                
                // Try to write something to the "col2" column of the "colfam1" of the "temp3" table as the "dev" group 
                // - this should fail
                put = new Put(Bytes.toBytes("row3"));
                put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col2"), Bytes.toBytes("val2"));
                try {
                    table.put(put);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }
                
                conn.close();
                return null;
            }
        });
        
        ugi = UserGroupInformation.createUserForTesting(user, new String[] {"IT"});
        ugi.doAs(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
                Connection conn = ConnectionFactory.createConnection(conf);
                Table table = conn.getTable(TableName.valueOf("temp3"));
                
                // Try to write something to the "col1" column of the "colfam1" of the "temp3" table as the "IT" group 
                // - this should fail
                Put put = new Put(Bytes.toBytes("row3"));
                put.addColumn(Bytes.toBytes("colfam1"), Bytes.toBytes("col1"), Bytes.toBytes("val2"));
                try {
                    table.put(put);
                    Assert.fail("Failure expected on an unauthorized user");
                } catch (IOException ex) {
                    // expected
                }
                
                conn.close();
                return null;
            }
        });
        
        // Drop the table
        conn = ConnectionFactory.createConnection(conf);
        admin = conn.getAdmin();
        
        admin.disableTable(TableName.valueOf("temp3"));
        admin.deleteTable(TableName.valueOf("temp3"));
        
        conn.close();
    }
    
    private static int getFreePort() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }
}

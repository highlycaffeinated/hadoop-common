/**
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
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_MAX_LOCKED_MEMORY_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_BLOCK_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_CACHING_ENABLED_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CACHEREPORT_INTERVAL_MSEC_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_PATH_BASED_CACHE_REFRESH_INTERVAL_MS;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystemTestHelper;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.AddPathBasedCacheDirectiveException.InvalidPathNameError;
import org.apache.hadoop.hdfs.protocol.AddPathBasedCacheDirectiveException.InvalidPoolNameError;
import org.apache.hadoop.hdfs.protocol.AddPathBasedCacheDirectiveException.PoolWritePermissionDeniedError;
import org.apache.hadoop.hdfs.protocol.CachePoolInfo;
import org.apache.hadoop.hdfs.protocol.PathBasedCacheDescriptor;
import org.apache.hadoop.hdfs.protocol.PathBasedCacheDirective;
import org.apache.hadoop.hdfs.protocol.RemovePathBasedCacheDescriptorException.InvalidIdException;
import org.apache.hadoop.hdfs.protocol.RemovePathBasedCacheDescriptorException.NoSuchIdException;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.CachedBlocksList.Type;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.GSet;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Supplier;

public class TestPathBasedCacheRequests {
  static final Log LOG = LogFactory.getLog(TestPathBasedCacheRequests.class);

  private static final UserGroupInformation unprivilegedUser =
      UserGroupInformation.createRemoteUser("unprivilegedUser");

  static private Configuration conf;
  static private MiniDFSCluster cluster;
  static private DistributedFileSystem dfs;
  static private NamenodeProtocols proto;

  @Before
  public void setup() throws Exception {
    conf = new HdfsConfiguration();
    // set low limits here for testing purposes
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_LIST_CACHE_POOLS_NUM_RESPONSES, 2);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_LIST_CACHE_DESCRIPTORS_NUM_RESPONSES, 2);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    dfs = cluster.getFileSystem();
    proto = cluster.getNameNodeRpc();
  }

  @After
  public void teardown() throws Exception {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test(timeout=60000)
  public void testBasicPoolOperations() throws Exception {
    final String poolName = "pool1";
    CachePoolInfo info = new CachePoolInfo(poolName).
        setOwnerName("bob").setGroupName("bobgroup").
        setMode(new FsPermission((short)0755)).setWeight(150);

    // Add a pool
    dfs.addCachePool(info);

    // Do some bad addCachePools
    try {
      dfs.addCachePool(info);
      fail("added the pool with the same name twice");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("pool1 already exists", ioe);
    }
    try {
      dfs.addCachePool(new CachePoolInfo(""));
      fail("added empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      dfs.addCachePool(null);
      fail("added null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("CachePoolInfo is null", ioe);
    }
    try {
      proto.addCachePool(new CachePoolInfo(""));
      fail("added empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      proto.addCachePool(null);
      fail("added null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("CachePoolInfo is null", ioe);
    }

    // Modify the pool
    info.setOwnerName("jane").setGroupName("janegroup")
        .setMode(new FsPermission((short)0700)).setWeight(314);
    dfs.modifyCachePool(info);

    // Do some invalid modify pools
    try {
      dfs.modifyCachePool(new CachePoolInfo("fool"));
      fail("modified non-existent cache pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("fool does not exist", ioe);
    }
    try {
      dfs.modifyCachePool(new CachePoolInfo(""));
      fail("modified empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      dfs.modifyCachePool(null);
      fail("modified null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("CachePoolInfo is null", ioe);
    }
    try {
      proto.modifyCachePool(new CachePoolInfo(""));
      fail("modified empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      proto.modifyCachePool(null);
      fail("modified null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("CachePoolInfo is null", ioe);
    }

    // Remove the pool
    dfs.removeCachePool(poolName);
    // Do some bad removePools
    try {
      dfs.removeCachePool("pool99");
      fail("expected to get an exception when " +
          "removing a non-existent pool.");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("can't remove " +
          "non-existent cache pool", ioe);
    }
    try {
      dfs.removeCachePool(poolName);
      Assert.fail("expected to get an exception when " +
          "removing a non-existent pool.");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("can't remove " +
          "non-existent cache pool", ioe);
    }
    try {
      dfs.removeCachePool("");
      fail("removed empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      dfs.removeCachePool(null);
      fail("removed null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      proto.removeCachePool("");
      fail("removed empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      proto.removeCachePool(null);
      fail("removed null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }

    info = new CachePoolInfo("pool2");
    dfs.addCachePool(info);
  }

  @Test(timeout=60000)
  public void testCreateAndModifyPools() throws Exception {
    String poolName = "pool1";
    String ownerName = "abc";
    String groupName = "123";
    FsPermission mode = new FsPermission((short)0755);
    int weight = 150;
    dfs.addCachePool(new CachePoolInfo(poolName).
        setOwnerName(ownerName).setGroupName(groupName).
        setMode(mode).setWeight(weight));
    
    RemoteIterator<CachePoolInfo> iter = dfs.listCachePools();
    CachePoolInfo info = iter.next();
    assertEquals(poolName, info.getPoolName());
    assertEquals(ownerName, info.getOwnerName());
    assertEquals(groupName, info.getGroupName());

    ownerName = "def";
    groupName = "456";
    mode = new FsPermission((short)0700);
    weight = 151;
    dfs.modifyCachePool(new CachePoolInfo(poolName).
        setOwnerName(ownerName).setGroupName(groupName).
        setMode(mode).setWeight(weight));

    iter = dfs.listCachePools();
    info = iter.next();
    assertEquals(poolName, info.getPoolName());
    assertEquals(ownerName, info.getOwnerName());
    assertEquals(groupName, info.getGroupName());
    assertEquals(mode, info.getMode());
    assertEquals(Integer.valueOf(weight), info.getWeight());

    dfs.removeCachePool(poolName);
    iter = dfs.listCachePools();
    assertFalse("expected no cache pools after deleting pool", iter.hasNext());

    proto.listCachePools(null);

    try {
      proto.removeCachePool("pool99");
      Assert.fail("expected to get an exception when " +
          "removing a non-existent pool.");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("can't remove non-existent",
          ioe);
    }
    try {
      proto.removeCachePool(poolName);
      Assert.fail("expected to get an exception when " +
          "removing a non-existent pool.");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("can't remove non-existent",
          ioe);
    }

    iter = dfs.listCachePools();
    assertFalse("expected no cache pools after deleting pool", iter.hasNext());
  }

  private static void validateListAll(
      RemoteIterator<PathBasedCacheDescriptor> iter,
      PathBasedCacheDescriptor... descriptors) throws Exception {
    for (PathBasedCacheDescriptor descriptor: descriptors) {
      assertTrue("Unexpectedly few elements", iter.hasNext());
      assertEquals("Unexpected descriptor", descriptor, iter.next());
    }
    assertFalse("Unexpectedly many list elements", iter.hasNext());
  }

  private static PathBasedCacheDescriptor addAsUnprivileged(
      final PathBasedCacheDirective directive) throws Exception {
    return unprivilegedUser
        .doAs(new PrivilegedExceptionAction<PathBasedCacheDescriptor>() {
          @Override
          public PathBasedCacheDescriptor run() throws IOException {
            DistributedFileSystem myDfs =
                (DistributedFileSystem) FileSystem.get(conf);
            return myDfs.addPathBasedCacheDirective(directive);
          }
        });
  }

  @Test(timeout=60000)
  public void testAddRemoveDirectives() throws Exception {
    proto.addCachePool(new CachePoolInfo("pool1").
        setMode(new FsPermission((short)0777)));
    proto.addCachePool(new CachePoolInfo("pool2").
        setMode(new FsPermission((short)0777)));
    proto.addCachePool(new CachePoolInfo("pool3").
        setMode(new FsPermission((short)0777)));
    proto.addCachePool(new CachePoolInfo("pool4").
        setMode(new FsPermission((short)0)));

    PathBasedCacheDirective alpha = new PathBasedCacheDirective.Builder().
        setPath(new Path("/alpha")).
        setPool("pool1").
        build();
    PathBasedCacheDirective beta = new PathBasedCacheDirective.Builder().
        setPath(new Path("/beta")).
        setPool("pool2").
        build();
    PathBasedCacheDirective delta = new PathBasedCacheDirective.Builder().
        setPath(new Path("/delta")).
        setPool("pool1").
        build();

    PathBasedCacheDescriptor alphaD = addAsUnprivileged(alpha);
    PathBasedCacheDescriptor alphaD2 = addAsUnprivileged(alpha);
    assertFalse("Expected to get unique descriptors when re-adding an "
        + "existing PathBasedCacheDirective",
        alphaD.getEntryId() == alphaD2.getEntryId());
    PathBasedCacheDescriptor betaD = addAsUnprivileged(beta);

    try {
      addAsUnprivileged(new PathBasedCacheDirective.Builder().
          setPath(new Path("/unicorn")).
          setPool("no_such_pool").
          build());
      fail("expected an error when adding to a non-existent pool.");
    } catch (IOException ioe) {
      assertTrue(ioe instanceof InvalidPoolNameError);
    }

    try {
      addAsUnprivileged(new PathBasedCacheDirective.Builder().
          setPath(new Path("/blackhole")).
          setPool("pool4").
          build());
      fail("expected an error when adding to a pool with " +
          "mode 0 (no permissions for anyone).");
    } catch (IOException ioe) {
      assertTrue(ioe instanceof PoolWritePermissionDeniedError);
    }

    try {
      addAsUnprivileged(new PathBasedCacheDirective.Builder().
          setPath(new Path("/illegal:path/")).
          setPool("pool1").
          build());
      fail("expected an error when adding a malformed path " +
          "to the cache directives.");
    } catch (IllegalArgumentException e) {
      // expected
    }

    try {
      addAsUnprivileged(new PathBasedCacheDirective.Builder().
          setPath(new Path("/emptypoolname")).
          setReplication((short)1).
          setPool("").
          build());
      Assert.fail("expected an error when adding a PathBasedCache " +
          "directive with an empty pool name.");
    } catch (IOException ioe) {
      Assert.assertTrue(ioe instanceof InvalidPoolNameError);
    }

    PathBasedCacheDescriptor deltaD = addAsUnprivileged(delta);

    // We expect the following to succeed, because DistributedFileSystem
    // qualifies the path.
    PathBasedCacheDescriptor relativeD = addAsUnprivileged(
        new PathBasedCacheDirective.Builder().
            setPath(new Path("relative")).
            setPool("pool1").
            build());

    RemoteIterator<PathBasedCacheDescriptor> iter;
    iter = dfs.listPathBasedCacheDescriptors(null, null);
    validateListAll(iter, alphaD, alphaD2, betaD, deltaD, relativeD);
    iter = dfs.listPathBasedCacheDescriptors("pool3", null);
    Assert.assertFalse(iter.hasNext());
    iter = dfs.listPathBasedCacheDescriptors("pool1", null);
    validateListAll(iter, alphaD, alphaD2, deltaD, relativeD);
    iter = dfs.listPathBasedCacheDescriptors("pool2", null);
    validateListAll(iter, betaD);

    dfs.removePathBasedCacheDescriptor(betaD);
    iter = dfs.listPathBasedCacheDescriptors("pool2", null);
    Assert.assertFalse(iter.hasNext());

    try {
      dfs.removePathBasedCacheDescriptor(betaD);
      Assert.fail("expected an error when removing a non-existent ID");
    } catch (IOException ioe) {
      Assert.assertTrue(ioe instanceof NoSuchIdException);
    }

    try {
      proto.removePathBasedCacheDescriptor(-42l);
      Assert.fail("expected an error when removing a negative ID");
    } catch (IOException ioe) {
      Assert.assertTrue(ioe instanceof InvalidIdException);
    }
    try {
      proto.removePathBasedCacheDescriptor(43l);
      Assert.fail("expected an error when removing a non-existent ID");
    } catch (IOException ioe) {
      Assert.assertTrue(ioe instanceof NoSuchIdException);
    }

    dfs.removePathBasedCacheDescriptor(alphaD);
    dfs.removePathBasedCacheDescriptor(alphaD2);
    dfs.removePathBasedCacheDescriptor(deltaD);
    dfs.removePathBasedCacheDescriptor(relativeD);
    iter = dfs.listPathBasedCacheDescriptors(null, null);
    assertFalse(iter.hasNext());
  }

  @Test(timeout=60000)
  public void testCacheManagerRestart() throws Exception {
    HdfsConfiguration conf = createCachingConf();
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(0).build();

    try {
      cluster.waitActive();
      DistributedFileSystem dfs = cluster.getFileSystem();

      // Create and validate a pool
      final String pool = "poolparty";
      String groupName = "partygroup";
      FsPermission mode = new FsPermission((short)0777);
      int weight = 747;
      dfs.addCachePool(new CachePoolInfo(pool)
          .setGroupName(groupName)
          .setMode(mode)
          .setWeight(weight));
      RemoteIterator<CachePoolInfo> pit = dfs.listCachePools();
      assertTrue("No cache pools found", pit.hasNext());
      CachePoolInfo info = pit.next();
      assertEquals(pool, info.getPoolName());
      assertEquals(groupName, info.getGroupName());
      assertEquals(mode, info.getMode());
      assertEquals(weight, (int)info.getWeight());
      assertFalse("Unexpected # of cache pools found", pit.hasNext());
  
      // Create some cache entries
      int numEntries = 10;
      String entryPrefix = "/party-";
      for (int i=0; i<numEntries; i++) {
        dfs.addPathBasedCacheDirective(
            new PathBasedCacheDirective.Builder().
              setPath(new Path(entryPrefix + i)).setPool(pool).build());
      }
      RemoteIterator<PathBasedCacheDescriptor> dit
          = dfs.listPathBasedCacheDescriptors(null, null);
      for (int i=0; i<numEntries; i++) {
        assertTrue("Unexpected # of cache entries: " + i, dit.hasNext());
        PathBasedCacheDescriptor cd = dit.next();
        assertEquals(i+1, cd.getEntryId());
        assertEquals(entryPrefix + i, cd.getPath().toUri().getPath());
        assertEquals(pool, cd.getPool());
      }
      assertFalse("Unexpected # of cache descriptors found", dit.hasNext());
  
      // Restart namenode
      cluster.restartNameNode();
  
      // Check that state came back up
      pit = dfs.listCachePools();
      assertTrue("No cache pools found", pit.hasNext());
      info = pit.next();
      assertEquals(pool, info.getPoolName());
      assertEquals(pool, info.getPoolName());
      assertEquals(groupName, info.getGroupName());
      assertEquals(mode, info.getMode());
      assertEquals(weight, (int)info.getWeight());
      assertFalse("Unexpected # of cache pools found", pit.hasNext());
  
      dit = dfs.listPathBasedCacheDescriptors(null, null);
      for (int i=0; i<numEntries; i++) {
        assertTrue("Unexpected # of cache entries: " + i, dit.hasNext());
        PathBasedCacheDescriptor cd = dit.next();
        assertEquals(i+1, cd.getEntryId());
        assertEquals(entryPrefix + i, cd.getPath().toUri().getPath());
        assertEquals(pool, cd.getPool());
      }
      assertFalse("Unexpected # of cache descriptors found", dit.hasNext());
    } finally {
      cluster.shutdown();
    }
  }

  private static void waitForCachedBlocks(NameNode nn,
      final int expectedCachedBlocks, final int expectedCachedReplicas) 
          throws Exception {
    final FSNamesystem namesystem = nn.getNamesystem();
    final CacheManager cacheManager = namesystem.getCacheManager();
    LOG.info("Waiting for " + expectedCachedBlocks + " blocks with " +
             expectedCachedReplicas + " replicas.");
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        int numCachedBlocks = 0, numCachedReplicas = 0;
        namesystem.readLock();
        try {
          GSet<CachedBlock, CachedBlock> cachedBlocks =
              cacheManager.getCachedBlocks();
          if (cachedBlocks != null) {
            for (Iterator<CachedBlock> iter = cachedBlocks.iterator();
                iter.hasNext(); ) {
              CachedBlock cachedBlock = iter.next();
              numCachedBlocks++;
              numCachedReplicas += cachedBlock.getDatanodes(Type.CACHED).size();
            }
          }
        } finally {
          namesystem.readUnlock();
        }
        if ((numCachedBlocks == expectedCachedBlocks) && 
            (numCachedReplicas == expectedCachedReplicas)) {
          return true;
        } else {
          LOG.info("cached blocks: have " + numCachedBlocks +
              " / " + expectedCachedBlocks);
          LOG.info("cached replicas: have " + numCachedReplicas +
              " / " + expectedCachedReplicas);
          return false;
        }
      }
    }, 500, 60000);
  }

  private static final long BLOCK_SIZE = 512;
  private static final int NUM_DATANODES = 4;

  // Most Linux installs will allow non-root users to lock 64KB.
  private static final long CACHE_CAPACITY = 64 * 1024 / NUM_DATANODES;

  /**
   * Return true if we can test DN caching.
   */
  private static boolean canTestDatanodeCaching() {
    if (!NativeIO.isAvailable()) {
      // Need NativeIO in order to cache blocks on the DN.
      return false;
    }
    if (NativeIO.getMemlockLimit() < CACHE_CAPACITY) {
      return false;
    }
    return true;
  }

  private static HdfsConfiguration createCachingConf() {
    HdfsConfiguration conf = new HdfsConfiguration();
    conf.setLong(DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    conf.setLong(DFS_DATANODE_MAX_LOCKED_MEMORY_KEY, CACHE_CAPACITY);
    conf.setLong(DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setBoolean(DFS_NAMENODE_CACHING_ENABLED_KEY, true);
    conf.setLong(DFS_CACHEREPORT_INTERVAL_MSEC_KEY, 1000);
    conf.setLong(DFS_NAMENODE_PATH_BASED_CACHE_REFRESH_INTERVAL_MS, 1000);
    return conf;
  }

  @Test(timeout=120000)
  public void testWaitForCachedReplicas() throws Exception {
    Assume.assumeTrue(canTestDatanodeCaching());
    HdfsConfiguration conf = createCachingConf();
    FileSystemTestHelper helper = new FileSystemTestHelper();
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();

    try {
      cluster.waitActive();
      DistributedFileSystem dfs = cluster.getFileSystem();
      NameNode namenode = cluster.getNameNode();
      NamenodeProtocols nnRpc = namenode.getRpcServer();
      Path rootDir = helper.getDefaultWorkingDirectory(dfs);
      // Create the pool
      final String pool = "friendlyPool";
      nnRpc.addCachePool(new CachePoolInfo("friendlyPool"));
      // Create some test files
      final int numFiles = 2;
      final int numBlocksPerFile = 2;
      final List<String> paths = new ArrayList<String>(numFiles);
      for (int i=0; i<numFiles; i++) {
        Path p = new Path(rootDir, "testCachePaths-" + i);
        FileSystemTestHelper.createFile(dfs, p, numBlocksPerFile,
            (int)BLOCK_SIZE);
        paths.add(p.toUri().getPath());
      }
      // Check the initial statistics at the namenode
      waitForCachedBlocks(namenode, 0, 0);
      // Cache and check each path in sequence
      int expected = 0;
      for (int i=0; i<numFiles; i++) {
        PathBasedCacheDirective directive =
            new PathBasedCacheDirective.Builder().
              setPath(new Path(paths.get(i))).
              setPool(pool).
              build();
        PathBasedCacheDescriptor descriptor =
            nnRpc.addPathBasedCacheDirective(directive);
        assertEquals("Descriptor does not match requested path",
            new Path(paths.get(i)), descriptor.getPath());
        assertEquals("Descriptor does not match requested pool", pool,
            descriptor.getPool());
        expected += numBlocksPerFile;
        waitForCachedBlocks(namenode, expected, expected);
      }
      // Uncache and check each path in sequence
      RemoteIterator<PathBasedCacheDescriptor> entries =
          nnRpc.listPathBasedCacheDescriptors(0, null, null);
      for (int i=0; i<numFiles; i++) {
        PathBasedCacheDescriptor descriptor = entries.next();
        nnRpc.removePathBasedCacheDescriptor(descriptor.getEntryId());
        expected -= numBlocksPerFile;
        waitForCachedBlocks(namenode, expected, expected);
      }
    } finally {
      cluster.shutdown();
    }
  }

  @Test(timeout=120000)
  public void testAddingPathBasedCacheDirectivesWhenCachingIsDisabled()
      throws Exception {
    Assume.assumeTrue(canTestDatanodeCaching());
    HdfsConfiguration conf = createCachingConf();
    conf.setBoolean(DFS_NAMENODE_CACHING_ENABLED_KEY, false);
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();

    try {
      cluster.waitActive();
      DistributedFileSystem dfs = cluster.getFileSystem();
      NameNode namenode = cluster.getNameNode();
      // Create the pool
      String pool = "pool1";
      namenode.getRpcServer().addCachePool(new CachePoolInfo(pool));
      // Create some test files
      final int numFiles = 2;
      final int numBlocksPerFile = 2;
      final List<String> paths = new ArrayList<String>(numFiles);
      for (int i=0; i<numFiles; i++) {
        Path p = new Path("/testCachePaths-" + i);
        FileSystemTestHelper.createFile(dfs, p, numBlocksPerFile,
            (int)BLOCK_SIZE);
        paths.add(p.toUri().getPath());
      }
      // Check the initial statistics at the namenode
      waitForCachedBlocks(namenode, 0, 0);
      // Cache and check each path in sequence
      int expected = 0;
      for (int i=0; i<numFiles; i++) {
        PathBasedCacheDirective directive =
            new PathBasedCacheDirective.Builder().
              setPath(new Path(paths.get(i))).
              setPool(pool).
              build();
        dfs.addPathBasedCacheDirective(directive);
        waitForCachedBlocks(namenode, expected, 0);
      }
      Thread.sleep(20000);
      waitForCachedBlocks(namenode, expected, 0);
    } finally {
      cluster.shutdown();
    }
  }

  @Test(timeout=120000)
  public void testWaitForCachedReplicasInDirectory() throws Exception {
    Assume.assumeTrue(canTestDatanodeCaching());
    HdfsConfiguration conf = createCachingConf();
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();

    try {
      cluster.waitActive();
      DistributedFileSystem dfs = cluster.getFileSystem();
      NameNode namenode = cluster.getNameNode();
      // Create the pool
      final String pool = "friendlyPool";
      dfs.addCachePool(new CachePoolInfo(pool));
      // Create some test files
      final List<Path> paths = new LinkedList<Path>();
      paths.add(new Path("/foo/bar"));
      paths.add(new Path("/foo/baz"));
      paths.add(new Path("/foo2/bar2"));
      paths.add(new Path("/foo2/baz2"));
      dfs.mkdir(new Path("/foo"), FsPermission.getDirDefault());
      dfs.mkdir(new Path("/foo2"), FsPermission.getDirDefault());
      final int numBlocksPerFile = 2;
      for (Path path : paths) {
        FileSystemTestHelper.createFile(dfs, path, numBlocksPerFile,
            (int)BLOCK_SIZE, (short)3, false);
      }
      waitForCachedBlocks(namenode, 0, 0);
      // cache entire directory
      PathBasedCacheDescriptor descriptor = dfs.addPathBasedCacheDirective(
            new PathBasedCacheDirective.Builder().
              setPath(new Path("/foo")).
              setReplication((short)2).
              setPool(pool).
              build());
      assertEquals("Descriptor does not match requested pool", pool,
          descriptor.getPool());
      waitForCachedBlocks(namenode, 4, 8);
      // remove and watch numCached go to 0
      dfs.removePathBasedCacheDescriptor(descriptor);
      waitForCachedBlocks(namenode, 0, 0);
    } finally {
      cluster.shutdown();
    }
  }

}
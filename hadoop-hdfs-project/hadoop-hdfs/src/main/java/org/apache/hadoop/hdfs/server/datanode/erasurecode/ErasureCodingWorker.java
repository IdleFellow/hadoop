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
package org.apache.hadoop.hdfs.server.datanode.erasurecode;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.BlockReader;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSPacket;
import org.apache.hadoop.hdfs.DFSUtilClient;
import org.apache.hadoop.hdfs.DFSUtilClient.CorruptedBlocks;
import org.apache.hadoop.hdfs.RemoteBlockReader2;
import org.apache.hadoop.hdfs.net.Peer;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.datatransfer.BlockConstructionStage;
import org.apache.hadoop.hdfs.protocol.datatransfer.IOStreamPair;
import org.apache.hadoop.hdfs.protocol.datatransfer.PacketHeader;
import org.apache.hadoop.hdfs.protocol.datatransfer.Sender;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.DataEncryptionKeyFactory;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.server.datanode.CachingStrategy;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.protocol.BlockECReconstructionCommand.BlockECReconstructionInfo;
import org.apache.hadoop.hdfs.util.StripedBlockUtil;
import org.apache.hadoop.hdfs.util.StripedBlockUtil.StripingChunkReadResult;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.erasurecode.CodecUtil;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.io.erasurecode.rawcoder.RawErasureDecoder;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.DataChecksum;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;

/**
 * ErasureCodingWorker handles the erasure coding reconstruction work commands.
 * These commands would be issued from Namenode as part of Datanode's heart
 * beat response. BPOfferService delegates the work to this class for handling
 * EC commands.
 */
@InterfaceAudience.Private
public final class ErasureCodingWorker {
  private static final Logger LOG = DataNode.LOG;
  
  private final DataNode datanode; 
  private final Configuration conf;

  private ThreadPoolExecutor EC_RECONSTRUCTION_STRIPED_BLK_THREAD_POOL;
  private ThreadPoolExecutor EC_RECONSTRUCTION_STRIPED_READ_THREAD_POOL;
  private final int EC_RECONSTRUCTION_STRIPED_READ_TIMEOUT_MILLIS;
  private final int EC_RECONSTRUCTION_STRIPED_READ_BUFFER_SIZE;

  public ErasureCodingWorker(Configuration conf, DataNode datanode) {
    this.datanode = datanode;
    this.conf = conf;

    EC_RECONSTRUCTION_STRIPED_READ_TIMEOUT_MILLIS = conf.getInt(
        DFSConfigKeys.DFS_DN_EC_RECONSTRUCTION_STRIPED_READ_TIMEOUT_MILLIS_KEY,
        DFSConfigKeys.DFS_DN_EC_RECONSTRUCTION_STRIPED_READ_TIMEOUT_MILLIS_DEFAULT);
    initializeStripedReadThreadPool(conf.getInt(
        DFSConfigKeys.DFS_DN_EC_RECONSTRUCTION_STRIPED_READ_THREADS_KEY,
        DFSConfigKeys.DFS_DN_EC_RECONSTRUCTION_STRIPED_READ_THREADS_DEFAULT));
    EC_RECONSTRUCTION_STRIPED_READ_BUFFER_SIZE = conf.getInt(
        DFSConfigKeys.DFS_DN_EC_RECONSTRUCTION_STRIPED_READ_BUFFER_SIZE_KEY,
        DFSConfigKeys.DFS_DN_EC_RECONSTRUCTION_STRIPED_READ_BUFFER_SIZE_DEFAULT);

    initializeStripedBlkReconstructionThreadPool(conf.getInt(
        DFSConfigKeys.DFS_DN_EC_RECONSTRUCTION_STRIPED_BLK_THREADS_KEY,
        DFSConfigKeys.DFS_DN_EC_RECONSTRUCTION_STRIPED_BLK_THREADS_DEFAULT));
  }
  
  private RawErasureDecoder newDecoder(int numDataUnits, int numParityUnits) {
    return CodecUtil.createRSRawDecoder(conf, numDataUnits, numParityUnits);
  }

  private void initializeStripedReadThreadPool(int num) {
    LOG.debug("Using striped reads; pool threads={}", num);

    EC_RECONSTRUCTION_STRIPED_READ_THREAD_POOL = new ThreadPoolExecutor(1, num,
        60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
        new Daemon.DaemonFactory() {
      private final AtomicInteger threadIndex = new AtomicInteger(0);

      @Override
      public Thread newThread(Runnable r) {
        Thread t = super.newThread(r);
        t.setName("stripedRead-" + threadIndex.getAndIncrement());
        return t;
      }
    }, new ThreadPoolExecutor.CallerRunsPolicy() {
      @Override
      public void rejectedExecution(Runnable runnable, ThreadPoolExecutor e) {
        LOG.info("Execution for striped reading rejected, "
            + "Executing in current thread");
        // will run in the current thread
        super.rejectedExecution(runnable, e);
      }
    });
    EC_RECONSTRUCTION_STRIPED_READ_THREAD_POOL.allowCoreThreadTimeOut(true);
  }

  private void initializeStripedBlkReconstructionThreadPool(int num) {
    LOG.debug("Using striped block reconstruction; pool threads={}" + num);
    EC_RECONSTRUCTION_STRIPED_BLK_THREAD_POOL = new ThreadPoolExecutor(2, num,
        60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
        new Daemon.DaemonFactory() {
          private final AtomicInteger threadIdx = new AtomicInteger(0);

          @Override
          public Thread newThread(Runnable r) {
            Thread t = super.newThread(r);
            t.setName(
                "stripedBlockReconstruction-" + threadIdx.getAndIncrement());
            return t;
          }
        });
    EC_RECONSTRUCTION_STRIPED_BLK_THREAD_POOL.allowCoreThreadTimeOut(true);
  }

  /**
   * Handles the Erasure Coding reconstruction work commands.
   *
   * @param ecTasks
   *          BlockECReconstructionInfo
   */
  public void processErasureCodingTasks(
      Collection<BlockECReconstructionInfo> ecTasks) {
    for (BlockECReconstructionInfo reconstructionInfo : ecTasks) {
      try {
        ReconstructAndTransferBlock task =
            new ReconstructAndTransferBlock(reconstructionInfo);
        if (task.hasValidTargets()) {
          EC_RECONSTRUCTION_STRIPED_BLK_THREAD_POOL.submit(task);
        } else {
          LOG.warn("No missing internal block. Skip reconstruction for task:{}",
              reconstructionInfo);
        }
      } catch (Throwable e) {
        LOG.warn("Failed to reconstruct striped block {}",
            reconstructionInfo.getExtendedBlock().getLocalBlock(), e);
      }
    }
  }

  /**
   * ReconstructAndTransferBlock reconstruct one or more missed striped block
   * in the striped block group, the minimum number of live striped blocks
   * should be no less than data block number.
   * 
   * | <- Striped Block Group -> |
   *  blk_0      blk_1       blk_2(*)   blk_3   ...   <- A striped block group
   *    |          |           |          |  
   *    v          v           v          v 
   * +------+   +------+   +------+   +------+
   * |cell_0|   |cell_1|   |cell_2|   |cell_3|  ...    
   * +------+   +------+   +------+   +------+     
   * |cell_4|   |cell_5|   |cell_6|   |cell_7|  ...
   * +------+   +------+   +------+   +------+
   * |cell_8|   |cell_9|   |cell10|   |cell11|  ...
   * +------+   +------+   +------+   +------+
   *  ...         ...       ...         ...
   *  
   * 
   * We use following steps to reconstruct striped block group, in each round,
   * we reconstruct <code>bufferSize</code> data until finish, the
   * <code>bufferSize</code> is configurable and may be less or larger than 
   * cell size:
   * step1: read <code>bufferSize</code> data from minimum number of sources 
   *        required by reconstruction.
   * step2: decode data for targets.
   * step3: transfer data to targets.
   * 
   * In step1, try to read <code>bufferSize</code> data from minimum number
   * of sources , if there is corrupt or stale sources, read from new source
   * will be scheduled. The best sources are remembered for next round and 
   * may be updated in each round.
   * 
   * In step2, typically if source blocks we read are all data blocks, we
   * need to call encode, and if there is one parity block, we need to call
   * decode. Notice we only read once and reconstruct all missed striped block
   * if they are more than one.
   * 
   * In step3, send the reconstructed data to targets by constructing packet
   * and send them directly. Same as continuous block replication, we
   * don't check the packet ack. Since the datanode doing the reconstruction
   * work are one of the source datanodes, so the reconstructed data are sent
   * remotely.
   * 
   * There are some points we can do further improvements in next phase:
   * 1. we can read the block file directly on the local datanode, 
   *    currently we use remote block reader. (Notice short-circuit is not
   *    a good choice, see inline comments).
   * 2. We need to check the packet ack for EC reconstruction? Since EC
   *    reconstruction is more expensive than continuous block replication,
   *    it needs to read from several other datanodes, should we make sure
   *    the reconstructed result received by targets?
   */
  private class ReconstructAndTransferBlock implements Runnable {
    private final int dataBlkNum;
    private final int parityBlkNum;
    private final int cellSize;
    
    private RawErasureDecoder decoder;

    // Striped read buffer size
    private int bufferSize;

    private final ExtendedBlock blockGroup;
    private final int minRequiredSources;
    // position in striped internal block
    private long positionInBlock;

    // sources
    private final byte[] liveIndices;
    private final DatanodeInfo[] sources;

    private final List<StripedReader> stripedReaders;

    // The buffers and indices for striped blocks whose length is 0
    private ByteBuffer[] zeroStripeBuffers;
    private short[] zeroStripeIndices;

    // targets
    private final DatanodeInfo[] targets;
    private final StorageType[] targetStorageTypes;

    private final short[] targetIndices;
    private final ByteBuffer[] targetBuffers;

    private final Socket[] targetSockets;
    private final DataOutputStream[] targetOutputStreams;
    private final DataInputStream[] targetInputStreams;

    private final long[] blockOffset4Targets;
    private final long[] seqNo4Targets;

    private final static int WRITE_PACKET_SIZE = 64 * 1024;
    private DataChecksum checksum;
    private int maxChunksPerPacket;
    private byte[] packetBuf;
    private byte[] checksumBuf;
    private int bytesPerChecksum;
    private int checksumSize;

    private final CachingStrategy cachingStrategy;

    private final Map<Future<Void>, Integer> futures = new HashMap<>();
    private final CompletionService<Void> readService =
        new ExecutorCompletionService<>(
            EC_RECONSTRUCTION_STRIPED_READ_THREAD_POOL);
    private final boolean hasValidTargets;

    ReconstructAndTransferBlock(BlockECReconstructionInfo reconstructionInfo) {
      ErasureCodingPolicy ecPolicy = reconstructionInfo
          .getErasureCodingPolicy();
      dataBlkNum = ecPolicy.getNumDataUnits();
      parityBlkNum = ecPolicy.getNumParityUnits();
      cellSize = ecPolicy.getCellSize();

      blockGroup = reconstructionInfo.getExtendedBlock();
      final int cellsNum = (int)((blockGroup.getNumBytes() - 1) / cellSize + 1);
      minRequiredSources = Math.min(cellsNum, dataBlkNum);

      liveIndices = reconstructionInfo.getLiveBlockIndices();
      sources = reconstructionInfo.getSourceDnInfos();
      stripedReaders = new ArrayList<>(sources.length);

      Preconditions.checkArgument(liveIndices.length >= minRequiredSources,
          "No enough live striped blocks.");
      Preconditions.checkArgument(liveIndices.length == sources.length,
          "liveBlockIndices and source dns should match");

      if (minRequiredSources < dataBlkNum) {
        zeroStripeBuffers = 
            new ByteBuffer[dataBlkNum - minRequiredSources];
        zeroStripeIndices = new short[dataBlkNum - minRequiredSources];
      }

      targets = reconstructionInfo.getTargetDnInfos();
      targetStorageTypes = reconstructionInfo.getTargetStorageTypes();
      targetIndices = new short[targets.length];
      targetBuffers = new ByteBuffer[targets.length];

      Preconditions.checkArgument(targetIndices.length <= parityBlkNum,
          "Too much missed striped blocks.");

      targetSockets = new Socket[targets.length];
      targetOutputStreams = new DataOutputStream[targets.length];
      targetInputStreams = new DataInputStream[targets.length];

      blockOffset4Targets = new long[targets.length];
      seqNo4Targets = new long[targets.length];

      for (int i = 0; i < targets.length; i++) {
        blockOffset4Targets[i] = 0;
        seqNo4Targets[i] = 0;
      }

      hasValidTargets = getTargetIndices();
      cachingStrategy = CachingStrategy.newDefaultStrategy();
    }

    boolean hasValidTargets() {
      return hasValidTargets;
    }

    private ByteBuffer allocateBuffer(int length) {
      return ByteBuffer.allocate(length);
    }

    private ExtendedBlock getBlock(ExtendedBlock blockGroup, int i) {
      return StripedBlockUtil.constructInternalBlock(blockGroup, cellSize,
          dataBlkNum, i);
    }

    private long getBlockLen(ExtendedBlock blockGroup, int i) { 
      return StripedBlockUtil.getInternalBlockLength(blockGroup.getNumBytes(),
          cellSize, dataBlkNum, i);
    }

    /**
     * StripedReader is used to read from one source DN, it contains a block
     * reader, buffer and striped block index.
     * Only allocate StripedReader once for one source, and the StripedReader
     * has the same array order with sources. Typically we only need to allocate
     * minimum number (minRequiredSources) of StripedReader, and allocate
     * new for new source DN if some existing DN invalid or slow.
     * If some source DN is corrupt, set the corresponding blockReader to 
     * null and will never read from it again.
     *  
     * @param i the array index of sources
     * @param offsetInBlock offset for the internal block
     * @return StripedReader
     */
    private StripedReader addStripedReader(int i, long offsetInBlock) {
      final ExtendedBlock block = getBlock(blockGroup, liveIndices[i]);
      StripedReader reader = new StripedReader(liveIndices[i], block, sources[i]);
      stripedReaders.add(reader);

      BlockReader blockReader = newBlockReader(block, offsetInBlock, sources[i]);
      if (blockReader != null) {
        initChecksumAndBufferSizeIfNeeded(blockReader);
        reader.blockReader = blockReader;
      }
      reader.buffer = allocateBuffer(bufferSize);
      return reader;
    }

    @Override
    public void run() {
      datanode.incrementXmitsInProgress();
      try {
        // Store the array indices of source DNs we have read successfully.
        // In each iteration of read, the success list may be updated if
        // some source DN is corrupted or slow. And use the updated success
        // list of DNs for next iteration read.
        int[] success = new int[minRequiredSources];

        int nsuccess = 0;
        for (int i = 0; 
            i < sources.length && nsuccess < minRequiredSources; i++) {
          StripedReader reader = addStripedReader(i, 0);
          if (reader.blockReader != null) {
            success[nsuccess++] = i;
          }
        }

        if (nsuccess < minRequiredSources) {
          String error = "Can't find minimum sources required by "
              + "reconstruction, block id: " + blockGroup.getBlockId();
          throw new IOException(error);
        }

        if (zeroStripeBuffers != null) {
          for (int i = 0; i < zeroStripeBuffers.length; i++) {
            zeroStripeBuffers[i] = allocateBuffer(bufferSize);
          }
        }

        for (int i = 0; i < targets.length; i++) {
          targetBuffers[i] = allocateBuffer(bufferSize);
        }

        checksumSize = checksum.getChecksumSize();
        int chunkSize = bytesPerChecksum + checksumSize;
        maxChunksPerPacket = Math.max(
            (WRITE_PACKET_SIZE - PacketHeader.PKT_MAX_HEADER_LEN)/chunkSize, 1);
        int maxPacketSize = chunkSize * maxChunksPerPacket 
            + PacketHeader.PKT_MAX_HEADER_LEN;

        packetBuf = new byte[maxPacketSize];
        checksumBuf = new byte[checksumSize * (bufferSize / bytesPerChecksum)];

        // targetsStatus store whether some target is success, it will record
        // any failed target once, if some target failed (invalid DN or transfer
        // failed), will not transfer data to it any more.
        boolean[] targetsStatus = new boolean[targets.length];
        if (initTargetStreams(targetsStatus) == 0) {
          String error = "All targets are failed.";
          throw new IOException(error);
        }

        long maxTargetLength = 0;
        for (short targetIndex : targetIndices) {
          maxTargetLength = Math.max(maxTargetLength,
              getBlockLen(blockGroup, targetIndex));
        }
        while (positionInBlock < maxTargetLength) {
          final int toReconstruct = (int) Math.min(
              bufferSize, maxTargetLength - positionInBlock);
          // step1: read from minimum source DNs required for reconstruction.
          // The returned success list is the source DNs we do real read from
          CorruptedBlocks corruptedBlocks = new CorruptedBlocks();
          try {
            success = readMinimumStripedData4Reconstruction(success,
                toReconstruct, corruptedBlocks);
          } finally {
            // report corrupted blocks to NN
            datanode.reportCorruptedBlocks(corruptedBlocks);
          }

          // step2: decode to reconstruct targets
          reconstructTargets(success, targetsStatus, toReconstruct);

          // step3: transfer data
          if (transferData2Targets(targetsStatus) == 0) {
            String error = "Transfer failed for all targets.";
            throw new IOException(error);
          }

          clearBuffers();
          positionInBlock += toReconstruct;
        }

        endTargetBlocks(targetsStatus);

        // Currently we don't check the acks for packets, this is similar as
        // block replication.
      } catch (Throwable e) {
        LOG.warn("Failed to reconstruct striped block: {}", blockGroup, e);
      } finally {
        datanode.decrementXmitsInProgress();
        // close block readers
        for (StripedReader stripedReader : stripedReaders) {
          IOUtils.closeStream(stripedReader.blockReader);
        }
        for (int i = 0; i < targets.length; i++) {
          IOUtils.closeStream(targetOutputStreams[i]);
          IOUtils.closeStream(targetInputStreams[i]);
          IOUtils.closeStream(targetSockets[i]);
        }
      }
    }

    // init checksum from block reader
    private void initChecksumAndBufferSizeIfNeeded(BlockReader blockReader) {
      if (checksum == null) {
        checksum = blockReader.getDataChecksum();
        bytesPerChecksum = checksum.getBytesPerChecksum();
        // The bufferSize is flat to divide bytesPerChecksum
        int readBufferSize = EC_RECONSTRUCTION_STRIPED_READ_BUFFER_SIZE;
        bufferSize = readBufferSize < bytesPerChecksum ? bytesPerChecksum :
          readBufferSize - readBufferSize % bytesPerChecksum;
      } else {
        assert blockReader.getDataChecksum().equals(checksum);
      }
    }

    /**
     * @return true if there is valid target for reconstruction
     */
    private boolean getTargetIndices() {
      BitSet bitset = new BitSet(dataBlkNum + parityBlkNum);
      for (int i = 0; i < sources.length; i++) {
        bitset.set(liveIndices[i]);
      }
      int m = 0;
      int k = 0;
      boolean hasValidTarget = false;
      for (int i = 0; i < dataBlkNum + parityBlkNum; i++) {
        if (!bitset.get(i)) {
          if (getBlockLen(blockGroup, i) > 0) {
            if (m < targets.length) {
              targetIndices[m++] = (short)i;
              hasValidTarget = true;
            }
          } else {
            zeroStripeIndices[k++] = (short)i;
          }
        }
      }
      return hasValidTarget;
    }

    /** the reading length should not exceed the length for reconstruction. */
    private int getReadLength(int index, int reconstructLength) {
      long blockLen = getBlockLen(blockGroup, index);
      long remaining = blockLen - positionInBlock;
      return (int) Math.min(remaining, reconstructLength);
    }

    /**
     * Read from minimum source DNs required for reconstruction in the iteration.
     * First try the success list which we think they are the best DNs
     * If source DN is corrupt or slow, try to read some other source DN, 
     * and will update the success list. 
     * 
     * Remember the updated success list and return it for following 
     * operations and next iteration read.
     * 
     * @param success the initial success list of source DNs we think best
     * @param reconstructLength the length to reconstruct.
     * @return updated success list of source DNs we do real read
     * @throws IOException
     */
    private int[] readMinimumStripedData4Reconstruction(final int[] success,
        int reconstructLength, CorruptedBlocks corruptedBlocks)
            throws IOException {
      Preconditions.checkArgument(reconstructLength >= 0 &&
          reconstructLength <= bufferSize);
      int nsuccess = 0;
      int[] newSuccess = new int[minRequiredSources];
      BitSet used = new BitSet(sources.length);
      /*
       * Read from minimum source DNs required, the success list contains
       * source DNs which we think best.
       */
      for (int i = 0; i < minRequiredSources; i++) {
        StripedReader reader = stripedReaders.get(success[i]);
        final int toRead = getReadLength(liveIndices[success[i]],
            reconstructLength);
        if (toRead > 0) {
          Callable<Void> readCallable = readFromBlock(reader, reader.buffer,
              toRead, corruptedBlocks);
          Future<Void> f = readService.submit(readCallable);
          futures.put(f, success[i]);
        } else {
          // If the read length is 0, we don't need to do real read
          reader.buffer.position(0);
          newSuccess[nsuccess++] = success[i];
        }
        used.set(success[i]);
      }

      while (!futures.isEmpty()) {
        try {
          StripingChunkReadResult result = StripedBlockUtil
              .getNextCompletedStripedRead(readService, futures,
                  EC_RECONSTRUCTION_STRIPED_READ_TIMEOUT_MILLIS);
          int resultIndex = -1;
          if (result.state == StripingChunkReadResult.SUCCESSFUL) {
            resultIndex = result.index;
          } else if (result.state == StripingChunkReadResult.FAILED) {
            // If read failed for some source DN, we should not use it anymore 
            // and schedule read from another source DN.
            StripedReader failedReader = stripedReaders.get(result.index);
            IOUtils.closeStream(failedReader.blockReader);
            failedReader.blockReader = null;
            resultIndex = scheduleNewRead(used, reconstructLength,
                corruptedBlocks);
          } else if (result.state == StripingChunkReadResult.TIMEOUT) {
            // If timeout, we also schedule a new read.
            resultIndex = scheduleNewRead(used, reconstructLength,
                corruptedBlocks);
          }
          if (resultIndex >= 0) {
            newSuccess[nsuccess++] = resultIndex;
            if (nsuccess >= minRequiredSources) {
              // cancel remaining reads if we read successfully from minimum
              // number of source DNs required by reconstruction.
              cancelReads(futures.keySet());
              futures.clear();
              break;
            }
          }
        } catch (InterruptedException e) {
          LOG.info("Read data interrupted.", e);
          cancelReads(futures.keySet());
          futures.clear();
          break;
        }
      }

      if (nsuccess < minRequiredSources) {
        String error = "Can't read data from minimum number of sources "
            + "required by reconstruction, block id: " + blockGroup.getBlockId();
        throw new IOException(error);
      }

      return newSuccess;
    }
    
    private void paddingBufferToLen(ByteBuffer buffer, int len) {
      if (len > buffer.limit()) {
        buffer.limit(len);
      }
      int toPadding = len - buffer.position();
      for (int i = 0; i < toPadding; i++) {
        buffer.put((byte) 0);
      }
    }
    
    // Initialize decoder
    private void initDecoderIfNecessary() {
      if (decoder == null) {
        decoder = newDecoder(dataBlkNum, parityBlkNum);
      }
    }

    private int[] getErasedIndices(boolean[] targetsStatus) {
      int[] result = new int[targets.length];
      int m = 0;
      for (int i = 0; i < targets.length; i++) {
        if (targetsStatus[i]) {
          result[m++] = targetIndices[i];
        }
      }
      return Arrays.copyOf(result, m);
    }

    private void reconstructTargets(int[] success, boolean[] targetsStatus,
        int toReconstructLen) {
      initDecoderIfNecessary();
      ByteBuffer[] inputs = new ByteBuffer[dataBlkNum + parityBlkNum];
      for (int i = 0; i < success.length; i++) {
        StripedReader reader = stripedReaders.get(success[i]);
        ByteBuffer buffer = reader.buffer;
        paddingBufferToLen(buffer, toReconstructLen);
        inputs[reader.index] = (ByteBuffer)buffer.flip();
      }
      if (success.length < dataBlkNum) {
        for (int i = 0; i < zeroStripeBuffers.length; i++) {
          ByteBuffer buffer = zeroStripeBuffers[i];
          paddingBufferToLen(buffer, toReconstructLen);
          int index = zeroStripeIndices[i];
          inputs[index] = (ByteBuffer)buffer.flip();
        }
      }
      int[] erasedIndices = getErasedIndices(targetsStatus);
      ByteBuffer[] outputs = new ByteBuffer[erasedIndices.length];
      int m = 0;
      for (int i = 0; i < targetBuffers.length; i++) {
        if (targetsStatus[i]) {
          targetBuffers[i].limit(toReconstructLen);
          outputs[m++] = targetBuffers[i];
        }
      }
      decoder.decode(inputs, erasedIndices, outputs);

      for (int i = 0; i < targets.length; i++) {
        if (targetsStatus[i]) {
          long blockLen = getBlockLen(blockGroup, targetIndices[i]);
          long remaining = blockLen - positionInBlock;
          if (remaining <= 0) {
            targetBuffers[i].limit(0);
          } else if (remaining < toReconstructLen) {
            targetBuffers[i].limit((int)remaining);
          }
        }
      }
    }

    /**
     * Schedule a read from some new source DN if some DN is corrupted
     * or slow, this is called from the read iteration.
     * Initially we may only have <code>minRequiredSources</code> number of 
     * StripedReader.
     * If the position is at the end of target block, don't need to do 
     * real read, and return the array index of source DN, otherwise -1.
     * 
     * @param used the used source DNs in this iteration.
     * @return the array index of source DN if don't need to do real read.
     */
    private int scheduleNewRead(BitSet used, int reconstructLen,
                                CorruptedBlocks corruptedBlocks) {
      StripedReader reader = null;
      // step1: initially we may only have <code>minRequiredSources</code>
      // number of StripedReader, and there may be some source DNs we never 
      // read before, so will try to create StripedReader for one new source DN
      // and try to read from it. If found, go to step 3.
      int m = stripedReaders.size();
      int toRead = 0;
      while (reader == null && m < sources.length) {
        reader = addStripedReader(m, positionInBlock);
        toRead = getReadLength(liveIndices[m], reconstructLen);
        if (toRead > 0) {
          if (reader.blockReader == null) {
            reader = null;
            m++;
          }
        } else {
          used.set(m);
          return m;
        }
      }

      // step2: if there is no new source DN we can use, try to find a source 
      // DN we ever read from but because some reason, e.g., slow, it
      // is not in the success DN list at the begin of this iteration, so 
      // we have not tried it in this iteration. Now we have a chance to 
      // revisit it again.
      for (int i = 0; reader == null && i < stripedReaders.size(); i++) {
        if (!used.get(i)) {
          StripedReader r = stripedReaders.get(i);
          toRead = getReadLength(liveIndices[i], reconstructLen);
          if (toRead > 0) {
            IOUtils.closeStream(r.blockReader);
            r.blockReader = newBlockReader(
                getBlock(blockGroup, liveIndices[i]), positionInBlock,
                sources[i]);
            if (r.blockReader != null) {
              r.buffer.position(0);
              m = i;
              reader = r;
            }
          } else {
            used.set(i);
            r.buffer.position(0);
            return i;
          }
        }
      }

      // step3: schedule if find a correct source DN and need to do real read.
      if (reader != null) {
        Callable<Void> readCallable = readFromBlock(reader, reader.buffer,
            toRead, corruptedBlocks);
        Future<Void> f = readService.submit(readCallable);
        futures.put(f, m);
        used.set(m);
      }

      return -1;
    }

    // cancel all reads.
    private void cancelReads(Collection<Future<Void>> futures) {
      for (Future<Void> future : futures) {
        future.cancel(true);
      }
    }

    private Callable<Void> readFromBlock(final StripedReader reader,
        final ByteBuffer buf, final int length,
        final CorruptedBlocks corruptedBlocks) {
      return new Callable<Void>() {

        @Override
        public Void call() throws Exception {
          try {
            buf.limit(length);
            actualReadFromBlock(reader.blockReader, buf);
            return null;
          } catch (ChecksumException e) {
            LOG.warn("Found Checksum error for {} from {} at {}", reader.block,
                reader.source, e.getPos());
            corruptedBlocks.addCorruptedBlock(reader.block, reader.source);
            throw e;
          } catch (IOException e) {
            LOG.info(e.getMessage());
            throw e;
          }
        }

      };
    }

    /**
     * Read bytes from block
     */
    private void actualReadFromBlock(BlockReader reader, ByteBuffer buf)
        throws IOException {
      int len = buf.remaining();
      int n = 0;
      while (n < len) {
        int nread = reader.read(buf);
        if (nread <= 0) {
          break;
        }
        n += nread;
      }
    }

    private InetSocketAddress getSocketAddress4Transfer(DatanodeInfo dnInfo) {
      return NetUtils.createSocketAddr(dnInfo.getXferAddr(
          datanode.getDnConf().getConnectToDnViaHostname()));
    }

    private BlockReader newBlockReader(final ExtendedBlock block, 
        long offsetInBlock, DatanodeInfo dnInfo) {
      if (offsetInBlock >= block.getNumBytes()) {
        return null;
      }
      try {
        InetSocketAddress dnAddr = getSocketAddress4Transfer(dnInfo);
        Token<BlockTokenIdentifier> blockToken = datanode.getBlockAccessToken(
            block, EnumSet.of(BlockTokenIdentifier.AccessMode.READ));
        /*
         * This can be further improved if the replica is local, then we can
         * read directly from DN and need to check the replica is FINALIZED
         * state, notice we should not use short-circuit local read which
         * requires config for domain-socket in UNIX or legacy config in Windows.
         */
        return RemoteBlockReader2.newBlockReader(
            "dummy", block, blockToken, offsetInBlock, 
            block.getNumBytes() - offsetInBlock, true,
            "", newConnectedPeer(block, dnAddr, blockToken, dnInfo), dnInfo,
            null, cachingStrategy, datanode.getTracer());
      } catch (IOException e) {
        LOG.debug("Exception while creating remote block reader, datanode {}",
            dnInfo, e);
        return null;
      }
    }

    private Peer newConnectedPeer(ExtendedBlock b, InetSocketAddress addr,
        Token<BlockTokenIdentifier> blockToken, DatanodeID datanodeId)
        throws IOException {
      Peer peer = null;
      boolean success = false;
      Socket sock = null;
      final int socketTimeout = datanode.getDnConf().getSocketTimeout(); 
      try {
        sock = NetUtils.getDefaultSocketFactory(conf).createSocket();
        NetUtils.connect(sock, addr, socketTimeout);
        peer = DFSUtilClient.peerFromSocketAndKey(datanode.getSaslClient(),
            sock, datanode.getDataEncryptionKeyFactoryForBlock(b),
            blockToken, datanodeId);
        peer.setReadTimeout(socketTimeout);
        success = true;
        return peer;
      } finally {
        if (!success) {
          IOUtils.cleanup(null, peer);
          IOUtils.closeSocket(sock);
        }
      }
    }

    /**
     * Send data to targets
     */
    private int transferData2Targets(boolean[] targetsStatus) {
      int nsuccess = 0;
      for (int i = 0; i < targets.length; i++) {
        if (targetsStatus[i]) {
          boolean success = false;
          try {
            ByteBuffer buffer = targetBuffers[i];
            
            if (buffer.remaining() == 0) {
              continue;
            }

            checksum.calculateChunkedSums(
                buffer.array(), 0, buffer.remaining(), checksumBuf, 0);

            int ckOff = 0;
            while (buffer.remaining() > 0) {
              DFSPacket packet = new DFSPacket(packetBuf, maxChunksPerPacket,
                  blockOffset4Targets[i], seqNo4Targets[i]++, checksumSize, false);
              int maxBytesToPacket = maxChunksPerPacket * bytesPerChecksum;
              int toWrite = buffer.remaining() > maxBytesToPacket ?
                  maxBytesToPacket : buffer.remaining();
              int ckLen = ((toWrite - 1) / bytesPerChecksum + 1) * checksumSize;
              packet.writeChecksum(checksumBuf, ckOff, ckLen);
              ckOff += ckLen;
              packet.writeData(buffer, toWrite);

              // Send packet
              packet.writeTo(targetOutputStreams[i]);

              blockOffset4Targets[i] += toWrite;
              nsuccess++;
              success = true;
            }
          } catch (IOException e) {
            LOG.warn(e.getMessage());
          }
          targetsStatus[i] = success;
        }
      }
      return nsuccess;
    }

    /**
     * clear all buffers
     */
    private void clearBuffers() {
      for (StripedReader stripedReader : stripedReaders) {
        if (stripedReader.buffer != null) {
          stripedReader.buffer.clear();
        }
      }

      if (zeroStripeBuffers != null) {
        for (ByteBuffer zeroStripeBuffer : zeroStripeBuffers) {
          zeroStripeBuffer.clear();
        }
      }

      for (ByteBuffer targetBuffer : targetBuffers) {
        if (targetBuffer != null) {
          targetBuffer.clear();
        }
      }
    }

    // send an empty packet to mark the end of the block
    private void endTargetBlocks(boolean[] targetsStatus) {
      for (int i = 0; i < targets.length; i++) {
        if (targetsStatus[i]) {
          try {
            DFSPacket packet = new DFSPacket(packetBuf, 0, 
                blockOffset4Targets[i], seqNo4Targets[i]++, checksumSize, true);
            packet.writeTo(targetOutputStreams[i]);
            targetOutputStreams[i].flush();
          } catch (IOException e) {
            LOG.warn(e.getMessage());
          }
        }
      }
    }

    /**
     * Initialize  output/input streams for transferring data to target
     * and send create block request. 
     */
    private int initTargetStreams(boolean[] targetsStatus) {
      int nsuccess = 0;
      for (int i = 0; i < targets.length; i++) {
        Socket socket = null;
        DataOutputStream out = null;
        DataInputStream in = null;
        boolean success = false;
        try {
          InetSocketAddress targetAddr = 
              getSocketAddress4Transfer(targets[i]);
          socket = datanode.newSocket();
          NetUtils.connect(socket, targetAddr, 
              datanode.getDnConf().getSocketTimeout());
          socket.setSoTimeout(datanode.getDnConf().getSocketTimeout());

          ExtendedBlock block = getBlock(blockGroup, targetIndices[i]);
          Token<BlockTokenIdentifier> blockToken = 
              datanode.getBlockAccessToken(block,
                  EnumSet.of(BlockTokenIdentifier.AccessMode.WRITE));

          long writeTimeout = datanode.getDnConf().getSocketWriteTimeout();
          OutputStream unbufOut = NetUtils.getOutputStream(socket, writeTimeout);
          InputStream unbufIn = NetUtils.getInputStream(socket);
          DataEncryptionKeyFactory keyFactory =
            datanode.getDataEncryptionKeyFactoryForBlock(block);
          IOStreamPair saslStreams = datanode.getSaslClient().socketSend(
              socket, unbufOut, unbufIn, keyFactory, blockToken, targets[i]);

          unbufOut = saslStreams.out;
          unbufIn = saslStreams.in;

          out = new DataOutputStream(new BufferedOutputStream(unbufOut,
              DFSUtilClient.getSmallBufferSize(conf)));
          in = new DataInputStream(unbufIn);

          DatanodeInfo source = new DatanodeInfo(datanode.getDatanodeId());
          new Sender(out).writeBlock(block, targetStorageTypes[i], 
              blockToken, "", new DatanodeInfo[]{targets[i]}, 
              new StorageType[]{targetStorageTypes[i]}, source, 
              BlockConstructionStage.PIPELINE_SETUP_CREATE, 0, 0, 0, 0, 
              checksum, cachingStrategy, false, false, null);

          targetSockets[i] = socket;
          targetOutputStreams[i] = out;
          targetInputStreams[i] = in;
          nsuccess++;
          success = true;
        } catch (Throwable e) {
          LOG.warn(e.getMessage());
        } finally {
          if (!success) {
            IOUtils.closeStream(out);
            IOUtils.closeStream(in);
            IOUtils.closeStream(socket);
          }
        }
        targetsStatus[i] = success;
      }
      return nsuccess;
    }
  }

  private static class StripedReader {
    private final short index; // internal block index
    private BlockReader blockReader;
    private ByteBuffer buffer;
    private final ExtendedBlock block;
    private final DatanodeInfo source;

    StripedReader(short index, ExtendedBlock block, DatanodeInfo source) {
      this.index = index;
      this.block = block;
      this.source = source;
    }
  }
}

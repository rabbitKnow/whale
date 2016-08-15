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

import java.io.DataInput;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.server.common.GenerationStamp;
import org.apache.hadoop.hdfs.server.namenode.BlocksMap.BlockInfo;
import org.apache.hadoop.hdfs.server.namenode.whalebase.Tools;
import org.apache.hadoop.hdfs.server.protocol.BlockCommand;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.io.WritableUtils;

/**************************************************
 * DatanodeDescriptor tracks stats on a given DataNode, such as available
 * storage capacity, last update time, etc., and maintains a set of blocks
 * stored on the datanode.
 * 
 * This data structure is a data structure that is internal to the namenode. It
 * is *not* sent over-the-wire to the Client or the Datnodes. Neither is it
 * stored persistently in the fsImage.
 **************************************************/
public class DatanodeDescriptor extends DatanodeInfo {

	// Stores status of decommissioning.
	// If node is not decommissioning, do not use this object for anything.
	DecommissioningStatus decommissioningStatus = new DecommissioningStatus();

	// TODO the file channel for operate the disk file
	FileChannel fc;
	long blockNum;
	RandomAccessFile blockListFile;

	// 28byte /unit
	/** Block and targets pair */
	public static class BlockTargetPair {
		public final Block block;
		public final DatanodeDescriptor[] targets;

		BlockTargetPair(Block block, DatanodeDescriptor[] targets) {
			this.block = block;
			this.targets = targets;
		}
	}

	/** A BlockTargetPair queue. */
	private static class BlockQueue {
		private final Queue<BlockTargetPair> blockq = new LinkedList<BlockTargetPair>();

		/** Size of the queue */
		synchronized int size() {
			return blockq.size();
		}

		/** Enqueue */
		synchronized boolean offer(Block block, DatanodeDescriptor[] targets) {
			return blockq.offer(new BlockTargetPair(block, targets));
		}

		/** Dequeue */
		synchronized List<BlockTargetPair> poll(int numBlocks) {
			if (numBlocks <= 0 || blockq.isEmpty()) {
				return null;
			}

			List<BlockTargetPair> results = new ArrayList<BlockTargetPair>();
			for (; !blockq.isEmpty() && numBlocks > 0; numBlocks--) {
				results.add(blockq.poll());
			}
			return results;
		}
	}

	private volatile BlockInfo blockList = null;
	// isAlive == heartbeats.contains(this)
	// This is an optimization, because contains takes O(n) time on Arraylist
	protected boolean isAlive = false;
	protected boolean needKeyUpdate = false;

	// A system administrator can tune the balancer bandwidth parameter
	// (dfs.balance.bandwidthPerSec) dynamically by calling
	// "dfsadmin -setBalanacerBandwidth <newbandwidth>", at which point the
	// following 'bandwidth' variable gets updated with the new value for each
	// node. Once the heartbeat command is issued to update the value on the
	// specified datanode, this value will be set back to 0.
	private long bandwidth;

	/** A queue of blocks to be replicated by this datanode */
	private BlockQueue replicateBlocks = new BlockQueue();
	/** A queue of blocks to be recovered by this datanode */
	private BlockQueue recoverBlocks = new BlockQueue();
	/** A set of blocks to be invalidated by this datanode */
	private Set<Block> invalidateBlocks = new TreeSet<Block>();

	/*
	 * Variables for maintaning number of blocks scheduled to be written to this
	 * datanode. This count is approximate and might be slightly higger in case
	 * of errors (e.g. datanode does not report if an error occurs while writing
	 * the block).
	 */
	private int currApproxBlocksScheduled = 0;
	private int prevApproxBlocksScheduled = 0;
	private long lastBlocksScheduledRollTime = 0;
	private static final int BLOCKS_SCHEDULED_ROLL_INTERVAL = 600 * 1000; // 10min

	// Set to false after processing first block report
	private boolean firstBlockReport = true;

	/** Default constructor */
	public DatanodeDescriptor() {
	}

	/**
	 * DatanodeDescriptor constructor
	 * 
	 * @param nodeID
	 *            id of the data node
	 */
	public DatanodeDescriptor(DatanodeID nodeID) {
		this(nodeID, 0L, 0L, 0L, 0);
	}

	/**
	 * DatanodeDescriptor constructor
	 * 
	 * @param nodeID
	 *            id of the data node
	 * @param networkLocation
	 *            location of the data node in network
	 */
	public DatanodeDescriptor(DatanodeID nodeID, String networkLocation) {
		this(nodeID, networkLocation, null);
	}

	/**
	 * DatanodeDescriptor constructor
	 * 
	 * @param nodeID
	 *            id of the data node
	 * @param networkLocation
	 *            location of the data node in network
	 * @param hostName
	 *            it could be different from host specified for DatanodeID
	 */
	public DatanodeDescriptor(DatanodeID nodeID, String networkLocation,
			String hostName) {
		this(nodeID, networkLocation, hostName, 0L, 0L, 0L, 0);
	}

	/**
	 * DatanodeDescriptor constructor
	 * 
	 * @param nodeID
	 *            id of the data node
	 * @param capacity
	 *            capacity of the data node
	 * @param dfsUsed
	 *            space used by the data node
	 * @param remaining
	 *            remaing capacity of the data node
	 * @param xceiverCount
	 *            # of data transfers at the data node
	 */
	public DatanodeDescriptor(DatanodeID nodeID, long capacity, long dfsUsed,
			long remaining, int xceiverCount) {
		super(nodeID);
		initialize();
		updateHeartbeat(capacity, dfsUsed, remaining, xceiverCount);
	}

	/**
	 * DatanodeDescriptor constructor
	 * 
	 * @param nodeID
	 *            id of the data node
	 * @param networkLocation
	 *            location of the data node in network
	 * @param capacity
	 *            capacity of the data node, including space used by non-dfs
	 * @param dfsUsed
	 *            the used space by dfs datanode
	 * @param remaining
	 *            remaing capacity of the data node
	 * @param xceiverCount
	 *            # of data transfers at the data node
	 */
	public DatanodeDescriptor(DatanodeID nodeID, String networkLocation,
			String hostName, long capacity, long dfsUsed, long remaining,
			int xceiverCount) {
		super(nodeID, networkLocation, hostName);
		initialize();
		updateHeartbeat(capacity, dfsUsed, remaining, xceiverCount);
	}

	/**
	 * Add data-node to the block. Add block to the head of the list of blocks
	 * belonging to the data-node.
	 */
	/*
	boolean addBlock(BlockInfo b) {
		if (!b.addNode(this))
			return false;
		// add to the head of the data-node list
		blockList = b.listInsert(blockList, this);
		return true;
	}
*/
	/**
	 * Remove block from the list of blocks belonging to the data-node. Remove
	 * data-node from the block.
	 */
	/*
	boolean removeBlock(BlockInfo b) {
		blockList = b.listRemove(blockList, this);
		return b.removeNode(this);
	}
*/
	/**
	 * Move block to the head of the list of blocks belonging to the data-node.
	 */
	/*
	void moveBlockToHead(BlockInfo b) {
		blockList = b.listRemove(blockList, this);
		blockList = b.listInsert(blockList, this);
	}
*/
	void resetBlocks() {
		this.capacity = 0;
		this.remaining = 0;
		this.dfsUsed = 0;
		this.xceiverCount = 0;
		this.blockList = null;
		this.invalidateBlocks.clear();
	}
//@author cokez changed using the blockNum
	public int numBlocks() {
		return (int) blockNum;
	}

	/**
   */
	void updateHeartbeat(long capacity, long dfsUsed, long remaining,
			int xceiverCount) {
		this.capacity = capacity;
		this.dfsUsed = dfsUsed;
		this.remaining = remaining;
		this.lastUpdate = System.currentTimeMillis();
		this.xceiverCount = xceiverCount;
		rollBlocksScheduled(lastUpdate);
	}

	/**change by cokez
	 * Iterates over the list of blocks belonging to the data-node.
	 */
	static private class BlockIterator implements Iterator<Block> {
		private Block current;
		private DatanodeDescriptor node;
		private long interatorIndex;
		BlockIterator(DatanodeDescriptor dn) {
			
			this.node = dn;
			interatorIndex=0;
			this.current = readBlock();
			
		}

		public boolean hasNext() {
			return current != null;
		}

		public Block next() {
			Block res = current;
			current = readBlock();
			interatorIndex++;
			return res;
		}

		public void remove() {
			throw new UnsupportedOperationException("Sorry. can't remove.");
		}
		public Block readBlock(){
			FileChannel fc=node.fc;
			Block result=null;
			if(interatorIndex<node.blockNum)
			try {
				fc.position(interatorIndex*28);
				long blockId=Tools.readLong(fc);
				long blockNumBytes=Tools.readLong(fc);
				long blockGenerationStamp=Tools.readLong(fc);
				int blockINodeIndex=Tools.readInt(fc);
				result=new Block(blockId,blockNumBytes,blockGenerationStamp);
				result.inodeIndex=blockINodeIndex;
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			return result;
		}
	}

	Iterator<Block> getBlockIterator() {
		return new BlockIterator(this);
	}

	/**
	 * Store block replication work.
	 */
	void addBlockToBeReplicated(Block block, DatanodeDescriptor[] targets) {
		assert (block != null && targets != null && targets.length > 0);
		replicateBlocks.offer(block, targets);
	}

	/**
	 * Store block recovery work.
	 */
	void addBlockToBeRecovered(Block block, DatanodeDescriptor[] targets) {
		assert (block != null && targets != null && targets.length > 0);
		recoverBlocks.offer(block, targets);
	}

	/**
	 * Store block invalidation work.
	 */
	void addBlocksToBeInvalidated(List<Block> blocklist) {
		assert (blocklist != null && blocklist.size() > 0);
		synchronized (invalidateBlocks) {
			for (Block blk : blocklist) {
				invalidateBlocks.add(blk);
			}
		}
	}

	/**
	 * The number of work items that are pending to be replicated
	 */
	int getNumberOfBlocksToBeReplicated() {
		return replicateBlocks.size();
	}

	/**
	 * The number of block invalidation items that are pending to be sent to the
	 * datanode
	 */
	int getNumberOfBlocksToBeInvalidated() {
		synchronized (invalidateBlocks) {
			return invalidateBlocks.size();
		}
	}

	BlockCommand getReplicationCommand(int maxTransfers) {
		List<BlockTargetPair> blocktargetlist = replicateBlocks
				.poll(maxTransfers);
		return blocktargetlist == null ? null : new BlockCommand(
				DatanodeProtocol.DNA_TRANSFER, blocktargetlist);
	}

	BlockCommand getLeaseRecoveryCommand(int maxTransfers) {
		List<BlockTargetPair> blocktargetlist = recoverBlocks
				.poll(maxTransfers);
		return blocktargetlist == null ? null : new BlockCommand(
				DatanodeProtocol.DNA_RECOVERBLOCK, blocktargetlist);
	}

	/**
	 * Remove the specified number of blocks to be invalidated
	 */
	BlockCommand getInvalidateBlocks(int maxblocks) {
		Block[] deleteList = getBlockArray(invalidateBlocks, maxblocks);
		return deleteList == null ? null : new BlockCommand(
				DatanodeProtocol.DNA_INVALIDATE, deleteList);
	}

	static private Block[] getBlockArray(Collection<Block> blocks, int max) {
		Block[] blockarray = null;
		synchronized (blocks) {
			int available = blocks.size();
			int n = available;
			if (max > 0 && n > 0) {
				if (max < n) {
					n = max;
				}
				// allocate the properly sized block array ...
				blockarray = new Block[n];

				// iterate tree collecting n blocks...
				Iterator<Block> e = blocks.iterator();
				int blockCount = 0;

				while (blockCount < n && e.hasNext()) {
					// insert into array ...
					blockarray[blockCount++] = e.next();

					// remove from tree via iterator, if we are removing
					// less than total available blocks
					if (n < available) {
						e.remove();
					}
				}
				assert (blockarray.length == n);

				// now if the number of blocks removed equals available blocks,
				// them remove all blocks in one fell swoop via clear
				if (n == available) {
					blocks.clear();
				}
			}
		}
		return blockarray;
	}
	// find the block from the datanode blocks report
	Block findLongArrayBlock(Block block,BlockListAsLongs newReport,boolean[] flag){
		for(int i=0;i<newReport.getNumberOfBlocks();i++){
			if(block.getBlockId()==newReport.getBlockId(i)){
				//newReport.getNumberOfBlocks();
				flag[i]=true;
				
				return new Block(newReport.getBlockId(i), newReport.getBlockLen(i),
					newReport.getBlockGenStamp(i));
				
			}
			
		}
		return null;
	}
	//invalid is large range
	//actually not need the blocksMap,time complexity is O(n2)
	void reportDiff(BlocksMap blocksMap, BlockListAsLongs newReport,
			Collection<Block> toAdd, Collection<Block> toRemove,
			Collection<Block> toInvalidate) {
		// @author cokez change using the disk interface
		
		
		
		if (newReport == null)
			newReport = new BlockListAsLongs(new long[0]);
		// scan the report and collect newly reported blocks
		// Note we are taking special precaution to limit tmp blocks allocated
		// as part this block report - which why block list is stored as longs
		BlockIterator blockInterator=new BlockIterator(this);
		boolean[] flag=new boolean[newReport.getNumberOfBlocks()];
		for(int i=0;i<flag.length;i++)
			flag[i]=false;
		while(blockInterator.hasNext()){
			Block storedBlock=blockInterator.next();
			FSNamesystem.MetaLog.info("stored Block id:"+storedBlock.getBlockId());
			Block reportedBlock=findLongArrayBlock(storedBlock,newReport,flag);
			if(reportedBlock!=null){
				if(reportedBlock.getGenerationStamp()!=storedBlock.getGenerationStamp()){
					toRemove.add(storedBlock);
					toInvalidate.add(reportedBlock);
				}
			}
			else{
				toRemove.add(storedBlock);
			}
			
			
		}
		for(int i=0;i<flag.length;i++){
			if(!flag[i]){
				FSNamesystem.MetaLog.info("Block is invalidate,id:"+newReport.getBlockId(i));
				toInvalidate.add(new Block(newReport.getBlockId(i),newReport.getBlockLen(i),newReport.getBlockGenStamp(i)));
			}
		}
		
		
		
		
	
		
	}

	/** Serialization for FSEditLog */
	void readFieldsFromFSEditLog(DataInput in) throws IOException {
		this.name = UTF8.readString(in);
		this.storageID = UTF8.readString(in);
		this.infoPort = in.readShort() & 0x0000ffff;

		this.capacity = in.readLong();
		this.dfsUsed = in.readLong();
		this.remaining = in.readLong();
		this.lastUpdate = in.readLong();
		this.xceiverCount = in.readInt();
		this.location = Text.readString(in);
		this.hostName = Text.readString(in);
		setAdminState(WritableUtils.readEnum(in, AdminStates.class));
	}

	/**
	 * @return Approximate number of blocks currently scheduled to be written to
	 *         this datanode.
	 */
	public int getBlocksScheduled() {
		return currApproxBlocksScheduled + prevApproxBlocksScheduled;
	}

	/**
	 * Increments counter for number of blocks scheduled.
	 */
	void incBlocksScheduled() {
		currApproxBlocksScheduled++;
	}

	/**
	 * Decrements counter for number of blocks scheduled.
	 */
	void decBlocksScheduled() {
		if (prevApproxBlocksScheduled > 0) {
			prevApproxBlocksScheduled--;
		} else if (currApproxBlocksScheduled > 0) {
			currApproxBlocksScheduled--;
		}
		// its ok if both counters are zero.
	}

	/**
	 * Adjusts curr and prev number of blocks scheduled every few minutes.
	 */
	private void rollBlocksScheduled(long now) {
		if ((now - lastBlocksScheduledRollTime) > BLOCKS_SCHEDULED_ROLL_INTERVAL) {
			prevApproxBlocksScheduled = currApproxBlocksScheduled;
			currApproxBlocksScheduled = 0;
			lastBlocksScheduledRollTime = now;
		}
	}

	class DecommissioningStatus {
		int underReplicatedBlocks;
		int decommissionOnlyReplicas;
		int underReplicatedInOpenFiles;
		long startTime;

		synchronized void set(int underRep, int onlyRep, int underConstruction) {
			if (isDecommissionInProgress() == false) {
				return;
			}
			underReplicatedBlocks = underRep;
			decommissionOnlyReplicas = onlyRep;
			underReplicatedInOpenFiles = underConstruction;
		}

		synchronized int getUnderReplicatedBlocks() {
			if (isDecommissionInProgress() == false) {
				return 0;
			}
			return underReplicatedBlocks;
		}

		synchronized int getDecommissionOnlyReplicas() {
			if (isDecommissionInProgress() == false) {
				return 0;
			}
			return decommissionOnlyReplicas;
		}

		synchronized int getUnderReplicatedInOpenFiles() {
			if (isDecommissionInProgress() == false) {
				return 0;
			}
			return underReplicatedInOpenFiles;
		}

		synchronized void setStartTime(long time) {
			startTime = time;
		}

		synchronized long getStartTime() {
			if (isDecommissionInProgress() == false) {
				return 0;
			}
			return startTime;
		}
	} // End of class DecommissioningStatus

	/**
	 * @return Blanacer bandwidth in bytes per second for this datanode.
	 */
	public long getBalancerBandwidth() {
		return this.bandwidth;
	}

	/**
	 * @param bandwidth
	 *            Blanacer bandwidth in bytes per second for this datanode.
	 */
	public void setBalancerBandwidth(long bandwidth) {
		this.bandwidth = bandwidth;
	}

	boolean firstBlockReport() {
		return firstBlockReport;
	}

	void processedBlockReport() {
		firstBlockReport = false;
	}

	/**
	 * process the blocks with disk list file if the file is not exist,then
	 * create it
	 * 
	 * @throws IOException
	 * 
	 * @cokez
	 * 
	 */
	public boolean closeBlockFile() throws IOException {
		blockListFile.close();
		return firstBlockReport;
	}

	public void initialize() {
		// the location for test

		String path = "/home/cokez/metaData/" + this.storageID;
		File blocksFile = new File(path);
		try {
			blockListFile = new RandomAccessFile(path, "rw");
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		fc = blockListFile.getChannel();

		long fileLength = blocksFile.length();

		blockNum = (fileLength - 8) / 28;
	}
	
	
	
	// reference the blockmap
	public boolean addBlock(BlockInfo block) {
		
		
		
		if (! block.addNode(this))
			return false;
		
		try {
			fc.position(28*blockNum++);
			Tools.writeLong(fc, block.getBlockId());
			Tools.writeLong(fc, block.getNumBytes());
			Tools.writeLong(fc, block.getGenerationStamp());
			Tools.writeInt(fc, block.getINodeIndex());
//			block.setIndex(index, blockNum++);
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return true;
		
	}
	public boolean removeBlock(BlockInfo block){
		long removeId=block.getBlockId();
		long blockId=-1;
		try {
			for(int i=0;i<blockNum;i++){
				fc.position(i*28);
				blockId=Tools.readLong(fc);
				if(removeId==blockId){
					fc.position(blockNum*28-28);
					long replaceId=Tools.readLong(fc);
					long replaceNumBytes=Tools.readLong(fc);
					long replcaeGenerationStamp=Tools.readLong(fc);
					int replaceINodeIndex=Tools.readInt(fc);
					fc.position(i*28);
					Tools.writeLong(fc, replaceId);
					Tools.writeLong(fc, replaceNumBytes);
					Tools.writeLong(fc, replcaeGenerationStamp);
					Tools.writeInt(fc, replaceINodeIndex);
					blockNum--;
					return block.removeNode(this);
				}
							
			}
			
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
	}
}

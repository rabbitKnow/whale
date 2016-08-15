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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;

import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.namenode.BlocksMap.BlockInfo;
import org.apache.hadoop.hdfs.server.namenode.whalebase.IndexManagement;
import org.apache.hadoop.hdfs.server.namenode.whalebase.Tools;

public class INodeFile extends INode {
	static final FsPermission UMASK = FsPermission
			.createImmutable((short) 0111);

	// Number of bits for Block size
	static final short BLOCKBITS = 48;

	// Header mask 64-bit representation
	// Format: [16 bits for replication][48 bits for PreferredBlockSize]
	static final long HEADERMASK = 0xffffL << BLOCKBITS;

	protected long header;

	protected BlockInfo blocks[] = null;

	INodeFile(PermissionStatus permissions, int nrBlocks, short replication,
			long modificationTime, long atime, long preferredBlockSize) {
		this(permissions, new BlockInfo[nrBlocks], replication,
				modificationTime, atime, preferredBlockSize);
	}

	protected INodeFile() {
		blocks = null;
		header = 0;
	}

	/**
	 * create the INodeFile from the disk metadata
	 * 
	 * @author cokez
	 * @param im
	 * @param index
	 * @throws Exception
	 */
	public INodeFile(IndexManagement im, int index) throws IOException {
		// TODO Auto-generated constructor stub
		readDisk(im, index);
		this.index = index;
	}

	protected INodeFile(PermissionStatus permissions, BlockInfo[] blklist,
			short replication, long modificationTime, long atime,
			long preferredBlockSize) {
		super(permissions, modificationTime, atime);
		this.setReplication(replication);
		this.setPreferredBlockSize(preferredBlockSize);
		blocks = blklist;
	}

	/**
	 * Set the {@link FsPermission} of this {@link INodeFile}. Since this is a
	 * file, the {@link FsAction#EXECUTE} action, if any, is ignored.
	 */
	protected void setPermission(FsPermission permission) {
		super.setPermission(permission.applyUMask(UMASK));
	}

	public boolean isDirectory() {
		return false;
	}

	/**
	 * Get block replication for the file
	 * 
	 * @return block replication value
	 */
	public short getReplication() {
		return (short) ((header & HEADERMASK) >> BLOCKBITS);
	}

	public void setReplication(short replication) {
		if (replication <= 0)
			throw new IllegalArgumentException(
					"Unexpected value for the replication");
		header = ((long) replication << BLOCKBITS) | (header & ~HEADERMASK);
	}

	/**
	 * Get preferred block size for the file
	 * 
	 * @return preferred block size in bytes
	 */
	public long getPreferredBlockSize() {
		return header & ~HEADERMASK;
	}

	public void setPreferredBlockSize(long preferredBlkSize) {
		if ((preferredBlkSize < 0) || (preferredBlkSize > ~HEADERMASK))
			throw new IllegalArgumentException(
					"Unexpected value for the block size");
		header = (header & HEADERMASK) | (preferredBlkSize & ~HEADERMASK);
	}

	/**
	 * Get file blocks
	 * 
	 * @return file blocks
	 */
	BlockInfo[] getBlocks() {
		return this.blocks;
	}

	/**
	 * add a block to the block list
	 */
	void addBlock(BlockInfo newblock) {
		if (this.blocks == null) {
			this.blocks = new BlockInfo[1];
			this.blocks[0] = newblock;
		} else {
			int size = this.blocks.length;
			BlockInfo[] newlist = new BlockInfo[size + 1];
			System.arraycopy(this.blocks, 0, newlist, 0, size);
			newlist[size] = newblock;
			this.blocks = newlist;
		}
	}

	/**
	 * Set file block
	 */
	void setBlock(int idx, BlockInfo blk) {
		this.blocks[idx] = blk;
	}

	int collectSubtreeBlocksAndClear(List<Block> v) {
		parent = null;
		for (Block blk : blocks) {
			v.add(blk);
		}
		blocks = null;
		return 1;
	}

	/** {@inheritDoc} */
	long[] computeContentSummary(long[] summary) {
		long bytes = 0;
		for (Block blk : blocks) {
			bytes += blk.getNumBytes();
		}
		summary[0] += bytes;
		summary[1]++;
		summary[3] += diskspaceConsumed();
		return summary;
	}

	@Override
	DirCounts spaceConsumedInTree(DirCounts counts) {
		counts.nsCount += 1;
		counts.dsCount += diskspaceConsumed();
		return counts;
	}

	long diskspaceConsumed() {
		return diskspaceConsumed(blocks);
	}

	long diskspaceConsumed(Block[] blkArr) {
		long size = 0;
		for (Block blk : blkArr) {
			if (blk != null) {
				size += blk.getNumBytes();
			}
		}
		/*
		 * If the last block is being written to, use prefferedBlockSize rather
		 * than the actual block size.
		 */
		if (blkArr.length > 0 && blkArr[blkArr.length - 1] != null
				&& isUnderConstruction()) {
			size += getPreferredBlockSize()
					- blocks[blocks.length - 1].getNumBytes();
		}
		return size * getReplication();
	}

	/**
	 * Return the penultimate allocated block for this file.
	 */
	Block getPenultimateBlock() {
		if (blocks == null || blocks.length <= 1) {
			return null;
		}
		return blocks[blocks.length - 2];
	}

	// TODO not used author cokez
	/*
	 * INodeFileUnderConstruction toINodeFileUnderConstruction(String
	 * clientName, String clientMachine, DatanodeDescriptor clientNode) throws
	 * IOException { if (isUnderConstruction()) { return
	 * (INodeFileUnderConstruction) this; } return new
	 * INodeFileUnderConstruction(name, getReplication(), modificationTime,
	 * getPreferredBlockSize(), blocks, getPermissionStatus(), clientName,
	 * clientMachine, clientNode); }
	 */
	/**
	 * Return the last block in this file, or null if there are no blocks.
	 */
	Block getLastBlock() {
		if (this.blocks == null || this.blocks.length == 0)
			return null;
		return this.blocks[this.blocks.length - 1];
	}

	public boolean readDisk(IndexManagement im, int index) throws IOException {
		FileChannel fc1 = im.iNodeFileReadChannel[0];
		FileChannel fc2 = im.iNodeFileReadChannel[1];
		FileChannel fc3 = im.iNodeFileReadChannel[2];
		fc1.position(index * 7068);
		readIDisk2INode(fc1);
		header = Tools.readLong(fc1);
		int blockNum = Tools.readShort(fc1);
		if (blockNum == 0)
			return true;
		int blockIndex = 0;
		blocks = new BlockInfo[blockNum];
		// read the first level
		for (; blockIndex < 20 && blockIndex < blockNum; blockIndex++) {
			blocks[blockIndex] = new BlockInfo(fc1);
			namesystem.blocksMap.BlockInfo2Mem(blocks[blockIndex], this);
		}
		if (blockIndex > 20 && blockNum <= 420) {
			for (int i = 0; i < 20 && blockIndex < blockNum; i++)
				fc2.position(Tools.readInt(fc1) * 5340);
			{
				for (int j = 0; j < 20 && blockIndex < blockNum; j++) {
					blocks[blockIndex++] = new BlockInfo(fc2);
					namesystem.blocksMap
							.BlockInfo2Mem(blocks[blockIndex], this);
					blockIndex++;
				}
			}
		}
		if (blockIndex > 420) {
			for (int i = 0; i < 20 && blockIndex < blockNum; i++) {
				fc3.position(Tools.readInt(fc1) * 80);
				for (int j = 0; j < 20 && blockIndex < blockNum; j++) {
					fc2.position(Tools.readInt(fc3) * 5340);
					for (int z = 0; z < 20 && blockIndex < blockNum; z++) {
						blocks[blockIndex] = new BlockInfo(fc2);
						namesystem.blocksMap.BlockInfo2Mem(blocks[blockIndex],
								this);
						blockIndex++;
					}
				}
			}
		}

		return true;
	}

	public int writeDisk(IndexManagement im) throws IOException {
		// TODO Auto-generated method stub
		if (index == -1)
			index = im.iNodeFileIndex[0].getEmptyIndex();
		FileChannel fc = im.iNodeFileChannel[0];
		fc.position(index * 7068);
		writeINode2Disk(fc);
		
		short blockNum = blocks==null?0:(short) blocks.length;
		Tools.writeLong(fc, header);
		Tools.writeShort(fc, blockNum);
		int blockIndex = 0;
		// write the blocks info
		for (; blockIndex < 20 && blockIndex < blockNum; blockIndex++) {
			blocks[blockIndex].writeBlock2Disk(fc);
		}
		// 2-level index 8280
		if (blockNum > 20 && blockNum <= 420) {
			for (int i = 0; i < 20 && blockIndex < blockNum; i++) {
				FileChannel fc1 = im.iNodeFileChannel[1];
				int l2index = im.iNodeFileIndex[1].getEmptyIndex();
				fc1.position(l2index * 5340);
				Tools.writeInt(fc, l2index);
				for (int j = 0; j < 20 && blockIndex < blockNum; j++) {
					blocks[blockIndex++].writeBlock2Disk(fc1);
				}
			}
			// 3-level index 8280 80 ?640
			if (blockNum > 420) {
				for (int i = 0; i < 20 && blockIndex < blockNum; i++) {
					FileChannel fc2 = im.iNodeFileChannel[2];
					int l3index = im.iNodeFileIndex[2].getEmptyIndex();
					fc2.position(l3index * 80);
					Tools.writeInt(fc, l3index);
					for (int j = 0; j < 20 && blockIndex < blockNum; j++) {
						int l2index = im.iNodeFileIndex[1].getEmptyIndex();
						FileChannel fc1 = im.iNodeFileChannel[1];
						fc1.position(l2index * 5340);
						Tools.writeInt(fc2, l2index);
						for (int z = 0; z < 20 && blockIndex < blockNum; z++) {
							blocks[blockIndex++].writeBlock2Disk(fc1);
						}

					}
				}
			}

		}
		return index;
	}

	@Override
	public boolean delete(IndexManagement im) throws IOException {
		// TODO Auto-generated method stub
		int blockNum=0;
		if(blocks!=null)
			blockNum= blocks.length;
		if (blockNum > 20) {
			FileChannel fc = im.iNodeFileReadChannel[0];
			fc.position(index * 7068 + 5350);// 267*20+8+2
			for (int i = 0; i < (blockNum - 20) / 20 + 1; i++) {
				int secondaryIndex = Tools.readInt(fc);
				im.iNodeFileIndex[1].deleteIndex(secondaryIndex);
			}
			if (blockNum > 420) {
				FileChannel fc2 = im.iNodeFileReadChannel[2];
				for (int i = 0; i < (blockNum - 420) / 400 + 1; i++) {
					int thirdaryIndex = Tools.readInt(fc);
					fc2.position(80 * thirdaryIndex);
					for (int j = 0; j < 20 && 420 + i * 400 + j * 20 < blockNum; j++) {
						int secondaryIndex = Tools.readInt(fc2);
						im.iNodeFileIndex[1].deleteIndex(secondaryIndex);
					}
					im.iNodeFileIndex[2].deleteIndex(thirdaryIndex);
				}
			}

		}
		im.iNodeFileIndex[0].deleteIndex(index);// delete it at the end
		return true;
	}

	@Override
	public boolean clean(IndexManagement im) throws IOException {
		// TODO Auto-generated method stub
		int blockNum = blocks.length;
		if (blockNum > 20) {
			FileChannel fc = im.iNodeFileReadChannel[0];
			fc.position(index * 7068 + 5350);// 267*20+8+2
			for (int i = 0; i < (blockNum - 20) / 20 + 1; i++) {
				int secondaryIndex = Tools.readInt(fc);
				im.iNodeFileIndex[1].deleteIndex(secondaryIndex);
			}
			if (blockNum > 420) {
				FileChannel fc2 = im.iNodeFileReadChannel[2];
				for (int i = 0; i < (blockNum - 420) / 400 + 1; i++) {
					int thirdaryIndex = Tools.readInt(fc);
					fc2.position(80 * thirdaryIndex);
					for (int j = 0; j < 20 && 420 + i * 400 + j * 20 < blockNum; j++) {
						int secondaryIndex = Tools.readInt(fc2);
						im.iNodeFileIndex[1].deleteIndex(secondaryIndex);
					}
					im.iNodeFileIndex[2].deleteIndex(thirdaryIndex);
				}
			}
		}
		return true;

	}

	@Override
	public boolean update(IndexManagement im) throws IOException {
		// TODO Auto-generated method stub
		clean(im);
		if (index == -1)
			return false;

		FileChannel fc = im.iNodeFileChannel[0];
		fc.position(index * 7068);
		writeINode2Disk(fc);
		short blockNum = (short) blocks.length;
		Tools.writeLong(fc, header);
		Tools.writeShort(fc, blockNum);
		int blockIndex = 0;
		// write the blocks info
		for (; blockIndex < 20 && blockIndex < blockNum; blockIndex++) {
			blocks[blockIndex].writeBlock2Disk(fc);
		}
		// 2-level index 5340
		if (blockNum > 20 && blockNum <= 420) {
			for (int i = 0; i < 20 && blockIndex < blockNum; i++) {
				FileChannel fc1 = im.iNodeFileChannel[1];
				int l2index = im.iNodeFileIndex[1].getEmptyIndex();
				fc1.position(l2index * 5340);
				Tools.writeInt(fc, l2index);
				for (int j = 0; j < 20 && blockIndex < blockNum; j++) {
					blocks[blockIndex++].writeBlock2Disk(fc1);
				}
			}
			// 3-level index 5340 80 ?640
			if (blockNum > 420) {
				for (int i = 0; i < 20 && blockIndex < blockNum; i++) {
					FileChannel fc2 = im.iNodeFileChannel[2];
					int l3index = im.iNodeFileIndex[2].getEmptyIndex();
					fc2.position(l3index * 80);
					Tools.writeInt(fc, l3index);
					for (int j = 0; j < 20 && blockIndex < blockNum; j++) {
						int l2index = im.iNodeFileIndex[1].getEmptyIndex();
						FileChannel fc1 = im.iNodeFileChannel[1];
						fc1.position(l2index * 5340);
						Tools.writeInt(fc2, l2index);
						for (int z = 0; z < 20 && blockIndex < blockNum; z++) {
							blocks[blockIndex++].writeBlock2Disk(fc1);
						}

					}
				}
			}

		}
		return true;
	}
}

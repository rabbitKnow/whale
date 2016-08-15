package org.apache.hadoop.hdfs.server.namenode.whalebase;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;

 
/**
 * 
 * @author cokez <br>
 *         Empty Index is needed consistent stored on the disk mutex is
 *         needed to be implement to be implement.<br>
 *         local value may need a mutex <br>
 *         need a atom operation strategy for data consistent <br>
 *         block report strategy since the block is
 *         consistently stored on the disk:using a datanode list to store a blocklist<br>
 *         wait to implement period garbage collection
 */


/**
	 * two layer consistent storage periodically arrange the space
	 * 
	 * using binary file to consistently store index <br>
	 * --max metadata unit index-32Byte----------------------------<br>
	 * --empty list now index-32Byte-------------------------------<br>
	 * --empty index-----------------------------------------------<br>
	 */
	public class EmptyIndex {
		/**
		 * 
		 * @author cokez <br>
		 *         Empty Index is needed consistent stored on the disk 
		 *         mutex is needed to be implement to be implement.<br>
		 *         local value may need a mutex <br>
		 *         need a atom operation strategy for data consistent <br>
		 *         how to define the block report strategy since the block is
		 *         consistently stored on the disk<br>
		 *         
		 */
		class Index {
			Index next;
			int index;

			public Index() {
				index = -1;
			}

			public Index(int DeleIndex) {
				index = DeleIndex;
			}
		}
		static int diff=0;

		String indexPath;
		Index root;
		int maxIndex;// max metadata unit index,period reducing it
		int emptyMaxIndex; // empty list now index
		FileChannel indexChannel;
		RandomAccessFile emptyIndexStream;

		EmptyIndex() throws IOException {
			initialize();
			boolean recoverIndex=recoverIndexList();
			FSNamesystem.MetaLog.info("recover Index"+recoverIndex);
		}
		
		EmptyIndex(String path)throws IOException {
			root=null;
			initialize();
			indexPath=path;
			openFileChannel();
		}
		private void initialize(){
			maxIndex = 0;
			emptyMaxIndex = 0;
			indexPath = "/home/cokez/indexPath"+diff++;// for test not used
		}
		private void openFileChannel() throws IOException{
			emptyIndexStream = new RandomAccessFile(indexPath, "rw");
			indexChannel = emptyIndexStream.getChannel();
		}
		// finally close the stream
		protected void finalize() throws IOException

		{
			indexChannel.close();
			emptyIndexStream.close();

		}

		// get available meta-index on disk
		public int getEmptyIndex() throws IOException {
			int waitToReturn = -1;
			if (root != null) {
				waitToReturn = root.index;
				root = root.next;
				syncMaxIndex(emptyMaxIndex--);
			} else if (maxIndex <= 20000000000L)// 20000 million
			{
				waitToReturn = maxIndex;
				syncMaxIndex(maxIndex++);
			} else
				throw new IOException("Not enough space");
			return waitToReturn;
		}

		// set an meta-index is available for using again
		public boolean deleteIndex(int DeleIndex) throws IOException {
			Index waitToInsert = new Index(DeleIndex);
			waitToInsert.next = root;
			root = waitToInsert;
			syncEmptyIndex(DeleIndex);
			syncMaxEmptyIndex(emptyMaxIndex++);
			return true;
		}

		// sync the max index value to the disk
		private boolean syncMaxIndex(int index) throws IOException {
			indexChannel.position(0);
			Tools.writeInt(indexChannel, 0);
			
			return true;
		}

		// sync the max empty list index value to the disk
		private boolean syncMaxEmptyIndex(int index) throws IOException {
			indexChannel.position(32);
			Tools.writeInt(indexChannel, index);
			return true;
		}

		// sync the empty list index unit value to the disk
		private boolean syncEmptyIndex(int index) throws IOException {
			indexChannel.position(64+32*emptyMaxIndex);
			Tools.writeInt(indexChannel, index);
			return true;
		}

		private boolean recoverIndexList()throws IOException{
			if(emptyIndexStream.length()>=64){
				maxIndex=emptyIndexStream.readInt();
				emptyMaxIndex=emptyIndexStream.readInt();
				for(int i=0;i<emptyMaxIndex;i++)
				{
					Index waitToInsert = new Index(emptyIndexStream.readInt());
					waitToInsert.next = root;
					root = waitToInsert;
				}
				return true;
			}
			return false;
		}
		
	}
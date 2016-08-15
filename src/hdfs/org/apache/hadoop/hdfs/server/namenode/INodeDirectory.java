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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.namenode.whalebase.IndexManagement;
import org.apache.hadoop.hdfs.server.namenode.whalebase.Tools;

/**
 * Directory INode class. total size in disk is 12060
 */
public class INodeDirectory extends INode {
	protected static final int DEFAULT_FILES_PER_DIRECTORY = 5;
	final static String ROOT_NAME = "";

	private List<INode> children;
	private List<Child> childrenIndex;
	// author cokez
	// TODO
	private List<INode> cacheChildren;

	class Child implements Comparable<byte[]> {
		byte childIsDirectory;// 0 is directory,1 is file
		int index;
		byte[] childName;

		Child() {

		}

		Child(INode inode) {
			childName = inode.name;
			index = inode.index;
			childIsDirectory = (byte) (inode.isDirectory() ? 0 : 1);

		}

		Child(FileChannel fc) throws IOException {
			readChild2Mem(fc);
		}

		void writeChild2Disk(FileChannel fc) throws IOException {
			Tools.writeByte(fc, childIsDirectory);
			Tools.writeInt(fc, index);
			Tools.writeFBytes(fc, childName);
		}

		void readChild2Mem(FileChannel fc) throws IOException {
			childIsDirectory = Tools.readByte(fc);
			index = Tools.readInt(fc);
			childName = Tools.readFBytes(fc);
		}

		boolean isDirectory() {
			return childIsDirectory == 0 ? true : false;
		}

		@Override
		public int compareTo(byte[] o) {
			// TODO Auto-generated method stub
			return INode.compareBytes(childName, o);
		}

	}

	// add the null argument values construct method for the cache list using
	INodeDirectory() {

	}

	INodeDirectory(String name, PermissionStatus permissions) {
		super(name, permissions);
		this.children = null;
	}

	public INodeDirectory(PermissionStatus permissions, long mTime) {
		super(permissions, mTime, 0);
		this.children = null;
	}

	/** constructor */
	INodeDirectory(byte[] localName, PermissionStatus permissions, long mTime) {
		this(permissions, mTime);
		this.name = localName;
	}

	/**
	 * copy constructor
	 * 
	 * @param other
	 */
	INodeDirectory(INodeDirectory other) {
		super(other);
		try {
			this.children = other.getChildren();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public INodeDirectory(IndexManagement im, int index) throws IOException {
		// TODO Auto-generated constructor stub
		this.index = index;
		readDisk(im, index);
	}

	/**
	 * Check whether it's a directory
	 */
	public boolean isDirectory() {
		return true;
	}

	// @author cokez
	INode removeChild(INode node) {
		assert childrenIndex != null;
		// delete the element in the Child list

		int low = Collections.binarySearch(childrenIndex, node.name);
		if (low >= 0) {
			childrenIndex.remove(low);
		} else {
			return null;
		}
		low = Collections.binarySearch(children, node.name);
		if (low >= 0) {
			return children.remove(low);
		} else {
			return null;
		}
	}

	/**
	 * Replace a child that has the same name as newChild by newChild.
	 * 
	 * @param newChild
	 *            Child node to be added
	 */
	void replaceChild(INode newChild) {
		if (children == null) {
			throw new IllegalArgumentException("The directory is empty");
		}
		int low = Collections.binarySearch(children, newChild.name);
		if (low >= 0) { // an old child exists so replace by the newChild
			children.set(low, newChild);
		} else {
			throw new IllegalArgumentException("No child exists to be replaced");
		}
	}

	INode getChild(String name) {
		return getChildINode(DFSUtil.string2Bytes(name));
	}

	private INode getChildINode(byte[] name) {
		if (childrenIndex == null) {
			return null;
		}
		if(children==null)
			children=new ArrayList<INode>(DEFAULT_FILES_PER_DIRECTORY);
		int low = Collections.binarySearch(children, name);
		if (low >= 0) {
			return children.get(low);
		}
		low = Collections.binarySearch(childrenIndex, name);
		if (low >= 0) {
			Child child = childrenIndex.get(low);
			try {
				INode inode=null;
				if (child.isDirectory()) {
					
					byte tempName[] = new byte[name.length + 1];
					// create the temp name string for the split identification
					System.arraycopy(name, 0, tempName, 0, name.length);
					inode = FSNamesystem.diskIO.readINodeDirectory(child.index);
					if (cacheChildren != null) {
						low = Collections.binarySearch(cacheChildren, tempName);
						// this is the start index
						low = -low - 1;
						// change the last char of the bytes TODO maybe having
						// an issue
						tempName[tempName.length - 1] = (byte) (tempName[tempName.length - 1] + 1);
						int high = Collections.binarySearch(cacheChildren,
								tempName);
						high = -high - 1;
						INode cacheINode = null;
						for (int i = low; i < high - low; i++) {
							cacheINode = cacheChildren.get(low);
							// support 127 level
							if (cacheINode.cacheName[cacheName.length - 1] == 0) {
								cacheINode.cacheName = null;
								((INodeDirectory)inode).Child2Mem(cacheINode);
							} else {
								// updata the transfered node name
								byte inodeName[] = new byte[cacheINode.cacheName.length
										- inode.name.length - 1];
								System.arraycopy(cacheINode.cacheName,
										inode.name.length + 1, inodeName, 0,
										inodeName.length);
								inodeName[inodeName.length - 1]--;
								cacheINode.cacheName = inodeName;
								((INodeDirectory) inode).INode2Cache(cacheINode);
							}

						}
					}
					//if the inode is file
				} else {
					inode = FSNamesystem.diskIO
							.readINodeFile(child.index);
				}
				this.Child2Mem(inode);
				
				namesystem.totalCacheNum++;
				return inode;
			} catch (Exception e) {

				e.printStackTrace();
			}
			
		}
		return null;
	}

	/**
   */
	private INode getNode(byte[][] components) {
		INode[] inode = new INode[1];
		getExistingPathINodes(components, inode);
		return inode[0];
	}

	/**
	 * This is the external interface
	 */
	INode getNode(String path) {
		return getNode(getPathComponents(path));
	}

	/**
	 * Retrieve existing INodes from a path. If existing is big enough to store
	 * all path components (existing and non-existing), then existing INodes
	 * will be stored starting from the root INode into existing[0]; if existing
	 * is not big enough to store all path components, then only the last
	 * existing and non existing INodes will be stored so that
	 * existing[existing.length-1] refers to the target INode.
	 * 
	 * <p>
	 * Example: <br>
	 * Given the path /c1/c2/c3 where only /c1/c2 exists, resulting in the
	 * following path components: ["","c1","c2","c3"],
	 * 
	 * <p>
	 * <code>getExistingPathINodes(["","c1","c2"], [?])</code> should fill the
	 * array with [c2] <br>
	 * <code>getExistingPathINodes(["","c1","c2","c3"], [?])</code> should fill
	 * the array with [null]
	 * 
	 * <p>
	 * <code>getExistingPathINodes(["","c1","c2"], [?,?])</code> should fill the
	 * array with [c1,c2] <br>
	 * <code>getExistingPathINodes(["","c1","c2","c3"], [?,?])</code> should
	 * fill the array with [c2,null]
	 * 
	 * <p>
	 * <code>getExistingPathINodes(["","c1","c2"], [?,?,?,?])</code> should fill
	 * the array with [rootINode,c1,c2,null], <br>
	 * <code>getExistingPathINodes(["","c1","c2","c3"], [?,?,?,?])</code> should
	 * fill the array with [rootINode,c1,c2,null]
	 * 
	 * @param components
	 *            array of path component name
	 * @param existing
	 *            INode array to fill with existing INodes
	 * @return number of existing INodes in the path
	 */
	int getExistingPathINodes(byte[][] components, INode[] existing) {
		assert compareBytes(this.name, components[0]) == 0 : "Incorrect name "
				+ getLocalName() + " expected " + components[0];

		INode curNode = this;
		int count = 0;
		int index = existing.length - components.length;
		if (index > 0)
			index = 0;
		while ((count < components.length) && (curNode != null)) {
			if (index >= 0)
				existing[index] = curNode;
			if (!curNode.isDirectory() || (count == components.length - 1))
				break; // no more child, stop here
			INodeDirectory parentDir = (INodeDirectory) curNode;

			curNode = parentDir.getChildINode(components[count + 1]);
			// TODO cokez
			// move the INode to the cache root
			// not consider the inodefileunderconstruction
			// if the curNode is null
			if (curNode != null) {
				FSNamesystem.MetaLog.info("curNode:not null");

				// link the original previous and next

				if (curNode.previous != null && curNode.next != null) {
					curNode.previous.next = curNode.next;
					curNode.next.previous = curNode.previous;
				}

				// add the curNode to root next
				curNode.next = FSNamesystem.cacheRoot.next;
				curNode.previous = FSNamesystem.cacheRoot;
				FSNamesystem.cacheRoot.next.previous = curNode;
				FSNamesystem.cacheRoot.next = curNode;
			}
			count += 1;
			index += 1;
		}
		return count;
	}

	/**
	 * Retrieve the existing INodes along the given path. The first INode always
	 * exist and is this INode.
	 * 
	 * @param path
	 *            the path to explore
	 * @return INodes array containing the existing INodes in the order they
	 *         appear when following the path from the root INode to the deepest
	 *         INodes. The array size will be the number of expected components
	 *         in the path, and non existing components will be filled with null
	 * 
	 * @see #getExistingPathINodes(byte[][], INode[])
	 */
	INode[] getExistingPathINodes(String path) {
		byte[][] components = getPathComponents(path);
		INode[] inodes = new INode[components.length];

		this.getExistingPathINodes(components, inodes);

		return inodes;
	}

	/**
	 * TODO add childlist by cokez Add a child inode to the directory. TODO add
	 * the index info
	 * 
	 * @param node
	 *            INode to insert
	 * @param inheritPermission
	 *            inherit permission from parent?
	 * @return null if the child with this name already exists; node, otherwise
	 */
	<T extends INode> T addChild(final T node, boolean inheritPermission) {
		if (inheritPermission) {
			FsPermission p = getFsPermission();
			// make sure the permission has wx for the user
			if (!p.getUserAction().implies(FsAction.WRITE_EXECUTE)) {
				p = new FsPermission(p.getUserAction().or(
						FsAction.WRITE_EXECUTE), p.getGroupAction(),
						p.getOtherAction());
			}
			node.setPermission(p);
		}

		if (children == null) {
			children = new ArrayList<INode>(DEFAULT_FILES_PER_DIRECTORY);
		}
		int low = Collections.binarySearch(children, node.name);
		if (low >= 0)
			return null;
		node.parent = this;

		// god implement
		children.add(-low - 1, node);

		// update modification time of the parent directory
		setModificationTime(node.getModificationTime());
		if (node.getGroupName() == null) {
			node.setGroup(getGroupName());
		}

		// TODO write the INode (INodeUnderConstruction and INodeDir to the
		// disk)
		try {
			FSNamesystem.diskIO.writeINode(node);
			// add to cache
			if (!node.isUnderConstruction()) {
				node.next = FSNamesystem.cacheRoot.next;
				node.previous = FSNamesystem.cacheRoot;
				FSNamesystem.cacheRoot.next.previous = node;
				FSNamesystem.cacheRoot.next = node;

				namesystem.totalCacheNum++;
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if (childrenIndex == null)
			childrenIndex = new ArrayList<Child>(DEFAULT_FILES_PER_DIRECTORY);
		low = Collections.binarySearch(childrenIndex, node.name);
		// if not exit,add it just for not replace
		if (low < 0) {
			Child child = new Child(node);
			childrenIndex.add(-low - 1, child);
		}

		// @author cokez TODO update the metadata
		try {
			FSNamesystem.diskIO.update(this);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return node;
	}

	<T extends INode> T Child2Mem(final T node) {

		if (children == null) {
			children = new ArrayList<INode>(DEFAULT_FILES_PER_DIRECTORY);
		}
		int low = Collections.binarySearch(children, node.name);
		if (low >= 0)
			return null;
		node.parent = this;

		// god implement
		children.add(-low - 1, node);
		// update modification time of the parent directory

		return node;
	}

	<T extends INode> T INode2Cache(final T node) {

		if (cacheChildren == null) {
			cacheChildren = new ArrayList<INode>(DEFAULT_FILES_PER_DIRECTORY);
		}
		int low = Collections.binarySearch(cacheChildren, node.name);
		if (low >= 0)
			return null;

		// god implement
		cacheChildren.add(-low - 1, node);
		// update modification time of the parent directory

		return node;
	}

	/**
	 * Given a child's name, return the index of the next child
	 * 
	 * @param name
	 *            a child's name
	 * @return the index of the next child
	 */
	int nextChild(byte[] name) {
		if (name.length == 0) { // empty name
			return 0;
		}
		int nextPos = Collections.binarySearch(children, name) + 1;
		if (nextPos >= 0) {
			return nextPos;
		}
		return -nextPos;
	}

	/**
	 * Equivalent to addNode(path, newNode, false).
	 * 
	 * @see #addNode(String, INode, boolean)
	 */
	<T extends INode> T addNode(String path, T newNode)
			throws FileNotFoundException {
		return addNode(path, newNode, false);
	}

	/**
	 * Add new INode to the file tree. Find the parent and insert
	 * 
	 * @param path
	 *            file path
	 * @param newNode
	 *            INode to be added
	 * @param inheritPermission
	 *            If true, copy the parent's permission to newNode.
	 * @return null if the node already exists; inserted INode, otherwise
	 * @throws FileNotFoundException
	 *             if parent does not exist or is not a directory.
	 */
	<T extends INode> T addNode(String path, T newNode,
			boolean inheritPermission) throws FileNotFoundException {
		if (addToParent(path, newNode, null, inheritPermission) == null)
			return null;
		return newNode;
	}

	/**
	 * Add new inode to the parent if specified. Optimized version of addNode()
	 * if parent is not null.
	 * 
	 * @return parent INode if new inode is inserted or null if it already
	 *         exists.
	 * @throws FileNotFoundException
	 *             if parent does not exist or is not a directory.
	 */
	<T extends INode> INodeDirectory addToParent(String path, T newNode,
			INodeDirectory parent, boolean inheritPermission)
			throws FileNotFoundException {
		byte[][] pathComponents = getPathComponents(path);
		assert pathComponents != null : "Incorrect path " + path;
		int pathLen = pathComponents.length;
		if (pathLen < 2) // add root
			return null;
		if (parent == null) {
			// Gets the parent INode
			INode[] inodes = new INode[2];
			getExistingPathINodes(pathComponents, inodes);
			INode inode = inodes[0];
			if (inode == null) {
				throw new FileNotFoundException("Parent path does not exist: "
						+ path);
			}
			if (!inode.isDirectory()) {
				throw new FileNotFoundException(
						"Parent path is not a directory: " + path);
			}
			parent = (INodeDirectory) inode;
		}
		// insert into the parent children list
		newNode.name = pathComponents[pathLen - 1];
		if (parent.addChild(newNode, inheritPermission) == null)
			return null;
		return parent;
	}

	/** {@inheritDoc} */
	// TODO wait to reimplement
	DirCounts spaceConsumedInTree(DirCounts counts) {
		counts.nsCount += 1;
		if (children != null) {
			for (INode child : children) {
				child.spaceConsumedInTree(counts);
			}
		}
		return counts;
	}

	/** {@inheritDoc} */
	long[] computeContentSummary(long[] summary) {
		// Walk through the children of this node, using a new summary array
		// for the (sub)tree rooted at this node
		assert 4 == summary.length;
		long[] subtreeSummary = new long[] { 0, 0, 0, 0 };
		if (children != null) {
			for (INode child : children) {
				child.computeContentSummary(subtreeSummary);
			}
		}
		if (this instanceof INodeDirectoryWithQuota) {
			// Warn if the cached and computed diskspace values differ
			INodeDirectoryWithQuota node = (INodeDirectoryWithQuota) this;
			long space = node.diskspaceConsumed();
			assert -1 == node.getDsQuota() || space == subtreeSummary[3];
			if (-1 != node.getDsQuota() && space != subtreeSummary[3]) {
				NameNode.LOG.warn("Inconsistent diskspace for directory "
						+ getLocalName() + ". Cached: " + space + " Computed: "
						+ subtreeSummary[3]);
			}
		}

		// update the passed summary array with the values for this node's
		// subtree
		for (int i = 0; i < summary.length; i++) {
			summary[i] += subtreeSummary[i];
		}

		summary[2]++;
		return summary;
	}

	/**
	 * TODO huge consume imp
	 * 
	 * @throws Exception
	 */
	List<INode> getChildren() throws IOException {
		if(childrenIndex ==null)
			return new ArrayList<INode>();
		if (children == null)
			children=new ArrayList<INode>();
		if (children.size() == childrenIndex.size())
			return children;
		else {
			for(Child child:childrenIndex){
				int low = Collections.binarySearch(children,
						child.childName);
				if (low < 0) {
					getChildINode(child.childName);
				}
				
				
				
			}
		}
		return children;
	}

	List<INode> getChildrenRaw() {
		return children;
	}

	// @cokez change for disk operators
	// recurrence delete the children node
	int collectSubtreeBlocksAndClear(List<Block> v) {
		int total = 1;

		if (childrenIndex == null) {
			return total;
		}
		for (Child child : childrenIndex) {
			int low = Collections.binarySearch(children, child.childName);
			INode inodeChild = null;
			if (low >= 0) {
				inodeChild = children.get(low);

			} else {
				try {
					inodeChild = FSNamesystem.diskIO.readINode(
							child.isDirectory(), child.index);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			total += inodeChild.collectSubtreeBlocksAndClear(v);
			try {
				FSNamesystem.diskIO.delete(inodeChild);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}

		parent = null;
		children = null;

		return total;
	}

	@Override
	public int writeDisk(IndexManagement im) throws IOException {
		if (index == -1)
			index = im.iNodeDirectoryIndex[0].getEmptyIndex();
		FileChannel fc = im.iNodeDirectoryChannel[0];
		fc.position(index * 12060);
		short childNum = -1;
		if (childrenIndex != null)
			childNum = (short) childrenIndex.size();
		else
			childNum = 0;
		writeINode2Disk(fc);

		Tools.writeShort(fc, (childNum));
		int childIndex = 0;
		// flag+index +name
		for (; childIndex < 20 && childIndex < childNum; childIndex++) {
			childrenIndex.get(childIndex).writeChild2Disk(fc);
		}
		// 2-level index 10340
		if (childNum > 20 && childNum <= 420) {
			for (int i = 0; i < 20 && childIndex < childNum; i++) {
				FileChannel fc1 = im.iNodeDirectoryChannel[1];
				int l2index = im.iNodeDirectoryIndex[1].getEmptyIndex();
				fc1.position(l2index * 10340);
				Tools.writeInt(fc, l2index);
				for (int j = 0; j < 20 && childIndex < childNum; j++) {
					childrenIndex.get(childIndex++).writeChild2Disk(fc1);
				}
			}
			// 3-level index 10340 80
			if (childNum > 420) {
				for (int i = 0; i < 20 && childIndex < childNum; i++) {
					FileChannel fc2 = im.iNodeDirectoryChannel[2];
					int l3index = im.iNodeDirectoryIndex[2].getEmptyIndex();
					fc2.position(l3index * 80);
					Tools.writeInt(fc, l3index);
					for (int j = 0; j < 20 && childIndex < childNum; j++) {
						int l2index = im.iNodeDirectoryIndex[1].getEmptyIndex();
						FileChannel fc1 = im.iNodeDirectoryChannel[1];
						fc1.position(l2index * 10340);
						Tools.writeInt(fc2, l2index);
						for (int z = 0; z < 20 && childIndex < childNum; z++) {
							childrenIndex.get(childIndex++)
									.writeChild2Disk(fc1);
						}

					}
				}
			}

		}
		FSNamesystem.MetaLog.info("inode:"+new String(name));
		FSNamesystem.MetaLog.info("after write position:"+fc.position());
		
		
		
		return index;

	}

	@Override
	public boolean readDisk(IndexManagement im, int index) throws IOException {
		// TODO Auto-generated method stub

		FileChannel fc1 = im.iNodeDirectoryReadChannel[0];
		FileChannel fc2 = im.iNodeDirectoryReadChannel[1];
		FileChannel fc3 = im.iNodeDirectoryReadChannel[2];
		fc1.position(index * 12060);// set the offset
		// read the INode info
		readIDisk2INode(fc1);

		// read the INodeDirectory info
		short childNum = Tools.readShort(fc1);
		int childIndex = 0;
		if (childrenIndex == null)
			childrenIndex = new ArrayList<Child>();
		for (; childIndex < 20 & childIndex < childNum; childIndex++) {

			childrenIndex.add(new Child(fc1));

		}
		if (childNum > 20 && childNum <= 420) {
			for (int i = 0; i < 20 && childIndex < childNum; i++) {

				fc2.position(Tools.readInt(fc1) * 10340);// get the position of
															// secondary index
				for (int j = 0; j < 20 && childIndex < childNum; j++) {
					childrenIndex.add(new Child(fc2));
					childIndex++;
				}
			}
		}
		if (childNum > 420) {
			for (int i = 0; i < 20 && childIndex < childNum; i++) {
				fc3.position(Tools.readInt(fc1) * 80);

				for (int j = 0; j < 20 && childIndex < childNum; j++) {

					fc2.position(Tools.readInt(fc3) * 10340);
					for (int z = 0; z < 20 && childIndex < childNum; z++) {
						childrenIndex.add(new Child(fc2));
						childIndex++;
					}
				}
			}
		}
		
		FSNamesystem.MetaLog.info("child num:"+childNum);
		//log out the children 
		for(Child child:childrenIndex){
			FSNamesystem.MetaLog.info("Child:"+new String(child.childName));
		}
		
		
		return true;
	}

	public boolean delete(IndexManagement im) throws IOException {

		
		int childNum =0;
		if(childrenIndex!=null)
		childNum=childrenIndex.size();
		if (childNum > 20) {
			FileChannel fc = im.iNodeDirectoryReadChannel[0];
			fc.position(index * 12060 + 11900);// 10340+1558+2
			for (int i = 0; i < (childNum - 20) / 20 + 1; i++) {
				int secondaryIndex = Tools.readInt(fc);
				im.iNodeDirectoryIndex[1].deleteIndex(secondaryIndex);
			}
			if (childNum > 420) {
				FileChannel fc2 = im.iNodeDirectoryReadChannel[2];
				for (int i = 0; i < (childNum - 420) / 400 + 1; i++) {
					int thirdaryIndex = Tools.readInt(fc);
					fc2.position(80 * thirdaryIndex);
					for (int j = 0; j < 20 && 420 + i * 400 + j * 20 < childNum; j++) {
						int secondaryIndex = Tools.readInt(fc2);
						im.iNodeDirectoryIndex[1].deleteIndex(secondaryIndex);
					}
					im.iNodeDirectoryIndex[2].deleteIndex(thirdaryIndex);
				}
			}

		}
		im.iNodeDirectoryIndex[0].deleteIndex(index);// delete it at the end
		return true;

	}

	@Override
	public boolean clean(IndexManagement im) throws IOException {
		// TODO Auto-generated method stub
		int childNum = childrenIndex.size();
		if (childNum > 20) {
			FileChannel fc = im.iNodeDirectoryReadChannel[0];
			fc.position(index * 12060 + 11900);// 10340+1558+2
			for (int i = 0; i < (childNum - 20) / 20 + 1; i++) {
				int secondaryIndex = Tools.readInt(fc);
				im.iNodeDirectoryIndex[1].deleteIndex(secondaryIndex);
			}
			if (childNum > 420) {
				FileChannel fc2 = im.iNodeDirectoryReadChannel[2];
				for (int i = 0; i < (childNum - 420) / 400 + 1; i++) {
					int thirdaryIndex = Tools.readInt(fc);
					fc2.position(80 * thirdaryIndex);
					for (int j = 0; j < 20 && 420 + i * 400 + j * 20 < childNum; j++) {
						int secondaryIndex = Tools.readInt(fc2);
						im.iNodeDirectoryIndex[1].deleteIndex(secondaryIndex);
					}
					im.iNodeDirectoryIndex[2].deleteIndex(thirdaryIndex);
				}
			}

		}
		return true;

	}

	public boolean update(IndexManagement im) throws IOException {
		clean(im);
		if (index == -1)
			return false;
		FileChannel fc = im.iNodeDirectoryChannel[0];
		fc.position(index * 12060);
		short childNum = (short) childrenIndex.size();
		writeINode2Disk(fc);
		Tools.writeShort(fc, (childNum));
		int childIndex = 0;
		// flag+index +name
		for (; childIndex < 20 && childIndex < childNum; childIndex++) {
			childrenIndex.get(childIndex).writeChild2Disk(fc);
		}
		// 2-level index 10340
		if (childNum > 20 && childNum <= 420) {
			for (int i = 0; i < 20 && childIndex < childNum; i++) {
				FileChannel fc1 = im.iNodeDirectoryChannel[1];
				int l2index = im.iNodeDirectoryIndex[1].getEmptyIndex();
				fc1.position(l2index * 10340);
				Tools.writeInt(fc, l2index);
				for (int j = 0; j < 20 && childIndex < childNum; j++) {
					childrenIndex.get(childIndex++).writeChild2Disk(fc1);
				}
			}
			// 3-level index 10340 80
			if (childNum > 420) {
				for (int i = 0; i < 20 && childIndex < childNum; i++) {
					FileChannel fc2 = im.iNodeDirectoryChannel[2];
					int l3index = im.iNodeDirectoryIndex[2].getEmptyIndex();
					fc2.position(l3index * 80);
					Tools.writeInt(fc, l3index);
					for (int j = 0; j < 20 && childIndex < childNum; j++) {
						int l2index = im.iNodeDirectoryIndex[1].getEmptyIndex();
						FileChannel fc1 = im.iNodeDirectoryChannel[1];
						fc1.position(l2index * 10340);
						Tools.writeInt(fc2, l2index);
						for (int z = 0; z < 20 && childIndex < childNum; z++) {
							childrenIndex.get(childIndex++)
									.writeChild2Disk(fc1);
						}

					}
				}
			}

		}
		FSNamesystem.MetaLog.info("inode:"+new String(name));
		FSNamesystem.MetaLog.info("after update position:"+fc.position());
		return true;
	}

	public boolean removeChildFromMem(INode node) {
		int low = -1;
		// directly children
		if (node.cacheName == null) {
			low = Collections.binarySearch(children, node.name);
			if (low >= 0) {
				children.remove(low);
			} else {
				return false;
			}
			// cache child
		} else {

			low = Collections.binarySearch(cacheChildren, node.cacheName);
			if (low >= 0) {
				cacheChildren.remove(low);
			} else {
				return false;
			}
		}
		return true;

	}

	/**
	 * design for cache strategy move the inode child to its parent
	 * 
	 * @author cokez
	 * @return
	 */
	public boolean cacheChild2Parent() {
		// the inode is cacheINode
		if (cacheName != null) {
			byte level = cacheName[cacheName.length - 1];
			byte inodeName[] = null;
			if (children != null) {
				for (INode inode : children) {
					// add child to parent cache children
					inodeName = new byte[cacheName.length + inode.name.length
							+ 1];
					System.arraycopy(cacheName, 0, inodeName, 0,
							cacheName.length - 1);
					inodeName[cacheName.length - 1] = '/';

					System.arraycopy(inode.name, 0, inodeName,
							cacheName.length, inode.name.length);
					inodeName[inodeName.length - 1] = (byte) (level + 1);
					inode.cacheName = inodeName;
					FSNamesystem.MetaLog.info("cache the child to parent 1:"
							+ new String(inode.cacheName) + " parent is "
							+ new String(parent.name));
					((INodeDirectory) parent).addCacheChildren(inode);
					inode.parent = parent;

				}
				children = null;
			}

			if (cacheChildren != null) {
				for (INode inode : cacheChildren) {
					// add child to parent cache children
					inodeName = new byte[cacheName.length
							+ inode.cacheName.length];
					System.arraycopy(cacheName, 0, inodeName, 0,
							cacheName.length - 1);
					inodeName[cacheName.length - 1] = '/';
					System.arraycopy(inode.cacheName, 0, inodeName,
							cacheName.length, inode.cacheName.length - 1);
					inodeName[inodeName.length - 1] = (byte) (level + inode.cacheName[inode.cacheName.length - 1]);
					inode.cacheName = inodeName;
					FSNamesystem.MetaLog.info("cache the child to parent 2:"
							+ new String(inode.cacheName) + " parent is "
							+ new String(parent.name));
					((INodeDirectory) parent).addCacheChildren(inode);
					inode.parent = parent;
				}
				cacheChildren = null;
			}

		}
		// the inode is not cache inode
		else {
			byte level = 0;
			byte inodeName[] = null;
			if (children != null) {
				for (INode inode : children) {
					// add child to parent cache children
					inodeName = new byte[name.length + inode.name.length + 2];
					System.arraycopy(name, 0, inodeName, 0, name.length);
					inodeName[name.length] = '/';
					System.arraycopy(inode.name, 0, inodeName, name.length + 1,
							inode.name.length);
					inodeName[inodeName.length - 1] = (byte) (level + 1);
					inode.cacheName = inodeName;
					FSNamesystem.MetaLog.info("cache the child to parent3 :"
							+ new String(inode.cacheName) + " parent is "
							+ new String(parent.name));
					((INodeDirectory) parent).addCacheChildren(inode);
					inode.parent = parent;

				}
				children = null;
			}

			if (cacheChildren != null) {
				for (INode inode : cacheChildren) {
					// add child to parent cache children
					inodeName = new byte[name.length + inode.cacheName.length
							+ 1];
					System.arraycopy(name, 0, inodeName, 0, name.length);
					inodeName[name.length] = '/';
					System.arraycopy(inode.cacheName, 0, inodeName,
							name.length + 1, inode.cacheName.length - 1);
					inodeName[inodeName.length - 1] = (byte) (level + inode.cacheName[inode.cacheName.length - 1]);
					inode.cacheName = inodeName;
					FSNamesystem.MetaLog.info("cache the child to parent4 :"
							+ new String(inode.cacheName) + " parent is "
							+ new String(parent.name));
					((INodeDirectory) parent).addCacheChildren(inode);
					inode.parent = parent;
				}
				cacheChildren = null;
			}
		}
		return true;
	}

	public void addCacheChildren(INode inode) {
		if (inode == null)
			return;
		if (inode.cacheName == null)
			return;
		if (cacheChildren == null)
			cacheChildren = new ArrayList<INode>();
		int low = Collections.binarySearch(cacheChildren, inode.cacheName);
		// this is the start index
		if (low < 0) {
			low = -low - 1;
			cacheChildren.add(low, inode);

		}

	}

}

package org.apache.hadoop.hdfs.server.namenode.whalebase;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.server.namenode.*;
/**
 * provide write and read operate  
 * wait op del imp
 * @author cokez
 *
 */

public class DiskIO {
	IndexManagement im;
	
	public DiskIO(Configuration conf) throws IOException  {
		// TODO Auto-generated constructor stub
		im=new IndexManagement(conf);
	}
	// recover the root info
	public boolean recoverRoot(INodeDirectory inode) throws IOException{
		if(im.isRecoverable()){
			inode.readDisk(im, 0);
			return true;
		}
		//write the root to index 0
		else{
			writeINode(inode);
			return false;
		}
		
	}
	

	public int writeINode(INode inode) throws IOException {
		inode.index=inode.writeDisk(im);
		return inode.index;
	}
	

	public INode readINode(boolean isDirectory,int index) throws IOException{
		if(!isDirectory){
			return (INode)readINodeFile(index);
		}
		
		return (INode)readINodeDirectory(index);
	}
	public INodeFile readINodeFile(int index) throws IOException{
		return new INodeFile(im,index);
	}
	public INodeDirectory readINodeDirectory(int index) throws IOException{
		
		return new INodeDirectory(im, index);
	}
	
	/**
	 * TODO delete all element 
	 * 
	 * @author cokez
	 * @throws IOException 
	 */
	public boolean delete(INode inode) throws IOException{
		
		return inode.delete(im);
	}
	public boolean clean(INode inode)throws IOException{
		return inode.clean(im);
	}
	public boolean update(INode inode) throws IOException{
		return inode.update(im);
	}
}

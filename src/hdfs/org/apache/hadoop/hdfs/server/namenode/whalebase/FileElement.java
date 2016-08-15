package org.apache.hadoop.hdfs.server.namenode.whalebase;

import org.apache.hadoop.hdfs.server.namenode.INodeFile;

public class FileElement implements Comparable <Integer >{
	INodeFile file;
	@Override
	public int compareTo(Integer o) {
		// TODO Auto-generated method stub
		return file.index-o.intValue();
	}
	public boolean setINodeFile(INodeFile inode){
		file=inode;
		return true;
	}
	public INodeFile getINodeFile(){
		return file;
		
	}

}

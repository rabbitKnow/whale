package org.apache.hadoop.hdfs.server.namenode.whalebase;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

import org.apache.hadoop.conf.Configuration;

/**
 * Management inode dir and file index disk file</br> three-layer model
 * 
 * @author cokez
 * 
 */
public class IndexManagement {

	public FileChannel iNodeFileReadChannel[];
	public FileChannel iNodeDirectoryReadChannel[];
	public FileChannel iNodeFileChannel[];
	public EmptyIndex iNodeFileIndex[];
	public FileChannel iNodeDirectoryChannel[];
	public EmptyIndex iNodeDirectoryIndex[];
	private RandomAccessFile iNodeFileStream[];
	private RandomAccessFile iNodeDirectoryStream[];
	static String Metadir;
	// the flag for wether the namesystem can be recover
	boolean recoverable = false;

	public IndexManagement(Configuration conf) throws IOException {
		// TODO Auto-generated constructor stub

		initialize(conf);
	}

	// TODO two channel use the same random access,wait for test
	private void initialize(Configuration conf) throws IOException {
		iNodeFileReadChannel = new FileChannel[3];
		iNodeFileStream = new RandomAccessFile[3];

		iNodeFileChannel = new FileChannel[3];
		iNodeFileIndex = new EmptyIndex[3];
		iNodeDirectoryReadChannel = new FileChannel[3];
		iNodeDirectoryIndex = new EmptyIndex[3];
		iNodeDirectoryChannel = new FileChannel[3];
		iNodeDirectoryStream = new RandomAccessFile[3];
		// according to the conf set the metadir

		Metadir = conf.getStrings("dfs.metadata.path", "~/metaData")[0];
		recoverable = isExistMetaDirFile();
		for (int i = 0; i < 3; i++) {
			iNodeFileIndex[i] = new EmptyIndex(Metadir + "fileIndex" + i);
			iNodeFileStream[i] = new RandomAccessFile(Metadir + "fileMeta" + i,
					"rw");
			iNodeFileChannel[i] = iNodeFileStream[i].getChannel();
			iNodeFileReadChannel[i] = iNodeFileStream[i].getChannel();
			iNodeDirectoryIndex[i] = new EmptyIndex(Metadir + "directoryIndex"
					+ i);
			iNodeDirectoryStream[i] = new RandomAccessFile(Metadir
					+ "directoryMeta" + i, "rw");
			iNodeDirectoryChannel[i] = iNodeDirectoryStream[i].getChannel();
			iNodeDirectoryReadChannel[i] = iNodeDirectoryStream[i].getChannel();

		}
	}

	private boolean isExistMetaDirFile() {
		File confDir = new File(Metadir);
		File[] list = confDir.listFiles();
		if (!confDir.exists()) {
			System.out.println("MetadirPath:"+Metadir+" is not exist");
			return false;
			}
		if (list.length < 0)
			return true;
		else
			return false;

	}

	public boolean isRecoverable() {
		return recoverable;
	}

	// TODO to recover the data on the disk
	public static void recoverData() throws IOException {

	}

	public static void cleanData(Configuration conf) throws IOException {
		Metadir = conf.getStrings("dfs.metadata.path", "~/metaData")[0];
		File confDir = new File(Metadir);
		if (!confDir.exists()) {
		System.out.println("MetadirPath:"+Metadir+" is not exist");
		return ;
		}
		File[] list = confDir.listFiles();
		for (int i = 0; i < list.length; i++) {
			list[i].delete();
		}
		System.out.println("clean the index file");
	}

}

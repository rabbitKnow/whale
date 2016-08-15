package org.apache.hadoop.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
// String is 254+2
public class Tools {

	public static void writeString(FileChannel fc,String string) throws IOException{
		writeShort(fc,(short)string.length());
		fc.write(ByteBuffer.wrap(string.getBytes()));
	}
	public static void writeFString(FileChannel fc,String string) throws IOException{
		byte[] bytes=string.getBytes();
		writeShort(fc,(short)bytes.length);
		fc.write(ByteBuffer.wrap(bytes));
		fc.position(fc.position()+254-bytes.length);
	}
	public static void writeShort(FileChannel fc,short value)throws IOException{
		fc.write(ByteBuffer.wrap(short2Bytes(value)));
	}
	public static void writeByte(FileChannel fc,byte value)throws IOException{
		byte temp[]=new byte[1];
		temp[0]=value;
		fc.write(ByteBuffer.wrap(temp));
	}
	public static void writeBytes(FileChannel fc,byte value[])throws IOException{
		fc.write(ByteBuffer.wrap(value));
	}
	public static void writeFBytes(FileChannel fc,byte value[])throws IOException{
		writeShort(fc,(short)value.length);
		fc.write(ByteBuffer.wrap(value));
		fc.position(fc.position()+254-value.length);
	}
	public static String readString(FileChannel fc)throws IOException{
		short length=readShort(fc);
		ByteBuffer buffer=ByteBuffer.allocate(length);
		fc.read(buffer);
		return buffer.toString();
	}
	public static String readFString(FileChannel fc)throws IOException{
		short length=readShort(fc);
		ByteBuffer buffer=ByteBuffer.allocate(length);
		fc.read(buffer);
		String result=new String(buffer.array());  
		fc.position(fc.position()+254-length);
		return result;
	}

	public static byte readByte(FileChannel fc)throws IOException{
		ByteBuffer buffer=ByteBuffer.allocate(1);
		fc.read(buffer);
		return buffer.get(0);
		
	}
	public static byte[] readBytes(FileChannel fc,int length)throws IOException{
		ByteBuffer buffer=ByteBuffer.allocate(length);
		fc.read(buffer);
		return buffer.array();
	}
	public static byte[] readFBytes(FileChannel fc)throws IOException{
		short length=readShort(fc);
		ByteBuffer buffer=ByteBuffer.allocate(length);
		fc.read(buffer);
		fc.position(fc.position()+254-length);
		return buffer.array();
	}
	public static short readShort(FileChannel fc)throws IOException{
		ByteBuffer buffer=ByteBuffer.allocate(2);
		fc.read(buffer);
		return buffer.getShort(0);
	}
	public static void writeLong(FileChannel fc,long value)throws IOException{
		fc.write(ByteBuffer.wrap(long2Bytes(value)));
	}
	public static byte[] readFBytes(FileChannel fc,byte length)throws IOException{
		byte readLen=readByte(fc);
		ByteBuffer buffer=ByteBuffer.allocate(readLen);
		fc.read(buffer);
		fc.position(fc.position()+length-readLen);
		return buffer.array();
	}
	public static void writeFBytes(FileChannel fc,byte value[],byte length) throws IOException{
		
		int writeLen=value.length;
		writeByte(fc,(byte)writeLen);
		fc.write(ByteBuffer.wrap(value));
		fc.position(fc.position()+length-writeLen);
	}
	public static void writeInt(FileChannel fc,int value)throws IOException{
		fc.write(ByteBuffer.wrap(int2Bytes(value)));
	}
	public static int readInt(FileChannel fc)throws IOException{
		ByteBuffer buffer=ByteBuffer.allocate(4);
		fc.read(buffer);
		return buffer.getInt(0);
	}
	public static long readLong(FileChannel fc)throws IOException{
		ByteBuffer buffer=ByteBuffer.allocate(8);
		fc.read(buffer);
		return buffer.getLong(0);
	}
	public static byte[] short2Bytes(short n){
		byte[] b = new byte[2];
		b[0] = (byte)((n & 0xFF00) >> 8);
		b[1] = (byte)(n & 0xFF);
		return b;
	}
	public static byte[] long2Bytes(long n){
		byte[] result = new byte[8];  
	    for (int ix = 0; ix < 8; ++ix) {  
	        int offset = 64 - (ix + 1) * 8;  
	        result[ix] = (byte) ((n >> offset) & 0xff);  
	    }  
	    return result; 
	}
	public static byte[] int2Bytes(int n){
		byte[] result = new byte[4];  
	    for (int ix = 0; ix < 4; ++ix) {  
	        int offset = 32 - (ix + 1) * 8;  
	        result[ix] = (byte) ((n >> offset) & 0xff);  
	    }  
	    return result; 
	}
	public static boolean findByteFromBytes(byte[] bytes,byte b,int num){
		return true;
	}
	public static void main(String args[]){
		ByteBuffer bb=ByteBuffer.wrap(int2Bytes(2222));
		System.out.println(bb.getInt());
		
	}
	
}

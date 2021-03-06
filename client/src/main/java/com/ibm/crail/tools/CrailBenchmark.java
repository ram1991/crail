/*
 * Crail: A Multi-tiered Distributed Direct Access File System
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.crail.tools;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import com.ibm.crail.CrailBuffer;
import com.ibm.crail.CrailBufferedInputStream;
import com.ibm.crail.CrailBufferedOutputStream;
import com.ibm.crail.CrailFile;
import com.ibm.crail.CrailFS;
import com.ibm.crail.CrailInputStream;
import com.ibm.crail.CrailNode;
import com.ibm.crail.CrailOutputStream;
import com.ibm.crail.CrailResult;
import com.ibm.crail.CrailNodeType;
import com.ibm.crail.conf.CrailConfiguration;
import com.ibm.crail.conf.CrailConstants;
import com.ibm.crail.memory.OffHeapBuffer;
import com.ibm.crail.utils.CrailUtils;
import com.ibm.crail.utils.GetOpt;
import com.ibm.crail.utils.RingBuffer;

public class CrailBenchmark {
	private int warmup;
	
	public CrailBenchmark(int warmup){
		this.warmup = warmup;
	}
	
	public static void usage() {
		System.out.println("Usage: ");
		System.out.println(
				"iobench -t <writeClusterHeap|writeClusterDirect|writeLocalHeap|writeLocalDirect|writeAsyncCluster|writeAsyncLocal|"
				+ "readSequentialHeap|readSequentialDirect|readRandomHeap|readRandomDirect|readAsync|readMultiStream|"
				+ "enumerateDir|keyGet|createFile|getFile|createMultiFile|writeInt|readInt|seekInt|readMultiStreamInt>"
				+ "-f <filename> -s <size> -k <iterations> -b <batch> -e <experiments>");
		System.exit(1);
	}

	void writeSequential(String filename, int size, int loop, boolean affinity, boolean direct) throws Exception {
		System.out.println("writeSequential, filename " + filename  + ", size " + size + ", loop " + loop + ", affinity " + affinity + ", direct " + direct);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		int hosthash = 0;
		if (affinity){
			hosthash = fs.getHostHash();
		}
		
		CrailBuffer buf = null;
		if (size == CrailConstants.BUFFER_SIZE){
			buf = fs.allocateBuffer();
		} else if (size < CrailConstants.BUFFER_SIZE){
			CrailBuffer _buf = fs.allocateBuffer();
			_buf.clear().limit(size);
			buf = _buf.slice();
		} else {
			buf = OffHeapBuffer.wrap(ByteBuffer.allocateDirect(size));
		}
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		bufferQueue.add(buf);
		warmUp(fs, filename, warmup, bufferQueue);
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		long _loop = (long) loop;
		long _bufsize = (long) CrailConstants.BUFFER_SIZE;
		long _capacity = _loop*_bufsize;
		double sumbytes = 0;
		double ops = 0;
		CrailFile file = fs.create(filename, CrailNodeType.DATAFILE, 0, hosthash).get().asFile();
		CrailBufferedOutputStream bufferedStream = !direct ? file.getBufferedOutputStream(_capacity) : null;	
		CrailOutputStream directStream = direct? file.getDirectOutputStream(_capacity) : null;	
		long start = System.currentTimeMillis();
		while (ops < loop) {
			buf.clear();
			if (direct){
				directStream.write(buf).get();
			} else {
				bufferedStream.write(buf.getByteBuffer());
			}
			sumbytes = sumbytes + buf.capacity();
			ops = ops + 1.0;				
		}
		if (!direct){
			bufferedStream.close();
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start)) / 1000.0;
		double throughput = 0.0;
		double latency = 0.0;
		double sumbits = sumbytes * 8.0;
		if (executionTime > 0) {
			throughput = sumbits / executionTime / 1000.0 / 1000.0;
			latency = 1000000.0 * executionTime / ops;
		}
		
		System.out.println("execution time " + executionTime);
		System.out.println("ops " + ops);
		System.out.println("sumbytes " + sumbytes);
		System.out.println("throughput " + throughput);
		System.out.println("latency " + latency);
		
		fs.close();		
		fs.getStatistics().print("close");
	}
	
	void writeSequentialAsync(String filename, int size, int loop, int batch, boolean affinity) throws Exception {
		System.out.println("writeSequentialAsync, filename " + filename  + ", size " + size + ", loop " + loop + ", batch " + batch + ", affinity " + affinity);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		int hosthash = 0;
		if (affinity){
			hosthash = fs.getHostHash();
		}
		
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		for (int i = 0; i < batch; i++){
			CrailBuffer buf = null;
			if (size == CrailConstants.BUFFER_SIZE){
				buf = fs.allocateBuffer();
			} else if (size < CrailConstants.BUFFER_SIZE){
				CrailBuffer _buf = fs.allocateBuffer();
				_buf.clear().limit(size);
				buf = _buf.slice();
			} else {
				buf = OffHeapBuffer.wrap(ByteBuffer.allocateDirect(size));
			}
			bufferQueue.add(buf);
		}
		
		//warmup
		warmUp(fs, filename, warmup, bufferQueue);				
		
		//benchmark
		System.out.println("starting benchmark...");
		LinkedBlockingQueue<Future<CrailResult>> futureQueue = new LinkedBlockingQueue<Future<CrailResult>>();
		HashMap<Integer, CrailBuffer> futureMap = new HashMap<Integer, CrailBuffer>();
		fs.getStatistics().reset();
		long _loop = (long) loop;
		long _bufsize = (long) CrailConstants.BUFFER_SIZE;
		long _capacity = _loop*_bufsize;
		double sumbytes = 0;
		double ops = 0;
		CrailFile file = fs.create(filename, CrailNodeType.DATAFILE, hosthash, 0).get().asFile();
		CrailOutputStream directStream = file.getDirectOutputStream(_capacity);	
		long start = System.currentTimeMillis();
		for (int i = 0; i < batch - 1 && ops < loop; i++){
			CrailBuffer buf = bufferQueue.poll();
			buf.clear();
			Future<CrailResult> future = directStream.write(buf);
			futureQueue.add(future);
			futureMap.put(future.hashCode(), buf);
			ops = ops + 1.0;
		}
		while (ops < loop) {
			CrailBuffer buf = bufferQueue.poll();
			buf.clear();
			Future<CrailResult> future = directStream.write(buf);
			futureQueue.add(future);
			futureMap.put(future.hashCode(), buf);
			
			future = futureQueue.poll();
			future.get();
			buf = futureMap.get(future.hashCode());
			bufferQueue.add(buf);
			
			sumbytes = sumbytes + buf.capacity();
			ops = ops + 1.0;
		}
		while (!futureQueue.isEmpty()){
			Future<CrailResult> future = futureQueue.poll();
			future.get();
			CrailBuffer buf = futureMap.get(future.hashCode());
			sumbytes = sumbytes + buf.capacity();
			ops = ops + 1.0;			
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start)) / 1000.0;
		double throughput = 0.0;
		double latency = 0.0;
		double sumbits = sumbytes * 8.0;
		if (executionTime > 0) {
			throughput = sumbits / executionTime / 1000.0 / 1000.0;
			latency = 1000000.0 * executionTime / ops;
		}
		directStream.close();	
		
		System.out.println("execution time " + executionTime);
		System.out.println("ops " + ops);
		System.out.println("sumbytes " + sumbytes);
		System.out.println("throughput " + throughput);
		System.out.println("latency " + latency);
		
		fs.getStatistics().print("close");
		fs.close();		
	}

	void readSequential(String filename, int size, int loop, boolean direct) throws Exception {
		System.out.println("readSequential, filename " + filename  + ", size " + size + ", loop " + loop + ", direct " + direct);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);

		CrailBuffer buf = null;
		if (size == CrailConstants.BUFFER_SIZE){
			buf = fs.allocateBuffer();
		} else if (size < CrailConstants.BUFFER_SIZE){
			CrailBuffer _buf = fs.allocateBuffer();
			_buf.clear().limit(size);
			buf = _buf.slice();
		} else {
			buf = OffHeapBuffer.wrap(ByteBuffer.allocateDirect(size));
		}
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		bufferQueue.add(buf);
		warmUp(fs, filename, warmup, bufferQueue);
		
		CrailFile file = fs.lookup(filename).get().asFile();
		CrailBufferedInputStream bufferedStream = file.getBufferedInputStream(file.getCapacity());
		CrailInputStream directStream = file.getDirectInputStream(file.getCapacity());
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		double sumbytes = 0;
		double ops = 0;
		long start = System.currentTimeMillis();
		while (ops < loop) {
			if (direct){
				buf.clear();
				double ret = (double) directStream.read(buf).get().getLen();
				if (ret > 0) {
					sumbytes = sumbytes + ret;
					ops = ops + 1.0;
				} else {
					ops = ops + 1.0;
					if (directStream.position() == 0){
						break;
					} else {
						directStream.seek(0);
					}
				}
				
			} else {
				buf.clear();
				double ret = (double) bufferedStream.read(buf.getByteBuffer());
				if (ret > 0) {
					sumbytes = sumbytes + ret;
					ops = ops + 1.0;
				} else {
					ops = ops + 1.0;
					if (bufferedStream.position() == 0){
						break;
					} else {
						bufferedStream.seek(0);
					}
				}
			}
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start)) / 1000.0;
		double throughput = 0.0;
		double latency = 0.0;
		double sumbits = sumbytes * 8.0;
		if (executionTime > 0) {
			throughput = sumbits / executionTime / 1000.0 / 1000.0;
			latency = 1000000.0 * executionTime / ops;
		}
		bufferedStream.close();	
		directStream.close();
		
		System.out.println("execution time " + executionTime);
		System.out.println("ops " + ops);
		System.out.println("sumbytes " + sumbytes);
		System.out.println("throughput " + throughput);
		System.out.println("latency " + latency);
		
		fs.getStatistics().print("close");
		fs.close();
	}
	
	void readRandom(String filename, int size, int loop, boolean direct) throws Exception{
		System.out.println("readRandom, filename " + filename  + ", size " + size + ", loop " + loop + ", direct " + direct);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);

		CrailBuffer buf = null;
		if (size == CrailConstants.BUFFER_SIZE){
			buf = fs.allocateBuffer();
		} else if (size < CrailConstants.BUFFER_SIZE){
			CrailBuffer _buf = fs.allocateBuffer();
			_buf.clear().limit(size);
			buf = _buf.slice();
		} else {
			buf = OffHeapBuffer.wrap(ByteBuffer.allocateDirect(size));
		}
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		bufferQueue.add(buf);
		warmUp(fs, filename, warmup, bufferQueue);		
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		CrailFile file = fs.lookup(filename).get().asFile();
		CrailBufferedInputStream bufferedStream = file.getBufferedInputStream(file.getCapacity());
		CrailInputStream directStream = file.getDirectInputStream(file.getCapacity());		
		
		double sumbytes = 0;
		double ops = 0;
        long _range = file.getCapacity() - ((long)buf.capacity());
        double range = (double) _range;
		Random random = new Random();
		
		long start = System.currentTimeMillis();
		while (ops < loop) {
			if (direct){
				buf.clear();
				double _offset = range*random.nextDouble();
				long offset = (long) _offset;
				directStream.seek(offset);
				double ret = (double) directStream.read(buf).get().getLen();
				if (ret > 0) {
					sumbytes = sumbytes + ret;
					ops = ops + 1.0;
				} else {
					break;
				}
			} else {
				buf.clear();
				double _offset = range*random.nextDouble();
				long offset = (long) _offset;
				bufferedStream.seek(offset);
				double ret = (double) bufferedStream.read(buf.getByteBuffer());
				if (ret > 0) {
					sumbytes = sumbytes + ret;
					ops = ops + 1.0;
				} else {
					break;
				}
			}
			
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start)) / 1000.0;
		double throughput = 0.0;
		double latency = 0.0;
		double sumbits = sumbytes * 8.0;
		if (executionTime > 0) {
			throughput = sumbits / executionTime / 1000.0 / 1000.0;
			latency = 1000000.0 * executionTime / ops;
		}		
		bufferedStream.close();
		directStream.close();
		
		System.out.println("execution time " + executionTime);
		System.out.println("ops " + ops);
		System.out.println("sumbytes " + sumbytes);
		System.out.println("throughput " + throughput);
		System.out.println("latency " + latency);
		
		fs.getStatistics().print("close");
		fs.close();
	}	
	
	void readSequentialAsync(String filename, int size, int loop, int batch, boolean direct) throws Exception {
		System.out.println("readSequentialAsync, filename " + filename  + ", size " + size + ", loop " + loop + ", batch " + batch + ", direct " + direct);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		for (int i = 0; i < batch; i++){
			CrailBuffer buf = null;
			if (size == CrailConstants.BUFFER_SIZE){
				buf = fs.allocateBuffer();
			} else if (size < CrailConstants.BUFFER_SIZE){
				CrailBuffer _buf = fs.allocateBuffer();
				_buf.clear().limit(size);
				buf = _buf.slice();
			} else {
				buf = OffHeapBuffer.wrap(ByteBuffer.allocateDirect(size));
			}
			bufferQueue.add(buf);
		}

		//warmup
		warmUp(fs, filename, warmup, bufferQueue);	
		
		//benchmark
		System.out.println("starting benchmark...");
		double sumbytes = 0;
		double ops = 0;
		fs.getStatistics().reset();
		CrailFile file = fs.lookup(filename).get().asFile();
		CrailInputStream directStream = file.getDirectInputStream(file.getCapacity());			
		HashMap<Integer, CrailBuffer> futureMap = new HashMap<Integer, CrailBuffer>();
		LinkedBlockingQueue<Future<CrailResult>> futureQueue = new LinkedBlockingQueue<Future<CrailResult>>();
		long start = System.currentTimeMillis();
		for (int i = 0; i < batch - 1 && ops < loop; i++){
			CrailBuffer buf = bufferQueue.poll();
			buf.clear();
			Future<CrailResult> future = directStream.read(buf);
			futureQueue.add(future);
			futureMap.put(future.hashCode(), buf);
			ops = ops + 1.0;
		}
		while (ops < loop) {
			CrailBuffer buf = bufferQueue.poll();
			buf.clear();
			Future<CrailResult> future = directStream.read(buf);
			futureQueue.add(future);
			futureMap.put(future.hashCode(), buf);
			
			future = futureQueue.poll();
			CrailResult result = future.get();
			buf = futureMap.get(future.hashCode());
			bufferQueue.add(buf);
			
			sumbytes = sumbytes + result.getLen();
			ops = ops + 1.0;
		}
		while (!futureQueue.isEmpty()){
			Future<CrailResult> future = futureQueue.poll();
			CrailResult result = future.get();
			futureMap.get(future.hashCode());
			sumbytes = sumbytes + result.getLen();
			ops = ops + 1.0;			
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start)) / 1000.0;
		double throughput = 0.0;
		double latency = 0.0;
		double sumbits = sumbytes * 8.0;
		if (executionTime > 0) {
			throughput = sumbits / executionTime / 1000.0 / 1000.0;
			latency = 1000000.0 * executionTime / ops;
		}
		directStream.close();	
		
		System.out.println("execution time " + executionTime);
		System.out.println("ops " + ops);
		System.out.println("sumbytes " + sumbytes);
		System.out.println("throughput " + throughput);
		System.out.println("latency " + latency);
		
		fs.getStatistics().print("close");
		fs.close();		
	}

	void readMultiStream(String filename, int size, int loop, int batch) throws Exception {
		System.out.println("readMultiStream, filename " + filename  + ", size " + size + ", loop " + loop + ", batch " + batch);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		for (int i = 0; i < warmup; i++){
			CrailBuffer buf = fs.allocateBuffer().limit(size).slice();
			bufferQueue.add(buf);
		}
		warmUp(fs, filename, warmup, bufferQueue);
		while(!bufferQueue.isEmpty()){
			CrailBuffer buf = bufferQueue.poll();
			fs.freeBuffer(buf);
		}
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		CrailBuffer _buf = null;
		if (size == CrailConstants.BUFFER_SIZE){
			_buf = fs.allocateBuffer();
		} else if (size < CrailConstants.BUFFER_SIZE){
			CrailBuffer __buf = fs.allocateBuffer();
			__buf.clear().limit(size);
			_buf = __buf.slice();
		} else {
			_buf = OffHeapBuffer.wrap(ByteBuffer.allocateDirect(size));
		}		
		ByteBuffer buf = _buf.getByteBuffer();
		for (int i = 0; i < loop; i++){
			CrailBufferedInputStream multiStream = fs.lookup(filename).get().asMultiFile().getMultiStream(batch);
			double sumbytes = 0;
			long _sumbytes = 0;
			double ops = 0;
			buf.clear();
			long start = System.currentTimeMillis();
			int ret = multiStream.read(buf);
			while(ret >= 0){
				sumbytes = sumbytes + ret;
				long _ret = (long) ret;
				_sumbytes +=  _ret;				
				ops = ops + 1.0;
				buf.clear();
				ret = multiStream.read(buf);
			}
			long end = System.currentTimeMillis();
			multiStream.close();	
			
			double executionTime = ((double) (end - start)) / 1000.0;
			double throughput = 0.0;
			double latency = 0.0;
			double sumbits = sumbytes * 8.0;
			if (executionTime > 0) {
				throughput = sumbits / executionTime / 1000.0 / 1000.0;
				latency = 1000000.0 * executionTime / ops;
			}
			
			System.out.println("round " + i + ":");
			System.out.println("bytes read " + _sumbytes);
			System.out.println("execution time " + executionTime);
			System.out.println("ops " + ops);
			System.out.println("throughput " + throughput);
			System.out.println("latency " + latency);
		}
	
		fs.getStatistics().print("close");
		fs.close();
	}
	
	void getFile(String filename, int loop) throws Exception, InterruptedException {
		System.out.println("getFile, filename " + filename  + ", loop " + loop);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);	
		
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		CrailBuffer buf = fs.allocateBuffer();
		bufferQueue.add(buf);
		warmUp(fs, filename, warmup, bufferQueue);		
		fs.freeBuffer(buf);
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		double ops = 0;
		long start = System.currentTimeMillis();
		while (ops < loop) {
			ops = ops + 1.0;
			fs.lookup(filename).get().asFile();
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start)) / 1000.0;
		double latency = 0.0;
		if (executionTime > 0) {
			latency = 1000000.0 * executionTime / ops;
		}	
		System.out.println("execution time " + executionTime);
		System.out.println("ops " + ops);
		System.out.println("latency " + latency);
		
		fs.getStatistics().print("close");
		fs.close();
	}
	
	void getFileAsync(String filename, int loop, int batch) throws Exception, InterruptedException {
		System.out.println("getFileAsync, filename " + filename  + ", loop " + loop + ", batch " + batch);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);	
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		CrailBuffer buf = fs.allocateBuffer();
		bufferQueue.add(buf);
		warmUp(fs, filename, warmup, bufferQueue);		
		fs.freeBuffer(buf);	
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		LinkedBlockingQueue<Future<CrailNode>> fileQueue = new LinkedBlockingQueue<Future<CrailNode>>();
		long start = System.currentTimeMillis();
		for (int i = 0; i < loop; i++){
			//single operation == loop
			for (int j = 0; j < batch; j++){
				Future<CrailNode> future = fs.lookup(filename);
				fileQueue.add(future);
			}
			for (int j = 0; j < batch; j++){
				Future<CrailNode> future = fileQueue.poll();
				future.get();
			}
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start));
		double latency = executionTime*1000.0 / ((double) batch);
		System.out.println("execution time [ms] " + executionTime);
		System.out.println("latency [us] " + latency);
		
		fs.getStatistics().print("close");
		fs.close();
	}
	
	void createFile(String filename, int loop) throws Exception, InterruptedException {
		System.out.println("createFile, filename " + filename  + ", loop " + loop);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);	
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		CrailBuffer buf = fs.allocateBuffer();
		bufferQueue.add(buf);
		warmUp(fs, filename, warmup, bufferQueue);		
		fs.freeBuffer(buf);	
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		LinkedBlockingQueue<String> pathQueue = new LinkedBlockingQueue<String>();
		fs.create(filename, CrailNodeType.DIRECTORY, 0, 0).get().syncDir();
		int filecounter = 0;
		for (int i = 0; i < loop; i++){
			String name = "" + filecounter++;
			String f = filename + "/" + name;
			pathQueue.add(f);
		}		
		
		double ops = 0;
		long start = System.currentTimeMillis();
		while(!pathQueue.isEmpty()){
			String path = pathQueue.poll();
			fs.create(path, CrailNodeType.DATAFILE, 0, 0).get().syncDir();
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start)) / 1000.0;
		double latency = 0.0;
		if (executionTime > 0) {
			latency = 1000000.0 * executionTime / ops;
		}	
		
		System.out.println("execution time " + executionTime);
		System.out.println("ops " + ops);
		System.out.println("latency " + latency);
		
		fs.getStatistics().print("close");
		fs.close();
	}	
	
	void createFileAsync(String filename, int loop, int batch) throws Exception, InterruptedException {
		System.out.println("createFileAsync, filename " + filename  + ", loop " + loop + ", batch " + batch);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);	
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		CrailBuffer buf = fs.allocateBuffer();
		bufferQueue.add(buf);
		warmUp(fs, filename, warmup, bufferQueue);		
		fs.freeBuffer(buf);			
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		LinkedBlockingQueue<Future<CrailNode>> futureQueue = new LinkedBlockingQueue<Future<CrailNode>>();
		LinkedBlockingQueue<CrailFile> fileQueue = new LinkedBlockingQueue<CrailFile>();
		LinkedBlockingQueue<String> pathQueue = new LinkedBlockingQueue<String>();
		fs.create(filename, CrailNodeType.DIRECTORY, 0, 0).get().syncDir();	
		
		for (int i = 0; i < loop; i++){
			String name = "/" + i;
			String f = filename + name;
			pathQueue.add(f);
		}			
		
		long start = System.currentTimeMillis();
		for (int i = 0; i < loop; i += batch){
			//single operation == loop
			for (int j = 0; j < batch; j++) {
				String path = pathQueue.poll();
				Future<CrailNode> future = fs.create(path, CrailNodeType.DATAFILE, 0, 0);
				futureQueue.add(future);
			}
			for (int j = 0; j < batch; j++){
				Future<CrailNode> future = futureQueue.poll();
				CrailFile file = future.get().asFile();
				fileQueue.add(file);					
			}
			for (int j = 0; j < batch; j++){
				CrailFile file = fileQueue.poll();
				file.syncDir();
			}
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start));
		double latency = executionTime*1000.0 / ((double) loop);
		System.out.println("execution time [ms] " + executionTime);
		System.out.println("latency [us] " + latency);

		fs.delete(filename, true).get().syncDir();
		
		fs.getStatistics().print("close");
		fs.close();
		
	}	
	
	void enumerateDir(String filename, int loop) throws Exception {
		System.out.println("reading enumarate dir, path " + filename);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		//warmup
		ConcurrentLinkedQueue<CrailBuffer> bufferQueue = new ConcurrentLinkedQueue<CrailBuffer>();
		CrailBuffer buf = fs.allocateBuffer();
		bufferQueue.add(buf);
		warmUp(fs, filename, warmup, bufferQueue);		
		fs.freeBuffer(buf);			

		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		long start = System.currentTimeMillis();
		for (int i = 0; i < loop; i++) {
			// single operation == loop
			Iterator<String> iter = fs.lookup(filename).get().asDirectory().listEntries();
			while (iter.hasNext()) {
				iter.next();
			}
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start));
		double latency = executionTime * 1000.0 / ((double) loop);
		System.out.println("execution time [ms] " + executionTime);
		System.out.println("latency [us] " + latency);

		fs.getStatistics().print("close");
		fs.close();
	}
	
	void browseDir(String filename) throws Exception {
		System.out.println("reading enumarate dir, path " + filename);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		CrailNode node = fs.lookup(filename).get();
		System.out.println("node type is " + node.getType());
		
		Iterator<String> iter = node.getType() == CrailNodeType.DIRECTORY ? node.asDirectory().listEntries() : node.asMultiFile().listEntries();
		while (iter.hasNext()) {
			String name = iter.next();
			System.out.println(name);
		}
		fs.getStatistics().print("close");
		fs.close();
	}	
	
	void createMultiFile(String filename) throws Exception, InterruptedException {
		System.out.println("createMultiFile, filename " + filename);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);	
		fs.create(filename, CrailNodeType.MULTIFILE, 0, 0).get().syncDir();
		fs.close();
	}
	
	void keyGet(String filename, int size, int loop) throws Exception {
		System.out.println("keyGet, path " + filename + ", size " + size + ", loop " + loop);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		CrailBuffer buf = fs.allocateBuffer();
		CrailFile file = fs.create(filename, CrailNodeType.DATAFILE, 0, 0).get().asFile();
		file.syncDir();
		CrailOutputStream directOutputStream = file.getDirectOutputStream(0);
		directOutputStream.write(buf).get();
		directOutputStream.close();
		
		//benchmark
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		long start = System.currentTimeMillis();
		for (int i = 0; i < loop; i++){
			CrailInputStream directInputStream = fs.lookup(filename).get().asFile().getDirectInputStream(0);
			buf.clear();
			directInputStream.read(buf).get();
			directInputStream.close();
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start));
		double latency = executionTime * 1000.0 / ((double) loop);
		System.out.println("execution time [ms] " + executionTime);
		System.out.println("latency [us] " + latency);		
		
		fs.getStatistics().print("close");
		fs.close();
	}	
	
	void early(String filename) throws Exception {
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		ByteBuffer buf = ByteBuffer.allocateDirect(32);
		CrailFile file = fs.create(filename, CrailNodeType.DATAFILE, 0, 0).early().asFile();
		CrailBufferedOutputStream stream = file.getBufferedOutputStream(0);
		System.out.println("buffered stream initialized");
		
		Thread.sleep(1000);
		stream.write(buf);
		System.out.println("buffered stream written");

		Thread.sleep(1000);
		stream.write(buf);
		System.out.println("buffered stream written");		
		
		stream.purge();
		stream.close();
		
		System.out.println("buffered stream closed");
		
		fs.getStatistics().print("close");
		fs.close();		
	}
	
	void writeInt(String filename, int loop) throws Exception {
		System.out.println("writeInt, filename " + filename  + ", loop " + loop);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		//benchmark
		System.out.println("starting benchmark...");
		double ops = 0;
		CrailFile file = fs.create(filename, CrailNodeType.DATAFILE, 0, 0).get().asFile();
		CrailBufferedOutputStream outputStream = file.getBufferedOutputStream(loop*4);	
		int intValue = 0;
		System.out.println("starting write at position " + outputStream.position());
		while (ops < loop) {
			System.out.println("writing position " + outputStream.position() + ", value " + intValue);
			outputStream.writeInt(intValue);
			intValue++;
			ops++;
		}
		outputStream.purge().get();
		outputStream.sync().get();
		
		fs.close();		
		fs.getStatistics().print("close");		
	}
	
	void readInt(String filename, int loop) throws Exception {
		System.out.println("seek, filename " + filename  + ", loop " + loop);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		//benchmark
		System.out.println("starting benchmark...");
		double ops = 0;
		CrailFile file = fs.lookup(filename).get().asFile();
		CrailBufferedInputStream inputStream = file.getBufferedInputStream(loop*4);	
		System.out.println("starting read at position " + inputStream.position());
		while (ops < loop) {
			System.out.print("reading position " + inputStream.position() + ", expected " + inputStream.position()/4 + " ");
			int intValue = inputStream.readInt();
			System.out.println(", value " + intValue);
			ops++;
		}
		inputStream.close();
		
		fs.close();		
		fs.getStatistics().print("close");		
	}
	
	void seekInt(String filename, int loop) throws Exception {
		System.out.println("seek, filename " + filename  + ", loop " + loop);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		//benchmark
		System.out.println("starting benchmark...");
		double ops = 0;
		CrailFile file = fs.lookup(filename).get().asFile();
		Random random = new Random();
		long nbrOfInts = file.getCapacity() / 4;
		CrailBufferedInputStream seekStream = file.getBufferedInputStream(loop*4);	
		System.out.println("starting seek phase, nbrOfInts " + nbrOfInts + ", position " + seekStream.position());
		long falseMatches = 0;
		while (ops < loop) {
			int intIndex = random.nextInt((int) nbrOfInts);
			int pos = intIndex*4;
			seekStream.seek((long) pos);
			int intValue = seekStream.readInt();
			if (intIndex != intValue){
				falseMatches++;
				System.out.println("reading, position " + pos + ", expected " + pos/4 + ", ########## value " + intValue);
			} else {
				System.out.println("reading, position " + pos + ", expected " + pos/4 + ", value " + intValue);
			}
			ops++;
		}			
		seekStream.close();
		long end = System.currentTimeMillis();
		
		System.out.println("falseMatches " + falseMatches);
		
		fs.close();		
		fs.getStatistics().print("close");
	}	
	
	void readMultiStreamInt(String filename, int loop, int batch) throws Exception {
		System.out.println("readMultiStreamInt, filename " + filename  + ", loop " + loop + ", batch " + batch);
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		
		System.out.println("starting benchmark...");
		fs.getStatistics().reset();
		CrailBufferedInputStream multiStream = fs.lookup(filename).get().asMultiFile().getMultiStream(batch);
		double ops = 0;
		long falseMatches = 0;
		while (ops < loop) {
			System.out.print("reading position " + multiStream.position() + ", expected " + multiStream.position()/4 + " ");
			long expected = multiStream.position()/4;
			int intValue = multiStream.readInt();
			if (expected != intValue){
				falseMatches++;
			}
			System.out.println(", value " + intValue);
			ops++;
		}
		multiStream.close();	
		
		System.out.println("falseMatches " + falseMatches);
		
		fs.getStatistics().print("close");
		fs.close();
	}	
	
	void locationMap() throws Exception {
		CrailConfiguration conf = new CrailConfiguration();
		CrailFS fs = CrailFS.newInstance(conf);
		ConcurrentHashMap<String, String> locationMap = new ConcurrentHashMap<String, String>();
		CrailUtils.parseMap(CrailConstants.LOCATION_MAP, locationMap);
		
		System.out.println("Parsing locationMap " + CrailConstants.LOCATION_MAP);
		for (String key : locationMap.keySet()){
			System.out.println("key " + key + ", value " + locationMap.get(key));
		}
		
		fs.close();
	}
	
	void collectionTest(int size, int loop) throws Exception {
		System.out.println("collectionTest, size " + size  + ", loop " + loop);

		RingBuffer<Object> ringBuffer = new RingBuffer<Object>(10);
		ArrayBlockingQueue<Object> arrayQueue = new ArrayBlockingQueue<Object>(10);
		LinkedBlockingQueue<Object> listQueue = new LinkedBlockingQueue<Object>();
		
		Object obj = new Object();
		long start = System.currentTimeMillis();
		for (int i = 0; i < loop; i++){
			for (int j = 0; j < size; j++){
				ringBuffer.add(obj);
				Object tmp = ringBuffer.peek();
				tmp = ringBuffer.poll();
			}
		}
		long end = System.currentTimeMillis();
		double executionTime = ((double) (end - start));
		System.out.println("ringbuffer, execution time [ms] " + executionTime);
		
		start = System.currentTimeMillis();
		for (int i = 0; i < loop; i++){
			for (int j = 0; j < size; j++){
				arrayQueue.add(obj);
				Object tmp = arrayQueue.peek();
				tmp = arrayQueue.poll();
			}
		}
		end = System.currentTimeMillis();
		executionTime = ((double) (end - start));
		System.out.println("arrayQueue, execution time [ms] " + executionTime);		
		
		start = System.currentTimeMillis();
		for (int i = 0; i < loop; i++){
			for (int j = 0; j < size; j++){
				listQueue.add(obj);
				Object tmp = listQueue.peek();
				tmp = listQueue.poll();
			}
		}
		end = System.currentTimeMillis();
		executionTime = ((double) (end - start));
		System.out.println("arrayQueue, execution time [ms] " + executionTime);			
	}	
	
	private void warmUp(CrailFS fs, String filename, int operations, ConcurrentLinkedQueue<CrailBuffer> bufferList) throws Exception {
		Random random = new Random();
		String warmupFilename = filename + random.nextInt();
		System.out.println("warmUp, warmupFile " + warmupFilename + ", operations " + operations);
		if (operations > 0){
			CrailFile warmupFile = fs.create(warmupFilename, CrailNodeType.DATAFILE, 0, 0).get().asFile();
			CrailBufferedOutputStream warmupStream = warmupFile.getBufferedOutputStream(0);
			for (int i = 0; i < operations; i++){
				CrailBuffer buf = bufferList.poll();
				buf.clear();
				warmupStream.write(buf.getByteBuffer());
				bufferList.add(buf);
			}
			warmupStream.purge().get();
			warmupStream.close();
			fs.delete(warmupFilename, false).get().syncDir();			
		}
	}
	
	public static void main(String[] args) throws Exception {
		String[] _args = args;
		GetOpt go = new GetOpt(_args, "t:f:s:k:b:w:e:");
		go.optErr = true;
		int ch = -1;
		if (args.length < 2){
			usage();
		}
		
		String type = "";
		String filename = "/tmp.dat";
		int size = CrailConstants.BUFFER_SIZE;
		int loop = 1;
		int batch = 1;
		int warmup = 32;
		int experiments = 1;
		
		while ((ch = go.getopt()) != GetOpt.optEOF) {
			if ((char) ch == 't') {
				type = go.optArgGet();
			} else if ((char) ch == 'f') {
				filename = go.optArgGet();
			} else if ((char) ch == 's') {
				size = Integer.parseInt(go.optArgGet());
			} else if ((char) ch == 'k') {
				loop = Integer.parseInt(go.optArgGet());
			} else if ((char) ch == 'b') {
				batch = Integer.parseInt(go.optArgGet());
			} else if ((char) ch == 'w') {
				warmup = Integer.parseInt(go.optArgGet());
			} else if ((char) ch == 'e') {
				experiments = Integer.parseInt(go.optArgGet());
			} else {
				System.exit(1); // undefined option
			}
		}		
		
		CrailBenchmark benchmark = new CrailBenchmark(warmup);
		if (type.equals("writeClusterHeap")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.writeSequential(filename, size, loop, false, false);
			}
		} else if (type.equals("writeClusterDirect")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.writeSequential(filename, size, loop, false, true);
			}			
		} else if (type.equals("writeLocalHeap")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.writeSequential(filename, size, loop, true, false);
			}			
		} else if (type.equals("writeLocalDirect")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.writeSequential(filename, size, loop, true, true);
			}			
		} else if (type.equalsIgnoreCase("writeAsyncCluster")) {
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.writeSequentialAsync(filename, size, loop, batch, false);
			}			
		} else if (type.equalsIgnoreCase("writeAsyncLocal")) {
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.writeSequentialAsync(filename, size, loop, batch, true);
			}
		} else if (type.equalsIgnoreCase("readSequentialDirect")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.readSequential(filename, size, loop, true);
			}			
		} else if (type.equals("readSequentialHeap")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.readSequential(filename, size, loop, false);
			}
		} else if (type.equals("readRandomDirect")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.readRandom(filename, size, loop, true);
			}
		} else if (type.equals("readRandomHeap")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.readRandom(filename, size, loop, false);
			}
		} else if (type.equalsIgnoreCase("readAsync")) {
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.readSequentialAsync(filename, size, loop, batch, true);
			}
		} else if (type.equalsIgnoreCase("readMultiStream")) {
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.readMultiStream(filename, size, loop, batch);
			}
		} else if (type.equals("getFile")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.getFile(filename, loop);
			}
		} else if (type.equals("getFileAsync")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.getFileAsync(filename, loop, batch);
			}
		} else if (type.equals("createFile")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.createFile(filename, loop);
			}
		} else if (type.equals("createFileAsync")){
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.createFileAsync(filename, loop, batch);
			}
		} else if (type.equalsIgnoreCase("enumerateDir")) {
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.enumerateDir(filename, batch);
			}
		} else if (type.equalsIgnoreCase("keyGet")) {
			for (int i = 0; i < experiments; i++){
				System.out.println("experiment " + i);
				benchmark.keyGet(filename, size, loop);
			}
		} else if (type.equalsIgnoreCase("createMultiFile")) {
			benchmark.createMultiFile(filename);
		} else if (type.equalsIgnoreCase("browseDir")) {
			benchmark.browseDir(filename);
		} else if (type.equalsIgnoreCase("early")) {
			benchmark.early(filename);
		} else if (type.equalsIgnoreCase("writeInt")) {
			benchmark.writeInt(filename, loop);
		} else if (type.equalsIgnoreCase("readInt")) {
			benchmark.readInt(filename, loop);
		} else if (type.equalsIgnoreCase("seekInt")) {
			benchmark.seekInt(filename, loop);
		} else if (type.equalsIgnoreCase("readMultiStreamInt")) {
			benchmark.readMultiStreamInt(filename, loop, batch);
		} else if (type.equalsIgnoreCase("collection")) {
			for (int i = 0; i < experiments; i++){
				benchmark.collectionTest(size, loop);
			}
		} else if (type.equalsIgnoreCase("locationMap")) {
			benchmark.locationMap();
		} else {
			usage();
			System.exit(0);
		}		
	}

}


/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package net.rubyeye.xmemcached.impl;

import net.rubyeye.xmemcached.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import net.rubyeye.xmemcached.buffer.BufferAllocator;
import net.rubyeye.xmemcached.buffer.IoBuffer;
import net.rubyeye.xmemcached.command.Command;
import net.rubyeye.xmemcached.command.CommandType;
import net.rubyeye.xmemcached.command.OperationStatus;
import net.rubyeye.xmemcached.command.text.TextGetOneCommand;
import net.rubyeye.xmemcached.monitor.Constants;
import net.rubyeye.xmemcached.monitor.XMemcachedMbeanServer;
import net.rubyeye.xmemcached.utils.ByteUtils;

/**
 * Memcached command optimizer,merge single-get comands to multi-get
 * command,merge ByteBuffers to fit the socket's sendBufferSize etc.
 * 
 * @author dennis
 */
public class Optimizer implements OptimizerMBean, MemcachedOptimizer {

	public static final int DEFAULT_MERGE_FACTOR = 150;
	private int mergeFactor = DEFAULT_MERGE_FACTOR; // default merge factor;
	private boolean optimiezeGet = true;
	private boolean optimiezeMergeBuffer = true;
	private BufferAllocator bufferAllocator;
	private static final Log log = LogFactory.getLog(Optimizer.class);

	public Optimizer() {
		XMemcachedMbeanServer.getInstance().registMBean(
				this,
				this.getClass().getPackage().getName() + ":type="
						+ this.getClass().getSimpleName());
	}

	public BufferAllocator getBufferAllocator() {
		return bufferAllocator;
	}

	public void setBufferAllocator(BufferAllocator bufferAllocator) {
		this.bufferAllocator = bufferAllocator;
	}

	public int getMergeFactor() {
		return mergeFactor;
	}

	public void setMergeFactor(int mergeFactor) {
		log.warn("change mergeFactor from " + this.mergeFactor + " to "
				+ mergeFactor);
		this.mergeFactor = mergeFactor;

	}

	public boolean isOptimizeGet() {
		return optimiezeGet;
	}

	public void setOptimizeGet(boolean optimiezeGet) {
		log.warn(optimiezeGet ? "Enable merge get commands"
				: "Disable merge get commands");
		this.optimiezeGet = optimiezeGet;
	}

	public boolean isOptimizeMergeBuffer() {
		return optimiezeMergeBuffer;
	}

	public void setOptimizeMergeBuffer(boolean optimiezeMergeBuffer) {
		log.warn(optimiezeMergeBuffer ? "Enable merge buffers"
				: "Disable merge buffers");
		this.optimiezeMergeBuffer = optimiezeMergeBuffer;
	}

	@SuppressWarnings("unchecked")
	public Command optimize(final Command currentCommand,
			final Queue writeQueue, final BlockingQueue<Command> executingCmds,
			int sendBufferSize) {
		Command optimiezeCommand = currentCommand;
		optimiezeCommand = optimiezeGet(writeQueue, executingCmds,
				optimiezeCommand);
		optimiezeCommand = optimiezeMergeBuffer(optimiezeCommand, writeQueue,
				executingCmds, sendBufferSize);
		return optimiezeCommand;
	}

	/**
	 *merge buffers to fit socket's send buffer size
	 * 
	 * @param currentCommand
	 * @return
	 * @throws InterruptedException
	 */
	@SuppressWarnings("unchecked")
	public final Command optimiezeMergeBuffer(Command optimiezeCommand,
			final Queue writeQueue, final BlockingQueue<Command> executingCmds,
			int sendBufferSize) {
		if (log.isDebugEnabled())
			log.debug("Optimieze merge buffer:" + optimiezeCommand.toString());
		if (optimiezeMergeBuffer
				&& optimiezeCommand.getIoBuffer().getByteBuffer().remaining() < sendBufferSize) {
			optimiezeCommand = mergeBuffer(optimiezeCommand, writeQueue,
					executingCmds, sendBufferSize);
		}
		return optimiezeCommand;
	}

	/**
	 * Merge get operation to multi-get operation
	 * 
	 * @param currentCmd
	 * @param mergeCommands
	 * @return
	 * @throws InterruptedException
	 */
	@SuppressWarnings("unchecked")
	public final Command optimiezeGet(final Queue writeQueue,
			final BlockingQueue<Command> executingCmds, Command optimiezeCommand) {
		if (optimiezeCommand.getCommandType() == CommandType.GET_ONE) {
			// 优化get操作
			if (optimiezeGet) {
				optimiezeCommand = mergeGetCommands(optimiezeCommand,
						writeQueue, executingCmds);
			}
		}
		return optimiezeCommand;
	}

	@SuppressWarnings("unchecked")
	private final Command mergeBuffer(final Command firstCommand,
			final Queue writeQueue, final BlockingQueue<Command> executingCmds,
			final int sendBufferSize) {
		Command lastCommand = firstCommand; // 合并的最后一个command
		Command nextCmd = (Command) writeQueue.peek();
		if (nextCmd == null)
			return lastCommand;

		final List<Command> commands = getLocalList();
		final ByteBuffer firstBuffer = firstCommand.getIoBuffer()
				.getByteBuffer();
		int totalBytes = firstBuffer.remaining();
		commands.add(firstCommand);
		while (totalBytes + nextCmd.getIoBuffer().getByteBuffer().remaining() <= sendBufferSize) {
			if (nextCmd.getStatus() == OperationStatus.WRITING) {
				break;
			}
			if (nextCmd.isCancel()) {
				writeQueue.remove();
				continue;
			}
			nextCmd.setStatus(OperationStatus.WRITING);

			if (!nextCmd.getIoBuffer().getByteBuffer().hasRemaining()) {
				writeQueue.remove();
				continue;
			}

			writeQueue.remove();
			// if it is get_one command,try to merge get commands
			if (nextCmd.getCommandType() == CommandType.GET_ONE && optimiezeGet)
				nextCmd = mergeGetCommands(nextCmd, writeQueue, executingCmds);

			commands.add(nextCmd);
			lastCommand = nextCmd;
			totalBytes += nextCmd.getIoBuffer().getByteBuffer().remaining();

			if (totalBytes > sendBufferSize)
				break;

			nextCmd = (Command) writeQueue.peek();
			if (nextCmd == null) {
				break;
			}
		}
		if (commands.size() > 1) {
			// ArrayIoBuffer arrayBuffer = new ArrayIoBuffer(buffers);
			IoBuffer gatherBuffer = this.bufferAllocator.allocate(totalBytes);
			for (Command command : commands) {
				gatherBuffer.put(command.getIoBuffer().getByteBuffer());
				if (command != lastCommand && !command.isNoreply())
					executingCmds.add(command);
			}
			// arrayBuffer.gathering(gatherBuffer);
			gatherBuffer.flip();
			lastCommand.setIoBuffer(gatherBuffer);

		}
		return lastCommand;
	}

	private final ThreadLocal<List<Command>> threadLocal = new ThreadLocal<List<Command>>() {

		@Override
		protected List<Command> initialValue() {
			return new ArrayList<Command>(mergeFactor);
		}
	};

	public final List<Command> getLocalList() {
		List<Command> list = threadLocal.get();
		list.clear();
		return list;
	}

	@SuppressWarnings("unchecked")
	private final Command mergeGetCommands(final Command currentCmd,
			final Queue writeQueue, final BlockingQueue<Command> executingCmds) {
		List<Command> mergeCommands = null;
		int mergeCount = 1;
		final StringBuilder key = new StringBuilder();
		currentCmd.setStatus(OperationStatus.WRITING);
		key.append((String) currentCmd.getKey());
		while (mergeCount < mergeFactor) {
			Command nextCmd = (Command) writeQueue.peek();
			if (nextCmd == null) {
				break;
			}
			if (nextCmd.isCancel()) {
				writeQueue.remove();
				continue;
			}
			if (nextCmd.getCommandType() == CommandType.GET_ONE) {
				if (mergeCommands == null) { // lazy initialize
					mergeCommands = new ArrayList<Command>(mergeFactor / 2);
					mergeCommands.add(currentCmd);
				}
				if (log.isDebugEnabled())
					log.debug("Merge get command:" + nextCmd.toString());
				nextCmd.setStatus(OperationStatus.WRITING);
				mergeCommands.add((Command) writeQueue.remove());
				key.append(" ").append((String) nextCmd.getKey());
				mergeCount++;
			} else {
				break;
			}
		}
		if (mergeCount == 1) {
			return currentCmd;
		} else {
			if (log.isDebugEnabled())
				log.debug("Merge optimieze:merge " + mergeCount
						+ " get commands");
			return newMergedCommand(mergeCommands, key);
		}
	}

	private Command newMergedCommand(final List<Command> mergeCommands,
			final StringBuilder key) {
		byte[] keyBytes = ByteUtils.getBytes(key.toString());
		final IoBuffer buffer = bufferAllocator.allocate(Constants.GET.length
				+ Constants.CRLF.length + 1 + keyBytes.length);
		ByteUtils.setArguments(buffer, Constants.GET, keyBytes);
		buffer.flip();
		Command cmd = new TextGetOneCommand(key.toString(), keyBytes,
				CommandType.GET_ONE, null) {
			public List<Command> getMergeCommands() {
				return mergeCommands;
			}
		};
		cmd.setMergeCount(mergeCommands.size());
		cmd.setIoBuffer(buffer);
		return cmd;
	}
}
package com.github.leeyazhou.cio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.leeyazhou.cio.channel.ChannelHandlerChain;
import com.github.leeyazhou.cio.channel.ChannelHandlerContext;
import com.github.leeyazhou.cio.message.Message;
import com.github.leeyazhou.cio.message.MessageBuffer;
import com.github.leeyazhou.cio.message.MessageProcessor;
import com.github.leeyazhou.cio.message.MessageReader;
import com.github.leeyazhou.cio.message.MessageReaderFactory;
import com.github.leeyazhou.cio.message.MessageWriter;

public class NioChannelProcessor implements Runnable {
	private static final Logger logger = LoggerFactory.getLogger(NioChannelProcessor.class);
	private Queue<ChannelHandlerContext> inboundChannelQueue = null;

	private MessageBuffer readMessageBuffer = null;
	private MessageBuffer writeMessageBuffer = null;
	private MessageReaderFactory messageReaderFactory = null;

	private Queue<Message> outboundMessageQueue = new LinkedList<>();

	private Map<Long, ChannelHandlerContext> socketCache = new HashMap<>();

	private ByteBuffer readByteBuffer = ByteBuffer.allocate(1024 * 1024);
	private ByteBuffer writeByteBuffer = ByteBuffer.allocate(1024 * 1024);
	private Selector selector = null;

	private MessageProcessor messageProcessor = null;
	private WriteProxy writeProxy = null;

	private boolean running = true;
	private long nextSocketId = 16 * 1024;
	private ChannelHandlerChain channelChain;

	public NioChannelProcessor(MessageReaderFactory messageReaderFactory, MessageProcessor messageProcessor,
			ChannelHandlerChain handlerChain) throws IOException {
		this.inboundChannelQueue = new ArrayBlockingQueue<>(1024);

		this.readMessageBuffer = new MessageBuffer();
		this.writeMessageBuffer = new MessageBuffer();
		this.writeProxy = new WriteProxy(writeMessageBuffer, this.outboundMessageQueue);

		this.messageReaderFactory = messageReaderFactory;

		this.messageProcessor = messageProcessor;

		this.selector = Selector.open();
		this.channelChain = handlerChain;
	}

	public void run() {
		while (running) {
			try {
				executeCycle();
			} catch (IOException e) {
				logger.error("", e);
			}

		}
	}

	public void executeCycle() throws IOException {
		takeNewChannels();
		processSelectionKeys();
	}

	public void takeNewChannels() throws IOException {
		ChannelHandlerContext channelContext = null;

		while ((channelContext = this.inboundChannelQueue.poll()) != null) {
			channelContext.setChannelId(nextSocketId++);
			channelContext.getChannel().configureBlocking(false);

			MessageReader messageReader = messageReaderFactory.createMessageReader();
			messageReader.init(this.readMessageBuffer);
			channelContext.setMessageReader(messageReader);

			channelContext.setMessageWriter(new MessageWriter());

			this.socketCache.put(channelContext.getChannelId(), channelContext);

			channelContext.getChannel().register(selector, SelectionKey.OP_READ, channelContext);
			channelChain.fireChannelRegistered(channelContext);

			if (channelContext.getChannel().isActive()) {
				channelChain.fireChannelActive(channelContext);
			}
		}
	}

	public void processSelectionKeys() throws IOException {
		logger.info("start read from channel");
		int readReady = this.selector.select();
		logger.info("read ready size : {}", readReady);
		if (readReady > 0) {
			Iterator<SelectionKey> it = selector.selectedKeys().iterator();
			while (it.hasNext()) {
				SelectionKey key = it.next();
				it.remove();
				ChannelHandlerContext context = (ChannelHandlerContext) key.attachment();
				try {
					readFromSocket(key);
					writeToChannel(key);
				} catch (Exception e) {
					logger.error("", e);
				} finally {
					if (context.isEndOfStreamReached()) {
						key.cancel();
						key.channel().close();
						channelChain.fireChannelClosed(context);
					}
				}

			}
		}
	}

	private void readFromSocket(SelectionKey key) throws IOException {
		ChannelHandlerContext socket = (ChannelHandlerContext) key.attachment();
		socket.getMessageReader().read(socket, readByteBuffer);
		channelChain.fireChannelRead(socket);

		List<Message> fullMessages = socket.getMessageReader().getMessages();
		if (fullMessages.size() > 0) {
			for (Message message : fullMessages) {
				message.setChannelId(socket.getChannelId());
				messageProcessor.process(message, writeProxy);
			}
			fullMessages.clear();
		}

	}

	public void writeToChannel(SelectionKey key) throws IOException {

		// Take all new messages from outboundMessageQueue
		takeNewOutboundMessages();

		ChannelHandlerContext socket = (ChannelHandlerContext) key.attachment();
		logger.info("Socket : {}, Message Writer : {}", socket);
		socket.getMessageWriter().write(socket, this.writeByteBuffer);

	}

	private void takeNewOutboundMessages() {
		Message outMessage = null;
		while ((outMessage = outboundMessageQueue.poll()) != null) {
			ChannelHandlerContext socket = this.socketCache.get(outMessage.getChannelId());

			if (socket != null) {
				MessageWriter messageWriter = socket.getMessageWriter();
				messageWriter.enqueue(outMessage);
			}

		}
	}

	public void addChannel(ChannelHandlerContext channelContext) {
		inboundChannelQueue.add(channelContext);
		selector.wakeup();
	}

}

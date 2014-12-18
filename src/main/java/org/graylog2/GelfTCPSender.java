package org.graylog2;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class GelfTCPSender implements GelfSender {
    public static final int MAX_QUEUE = 512;
    private volatile boolean shutdown = false;
	private String host;
	private InetAddress[] hosts;
	private int hostIndex;
	private int port;
	private SocketChannel channel;
	private long lastLookupTime;
	private int errorMessages = 5;
    private final ConcurrentSkipListSet<GelfMessage> messageQueue;
    private final Lock queueLock;
    private final Condition queueCondition;
    private Thread consumerThread;

	public GelfTCPSender() {
        messageQueue = new ConcurrentSkipListSet<GelfMessage>();
        queueLock = new ReentrantLock();
        queueCondition = queueLock.newCondition();
	}

	public GelfTCPSender(String host, int port) throws IOException {
        this();
		this.host = host;
		this.port = port;
		lookup();
		connect();
        consumerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!shutdown) {
                    GelfMessage msg = null;
                    queueLock.lock();
                    try {
                        msg = messageQueue.pollFirst();
                        if (msg == null) {
                            queueCondition.await(1, TimeUnit.SECONDS);
                            msg = messageQueue.pollFirst();
                        }
                    } catch (InterruptedException e) {
                        // pass
                    } finally {
                        queueLock.unlock();
                    }
                    if (msg != null)
                        sendMessageWithRetry(msg, true);
                }
            }
        }, "GelfTCPSenderConsumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
	}

	public boolean sendMessage(GelfMessage message) {
        messageQueue.add(message);
        boolean added = true;

        // Note that this is only approximate: size() might change, and we might have
        // concurrent threads adding and removing items here. But, at worst, we may remove
        // items from the tail of the queue that we didn't need to, OR we will lie and say
        // we added an item when we in fact rejected it.
        if (messageQueue.size() >= MAX_QUEUE)
            added = messageQueue.pollLast() == message;
        if (queueLock.tryLock()) {
            try {
                queueCondition.signalAll();
            } finally {
                queueLock.unlock();
            }
        }
        return added;
	}

	private boolean sendMessageWithRetry(GelfMessage message, boolean retry) {
		if (shutdown || !message.isValid()) {
			if (errorMessages > 0) {
				System.err.printf("not sending message, shutdown:%s valid?:%s message:%s", shutdown, message.isValid(), message);
				errorMessages--;
			}
			return false;
		}

		try {
			// Look up the DNS name again.
			if (System.currentTimeMillis() - lastLookupTime > TimeUnit.MILLISECONDS.convert(60, TimeUnit.SECONDS)) {
				lookup();
			}

			// reconnect if necessary
			if (channel == null || !channel.isConnected()) {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }
				connect();
			}

			ByteBuffer messageBuffer = message.toTCPBuffer();
			int remaining = messageBuffer.remaining();
			int written = channel.write(message.toTCPBuffer());
			if (written != remaining) {
				throw new IOException(String.format("short write, expected to write %d, wrote %d", remaining, written));
			}

			return true;
		} catch (IOException e) {
			// if an error occurs, signal failure
			if (errorMessages > 0) {
				System.err.printf("failed sending message");
				e.printStackTrace();
				errorMessages--;
			}
			try {
				channel.close();
			} catch (IOException e1) {
				// ignore
			}
			channel = null;
			if (retry) {
				return sendMessageWithRetry(message, false);
			}
			return false;
		}
	}

	private void connect() throws IOException {
		this.channel = SocketChannel.open(new InetSocketAddress(hosts[hostIndex], port));
		hostIndex = (hostIndex + 1) % hosts.length;
	}

	private void lookup() throws UnknownHostException {
		this.hosts = InetAddress.getAllByName(this.host);
		this.lastLookupTime = System.currentTimeMillis();
		hostIndex = 0;
	}

	public void close() {
		shutdown = true;
		try {
			if (channel != null) {
				channel.close();
				channel = null;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

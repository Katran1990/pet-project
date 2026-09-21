package dev.katran.pet.db;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A TCP proxy that binds nothing until {@link #open()} is called, so that connections attempted
 * before {@code open()} are refused, exactly like a Postgres server that is not ready to accept
 * connections yet. Plain class with no Spring annotations, so the component scan ignores it.
 *
 * <p>Test-only helper for {@link DatabaseStartupRetryIT}.
 */
final class LateTcpProxy implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(LateTcpProxy.class);

	private final int port;
	private final String targetHost;
	private final int targetPort;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final List<Socket> openSockets = new CopyOnWriteArrayList<>();
	private volatile ServerSocket serverSocket;
	private volatile boolean closed;

	LateTcpProxy(int port, String targetHost, int targetPort) {
		this.port = port;
		this.targetHost = targetHost;
		this.targetPort = targetPort;
	}

	/** Binds the listening socket and starts accepting connections. Must be called at most once; no-op after close(). */
	void open() throws IOException {
		if (closed) {
			return;
		}
		ServerSocket server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
		this.serverSocket = server;
		executor.submit(() -> acceptLoop(server));
	}

	private void acceptLoop(ServerSocket server) {
		try {
			while (!closed) {
				Socket client = server.accept();
				openSockets.add(client);
				executor.submit(() -> relay(client));
			}
		}
		catch (IOException e) {
			if (!closed) {
				log.warn("Proxy accept loop stopped unexpectedly", e);
			}
		}
	}

	private void relay(Socket client) {
		try (client; Socket upstream = new Socket(targetHost, targetPort)) {
			openSockets.add(upstream);
			var clientToUpstream = executor.submit(() -> pipe(client, upstream));
			var upstreamToClient = executor.submit(() -> pipe(upstream, client));
			clientToUpstream.get();
			upstreamToClient.get();
		}
		catch (Exception e) {
			// Connection closed by either side; nothing to do.
		}
	}

	private void pipe(Socket from, Socket to) {
		try {
			from.getInputStream().transferTo(to.getOutputStream());
		}
		catch (IOException e) {
			// Expected when either side closes the connection.
		}
		finally {
			try {
				to.shutdownOutput();
			}
			catch (IOException ignored) {
			}
		}
	}

	/** Works whether or not {@link #open()} was ever called. */
	@Override
	public void close() {
		closed = true;
		executor.shutdownNow();
		try {
			if (serverSocket != null) {
				serverSocket.close();
			}
		}
		catch (IOException ignored) {
		}
		for (Socket socket : openSockets) {
			try {
				socket.close();
			}
			catch (IOException ignored) {
			}
		}
	}

}

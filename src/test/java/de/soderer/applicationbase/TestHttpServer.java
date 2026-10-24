package de.soderer.applicationbase;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal local http server for tests, bound to the loopback address on a free port. Created via {@link #builder()}.
 */
public class TestHttpServer implements AutoCloseable {
	/**
	 * Response of a request
	 */
	public static class Response {
		final int statusCode;
		final byte[] body;

		public Response(final int statusCode, final byte[] body) {
			this.statusCode = statusCode;
			this.body = body;
		}

		public Response(final int statusCode, final String body) {
			this(statusCode, body.getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Creates the response for a request URI
	 */
	@FunctionalInterface
	public interface Handler {
		Response handle(URI requestUri);
	}

	/**
	 * Collects the responses of the server before it is started. Not closeable itself, so fluent configuration
	 * causes no resource leak warnings; only the server returned by {@link #start()} must be closed.
	 */
	public static class Builder {
		private final Map<String, Handler> handlers = new LinkedHashMap<>();

		/**
		 * Serves a fixed response for a path.
		 */
		public Builder withResponse(final String path, final int statusCode, final String body) {
			return withHandler(path, requestUri -> new Response(statusCode, body));
		}

		/**
		 * Serves fixed binary data for a path.
		 */
		public Builder withResponse(final String path, final int statusCode, final byte[] body) {
			return withHandler(path, requestUri -> new Response(statusCode, body));
		}

		/**
		 * Serves dynamic responses for a path.
		 */
		public Builder withHandler(final String path, final Handler handler) {
			handlers.put(path, handler);
			return this;
		}

		/**
		 * Starts the server with the configured responses.
		 */
		public TestHttpServer start() throws IOException {
			return new TestHttpServer(handlers);
		}
	}

	private final HttpServer httpServer;
	private final List<URI> requestUris = Collections.synchronizedList(new ArrayList<>());

	/**
	 * Creates a builder for a new server.
	 */
	public static Builder builder() {
		return new Builder();
	}

	private TestHttpServer(final Map<String, Handler> handlers) throws IOException {
		httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		for (final Map.Entry<String, Handler> entry : handlers.entrySet()) {
			final Handler handler = entry.getValue();
			httpServer.createContext(entry.getKey(), exchange -> handle(exchange, handler));
		}
		httpServer.start();
	}

	private void handle(final HttpExchange exchange, final Handler handler) throws IOException {
		try {
			requestUris.add(exchange.getRequestURI());
			final Response response = handler.handle(exchange.getRequestURI());
			if ("HEAD".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(response.statusCode, -1);
			} else {
				exchange.sendResponseHeaders(response.statusCode, response.body.length == 0 ? -1 : response.body.length);
				if (response.body.length > 0) {
					try (OutputStream outputStream = exchange.getResponseBody()) {
						outputStream.write(response.body);
					}
				}
			}
		} finally {
			exchange.close();
		}
	}

	/**
	 * Base URL like "http://127.0.0.1:12345"
	 */
	public String getBaseUrl() {
		return "http://" + httpServer.getAddress().getAddress().getHostAddress() + ":" + httpServer.getAddress().getPort();
	}

	/**
	 * URIs of all requests received so far
	 */
	public List<URI> getRequestUris() {
		synchronized (requestUris) {
			return new ArrayList<>(requestUris);
		}
	}

	@Override
	public void close() {
		httpServer.stop(0);
	}
}

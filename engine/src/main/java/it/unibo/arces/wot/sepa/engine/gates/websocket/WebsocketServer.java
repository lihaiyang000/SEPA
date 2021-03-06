/* Websocket protocol implementation
 * 
 * Author: Luca Roffia (luca.roffia@unibo.it)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package it.unibo.arces.wot.sepa.engine.gates.websocket;

import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;

import org.apache.http.HttpStatus;
import org.apache.jena.query.QueryException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import it.unibo.arces.wot.sepa.commons.exceptions.SEPAProtocolException;
import it.unibo.arces.wot.sepa.commons.exceptions.SEPASecurityException;
import it.unibo.arces.wot.sepa.commons.response.ErrorResponse;

import it.unibo.arces.wot.sepa.engine.bean.SEPABeans;
import it.unibo.arces.wot.sepa.engine.bean.GateBeans;
import it.unibo.arces.wot.sepa.engine.dependability.Dependability;
import it.unibo.arces.wot.sepa.engine.gates.WebsocketGate;
import it.unibo.arces.wot.sepa.engine.scheduling.Scheduler;

public class WebsocketServer extends WebSocketServer implements WebsocketServerMBean {
	protected static final Logger logger = LogManager.getLogger();
	
	// Active gates
	protected final HashMap<WebSocket, WebsocketGate> gates = new HashMap<WebSocket, WebsocketGate>();
		
	// Fragmentation support
	protected final HashMap<WebSocket, String> fragmentedMessages = new HashMap<WebSocket, String>();

	protected final Scheduler scheduler;
	protected final String welcomeMessage;
	protected final String path;
	
	public WebsocketServer(int port, String path, Scheduler scheduler) throws SEPAProtocolException {
		super(new InetSocketAddress(port));

		// Connection lost timeout (0 = disabled)
		setConnectionLostTimeout( 0 );

		if (path == null || scheduler == null)
			throw new SEPAProtocolException(new IllegalArgumentException("One or more arguments are null"));

		this.scheduler = scheduler;
		this.path = path;

		SEPABeans.registerMBean("SEPA:type=" + this.getClass().getSimpleName(), this);

		String address = getAddress().getAddress().toString();

		try {
			address = Inet4Address.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			logger.error(e.getMessage());
			throw new SEPAProtocolException(e);
		}

		welcomeMessage = String.format(getWelcomeMessage(), address, port, path);
	}

	protected String getWelcomeMessage() {
		return "SPARQL 1.1 Subscribe | ws://%s:%d%s";
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		// Add new gate
		synchronized (gates) {
			WebsocketGate gate = new WebsocketGate(conn, scheduler);
			
			gates.put(conn, gate);
			
			Dependability.addGate(gate);

			fragmentedMessages.put(conn, null);

			logger.debug("@onOpen (sockets: " + gates.size()+") GID: " + gate.getGID() + " socket: "+conn);
		}
	}
	
	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		synchronized (gates) {
			logger.debug("@onClose socket: " + conn + " reason: " + reason + " remote: "+remote);

			fragmentedMessages.remove(conn);

			// Close gate
			if (gates.get(conn) != null)
				try {
					gates.get(conn).close();
				} catch (InterruptedException e) {
					logger.warn(e.getMessage());
				}

			Dependability.removeGate(gates.get(conn));
			
			// Remove from active gates
			gates.remove(conn);
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		GateBeans.onMessage();

		// Check path
		if (!conn.getResourceDescriptor().equals(path)) {
			logger.warn("@onMessage bad resource descriptor: " + conn.getResourceDescriptor() + " Use: " + path);
			
			ErrorResponse response = new ErrorResponse(HttpStatus.SC_NOT_FOUND, "wrong_path",
					"Bad resource descriptor: " + conn.getResourceDescriptor() + " Use: " + path);
			
			try{
				conn.send(response.toString());
			}
			catch(Exception e) {
				logger.warn(e.getMessage());
			}
			return;
		}

		synchronized (gates) {
			try {
				if (gates.get(conn) !=  null) gates.get(conn).onMessage(message);
				else {
					logger.error("Gate NOT FOUND: "+conn);
				}
			} catch (SEPAProtocolException | SEPASecurityException | QueryException e) {
				logger.error(e);
				
				ErrorResponse response = new ErrorResponse(HttpStatus.SC_BAD_REQUEST, "parsing failed",
						e.getMessage());
				try{
					conn.send(response.toString());
				}
				catch(Exception e1) {
					logger.warn(e1.getMessage());
				}
			}
		}
	}

	/**
	 * Example: for a text message sent as three fragments, the first fragment would
	 * have an opcode of 0x1 and a FIN bit clear, the second fragment would have an
	 * opcode of 0x0 and a FIN bit clear, and the third fragment would have an
	 * opcode of 0x0 and a FIN bit that is set.
	 */

	// NOT IMPLEMENTED IN VERSION 1.5.5
//	@Override
//	public void onFragment(WebSocket conn, Framedata fragment) {
//		logger.debug("@onFragment WebSocket: <" + conn + "> Fragment data:<" + fragment + ">");
//
//		if (!conn.getResourceDescriptor().equals(path))
//			return;
//
//		if (fragmentedMessages.get(conn) == null)
//			fragmentedMessages.put(conn, new String(fragment.getPayloadData().array(), Charset.forName("UTF-8")));
//		else
//			fragmentedMessages.put(conn, fragmentedMessages.get(conn)
//					+ new String(fragment.getPayloadData().array(), Charset.forName("UTF-8")));
//
//		logger.debug("Fragmented message: " + fragmentedMessages.get(conn));
//
//		if (fragment.isFin()) {
//			GateBeans.onFragmentedMessage();
//
//			onMessage(conn, fragmentedMessages.get(conn));
//			fragmentedMessages.put(conn, null);
//		}
//	}

	@Override
	public void onError(WebSocket conn, Exception ex) {
		logger.error("@onError: " + conn + " Exception: " + ex);

		GateBeans.onError();

		if (ex.getClass().equals(BindException.class)) {
			logger.fatal("Failed to start. Exit");
			System.exit(-1);
		}

		if (!conn.getResourceDescriptor().equals(path)) {
			logger.warn("@onError bad resource descriptor: " + conn.getResourceDescriptor() + " Use: " + path);
			return;
		}
	}

	@Override
	public void onStart() {
		System.out.println(welcomeMessage);

		synchronized (this) {
			notify();
		}
	}

	@Override
	public void reset() {
		GateBeans.reset();
	}

	@Override
	public long getMessages() {
		return GateBeans.getMessages();
	}

	@Override
	public long getFragmented() {
		return GateBeans.getFragmented();
	}

	@Override
	public long getErrors() {
		return GateBeans.getErrors();
	}

	@Override
	public long getErrorResponses() {
		return GateBeans.getErrorResponses();
	}

	@Override
	public long getSubscribeResponse() {
		return GateBeans.getSubscribeResponses();
	}

	@Override
	public long getUnsubscribeResponse() {
		return GateBeans.getUnsubscribeResponses();
	}

	@Override
	public long getNotifications() {
		return GateBeans.getNotifications();
	}
}

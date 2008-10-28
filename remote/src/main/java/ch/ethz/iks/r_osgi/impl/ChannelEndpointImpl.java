/* Copyright (c) 2006-2008 Jan S. Rellermeyer
 * Systems Group,
 * Department of Computer Science, ETH Zurich.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *    - Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 *    - Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *    - Neither the name of ETH Zurich nor the names of its contributors may be
 *      used to endorse or promote products derived from this software without
 *      specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package ch.ethz.iks.r_osgi.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.NotSerializableException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.log.LogService;

import ch.ethz.iks.r_osgi.AsyncRemoteCallCallback;
import ch.ethz.iks.r_osgi.RemoteOSGiException;
import ch.ethz.iks.r_osgi.RemoteOSGiService;
import ch.ethz.iks.r_osgi.RemoteServiceEvent;
import ch.ethz.iks.r_osgi.RemoteServiceReference;
import ch.ethz.iks.r_osgi.URI;
import ch.ethz.iks.r_osgi.channels.ChannelEndpoint;
import ch.ethz.iks.r_osgi.channels.NetworkChannel;
import ch.ethz.iks.r_osgi.channels.NetworkChannelFactory;
import ch.ethz.iks.r_osgi.messages.DeliverDependenciesMessage;
import ch.ethz.iks.r_osgi.messages.DeliverServiceMessage;
import ch.ethz.iks.r_osgi.messages.RequestDependenciesMessage;
import ch.ethz.iks.r_osgi.messages.RequestServiceMessage;
import ch.ethz.iks.r_osgi.messages.RemoteCallMessage;
import ch.ethz.iks.r_osgi.messages.LeaseMessage;
import ch.ethz.iks.r_osgi.messages.LeaseUpdateMessage;
import ch.ethz.iks.r_osgi.messages.RemoteCallResultMessage;
import ch.ethz.iks.r_osgi.messages.RemoteEventMessage;
import ch.ethz.iks.r_osgi.messages.RemoteOSGiMessage;
import ch.ethz.iks.r_osgi.messages.StreamRequestMessage;
import ch.ethz.iks.r_osgi.messages.StreamResultMessage;
import ch.ethz.iks.r_osgi.messages.TimeOffsetMessage;
import ch.ethz.iks.r_osgi.streams.InputStreamHandle;
import ch.ethz.iks.r_osgi.streams.InputStreamProxy;
import ch.ethz.iks.r_osgi.streams.OutputStreamHandle;
import ch.ethz.iks.r_osgi.streams.OutputStreamProxy;

/**
 * <p>
 * The endpoint of a network channel encapsulates most of the communication
 * logic like sending of messages, service method invocation, timestamp
 * synchronization, and event delivery.
 * </p>
 * <p>
 * Endpoints exchange symmetric leases when they are established. These leases
 * contain the statements of supply and demand. The peer states the services it
 * offers and the event topics it is interested in. Whenever one of these
 * statements undergo a change, a lease update has to be sent. Leases expire
 * with the closing of the network channel and the two endpoints.
 * </p>
 * <p>
 * The network transport of channels is modular and exchangeable. Services can
 * state the supported protocols in their service uri. R-OSGi maintains a list
 * of network channel factories and the protocols they support. Each channel
 * uses exactly one protocol.
 * <p>
 * <p>
 * When the network channel breaks down, the channel endpoint tries to reconnect
 * and to restore the connection. If this is not possible (for instance, because
 * the other endpoint is not available any more, the endpoint is unregistered.
 * </p>
 * 
 * @author Jan S. Rellermeyer, ETH Zurich
 */
public final class ChannelEndpointImpl implements ChannelEndpoint {

	int usageCounter = 1;

	/**
	 * the channel.
	 */
	NetworkChannel networkChannel;

	/**
	 * the services provided by the OSGi framework holding the remote channel
	 * endpoint. Map of service URI -> RemoteServiceReferences
	 */
	private Map remoteServices = new HashMap(0);

	/**
	 * the topics of interest of the OSGi framework holding the remote channel
	 * endpoint. List of topic strings
	 */
	private List remoteTopics = new ArrayList(0);

	/**
	 * the time offset between this peer's local time and the local time of the
	 * remote channel endpoint.
	 */
	private TimeOffset timeOffset;

	/**
	 * Timeout.
	 */
	private static final int TIMEOUT = 120000;

	/**
	 * the callback register
	 */
	final Map callbacks = new HashMap(0);

	/**
	 * map of service uri -> RemoteServiceRegistration.
	 */
	private final HashMap localServices = new HashMap(2);

	/**
	 * map of service uri -> service registration.
	 */
	private final HashMap proxiedServices = new HashMap(0);

	/**
	 * map of service uri -> proxy bundle. If the endpoint is closed, the
	 * proxies are unregistered.
	 */
	final HashMap proxyBundles = new HashMap(0);
	/**
	 * map of stream id -> stream instance.
	 */
	private final HashMap streams = new HashMap(0);

	/**
	 * next stream id.
	 */
	private short nextStreamID = 0;

	/**
	 * the handler registration, if the remote topic space is not empty.
	 */
	private ServiceRegistration handlerReg = null;

	/**
	 * filter for events to prevent loops in the remote delivery if the peers
	 * connected by this channel have non-disjoint topic spaces.
	 */
	private static final String NO_LOOPS = "(!(" //$NON-NLS-1$
			+ RemoteEventMessage.EVENT_SENDER_URI + "=*))"; //$NON-NLS-1$

	boolean hasRedundantLinks = false;

	/**
	 * create a new channel endpoint.
	 * 
	 * @param factory
	 *            the transport channel factory.
	 * @param endpointAddress
	 *            the address of the remote endpoint.
	 * @throws RemoteOSGiException
	 *             if something goes wrong in R-OSGi.
	 * @throws IOException
	 *             if something goes wrong on the network layer.
	 */
	ChannelEndpointImpl(final NetworkChannelFactory factory,
			final URI endpointAddress) throws RemoteOSGiException, IOException {
		networkChannel = factory.getConnection(this, endpointAddress);
		if (RemoteOSGiServiceImpl.DEBUG) {
			RemoteOSGiServiceImpl.log.log(LogService.LOG_DEBUG,
					"opening new channel " + getRemoteAddress()); //$NON-NLS-1$
		}
		RemoteOSGiServiceImpl.registerChannelEndpoint(this);
	}

	/**
	 * create a new channel endpoint from an incoming connection.
	 * 
	 * @param channel
	 *            the network channel of the incoming connection.
	 */
	ChannelEndpointImpl(final NetworkChannel channel) {
		networkChannel = channel;
		channel.bind(this);
		RemoteOSGiServiceImpl.registerChannelEndpoint(this);
	}

	/**
	 * process a recieved message. Called by the channel.
	 * 
	 * @param msg
	 *            the received message.
	 * @see ch.ethz.iks.r_osgi.channels.ChannelEndpoint#receivedMessage(ch.ethz.iks.r_osgi.RemoteOSGiMessage)
	 * @category ChannelEndpoint
	 */
	public void receivedMessage(final RemoteOSGiMessage msg) {
		if (msg == null) {
			dispose();
			return;
		}
		final Integer xid = new Integer(msg.getXID());
		final BlockingCallback callback;
		synchronized (callbacks) {
			callback = (BlockingCallback) callbacks.remove(xid);
		}
		if (callback != null) {
			callback.result(msg);
			return;
		} else {
			new Thread() {
				public void run() {
					final RemoteOSGiMessage reply = handleMessage(msg);
					if (reply != null) {

						try {
							networkChannel.sendMessage(reply);
						} catch (final NotSerializableException nse) {
							throw new RemoteOSGiException("Error sending " //$NON-NLS-1$
									+ reply, nse);
						} catch (final IOException e) {
							dispose();
						}
					}
				}
			}.start();
		}
	}

	/**
	 * invoke a method on the remote host. This function is used by all proxy
	 * bundles.
	 * 
	 * @param service
	 *            the service uri.
	 * @param methodSignature
	 *            the method signature.
	 * @param args
	 *            the method parameter.
	 * @throws Throwable
	 *             can throw any exception that the original method can throw,
	 *             plus RemoteOSGiException.
	 * @return the result of the remote method invokation.
	 * @see ch.ethz.iks.r_osgi.Remoting#invokeMethod(java.lang.String,
	 *      java.lang.String, java.lang.String, java.lang.Object[])
	 * @category ChannelEndpoint
	 */
	public Object invokeMethod(final String service,
			final String methodSignature, final Object[] args) throws Throwable {
		if (networkChannel == null) {
			throw new RemoteOSGiException("Channel is closed"); //$NON-NLS-1$
		}
		// check arguments for streams and replace with placeholder
		replaceStreams(args);

		final RemoteCallMessage invokeMsg = new RemoteCallMessage();
		invokeMsg.setServiceID(URI.create(service).getFragment());
		invokeMsg.setMethodSignature(methodSignature);
		invokeMsg.setArgs(args);

		try {
			// send the message and get a MethodResultMessage in return
			final RemoteCallResultMessage result = (RemoteCallResultMessage) sendAndWait(invokeMsg);
			if (result.causedException()) {
				// TODO: debug output
				result.getException().printStackTrace();
				throw result.getException();
			}
			return replaceStreamHandle(result.getResult());
		} catch (final RemoteOSGiException e) {
			throw new RemoteOSGiException("Method invocation of " //$NON-NLS-1$
					+ service + " " + methodSignature + " failed.", e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * get the attributes of a service. This function is used to simplify proxy
	 * bundle generation.
	 * 
	 * @param serviceID
	 *            the serviceID of the remote service.
	 * @return the service attributes.
	 * @category ChannelEndpoint
	 */
	public Dictionary getProperties(final String serviceID) {
		return getRemoteReference(serviceID).getProperties();
	}

	/**
	 * get the attributes for the presentation of the service. This function is
	 * used by proxies that support ServiceUI presentations.
	 * 
	 * @param serviceID
	 *            the serviceID of the remote service.
	 * @return the presentation attributes.
	 * @category ChannelEndpoint
	 */
	public Dictionary getPresentationProperties(final String serviceID) {
		final Dictionary attribs = new Hashtable();
		attribs.put(RemoteOSGiService.SERVICE_URI, serviceID);
		attribs.put(RemoteOSGiService.PRESENTATION, getRemoteReference(
				serviceID).getProperty(RemoteOSGiService.PRESENTATION));
		return attribs;
	}

	/**
	 * track the registration of a proxy service.
	 * 
	 * @param serviceID
	 *            the service ID.
	 * @param reg
	 *            the service registration.
	 * @category ChannelEndpoint
	 */
	public void trackRegistration(final String serviceID,
			final ServiceRegistration reg) {
		proxiedServices.put(serviceID, reg);
	}

	/**
	 * untrack the registration of a proxy service.
	 * 
	 * @param serviceID
	 *            the service ID.
	 * @category ChannelEndpoint
	 */
	public void untrackRegistration(final String serviceID) {
		proxiedServices.remove(serviceID);
	}

	/**
	 * get the temporal offset of a remote peer.
	 * 
	 * @return the TimeOffset.
	 * @throws RemoteOSGiException
	 *             in case of network errors.
	 */
	public TimeOffset getOffset() throws RemoteOSGiException {
		if (timeOffset == null) {
			// if unknown, perform a initial offset measurement round of 4
			// messages
			TimeOffsetMessage timeMsg = new TimeOffsetMessage();
			for (int i = 0; i < 4; i++) {
				timeMsg.timestamp();
				timeMsg = (TimeOffsetMessage) sendAndWait(timeMsg);
			}
			timeOffset = new TimeOffset(timeMsg.getTimeSeries());
		} else if (timeOffset.isExpired()) {
			// if offset has expired, start a new measurement round
			TimeOffsetMessage timeMsg = new TimeOffsetMessage();
			for (int i = 0; i < timeOffset.seriesLength(); i += 2) {
				timeMsg.timestamp();
				timeMsg = (TimeOffsetMessage) sendAndWait(timeMsg);
			}
			timeOffset.update(timeMsg.getTimeSeries());
		}
		return timeOffset;
	}

	/**
	 * dispose the channel.
	 * 
	 * @category ChannelEndpoint
	 */
	public void dispose() {
		if (networkChannel == null) {
			return;
		}

		if (RemoteOSGiServiceImpl.DEBUG) {
			RemoteOSGiServiceImpl.log.log(LogService.LOG_DEBUG,
					"DISPOSING ENDPOINT " + getRemoteAddress()); //$NON-NLS-1$
		}

		RemoteOSGiServiceImpl.unregisterChannelEndpoint(getRemoteAddress()
				.toString());
		if (handlerReg != null) {
			handlerReg.unregister();
		}

		final NetworkChannel oldchannel = networkChannel;
		networkChannel = null;

		try {
			oldchannel.close();
		} catch (final IOException ioe) {
			ioe.printStackTrace();
		}

		if (!hasRedundantLinks) {
			// inform all listeners about all services
			final RemoteServiceReference[] refs = (RemoteServiceReference[]) remoteServices
					.values().toArray(
							new RemoteServiceReference[remoteServices.size()]);
			for (int i = 0; i < refs.length; i++) {
				RemoteOSGiServiceImpl
						.notifyRemoteServiceListeners(new RemoteServiceEvent(
								RemoteServiceEvent.UNREGISTERING, refs[i]));
			}

			// uninstall the proxy bundle
			final Bundle[] proxies = (Bundle[]) proxyBundles.values().toArray(
					new Bundle[proxyBundles.size()]);

			for (int i = 0; i < proxies.length; i++) {
				try {
					if (proxies[i].getState() != Bundle.UNINSTALLED) {
						proxies[i].uninstall();
					}
				} catch (final Throwable t) {

				}
			}
		}

		remoteServices = null;
		remoteTopics = null;
		timeOffset = null;
		callbacks.clear();
		localServices.clear();
		proxiedServices.clear();
		closeStreams();
		streams.clear();
		handlerReg = null;
		synchronized (callbacks) {
			callbacks.notifyAll();
		}
	}

	public boolean isConnected() {
		return networkChannel != null;
	}

	public String toString() {
		if (networkChannel == null) {
			throw new RemoteOSGiException("Channel is closed"); //$NON-NLS-1$
		}
		return "ChannelEndpoint(" + networkChannel.toString() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * read a byte from the input stream on the peer identified by id.
	 * 
	 * @param streamID
	 *            the ID of the stream.
	 * @return result of the read operation.
	 * @throws IOException
	 *             when an IOException occurs.
	 */
	public int readStream(final short streamID) throws IOException {
		final StreamRequestMessage requestMsg = new StreamRequestMessage();
		requestMsg.setOp(StreamRequestMessage.READ);
		requestMsg.setStreamID(streamID);
		final StreamResultMessage resultMsg = doStreamOp(requestMsg);
		return resultMsg.getResult();
	}

	/**
	 * read to an array from the input stream on the peer identified by id.
	 * 
	 * @param streamID
	 *            the ID of the stream.
	 * @param b
	 *            the array to write the result to.
	 * @param off
	 *            the offset for the destination array.
	 * @param len
	 *            the number of bytes to read.
	 * @return number of bytes actually read.
	 * @throws IOException
	 *             when an IOException occurs.
	 */
	public int readStream(final short streamID, final byte[] b, final int off,
			final int len) throws IOException {
		// handle special cases as defined in InputStream
		if (b == null) {
			throw new NullPointerException();
		}
		if ((off < 0) || (len < 0) || (len + off > b.length)) {
			throw new IndexOutOfBoundsException();
		}
		if (len == 0) {
			return 0;
		}
		final StreamRequestMessage requestMsg = new StreamRequestMessage();
		requestMsg.setOp(StreamRequestMessage.READ_ARRAY);
		requestMsg.setStreamID(streamID);
		requestMsg.setLenOrVal(len);
		final StreamResultMessage resultMsg = doStreamOp(requestMsg);
		final int length = resultMsg.getLen();
		// check the length first, could be -1 indicating EOF
		if (length > 0) {
			final byte[] readdata = resultMsg.getData();
			// copy result to byte array at correct offset
			System.arraycopy(readdata, 0, b, off, length);
		}
		return length;
	}

	/**
	 * write a byte to the output stream on the peer identified by id.
	 * 
	 * @param streamID
	 *            the ID of the stream.
	 * @param b
	 *            the value.
	 * @throws IOException
	 *             when an IOException occurs.
	 */
	public void writeStream(final short streamID, final int b)
			throws IOException {
		final StreamRequestMessage requestMsg = new StreamRequestMessage();
		requestMsg.setOp(StreamRequestMessage.WRITE);
		requestMsg.setStreamID(streamID);
		requestMsg.setLenOrVal(b);
		// wait for the stream operation to finish
		doStreamOp(requestMsg);
	}

	/**
	 * write bytes from array to output stream on the peer identified by id.
	 * 
	 * @param streamID
	 *            the ID of the stream.
	 * @param b
	 *            the source array.
	 * @param off
	 *            offset into the source array.
	 * @param len
	 *            number of bytes to copy.
	 * @throws IOException
	 *             when an IOException occurs.
	 */
	public void writeStream(final short streamID, final byte[] b,
			final int off, final int len) throws IOException {
		// handle special cases as defined in OutputStream
		if (b == null) {
			throw new NullPointerException();
		}
		if ((off < 0) || (len < 0) || (len + off > b.length)) {
			throw new IndexOutOfBoundsException();
		}
		final byte[] data = new byte[len];
		System.arraycopy(b, off, data, 0, len);

		final StreamRequestMessage requestMsg = new StreamRequestMessage();
		requestMsg.setOp(StreamRequestMessage.WRITE_ARRAY);
		requestMsg.setStreamID(streamID);
		requestMsg.setData(data);
		requestMsg.setLenOrVal(len);
		// wait for the stream operation to finish
		doStreamOp(requestMsg);
	}

	/**
	 * get the channel URI.
	 * 
	 * @return the channel ID.
	 * @category ChannelEndpoint
	 */
	public URI getRemoteAddress() {
		if (networkChannel == null) {
			throw new RemoteOSGiException("Channel is closed"); //$NON-NLS-1$
		}
		return networkChannel.getRemoteAddress();
	}

	/**
	 * get hte local address.
	 * 
	 * @return
	 */
	URI getLocalAddress() {
		if (networkChannel == null) {
			throw new RemoteOSGiException("Channel is closed"); //$NON-NLS-1$
		}
		return networkChannel.getLocalAddress();
	}

	/**
	 * send a lease.
	 * 
	 * @param myServices
	 *            the services of this peer.
	 * @param myTopics
	 *            the topics of this peer.
	 * @return the remote service references of the other peer.
	 */
	RemoteServiceReference[] sendLease(
			final RemoteServiceRegistration[] myServices,
			final String[] myTopics) {
		final LeaseMessage l = new LeaseMessage();
		populateLease(l, myServices, myTopics);
		final LeaseMessage lease = (LeaseMessage) sendAndWait(l);
		return processLease(lease);
	}

	/**
	 * send a lease update.
	 * 
	 * @param msg
	 *            a lease update message.
	 */
	void sendLeaseUpdate(final LeaseUpdateMessage msg) {
		send(msg);
	}

	/**
	 * is the other side still reachable?
	 * 
	 * @param uri
	 *            the remote endpoint address.
	 * @return true if the other side is reachable.
	 */
	boolean isActive(final String uri) {
		return remoteServices.get(uri) != null;
	}

	/**
	 * fetch the service from the remote peer.
	 * 
	 * @param ref
	 *            the remote service reference.
	 * @throws IOException
	 *             in case of network errors.
	 */
	void fetchService(final RemoteServiceReference ref) throws IOException,
			RemoteOSGiException {
		if (networkChannel == null) {
			throw new RemoteOSGiException("Channel is closed."); //$NON-NLS-1$
		}

		// build the FetchServiceMessage
		final RequestServiceMessage fetchReq = new RequestServiceMessage();
		fetchReq.setServiceID(ref.getURI().getFragment());

		// send the FetchServiceMessage and get a DeliverServiceMessage in
		// return
		final RemoteOSGiMessage msg = sendAndWait(fetchReq);

		try {
			final DeliverServiceMessage deliv = (DeliverServiceMessage) msg;
			final URI service = networkChannel.getRemoteAddress().resolve(
					"#" + deliv.getServiceID()); //$NON-NLS-1$

			// generate a proxy bundle for the service
			final InputStream in = new ProxyGenerator().generateProxyBundle(
					service, deliv);

			final Bundle bundle = RemoteOSGiActivator.getActivator()
					.getContext().installBundle(service.toString(), in);

			// store the bundle for state updates and cleanup
			proxyBundles.put(service.getFragment(), bundle);

			// start the bundle
			bundle.start();

		} catch (final BundleException e) {
			final Throwable nested = e.getNestedException() == null ? e : e
					.getNestedException();
			nested.printStackTrace();
			throw new RemoteOSGiException(
					"Could not install the generated bundle " + ref.toString(), //$NON-NLS-1$
					nested);
		}
	}

	/**
	 * get the remote reference for a given serviceID.
	 * 
	 * @param serviceID
	 *            the uri.
	 * @return the remote service reference, or <code>null</code>.
	 */
	RemoteServiceReferenceImpl getRemoteReference(final String uri) {
		return (RemoteServiceReferenceImpl) remoteServices.get(uri);
	}

	/**
	 * Get the remote references.
	 * 
	 * @param filter
	 *            a filter, or <code>null</code>.
	 * @return all remote service references which match the filter.
	 */
	RemoteServiceReference[] getAllRemoteReferences(final Filter filter) {
		final List result = new ArrayList();
		final RemoteServiceReferenceImpl[] refs = (RemoteServiceReferenceImpl[]) remoteServices
				.values().toArray(
						new RemoteServiceReferenceImpl[remoteServices.size()]);
		if (filter == null) {
			return refs.length > 0 ? refs : null;
		} else {
			for (int i = 0; i < refs.length; i++) {
				if (filter.match(refs[i].getProperties())) {
					result.add(refs[i]);
				}
			}
			final RemoteServiceReference[] refs2 = (RemoteServiceReference[]) result
					.toArray(new RemoteServiceReferenceImpl[result.size()]);
			return refs2.length > 0 ? refs2 : null;
		}
	}

	/**
	 * release the remote service. This leads to an uninstallation of the proxy
	 * bundle.
	 * 
	 * @param uri
	 *            the uri of the service.
	 */
	void ungetRemoteService(final URI uri) {
		try {
			((Bundle) proxyBundles.remove(uri.getFragment())).uninstall();
		} catch (final BundleException be) {

		}
	}

	/**
	 * send a message.
	 * 
	 * @param msg
	 *            a message.
	 */
	void send(final RemoteOSGiMessage msg) {
		if (networkChannel == null) {
			throw new RemoteOSGiException("Channel is closed"); //$NON-NLS-1$
		}

		if (msg.getXID() == 0) {
			msg.setXID(RemoteOSGiServiceImpl.nextXid());
		}

		try {
			try {
				networkChannel.sendMessage(msg);
				return;
			} catch (final IOException ioe) {
				// TimeOffsetMessages have to be handled differently
				// must send a new message with a new timestamp and XID
				// instead
				// of sending the same message again
				if (msg instanceof TimeOffsetMessage) {
					((TimeOffsetMessage) msg).restamp(RemoteOSGiServiceImpl
							.nextXid());
					networkChannel.sendMessage(msg);
				} else {
					networkChannel.sendMessage(msg);
				}
			}
		} catch (final NotSerializableException nse) {
			throw new RemoteOSGiException("Error sending " + msg, nse); //$NON-NLS-1$
		} catch (final IOException ioe) {
			// failed to reconnect...
			dispose();
			throw new RemoteOSGiException("Network error", ioe); //$NON-NLS-1$
		}
	}

	/**
	 * message handler method.
	 * 
	 * @param msg
	 *            the incoming message.
	 * @return if reply is created, null otherwise.
	 * @throws RemoteOSGiException
	 *             if something goes wrong.
	 */
	RemoteOSGiMessage handleMessage(final RemoteOSGiMessage msg)
			throws RemoteOSGiException {

		switch (msg.getFuncID()) {
		// requests
		case RemoteOSGiMessage.LEASE: {
			final LeaseMessage lease = (LeaseMessage) msg;
			processLease(lease);

			populateLease(lease, RemoteOSGiServiceImpl.getServices(),
					RemoteOSGiServiceImpl.getTopics());
			return lease;
		}
		case RemoteOSGiMessage.REQUEST_SERVICE: {
			final RequestServiceMessage fetchReq = (RequestServiceMessage) msg;
			final String serviceID = fetchReq.getServiceID();

			final RemoteServiceRegistration reg = getServiceRegistration(serviceID);

			final DeliverServiceMessage m = reg.getDeliverServiceMessage();
			m.setXID(fetchReq.getXID());
			m.setServiceID(fetchReq.getServiceID());
			return m;
		}
		case RemoteOSGiMessage.LEASE_UPDATE: {
			final LeaseUpdateMessage suMsg = (LeaseUpdateMessage) msg;

			final String serviceID = suMsg.getServiceID();
			final short stateUpdate = suMsg.getType();

			switch (stateUpdate) {
			case LeaseUpdateMessage.TOPIC_UPDATE: {
				updateTopics((String[]) suMsg.getPayload()[0], (String[]) suMsg
						.getPayload()[1]);
				return null;
			}
			case LeaseUpdateMessage.SERVICE_ADDED: {
				final RemoteServiceReferenceImpl ref = new RemoteServiceReferenceImpl(
						(String[]) suMsg.getPayload()[0], serviceID,
						(Dictionary) suMsg.getPayload()[1], this);

				remoteServices.put(getRemoteAddress().resolve("#" + serviceID) //$NON-NLS-1$
						.toString(), ref);

				RemoteOSGiServiceImpl
						.notifyRemoteServiceListeners(new RemoteServiceEvent(
								RemoteServiceEvent.REGISTERED, ref));

				return null;
			}
			case LeaseUpdateMessage.SERVICE_MODIFIED: {
				final Dictionary newProps = (Dictionary) suMsg.getPayload()[1];
				final ServiceRegistration reg = (ServiceRegistration) proxiedServices
						.get(serviceID);
				if (reg != null) {
					reg.setProperties(newProps);
				}

				final RemoteServiceReferenceImpl ref = getRemoteReference(getRemoteAddress()
						.resolve("#" + serviceID).toString()); //$NON-NLS-1$
				ref.setProperties(newProps);
				RemoteOSGiServiceImpl
						.notifyRemoteServiceListeners(new RemoteServiceEvent(
								RemoteServiceEvent.MODIFIED, ref));
				return null;
			}
			case LeaseUpdateMessage.SERVICE_REMOVED: {
				if (networkChannel == null) {
					return null;
				}
				final RemoteServiceReference ref = (RemoteServiceReference) remoteServices
						.remove(getRemoteAddress().resolve("#" + serviceID) //$NON-NLS-1$
								.toString());
				if (ref != null) {
					RemoteOSGiServiceImpl
							.notifyRemoteServiceListeners(new RemoteServiceEvent(
									RemoteServiceEvent.UNREGISTERING, ref));
				}

				final Bundle bundle = (Bundle) proxyBundles.remove(serviceID);
				if (bundle != null) {
					try {
						bundle.uninstall();
					} catch (final BundleException be) {
						be.printStackTrace();
					}
					proxiedServices.remove(serviceID);
					remoteServices.remove(getRemoteAddress().resolve(
							"#" + serviceID).toString()); //$NON-NLS-1$
				}
				return null;
			}
			}
			return null;
		}
		case RemoteOSGiMessage.REMOTE_CALL: {
			final RemoteCallMessage invMsg = (RemoteCallMessage) msg;
			try {
				RemoteServiceRegistration serv = (RemoteServiceRegistration) localServices
						.get(invMsg.getServiceID());
				if (serv == null) {
					final RemoteServiceRegistration reg = getServiceRegistration(invMsg
							.getServiceID());
					if (reg == null) {
						throw new IllegalStateException(toString()
								+ "Could not get " + invMsg.getServiceID() //$NON-NLS-1$
								+ ", known services " + localServices); //$NON-NLS-1$
					} else {
						serv = reg;
					}
				}

				// get the invocation arguments and the local method
				final Object[] arguments = invMsg.getArgs();
				replaceStreamHandles(arguments);

				final Method method = serv.getMethod(invMsg
						.getMethodSignature());

				// invoke method
				try {
					Object result = method.invoke(serv.getServiceObject(),
							arguments);
					final RemoteCallResultMessage m = new RemoteCallResultMessage();
					m.setXID(invMsg.getXID());
					m.setResult(replaceStream(result));
					return m;
				} catch (final InvocationTargetException t) {
					t.printStackTrace();
					throw t.getTargetException();
				}
			} catch (final Throwable t) {
				// TODO: send to log
				t.printStackTrace();
				final RemoteCallResultMessage m = new RemoteCallResultMessage();
				m.setXID(invMsg.getXID());
				m.setException(t);
				return m;
			}
		}
		case RemoteOSGiMessage.REMOTE_EVENT: {
			final RemoteEventMessage eventMsg = (RemoteEventMessage) msg;
			final Dictionary properties = eventMsg.getProperties();

			// transform the event timestamps
			final Long remoteTs;
			if ((remoteTs = (Long) properties.get(EventConstants.TIMESTAMP)) != null) {
				properties.put(EventConstants.TIMESTAMP, getOffset().transform(
						remoteTs));
			}

			final Event event = new Event(eventMsg.getTopic(), properties);

			// and deliver the event to the local framework
			if (RemoteOSGiServiceImpl.eventAdminTracker.getTrackingCount() > 0) {
				((EventAdmin) RemoteOSGiServiceImpl.eventAdminTracker
						.getService()).postEvent(event);
			} else {
				// TODO: to log
				System.err.println("Could not deliver received event: " + event //$NON-NLS-1$
						+ ". No EventAdmin available."); //$NON-NLS-1$
			}
			return null;
		}
		case RemoteOSGiMessage.TIME_OFFSET: {
			// add timestamp to the message and return the message to sender
			((TimeOffsetMessage) msg).timestamp();
			return msg;
		}
		case RemoteOSGiMessage.STREAM_REQUEST: {
			final StreamRequestMessage reqMsg = (StreamRequestMessage) msg;
			try {
				// fetch stream object
				final Object stream = streams.get(new Integer(reqMsg
						.getStreamID()));
				if (stream == null) {
					throw new IllegalStateException(
							"Could not get stream with ID " //$NON-NLS-1$
									+ reqMsg.getStreamID());
				}
				// invoke operation on stream
				switch (reqMsg.getOp()) {
				case StreamRequestMessage.READ: {
					final int result = ((InputStream) stream).read();
					final StreamResultMessage m = new StreamResultMessage();
					m.setXID(reqMsg.getXID());
					m.setResult((short) result);
					return m;
				}
				case StreamRequestMessage.READ_ARRAY: {
					final byte[] b = new byte[reqMsg.getLenOrVal()];
					final int len = ((InputStream) stream).read(b, 0, reqMsg
							.getLenOrVal());
					final StreamResultMessage m = new StreamResultMessage();
					m.setXID(reqMsg.getXID());
					m.setResult(StreamResultMessage.RESULT_ARRAY);
					m.setLen(len);
					if (len > 0) {
						m.setData(b);
					}
					return m;
				}
				case StreamRequestMessage.WRITE: {
					((OutputStream) stream).write(reqMsg.getLenOrVal());
					final StreamResultMessage m = new StreamResultMessage();
					m.setXID(reqMsg.getXID());
					m.setResult(StreamResultMessage.RESULT_WRITE_OK);
					return m;
				}
				case StreamRequestMessage.WRITE_ARRAY: {
					((OutputStream) stream).write(reqMsg.getData());
					final StreamResultMessage m = new StreamResultMessage();
					m.setXID(reqMsg.getXID());
					m.setResult(StreamResultMessage.RESULT_WRITE_OK);
					return m;
				}
				default:
					throw new RemoteOSGiException(
							"Unimplemented op code for stream request " + msg); //$NON-NLS-1$
				}
			} catch (final IOException e) {
				final StreamResultMessage m = new StreamResultMessage();
				m.setXID(reqMsg.getXID());
				m.setResult(StreamResultMessage.RESULT_EXCEPTION);
				m.setException(e);
				return m;
			}
		}
		case RemoteOSGiMessage.REQUEST_DEPENDENCIES:
			final RequestDependenciesMessage reqDeps = (RequestDependenciesMessage) msg;

			try {
				final byte[][] bundleBytes = RemoteOSGiServiceImpl
						.getBundlesForPackages(reqDeps.getPackages());
				final DeliverDependenciesMessage delDeps = new DeliverDependenciesMessage();
				delDeps.setXID(reqDeps.getXID());
				delDeps.setDependencies(bundleBytes);
				return delDeps;
			} catch (IOException ioe) {
				
			}

			return null;
		case RemoteOSGiMessage.DELIVER_DEPENDENCIES:

			return null;
		default:
			throw new RemoteOSGiException("Unimplemented message " + msg); //$NON-NLS-1$
		}
	}

	/**
	 * send a message and wait for the result.
	 * 
	 * @param msg
	 *            the message.
	 * @return the result message.
	 */
	private RemoteOSGiMessage sendAndWait(final RemoteOSGiMessage msg) {
		if (msg.getXID() == 0) {
			msg.setXID(RemoteOSGiServiceImpl.nextXid());
		}
		final Integer xid = new Integer(msg.getXID());
		final BlockingCallback blocking = new BlockingCallback();

		synchronized (callbacks) {
			callbacks.put(xid, blocking);
		}

		send(msg);

		// wait for the reply
		synchronized (blocking) {
			final long timeout = System.currentTimeMillis() + TIMEOUT;
			try {
				while (networkChannel != null
						&& System.currentTimeMillis() < timeout) {
					blocking.wait(TIMEOUT);
					final RemoteOSGiMessage result = blocking.getResult();
					if (result != null) {
						return result;
					}
				}
			} catch (InterruptedException ie) {
				throw new RemoteOSGiException(
						"Interrupted while waiting for callback", ie); //$NON-NLS-1$
			}
			if (networkChannel == null) {
				throw new RemoteOSGiException("Channel is closed"); //$NON-NLS-1$
			} else {
				throw new RemoteOSGiException(
						"Method Invocation failed, timeout exceeded."); //$NON-NLS-1$
			}
		}

	}

	/**
	 * get the remote service registration for a given service ID.
	 * 
	 * @param serviceID
	 *            the serviceID.
	 * @return the remote service registration, or <code>null</code>.
	 */
	private RemoteServiceRegistration getServiceRegistration(
			final String serviceID) {
		final RemoteServiceRegistration reg = RemoteOSGiServiceImpl
				.getServiceRegistration(serviceID);

		localServices.put(serviceID, reg);
		return reg;
	}

	/**
	 * populate a lease message with values.
	 * 
	 * @param lease
	 *            the lease message.
	 * @param regs
	 *            the registrations.
	 * @param topics
	 *            the topics.
	 */
	private void populateLease(final LeaseMessage lease,
			final RemoteServiceRegistration[] regs, final String[] topics) {
		final String[] serviceIDs = new String[regs.length];
		final String[][] serviceInterfaces = new String[regs.length][];
		final Dictionary[] serviceProperties = new Dictionary[regs.length];

		for (short i = 0; i < regs.length; i++) {
			serviceIDs[i] = String.valueOf(regs[i].getServiceID());
			serviceInterfaces[i] = regs[i].getInterfaceNames();
			serviceProperties[i] = regs[i].getProperties();
		}
		lease.setServiceIDs(serviceIDs);
		lease.setServiceInterfaces(serviceInterfaces);
		lease.setServiceProperties(serviceProperties);
		lease.setTopics(topics);
	}

	/**
	 * process a lease message.
	 * 
	 * @param lease
	 *            the lease message.
	 * @return the remote references.
	 */
	private RemoteServiceReference[] processLease(final LeaseMessage lease) {
		final String[] serviceIDs = lease.getServiceIDs();
		final String[][] serviceInterfaces = lease.getServiceInterfaces();
		final Dictionary[] serviceProperties = lease.getServiceProperties();

		final RemoteServiceReferenceImpl[] refs = new RemoteServiceReferenceImpl[serviceIDs.length];
		for (short i = 0; i < serviceIDs.length; i++) {
			refs[i] = new RemoteServiceReferenceImpl(serviceInterfaces[i],
					serviceIDs[i], serviceProperties[i], this);

			remoteServices.put(refs[i].getURI().toString(), refs[i]);
			RemoteOSGiServiceImpl
					.notifyRemoteServiceListeners(new RemoteServiceEvent(
							RemoteServiceEvent.REGISTERED, refs[i]));
		}
		updateTopics(lease.getTopics(), new String[0]);
		return refs;
	}

	/**
	 * perform a stream operation.
	 * 
	 * @param requestMsg
	 *            the request message.
	 * @return the result message.
	 * @throws IOException
	 */
	private StreamResultMessage doStreamOp(final StreamRequestMessage requestMsg)
			throws IOException {
		try {
			// send the message and get a StreamResultMessage in return
			final StreamResultMessage result = (StreamResultMessage) sendAndWait(requestMsg);
			if (result.causedException()) {
				throw result.getException();
			}
			return result;
		} catch (final RemoteOSGiException e) {
			throw new RemoteOSGiException("Invocation of operation " //$NON-NLS-1$
					+ requestMsg.getOp() + " on stream " //$NON-NLS-1$
					+ requestMsg.getStreamID() + " failed.", e); //$NON-NLS-1$
		}
	}

	/**
	 * update the statements of supply and demand.
	 * 
	 * @param topicsAdded
	 *            the topics added.
	 * @param topicsRemoved
	 *            the topics removed.
	 */
	private void updateTopics(final String[] topicsAdded,
			final String[] topicsRemoved) {

		if (handlerReg == null) {
			if (topicsAdded.length > 0) {
				// register handler
				final Dictionary properties = new Hashtable();
				properties.put(EventConstants.EVENT_TOPIC, topicsAdded);
				properties.put(EventConstants.EVENT_FILTER, NO_LOOPS);
				properties.put(RemoteOSGiServiceImpl.R_OSGi_INTERNAL,
						Boolean.TRUE);
				handlerReg = RemoteOSGiActivator.getActivator().getContext()
						.registerService(EventHandler.class.getName(),
								new EventForwarder(), properties);
				remoteTopics.addAll(Arrays.asList(topicsAdded));
			}
		} else {
			if (topicsRemoved != null) {
				remoteTopics.removeAll(Arrays.asList(topicsRemoved));
			}
			if (topicsAdded != null) {
				remoteTopics.addAll(Arrays.asList(topicsAdded));
			}

			if (remoteTopics.size() == 0) {
				// unregister handler
				handlerReg.unregister();
				handlerReg = null;
			} else {
				// update topics
				final Dictionary properties = new Hashtable();
				properties.put(EventConstants.EVENT_TOPIC, remoteTopics
						.toArray(new String[remoteTopics.size()]));
				properties.put(EventConstants.EVENT_FILTER, NO_LOOPS);
				properties.put(RemoteOSGiServiceImpl.R_OSGi_INTERNAL,
						Boolean.TRUE);
				handlerReg.setProperties(properties);
			}
		}

		if (RemoteOSGiServiceImpl.MSG_DEBUG) {
			RemoteOSGiServiceImpl.log.log(LogService.LOG_DEBUG,
					"NEW REMOTE TOPIC SPACE for " + getRemoteAddress() + " is " //$NON-NLS-1$ //$NON-NLS-2$
							+ remoteTopics);
		}
	}

	/**
	 * creates a placeholder for an InputStream that can be sent to the other
	 * party and will be converted to an InputStream proxy there.
	 * 
	 * @param origIS
	 *            the instance of InputStream that needs to be remoted
	 * @return the placeholder object that is sent to the actual client
	 */
	private InputStreamHandle getInputStreamPlaceholder(final InputStream origIS) {
		final InputStreamHandle sp = new InputStreamHandle(nextStreamID());
		streams.put(new Integer(sp.getStreamID()), origIS);
		return sp;
	}

	/**
	 * creates a proxy for the input stream that corresponds to the placeholder.
	 * 
	 * @param placeholder
	 *            the placeholder for the remote input stream
	 * @return the proxy for the input stream
	 */
	private InputStream getInputStreamProxy(final InputStreamHandle placeholder) {
		return new InputStreamProxy(placeholder.getStreamID(), this);
	}

	/**
	 * creates a placeholder for an OutputStream that can be sent to the other
	 * party and will be converted to an OutputStream proxy there.
	 * 
	 * @param origOS
	 *            the instance of OutputStream that needs to be remoted
	 * @return the placeholder object that is sent to the actual client
	 */
	private OutputStreamHandle getOutputStreamPlaceholder(
			final OutputStream origOS) {
		final OutputStreamHandle sp = new OutputStreamHandle(nextStreamID());
		streams.put(new Integer(sp.getStreamID()), origOS);
		return sp;
	}

	/**
	 * creates a proxy for the output stream that corresponds to the
	 * placeholder.
	 * 
	 * @param placeholder
	 *            the placeholder for the remote output stream
	 * @return the proxy for the output stream
	 */
	private OutputStream getOutputStreamProxy(
			final OutputStreamHandle placeholder) {
		return new OutputStreamProxy(placeholder.getStreamID(), this);
	}

	// TODO: to smart serialization
	private final void replaceStreams(final Object[] args) {
		for (int i = 0; i < args.length; i++) {
			if (args[i] instanceof InputStream) {
				args[i] = getInputStreamPlaceholder((InputStream) args[i]);
			} else if (args[i] instanceof OutputStream) {
				args[i] = getOutputStreamPlaceholder((OutputStream) args[i]);
			}
		}
	}

	// TODO: to smart serialization
	private final void replaceStreamHandles(final Object[] args) {
		// check args for stream placeholders and replace with proxies
		for (int i = 0; i < args.length; i++) {
			if (args[i] instanceof InputStreamHandle) {
				args[i] = getInputStreamProxy((InputStreamHandle) args[i]);
			} else if (args[i] instanceof OutputStreamHandle) {
				args[i] = getOutputStreamProxy((OutputStreamHandle) args[i]);
			}
		}
	}

	// TODO: to smart serialization
	final Object replaceStreamHandle(final Object obj) {
		if (obj instanceof InputStreamHandle) {
			return getInputStreamProxy((InputStreamHandle) obj);
		}
		if (obj instanceof OutputStreamHandle) {
			return getOutputStreamProxy((OutputStreamHandle) obj);
		}
		return obj;
	}

	// TODO: to smart serialization
	private final Object replaceStream(final Object obj) {
		if (obj instanceof InputStream) {
			return getInputStreamPlaceholder((InputStream) obj);
		} else if (obj instanceof OutputStream) {
			return getOutputStreamPlaceholder((OutputStream) obj);
		}
		return obj;
	}

	/**
	 * get the next stream wrapper id.
	 * 
	 * @return the next stream wrapper id.
	 */
	private synchronized short nextStreamID() {
		if (nextStreamID == -1) {
			nextStreamID = 0;
		}
		return (++nextStreamID);
	}

	/**
	 * closes all streams that are still open.
	 */
	private void closeStreams() {
		final Object[] s = streams.values().toArray();
		try {
			for (int i = 0; i < s.length; i++) {
				if (s[i] instanceof InputStream) {
					((InputStream) s[i]).close();
				} else if (s[i] instanceof OutputStream) {
					((OutputStream) s[i]).close();
				} else {
					RemoteOSGiServiceImpl.log
							.log(LogService.LOG_WARNING,
									"Object in input streams map was not an instance of a stream."); //$NON-NLS-1$
				}
			}
		} catch (final IOException e) {
		}
	}

	/**
	 * forwards events over the channel to the remote peer.
	 * 
	 * @author Jan S. Rellermeyer, ETH Zurich
	 * @category EventHandler
	 */
	final class EventForwarder implements EventHandler {

		/**
		 * handle an event.
		 * 
		 * @param event
		 *            the event.
		 */
		public void handleEvent(final Event event) {
			try {
				final RemoteEventMessage msg = new RemoteEventMessage();
				msg.setTopic(event.getTopic());
				final String[] propertyNames = event.getPropertyNames();
				final Dictionary props = new Hashtable();
				for (int i = 0; i < propertyNames.length; i++) {
					props.put(propertyNames[i], event
							.getProperty(propertyNames[i]));
				}
				props.put(RemoteEventMessage.EVENT_SENDER_URI, networkChannel
						.getLocalAddress());
				msg.setProperties(props);
				send(msg);

				if (RemoteOSGiServiceImpl.MSG_DEBUG) {
					RemoteOSGiServiceImpl.log.log(LogService.LOG_DEBUG,
							"Forwarding Event " + event); //$NON-NLS-1$
				}
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}
	}

	void asyncRemoteCall(final String fragment, final String methodSignature,
			final Object[] args, final AsyncRemoteCallCallback callback) {
		if (networkChannel == null) {
			throw new RemoteOSGiException("Channel is closed"); //$NON-NLS-1$
		}
		// check arguments for streams and replace with placeholder
		replaceStreams(args);

		final Integer xid = new Integer(RemoteOSGiServiceImpl.nextXid());

		synchronized (callbacks) {
			callbacks.put(xid, new AsyncCallback() {
				public void result(RemoteOSGiMessage msg) {
					final RemoteCallResultMessage result = (RemoteCallResultMessage) msg;
					if (result.causedException()) {
						callback.remoteCallResult(false, result.getException());
					}
					callback.remoteCallResult(true, replaceStreamHandle(result
							.getResult()));
				}
			});
		}

		final RemoteCallMessage invokeMsg = new RemoteCallMessage();
		invokeMsg.setServiceID(fragment);
		invokeMsg.setMethodSignature(methodSignature);
		invokeMsg.setArgs(args);
		invokeMsg.setXID(xid.shortValue());

		try {
			send(invokeMsg);
		} catch (final RemoteOSGiException e) {
			callbacks.remove(xid);
			callback
					.remoteCallResult(
							false,
							new RemoteOSGiException(
									"Method invocation of " //$NON-NLS-1$
											+ getRemoteAddress()
											+ "#" + fragment + " " + methodSignature + " failed.", e)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

	}

	class BlockingCallback implements AsyncCallback {

		private RemoteOSGiMessage result;

		public synchronized void result(RemoteOSGiMessage msg) {
			result = msg;
			this.notifyAll();
		}

		synchronized RemoteOSGiMessage getResult() {
			return result;
		}

	}

}

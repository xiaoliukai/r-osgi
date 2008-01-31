/* Copyright (c) 2006-2008 Jan S. Rellermeyer
 * Information and Communication Systems Research Group (IKS),
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

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.log.LogService;

import ch.ethz.iks.r_osgi.URI;
import ch.ethz.iks.r_osgi.RemoteOSGiException;
import ch.ethz.iks.r_osgi.messages.RemoteOSGiMessage;
import ch.ethz.iks.r_osgi.types.Timestamp;
import ch.ethz.iks.r_osgi.channels.ChannelEndpoint;
import ch.ethz.iks.r_osgi.channels.ChannelEndpointManager;

/**
 * 
 * @author Jan S. Rellermeyer, ETH Zurich
 */
class ChannelEndpointMultiplexer implements ChannelEndpoint,
		ChannelEndpointManager {

	/**
	 * 
	 */
	private ChannelEndpointImpl primary;

	/**
	 * 
	 */
	private HashMap policies = new HashMap(0);

	private ServiceRegistration reg;

	private Map mappings = new HashMap();

	ChannelEndpointMultiplexer(final ChannelEndpointImpl primary) {
		this.primary = primary;
	}

	public void dispose() {

	}

	public Dictionary getPresentationProperties(String serviceURL) {
		return primary.getPresentationProperties(serviceURL);
	}

	public Dictionary getProperties(String serviceURL) {
		return primary.getProperties(serviceURL);
	}

	public URI getRemoteAddress() {
		return primary.getRemoteAddress();
	}

	public Object invokeMethod(String serviceURI, String methodSignature,
			Object[] args) throws Throwable {
		final Mapping mapping = (Mapping) mappings.get(serviceURI);
		if (mapping == null) {
			return primary.invokeMethod(serviceURI, methodSignature, args);
		} else {
			final Integer p = (Integer) policies.get(serviceURI);
			if (p == null) {
				return primary.invokeMethod(mapping.getMapped(primary),
						methodSignature, args);
			} else {
				final int policy = p.intValue();
				if (policy == LOADBALANCING_ANY_POLICY) {
					final ChannelEndpoint endpoint = mapping.getAny();
					try {
						return endpoint.invokeMethod(mapping
								.getMapped(endpoint), methodSignature, args);
					} catch (RemoteOSGiException e) {
						final ChannelEndpointImpl next = mapping.getNext();
						if (next != null) {
							primary.untrackRegistration(serviceURI);
							primary = next;
							primary.trackRegistration(serviceURI, reg);
							if (RemoteOSGiServiceImpl.DEBUG) {
								RemoteOSGiServiceImpl.log.log(
										LogService.LOG_INFO,
										"DOING FAILOVER TO "
												+ primary.getRemoteAddress());
							}
							return primary.invokeMethod(mapping
									.getMapped(primary), methodSignature, args);
						}
						dispose();
						throw e;
					}
				} else {
					try {
						if (!primary.isConnected()) {
							throw new RemoteOSGiException("channel went down");
						}
						return primary.invokeMethod(mapping.getMapped(primary),
								methodSignature, args);
					} catch (RemoteOSGiException e) {
						if (policy == FAILOVER_REDUNDANCY_POLICY) {
							// do the failover
							final ChannelEndpointImpl next = mapping.getNext();
							if (next != null) {
								primary.untrackRegistration(serviceURI);
								primary = next;
								primary.trackRegistration(serviceURI, reg);
								if (RemoteOSGiServiceImpl.DEBUG) {
									RemoteOSGiServiceImpl.log
											.log(
													LogService.LOG_INFO,
													"DOING FAILOVER TO "
															+ primary
																	.getRemoteAddress());
								}
								return primary.invokeMethod(mapping
										.getMapped(primary), methodSignature,
										args);
							}
						}
						dispose();
						throw e;
					}
				}
			}
		}
	}

	public void receivedMessage(RemoteOSGiMessage msg) {
		throw new IllegalArgumentException(
				"Not supported through endpoint multiplexer");
	}

	public void trackRegistration(final String service,
			final ServiceRegistration reg) {
		this.reg = reg;
		primary.trackRegistration(service, reg);
	}

	public void untrackRegistration(final String service) {
		primary.untrackRegistration(service);
	}

	public boolean isConnected() {
		return true;
	}

	private class Mapping {

		private String serviceURI;
		private Random random = new Random(System.currentTimeMillis());
		private List redundant = new ArrayList(0);
		private Map uriMapping = new HashMap(0);

		private Mapping(final String serviceURI) {
			this.serviceURI = serviceURI;
			uriMapping.put(primary, serviceURI);
		}

		private ChannelEndpoint getOne() {
			return primary;
		}

		private void addRedundant(final String redundantServiceURI,
				final ChannelEndpoint endpoint) {
			redundant.add(endpoint);
			uriMapping.put(endpoint, redundantServiceURI);
		}

		private void removeRedundant(final ChannelEndpoint endpoint) {
			redundant.remove(endpoint);
			uriMapping.remove(endpoint);
		}

		private String getMapped(final ChannelEndpoint endpoint) {
			return (String) uriMapping.get(endpoint);
		}

		private ChannelEndpointImpl getNext() {
			return (ChannelEndpointImpl) redundant.remove(0);
		}

		private boolean isEmpty() {
			return redundant.size() == 0;
		}

		private ChannelEndpoint getAny() {
			int ran = random.nextInt(redundant.size() + 1);
			if (ran == 0) {
				return primary;
			} else {
				return (ChannelEndpoint) redundant.get(ran - 1);
			}
		}

	}

	public void addRedundantEndpoint(URI service, URI redundantService) {
		final ChannelEndpoint redundantEndpoint = RemoteOSGiServiceImpl
				.getChannel(redundantService);
		primary.hasRedundantLinks = true;
		Mapping mapping = (Mapping) mappings.get(service);
		if (mapping == null) {
			mapping = new Mapping(service.toString());
			mappings.put(service.toString(), mapping);
		}
		mapping.addRedundant(redundantService.toString(), redundantEndpoint);
	}

	public URI getLocalAddress() {
		return primary.getLocalAddress();
	}

	public void removeRedundantEndpoint(URI service, URI redundantService) {
		final ChannelEndpoint redundantEndpoint = RemoteOSGiServiceImpl
				.getChannel(redundantService);
		final Mapping mapping = (Mapping) mappings.get(service.toString());
		mapping.removeRedundant(redundantEndpoint);
		if (mapping.isEmpty()) {
			mappings.remove(service);
			primary.hasRedundantLinks = false;
		}
	}

	public void setEndpointPolicy(URI service, int policy) {
		policies.put(service.toString(), new Integer(policy));
	}

	/**
	 * transform a timestamp into the peer's local time.
	 * 
	 * @param sender
	 *            the sender serviceURL.
	 * @param timestamp
	 *            the Timestamp.
	 * @return the transformed timestamp.
	 * @throws RemoteOSGiException
	 *             if the transformation fails.
	 * @since 0.2
	 */
	public Timestamp transformTimestamp(final Timestamp timestamp)
			throws RemoteOSGiException {
		return primary.getOffset().transform(timestamp);
	}

}

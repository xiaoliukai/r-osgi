package ch.ethz.iks.r_osgi.impl;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.util.Arrays;

import ch.ethz.iks.util.SmartSerializer;

/**
 * 
 * @author Jan S. Rellermeyer, ETH Zurich
 */
class LeaseUpdateMessage extends RemoteOSGiMessageImpl {

	static final short TOPIC_UPDATE = 0;

	static final short SERVICE_ADDED = 1;

	static final short SERVICE_MODIFIED = 2;

	static final short SERVICE_REMOVED = 3;

	/**
	 * 
	 */
	private short type;

	/**
	 * 
	 */
	private Object content1;

	/**
	 * 
	 */
	private Object content2;

	/**
	 * 
	 */
	private String serviceID;

	/**
	 * creates a new LeaseUpdateMessage for service updates.
	 * 
	 */
	LeaseUpdateMessage(final short type, final RemoteServiceRegistration reg) {
		funcID = LEASE_UPDATE;
		if (reg == null) {
			throw new IllegalArgumentException("REG IS NULL");
		}
		this.type = type;
		this.serviceID = String.valueOf(reg.getServiceID());
		this.content1 = reg.getInterfaceNames();
		this.content2 = reg.getProperties();
	}

	/**
	 * creates a new LeaseUpdateMessage for topic updates.
	 * 
	 * @param addedTopics
	 * @param removedTopics
	 */
	LeaseUpdateMessage(final String[] addedTopics, final String[] removedTopics) {
		funcID = LEASE_UPDATE;
		this.type = TOPIC_UPDATE;
		this.serviceID = "";
		this.content1 = addedTopics == null ? new String[0] : addedTopics;
		this.content2 = removedTopics == null ? new String[0] : removedTopics;
	}

	/**
	 * creates a new LeaseUpdateMessage from a network packet:
	 * 
	 * <pre>
	 *     0                   1                   2                   3
	 *     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
	 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *    |       R-OSGi header (function = InvokeMsg = 3)                |
	 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *    |   update type  |  length of &lt;url&gt;   |  &lt;url&gt; String  \
	 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *    |   service information or url or topic array                      \
	 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 * </pre>.
	 * 
	 * @param input
	 *            an <code>ObjectInputStream</code> that provides the body of
	 *            a R-OSGi network packet.
	 * @throws IOException
	 *             in case of IO failures.
	 */
	LeaseUpdateMessage(final ObjectInputStream input) throws IOException {
		funcID = LEASE_UPDATE;
		type = input.readShort();
		serviceID = input.readUTF();
		content1 = SmartSerializer.deserialize(input);
		content2 = SmartSerializer.deserialize(input);
	}

	/**
	 * write the body of the message to a stream.
	 * 
	 * @param out
	 *            the ObjectOutputStream.
	 * @throws IOException
	 *             in case of IO failures.
	 * @see ch.ethz.iks.r_osgi.impl.RemoteOSGiMessageImpl#getBody()
	 */
	public void writeBody(final ObjectOutputStream out) throws IOException {
		out.writeShort(type);
		out.writeUTF(serviceID);
		SmartSerializer.serialize(content1, out);
		SmartSerializer.serialize(content2, out);
	}

	short getType() {
		return type;
	}
	
	String getServiceID() {
		return serviceID;
	}

	Object[] getContent() {
		return new Object[] { content1, content2 };
	}

	/**
	 * String representation for debug outputs.
	 * 
	 * @return a string representation.
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		StringBuffer buffer = new StringBuffer();
		buffer.append("[STATE_UPDATE] - XID: ");
		buffer.append(xid);
		buffer.append(", service ");
		buffer.append("#" + serviceID);
		buffer.append(", type ");
		buffer.append(type);
		if (type == TOPIC_UPDATE) {
			buffer.append(", topics added: ");
			buffer.append(Arrays.asList((String[]) content1));
			buffer.append(", topics removed: ");
			buffer.append(Arrays.asList((String[]) content2));
		} else {
			buffer.append(", service interfaces: ");
			buffer.append(Arrays.asList((String[]) content1));
			buffer.append(", properties: ");
			buffer.append(content2);			
		}
		return buffer.toString();
	}

}

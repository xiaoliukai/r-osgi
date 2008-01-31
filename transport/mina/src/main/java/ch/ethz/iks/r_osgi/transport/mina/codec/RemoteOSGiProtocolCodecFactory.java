package ch.ethz.iks.r_osgi.transport.mina.codec;

import org.apache.mina.filter.codec.demux.DemuxingProtocolCodecFactory;

public class RemoteOSGiProtocolCodecFactory extends
		DemuxingProtocolCodecFactory {

	public RemoteOSGiProtocolCodecFactory() {
		register(DeliverServiceMessageCodec.class);
		register(FetchServiceMessageCodec.class);
		register(InvokeMethodMessageCodec.class);
		register(LeaseMessageCodec.class);
		register(LeaseUpdateMessageCodec.class);
		register(MethodResultMessageCodec.class);
		register(RemoteEventMessageCodec.class);
		register(TimeOffsetMessageCodec.class);
	}

}
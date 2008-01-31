package ch.ethz.iks.r_osgi.test;

import java.util.ArrayList;
import java.util.Arrays;

import org.osgi.framework.BundleContext;

import ch.ethz.iks.r_osgi.RemoteOSGiService;
import ch.ethz.iks.r_osgi.RemoteServiceReference;
import ch.ethz.iks.r_osgi.URI;
import ch.ethz.iks.r_osgi.sample.api.ServiceInterface;
import junit.framework.TestCase;

public class SampleServiceTestCase extends TestCase {

	private RemoteOSGiService remote;

	private BundleContext context;

	public SampleServiceTestCase() {
		super("SampleServiceTest");

	}

	protected void setUp() throws Exception {
		super.setUp();
		context = Activator.getActivator().getContext();
		remote = Activator.getActivator().getR_OSGi();
	}

	protected void tearDown() throws Exception {
		super.tearDown();
	}

	public void testSampleService() throws Exception {
		// connect
		final URI uri = new URI("r-osgi://localhost:9278");
		final String s = "TEST";

		final RemoteServiceReference[] refs = remote.connect(uri);
		assertNotNull(refs);
		System.out.println("refs " + Arrays.asList(refs));
		assertTrue(refs.length > 0);
		final RemoteServiceReference[] refs2 = remote
				.getRemoteServiceReferences(uri, ServiceInterface.class
						.getName(), null);
		assertNotNull(refs);
		assertTrue(refs.length > 0);
		for (int i = 0; i < refs2.length; i++) {
			assertTrue(Arrays.asList(refs).contains(refs2[i]));
			Object o = remote.getRemoteService(refs2[i]);
			assertTrue(o instanceof ServiceInterface);
			assertEquals(
					context.getServiceReferences(ServiceInterface.class
							.getName(), "(" + RemoteOSGiService.SERVICE_URI
							+ "=*)").length, 1);

			ServiceInterface service = (ServiceInterface) o;
			service.local();
			assertEquals(service.echoService(s, new Integer(1)), s);
			service.reverseService(s);
			remote.ungetRemoteService(refs2[i]);
			assertEquals(context.getServiceReferences(ServiceInterface.class
					.getName(), "(" + RemoteOSGiService.SERVICE_URI + "=*)"),
					null);
		}
		remote.disconnect(uri);
	}

	public void testSampleServiceMina() throws Exception {
		// connect
		final URI uri = new URI("r-osgi+mina://localhost:9279");
		final String s = "TEST";

		final RemoteServiceReference[] refs = remote.connect(uri);
		assertNotNull(refs);
		System.out.println("refs " + Arrays.asList(refs));
		assertTrue(refs.length > 0);
		final RemoteServiceReference[] refs2 = remote
				.getRemoteServiceReferences(uri, ServiceInterface.class
						.getName(), null);
		assertNotNull(refs);
		assertTrue(refs.length > 0);
		for (int i = 0; i < refs2.length; i++) {
			assertTrue(Arrays.asList(refs).contains(refs2[i]));
			Object o = remote.getRemoteService(refs2[i]);
			assertTrue(o instanceof ServiceInterface);
			assertEquals(
					context.getServiceReferences(ServiceInterface.class
							.getName(), "(" + RemoteOSGiService.SERVICE_URI
							+ "=*)").length, 1);
			ServiceInterface service = (ServiceInterface) o;
			service.local();
			assertEquals(service.echoService(s, new Integer(1)), s);
			service.reverseService(s);
			remote.ungetRemoteService(refs2[i]);
			assertEquals(context.getServiceReferences(ServiceInterface.class
					.getName(), "(" + RemoteOSGiService.SERVICE_URI + "=*)"),
					null);
		}
		remote.disconnect(uri);
	}
}
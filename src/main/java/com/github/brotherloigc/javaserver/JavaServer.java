package com.github.brotherlogic.javaserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import discovery.Discovery.RegistryEntry;
import discovery.Discovery.RegisterRequest;
import discovery.Discovery.RegisterResponse;
import discovery.Discovery.DiscoverRequest;
import discovery.DiscoveryServiceGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import monitorproto.MonitorServiceGrpc;
import monitorproto.Monitorproto.MessageLog;
import monitorproto.Monitorproto.ValueLog;
import server.ServerGrpc;
import server.ServerOuterClass;
import server.ServerOuterClass.ChangeRequest;

import java.util.logging.LogManager;

public abstract class JavaServer {

	public abstract String getServerName();

	private RegistryEntry registry;
	private String discoveryHost;
	private int discoveryPort;
	private boolean screenOn = true;

	private int onTime = 7;
	private int offTime = 22;

	public void setTime(int on, int off) {
		onTime = on;
		offTime = off;
	}
	public void setOn(boolean screen) {
		screenOn = screen;
	}

	public String getHostName() throws UnknownHostException {
		return InetAddress.getLocalHost().getHostName();
	}

	// From
	// http://stackoverflow.com/questions/6164167/get-mac-address-on-local-machine-with-java
	private static String GetMacAddress(InetAddress ip) {
		String address = null;
		try {

			NetworkInterface network = NetworkInterface.getByInetAddress(ip);
			byte[] mac = network.getHardwareAddress();

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < mac.length; i++) {
				sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
			}
			address = sb.toString();

		} catch (SocketException e) {

			e.printStackTrace();

		}

		return address;
	}

	private static String GetAddress(String addressType) {
		return GetAddressLocal(addressType, true);
	}

	protected static String GetAddressLocal(String addressType, boolean retry) {
		String address = "";
		InetAddress lanIp = null;
		try {

			String ipAddress = null;
			Enumeration<NetworkInterface> net = null;
			net = NetworkInterface.getNetworkInterfaces();

			while (net.hasMoreElements()) {
				NetworkInterface element = net.nextElement();
				Enumeration<InetAddress> addresses = element.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress ip = addresses.nextElement();
					if (ip instanceof Inet4Address && !ip.getHostAddress().contains("127.0")) {
						if (ip.isSiteLocalAddress()) {
							ipAddress = ip.getHostAddress();
							lanIp = InetAddress.getByName(ipAddress);
						}

					}

				}
			}

			if (lanIp == null) {
				// Sleep for 30 seconds and retry
				if (retry) {
					try {
						Thread.sleep(30 * 1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					return GetAddressLocal(addressType, false);
				}
				return null;
			}

			if (addressType.equals("ip")) {

				address = lanIp.toString().replaceAll("^/+", "");

			} else if (addressType.equals("mac")) {

				address = GetMacAddress(lanIp);

			} else {

				throw new Exception("Specify \"ip\" or \"mac\"");

			}

		} catch (UnknownHostException e) {

			e.printStackTrace();

		} catch (SocketException e) {

			e.printStackTrace();

		} catch (Exception e) {

			e.printStackTrace();

		}

		return address;

	}

	protected String getIPAddress() {
		return GetAddress("ip");
	}

	protected String getMACAddress() {
		return GetAddress("mac");
	}

	private Server server;

	public List<BindableService> getLocalServices() {
		List<BindableService> services = getServices();

		// Add the change service in here
		services.add(new ServerService(this));

		return services;
	}

	public abstract List<BindableService> getServices();

	private boolean running = true;

	public void Log(String message) {
		String host = getHost("monitor");
		int port = getPort("monitor");

		if (host != null && port > 0) {
			try {
				ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build();
				MonitorServiceGrpc.MonitorServiceBlockingStub blockingStub = MonitorServiceGrpc
						.newBlockingStub(channel).withDeadlineAfter(1, TimeUnit.SECONDS);

				MessageLog messageLog = MessageLog.newBuilder().setEntry(registry).setMessage(message).build();
				blockingStub.writeMessageLog(messageLog);

				try {
					channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public void Log(float value) {
		String host = getHost("monitor");
		int port = getPort("monitor");

		if (host != null && port > 0) {
			try {

				ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build();
				MonitorServiceGrpc.MonitorServiceBlockingStub blockingStub = MonitorServiceGrpc
						.newBlockingStub(channel).withDeadlineAfter(1, TimeUnit.SECONDS);

				ValueLog valueLog = ValueLog.newBuilder().setEntry(registry).setValue(value).build();
				blockingStub.writeValueLog(valueLog);

				try {
					channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

    static class ServerService extends ServerGrpc.ServerImplBase {

	public ServerService(JavaServer in) {
			localServer = in;
		}

		private JavaServer localServer;
		@Override
		public void change(ChangeRequest request, StreamObserver<ServerOuterClass.Empty> responseObserver) {
			localServer.setOn(request.getScreenOn());
			responseObserver.onNext(ServerOuterClass.Empty.getDefaultInstance());
			responseObserver.onCompleted();
		}
	}

	private void discover(String server) {

		while (discoveryHost == null || discoveryHost.length() == 0 || discoveryPort < 0) {
			try {
				String add = "resolve";
				if (!server.endsWith("/")) {
					add = "/resolve";
				}
				URL url = new URL("http://" + server + add);

				BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
				String[] elems = reader.readLine().split(":");
				discoveryHost = elems[0];
				discoveryPort = Integer.parseInt(elems[1]);
			} catch (Exception e) {
				e.printStackTrace();
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private String rServer;

	public void Serve(String resolveServer) {
			LogManager.getLogManager().reset();
		this.rServer = resolveServer;
		discover(resolveServer);

		while (!register(discoveryHost, discoveryPort)) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		Thread heartbeat = new Thread(new Runnable() {
			@Override
			public void run() {
				while (running) {
					try {
						// Heartbeat every hour
						Thread.sleep(60 * 60 * 1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}

					sendHeartbeat();
				}
			}
		});
		heartbeat.start();

		Thread screen = new Thread(new Runnable() {
			@Override
			public void run() {
				while (running) {
					try {
						Thread.sleep(60 * 1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					dealWithScreen();
				}
			}
		});
		screen.start();

		if (getServices().size() > 0) {
			try {
				ServerBuilder builder = ServerBuilder.forPort(registry.getPort());
				for (BindableService service : getServices())
					builder.addService(service);
				server = builder.build().start();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			localServe();
		}
	}

	public abstract void localServe();

	private void stop() {
		if (server != null) {
			server.shutdown();
		}
	}

	private void blockUntilShutdown() throws InterruptedException {
		if (server != null) {
			server.awaitTermination();
		}
	}

    /**
     * Registers us with the discovery server
     *
     * @param host
     *            Hostname of the discovery server
     * @param port
     *            Port number of the discovery server
     */
    private boolean reRegister(String host, int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build();
        DiscoveryServiceGrpc.DiscoveryServiceBlockingStub blockingStub = DiscoveryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(1, TimeUnit.SECONDS);

        // Get a better host name
        String serverName = getMACAddress();
        try{
            serverName = getHostName();
        } catch (UnknownHostException e){
            e.printStackTrace();
        }

        RegisterResponse resp = null;
        try {
            resp = blockingStub.registerService(RegisterRequest.newBuilder().setService(registry).build());
            registry = resp.getService();
        } catch (StatusRuntimeException e) {
	    //Pass
        }

        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
	    //Pass
        }

        return resp != null && resp.getService().getPort() > 0;
    }

	/**
	 * Registers us with the discovery server
	 *
	 * @param host
	 *            Hostname of the discovery server
	 * @param port
	 *            Port number of the discovery server
	 */
	private boolean register(String host, int port) {
		this.discoveryHost = host;
		this.discoveryPort = port;
		ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build();
		DiscoveryServiceGrpc.DiscoveryServiceBlockingStub blockingStub = DiscoveryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(1, TimeUnit.SECONDS);

		// Get a better host name
        String serverName = getMACAddress();
        try{
            serverName = getHostName();
        } catch (UnknownHostException e){
	    // Pass
        }

        //Clean this after three hours
		RegistryEntry request = RegistryEntry.newBuilder().setName(getServerName()).setIp(getIPAddress()).setTimeToClean(1000*60*60*3).setIgnoresMaster(true)
                .setIdentifier(serverName).build();
        try {
			registry = blockingStub.registerService(RegisterRequest.newBuilder().setService(request).build()).getService();
		} catch (StatusRuntimeException e) {
	    //Pass
	}

		try {
			channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
		    //Pass
		}

		return registry != null && registry.getPort() > 0;
	}

	private void dealWithScreen() {
		String toggle = "off";
		Calendar now = Calendar.getInstance();
		if (screenOn && now.get(Calendar.HOUR_OF_DAY) >= onTime && now.get(Calendar.HOUR_OF_DAY) < offTime) {
			toggle = "on";
		}

		// Turn the display off
		try {
			Process p = Runtime.getRuntime().exec("xset -display :0.0 dpms force " + toggle);
			BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
			for (String line = reader.readLine(); line != null; line = reader.readLine()){}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void sendHeartbeat() {
        while(!reRegister(discoveryHost, discoveryPort)) {
            try {
                Thread.sleep(10*1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
	}

	public String getHost() {
		return registry.getIp();
	}

	public int getPort() {
		return registry.getPort();
	}

	public int getPort(String server) {
		RegistryEntry entry = resolveServer(server);
		if (entry != null) {
			return entry.getPort();
		}
		return 0;
	}

	public String getHost(String server) {
		RegistryEntry entry = resolveServer(server);
		if (entry != null) {
			return entry.getIp();
		}
		return null;
	}

	private RegistryEntry resolveServer(String serverName) {

		if (discoveryPort < 0) {
			discover(rServer);
		}

		ManagedChannel channel = ManagedChannelBuilder.forAddress(discoveryHost, discoveryPort).usePlaintext(true)
				.build();
		DiscoveryServiceGrpc.DiscoveryServiceBlockingStub blockingStub = DiscoveryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(1, TimeUnit.SECONDS);

		RegistryEntry response = null;
		RegistryEntry request = RegistryEntry.newBuilder().setName(serverName).build();
		try {
			response = blockingStub.discover(DiscoverRequest.newBuilder().setRequest(request).build()).getService();
		} catch (StatusRuntimeException e) {
			e.printStackTrace();

			// Let's see if we need to rediscover discover
			discoveryPort = -1;
		}

		try {
			channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		return response;
	}
}

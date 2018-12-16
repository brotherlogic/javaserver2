package com.github.brotherlogic.javaserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import discovery.Discovery.RegistryEntry;
import discovery.Discovery.DiscoverRequest;
import discovery.DiscoveryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

public class NetworkObject {

	String discoveryHost = "";
	int discoveryPort = -1;

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

	public ManagedChannel dial(String base, String server) {
		RegistryEntry entry = resolveServer(base, server);
		ManagedChannel channel = ManagedChannelBuilder.forAddress(entry.getIp(), entry.getPort()).usePlaintext(true)
				.build();
		return channel;
	}

	private RegistryEntry resolveServer(String base, String serverName) {
		if (discoveryPort < 0) {
			discover(base);
		}

		if (serverName.equals("discovery")) {
			return RegistryEntry.newBuilder().setIp(discoveryHost).setPort(discoveryPort).build();
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

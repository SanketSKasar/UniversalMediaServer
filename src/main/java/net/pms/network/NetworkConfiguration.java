/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2011  G.Zsombor
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.network;

import java.net.*;
import java.util.*;
import net.pms.PMS;
import net.pms.util.ConstantList;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class stores the network configuration information: which network
 * interfaces belong to which IP addresses, etc.
 * <p>
 * This class is a bit awkward to test, because it is largely dependent on the
 * {@link NetworkInterface} class which happens to be <code>final</code>. This
 * means it is not possible to provide mock network interface setups to the
 * class constructor and have those tested.
 *
 * @author zsombor
 *
 */
public class NetworkConfiguration {

	public static class InterfaceAssociation {
		String parentName;
		InetAddress addr;
		NetworkInterface iface;

		public InterfaceAssociation(InetAddress addr, NetworkInterface iface, String parentName) {
			super();
			this.addr = addr;
			this.iface = iface;
			this.parentName = parentName;
		}

		/**
		 * @return the addr
		 */
		public InetAddress getAddr() {
			return addr;
		}

		/**
		 * @return the iface
		 */
		public NetworkInterface getIface() {
			return iface;
		}

		/**
		 * Returns the name of the parent of the interface association.
		 *
		 * @return The name of the parent.
		 */
		public String getParentName() {
			return parentName;
		}

		/**
		 * Returns the name of the interface association.
		 *
		 * @return The name.
		 */
		public String getShortName() {
			return iface.getName();
		}

		/**
		 * Returns the display name of the interface association.
		 *
		 * @return The name.
		 */
		public String getDisplayName() {
			String displayName = iface.getDisplayName();

			if (displayName != null) {
				displayName = displayName.trim();
			} else {
				displayName = iface.getName();
			}

			if (addr != null) {
				displayName += " (" + addr.getHostAddress() + ")";
			}

			return displayName;
		}

		@Override
		public String toString() {
			return "InterfaceAssociation(addr=" + addr + ", iface=" + iface + ", parent=" + parentName + ')';
		}
	}

	/**
	 * The logger.
	 */
	private final static Logger LOGGER = LoggerFactory.getLogger(NetworkConfiguration.class);

	private final static Object instanceLock = new Object();

	/**
	 * Singleton instance of this class. All access must be protected by {@link #instanceLock}.
	 */
	private static NetworkConfiguration instance;

	/**
	 * The list of discovered network interfaces.
	 */
	private List<InterfaceAssociation> interfaces = new ArrayList<>();

	private final List<InterfaceAssociation> relevantInterfaces;

	/**
	 * The map of discovered default IP addresses belonging to a network interface.
	 */
	private Map<String, InterfaceAssociation> mainAddress = new HashMap<>();

	/**
	 * The map of IP addresses connected to an interface name.
	 */
	private Map<String, Set<InetAddress>> addressMap = new HashMap<>();

	/**
	 * Default constructor. However, this is a singleton class: use
	 * {@link #get()} to retrieve an instance.
	 * @throws SocketException
	 */
	private NetworkConfiguration() throws SocketException {
		System.setProperty("java.net.preferIPv4Stack", "true");

		checkNetworkInterface(NetworkInterface.getNetworkInterfaces(), null);

		relevantInterfaces = new ConstantList<NetworkConfiguration.InterfaceAssociation>(interfaces);

	}

	/**
	 * Collect all of the relevant addresses for the given network interface,
	 * add them to the global address map and return them.
	 *
	 * @param networkInterface
	 *            The network interface.
	 * @return The available addresses.
	 */
	private Set<InetAddress> addAvailableAddresses(NetworkInterface networkInterface) {
		Set<InetAddress> addrSet = new HashSet<>();
		LOGGER.trace("Available addresses for \"{}\" are: {}", networkInterface.getName(), Collections.list(networkInterface.getInetAddresses()));

		/**
		 * networkInterface.getInterfaceAddresses() returns 'null' on some adapters if
		 * the parameter 'java.net.preferIPv4Stack=true' is passed to the JVM
		 * Use networkInterface.getInetAddresses() instead
		 */
		for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
			if (address != null) {
				if (isRelevantAddress(address)) {
					addrSet.add(address);
				}
			}
		}

		LOGGER.trace("Non loopback/IPv4 addresses for \"{}\" are: {}", networkInterface.getName(), addrSet);

		// Store the addresses
		addressMap.put(networkInterface.getName(), addrSet);

		return addrSet;
	}

	/**
	 * Returns true if the provided address is relevant, i.e. when the address
	 * is not an IPv6 address or a loopback address.
	 *
	 * @param address
	 *            The address to test.
	 * @return True when the address is relevant, false otherwise.
	 */
	private boolean isRelevantAddress(InetAddress address) {
		return !(address instanceof Inet6Address || address.isLoopbackAddress());
	}

	/**
	 * Discovers the list of relevant network interfaces based on the provided
	 * list of network interfaces. The parent name is passed on for logging and
	 * identification purposes, it can be <code>null</code>.
	 *
	 * @param networkInterfaces
	 *            The network interface list to check.
	 * @param parentName
	 *            The name of the parent network interface.
	 */
	private void checkNetworkInterface(Enumeration<NetworkInterface> networkInterfaces, String parentName) {
		if (networkInterfaces == null) {
			return;
		}

		List<NetworkInterface> interfaces = Collections.list(networkInterfaces);

		if (interfaces.size() > 0 && LOGGER.isTraceEnabled()) {
			StringBuilder stringBuilder = new StringBuilder();
			for (NetworkInterface networkInterface : interfaces) {
				if (stringBuilder.length() > 0) {
					stringBuilder.append(", ").append(networkInterface.getDisplayName());
				} else {
					stringBuilder.append(networkInterface.getDisplayName());
				}
			}
			if (StringUtils.isNotBlank(parentName)) {
				LOGGER.trace("Checking network sub interfaces for \"{}\": {}", parentName, stringBuilder);
			} else {
				LOGGER.trace("Checking network interfaces: {}", stringBuilder);
			}
		}

		for (NetworkInterface ni : interfaces) {
			if (!skipNetworkInterface(ni.getName(), ni.getDisplayName())) {
				// check for interface has at least one IP address.
				checkNetworkInterface(ni, parentName);
			} else {
				LOGGER.trace("Child network interface ({},{}) skipped, because skip_network_interfaces='{}'",
					new Object[] { ni.getName(), ni.getDisplayName(), PMS.getConfiguration().getSkipNetworkInterfaces() });
			}
		}

		if (LOGGER.isTraceEnabled()) {
			if (StringUtils.isNotBlank(parentName)) {
				LOGGER.trace("Network sub interfaces check for \"{}\" completed", parentName);
			} else {
				LOGGER.trace("Network interfaces check completed");
			}
		}
	}

	/**
	 * Returns the list of discovered available addresses for the provided list
	 * of network interfaces.
	 *
	 * @param networkInterfaces
	 *            The list of network interfaces.
	 * @return The list of addresses.
	 */
	private Set<InetAddress> getAllAvailableAddresses(Enumeration<NetworkInterface> networkInterfaces) {
		Set<InetAddress> addrSet = new HashSet<>();

		while (networkInterfaces.hasMoreElements()) {
			NetworkInterface ni = networkInterfaces.nextElement();
			Set<InetAddress> set = addressMap.get(ni.getName());

			if (set != null) {
				addrSet.addAll(set);
			}
		}

		return addrSet;
	}

	/**
	 * Discover the list of relevant addresses for a single network interface,
	 * taking into account that a network interface can have sub interfaces that
	 * might also have relevant addresses. Discovery is therefore handled
	 * recursively. The parent name is passed on for identification and logging
	 * purposes, it can be <code>null</code>.
	 *
	 * @param networkInterface
	 *            The network interface to check.
	 * @param parentName
	 *            The name of the parent interface.
	 */
	private void checkNetworkInterface(NetworkInterface networkInterface, String parentName) {
		LOGGER.trace("Checking \"{}\", display name: \"{}\"",networkInterface.getName(), networkInterface.getDisplayName());
		addAvailableAddresses(networkInterface);
		checkNetworkInterface(networkInterface.getSubInterfaces(), networkInterface.getName());

		// Create address / iface pairs which are not IP address of the child iface too
		Set<InetAddress> subAddress = getAllAvailableAddresses(networkInterface.getSubInterfaces());
		if (subAddress != null && subAddress.size() > 0) {
			LOGGER.trace("Sub addresses for \"{}\" is {}", networkInterface.getName(), subAddress);
		}
		boolean foundAddress = false;

		// networkInterface.getInterfaceAddresses() returns 'null' on some adapters if
		// the parameter 'java.net.preferIPv4Stack=true' is passed to the JVM
		// Use networkInterface.getInetAddresses() instead
		for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
			if (address != null) {
				LOGGER.trace("Checking \"{}\" on \"{}\"", address.getHostAddress(), networkInterface.getName());

				if (isRelevantAddress(address)) {
					// Avoid adding duplicates
					if (!subAddress.contains(address)) {
						LOGGER.trace("Found \"{}\" -> \"{}\"", networkInterface.getName(), address.getHostAddress());
						final InterfaceAssociation ia = new InterfaceAssociation(address, networkInterface, parentName);
						interfaces.add(ia);
						mainAddress.put(networkInterface.getName(), ia);
						foundAddress = true;
					}
				} else if (LOGGER.isTraceEnabled()) {
					if (address.isLoopbackAddress()) {
						LOGGER.trace("Skipping \"{}\" because it's a loopback address", address.getHostAddress());
					} else {
						LOGGER.trace("Skipping \"{}\" because it's IPv6", address.getHostAddress());
					}
				}
			}
		}

		if (!foundAddress) {
			interfaces.add(new InterfaceAssociation(null, networkInterface, parentName));
			LOGGER.trace("Network interface \"{}\" has no valid address", networkInterface.getName());
		}
	}

	/**
	 * Returns the default IP address associated with the default network interface.
	 * This is the first network interface that does not have a parent. This
	 * should avoid alias interfaces being returned. If no interfaces were discovered,
	 * <code>null</code> is returned.
	 *
	 * @return The address.
	 */
	public InterfaceAssociation getDefaultNetworkInterfaceAddress() {
		LOGGER.trace("default network interface address from {}", interfaces);
		InterfaceAssociation association = getFirstInterfaceWithAddress();

		if (association != null) {
			if (association.getParentName() != null) {
				InterfaceAssociation ia = getAddressForNetworkInterfaceName(association.getParentName());
				LOGGER.trace("first association has parent: {} -> {}", association, ia);
				return ia;
			} else {
				LOGGER.trace("first network interface: {}", association);
				return association;
			}
		}

		return null;
	}

	/**
	 * @return All registered {@link NetworkInterface}s.
	 */
	public List<NetworkInterface> getNetworkInterfaces() {
		List<NetworkInterface> foundInterfaces = new ArrayList<>();
		for (InterfaceAssociation interfaceAssociation : interfaces) {
			foundInterfaces.add(interfaceAssociation.getIface());
		}

		return foundInterfaces;
	}

	/**
	 * @return All registered {@link NetworkInterface}s that has at least one
	 * relevant address as defined by {@link #isRelevantAddress(InetAddress)}.
	 */
	public List<NetworkInterface> getRelevantNetworkInterfaces() {
		List<NetworkInterface> foundInterfaces = new ArrayList<>();
		for (InterfaceAssociation interfaceAssociation : interfaces) {
			if (interfaceAssociation.getAddr() != null) {
				foundInterfaces.add(interfaceAssociation.getIface());
			}
		}

		return foundInterfaces;
	}

	/**
	 * @return All {@link NetworkInterface}s registered with the given {@link InetAddress}
	 */
	public List<NetworkInterface> getNetworkInterfaces(InetAddress inetAddress) {
		if (inetAddress == null) {
			return null;
		}
		List<NetworkInterface> foundInterfaces = new ArrayList<>();
		for (InterfaceAssociation interfaceAssociation : interfaces) {
			if (inetAddress.equals(interfaceAssociation.getAddr())) {
				foundInterfaces.add(interfaceAssociation.getIface());
			}
		}

		return foundInterfaces;
	}

	/**
	 * @param networkInterface the {@link NetworkInterface} for which to return
	 *                         {@link InetAddress}es.
	 * @return An array of relevant (as defined by {@link #isRelevantAddress(InetAddress)})
	 *         addresses for the given {@link NetworkInterface} or {@code null}
	 *         if none is found.
	 */
	public InetAddress[] getRelevantInterfaceAddresses(NetworkInterface networkInterface) {
		if (networkInterface == null) {
			return null;
		}

		List<InetAddress> inetAddresses = new ArrayList<>();
		for (InterfaceAssociation interfaceAssociation : interfaces) {
			if (interfaceAssociation.getAddr() != null && interfaceAssociation.getIface().equals(networkInterface)) {
				inetAddresses.add(interfaceAssociation.getAddr());
			}
		}

		if (inetAddresses.size() > 0) {
			return inetAddresses.toArray(new InetAddress[inetAddresses.size()]);
		}

		return null;
	}

	/**
	 * @return An array of relevant (as defined by {@link #isRelevantAddress(InetAddress)})
	 *         addresses for the all {@link NetworkInterface}s or {@code null}
	 *         if none is found.
	 */
	public InetAddress[] getRelevantInterfaceAddresses() {
		List<InetAddress> inetAddresses = new ArrayList<>();
		for (InterfaceAssociation interfaceAssociation : interfaces) {
			if (interfaceAssociation.getAddr() != null) {
				inetAddresses.add(interfaceAssociation.getAddr());
			}
		}

		if (inetAddresses.size() > 0) {
			return inetAddresses.toArray(new InetAddress[inetAddresses.size()]);
		}

		return null;
	}

	/**
	 * Returns the first interface from the list of discovered interfaces that
	 * has an address. If no such interface can be found or if no interfaces
	 * were discovered, <code>null</code> is returned.
	 *
	 * @return The interface.
	 */
	private InterfaceAssociation getFirstInterfaceWithAddress() {
		for (InterfaceAssociation ia : interfaces) {
			if (ia.getAddr() != null) {
				return ia;
			}
		}

		return null;
	}

	/**
	 * Returns the default IP address associated with the the given interface
	 * name, or <code>null</code> if it has not been discovered.
	 *
	 * @param name
	 *            The name of the interface.
	 * @return The IP address.
	 */
	public InterfaceAssociation getAddressForNetworkInterfaceName(String name) {
		return mainAddress.get(name);
	}

	/**
	 * Returns true if the name or displayname match the configured interfaces
	 * to skip, false otherwise.
	 *
	 * @param name
	 *            The name of the interface.
	 * @param displayName
	 *            The display name of the interface.
	 * @return True if the interface should be skipped, false otherwise.
	 */
	private boolean skipNetworkInterface(String name, String displayName) {
		for (String current : PMS.getConfiguration().getSkipNetworkInterfaces()) {
			if (current != null) {
				// We expect the configured network interface names to skip to be
				// defined with the start of the interface name, e.g. "tap" to
				// to skip "tap0", "tap1" and "tap2", but not "etap0".
				if (name != null && name.toLowerCase().startsWith(current.toLowerCase())) {
					return true;
				}

				if (displayName != null && displayName.toLowerCase().startsWith(current.toLowerCase())) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Returns the network interface for the servername configured in PMS, or
	 * <code>null</code> if no servername is configured.
	 *
	 * @return The network interface.
	 * @throws SocketException
	 *             If an I/O error occurs.
	 * @throws UnknownHostException
	 *             If no IP address for the server name could be found.
	 */
	public NetworkInterface getNetworkInterfaceByServerName() throws SocketException, UnknownHostException {
		String hostname = PMS.getConfiguration().getServerHostname();

		if (hostname != null) {
			LOGGER.trace("Searching network interface for " + hostname);
			return NetworkInterface.getByInetAddress(InetAddress.getByName(hostname));
		}

		return null;
	}

	/**
	 * Creates or returns the {@link NetworkConfiguration} singleton instance.
	 *
	 * @return The {@link NetworkConfiguration} instance.
	 */
	public static NetworkConfiguration get() {
		synchronized (instanceLock) {
			if (instance == null) {
				try {
					instance = new NetworkConfiguration();
				} catch (SocketException e) {
					LOGGER.error("Fatal error when trying to detect network configuration: {}", e.getMessage());
					LOGGER.error("No network services will be available");
					LOGGER.trace("", e);
					instance = null;
				}
			}

			return instance;
		}
	}

	/**
	 * Attempts to get the name of the local computer.
	 */
	public static String getDefaultHostName() {
		String hostname;
		try {
			hostname = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			hostname = InetAddress.getLoopbackAddress().getHostName();
		}
		return hostname;
	}

	/**
	 * Reinitializes the {@link NetworkConfiguration} singleton instance.
	 */
	public static void reinitialize() {
		synchronized (instanceLock) {
			try {
				instance = new NetworkConfiguration();
			} catch (SocketException e) {
				LOGGER.error("Fatal error when trying to detect network configuration: {}", e.getMessage());
				LOGGER.error("No network services will be available");
				LOGGER.trace("", e);
				instance = null;
			}
		}
	}
}

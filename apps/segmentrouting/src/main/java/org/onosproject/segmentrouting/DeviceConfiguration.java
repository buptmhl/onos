/*
 * Copyright 2014-2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.segmentrouting;

import com.google.common.collect.Lists;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.MacAddress;
import org.onosproject.incubator.net.config.basics.ConfigException;
import org.onosproject.incubator.net.config.basics.InterfaceConfig;
import org.onosproject.incubator.net.intf.Interface;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.host.InterfaceIpAddress;
import org.onosproject.segmentrouting.config.SegmentRoutingConfig;
import org.onosproject.segmentrouting.config.SegmentRoutingConfig.AdjacencySid;
import org.onosproject.segmentrouting.grouphandler.DeviceProperties;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Segment Routing configuration component that reads the
 * segment routing related configuration from Network Configuration Manager
 * component and organizes in more accessible formats.
 *
 * TODO: Merge multiple Segment Routing configuration wrapper classes into one.
 */
public class DeviceConfiguration implements DeviceProperties {

    private static final Logger log = LoggerFactory
            .getLogger(DeviceConfiguration.class);
    private final List<Integer> allSegmentIds = new ArrayList<>();
    private final ConcurrentHashMap<DeviceId, SegmentRouterInfo> deviceConfigMap
        = new ConcurrentHashMap<>();

    private class SegmentRouterInfo {
        int nodeSid;
        DeviceId deviceId;
        Ip4Address ip;
        MacAddress mac;
        boolean isEdge;
        HashMap<PortNumber, Ip4Address> gatewayIps;
        HashMap<PortNumber, Ip4Prefix> subnets;
        List<AdjacencySid> adjacencySids;

        public SegmentRouterInfo() {
            this.gatewayIps = new HashMap<>();
            this.subnets = new HashMap<>();
        }
    }

    /**
     * Constructor. Reads all the configuration for all devices of type
     * Segment Router and organizes into various maps for easier access.
     *
     * @param cfgService config service
     */
    public DeviceConfiguration(NetworkConfigRegistry cfgService) {
        // Read config from device subject, excluding gatewayIps and subnets.
        Set<DeviceId> deviceSubjects =
                cfgService.getSubjects(DeviceId.class, SegmentRoutingConfig.class);
        deviceSubjects.forEach(subject -> {
            SegmentRoutingConfig config =
                cfgService.getConfig(subject, SegmentRoutingConfig.class);
            SegmentRouterInfo info = new SegmentRouterInfo();
            info.deviceId = subject;
            info.nodeSid = config.getSid();
            info.ip = config.getIp();
            info.mac = config.getMac();
            info.isEdge = config.isEdgeRouter();
            info.adjacencySids = config.getAdjacencySids();

            this.deviceConfigMap.put(info.deviceId, info);
            this.allSegmentIds.add(info.nodeSid);
        });

        // Read gatewayIps and subnets from port subject.
        Set<ConnectPoint> portSubjects =
            cfgService.getSubjects(ConnectPoint.class, InterfaceConfig.class);
        portSubjects.forEach(subject -> {
            InterfaceConfig config =
                    cfgService.getConfig(subject, InterfaceConfig.class);
            Set<Interface> networkInterfaces;
            try {
                networkInterfaces = config.getInterfaces();
            } catch (ConfigException e) {
                log.error("Error loading port configuration");
                return;
            }
            networkInterfaces.forEach(networkInterface -> {
                DeviceId dpid = networkInterface.connectPoint().deviceId();
                PortNumber port = networkInterface.connectPoint().port();
                SegmentRouterInfo info = this.deviceConfigMap.get(dpid);

                // skip if there is no corresponding device for this ConenctPoint
                if (info != null) {
                    Set<InterfaceIpAddress> interfaceAddresses = networkInterface.ipAddresses();
                    interfaceAddresses.forEach(interfaceAddress -> {
                        info.gatewayIps.put(port, interfaceAddress.ipAddress().getIp4Address());
                        info.subnets.put(port, interfaceAddress.subnetAddress().getIp4Prefix());
                    });
                }
            });

        });
    }

    /**
     * Returns the Node segment id of a segment router.
     *
     * @param deviceId device identifier
     * @return segment id
     */
    @Override
    public int getSegmentId(DeviceId deviceId) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            log.trace("getSegmentId for device{} is {}", deviceId, srinfo.nodeSid);
            return srinfo.nodeSid;
        } else {
            log.warn("getSegmentId for device {} "
                    + "throwing IllegalStateException "
                    + "because device does not exist in config", deviceId);
            throw new IllegalStateException();
        }
    }

    /**
     * Returns the Node segment id of a segment router given its Router mac address.
     *
     * @param routerMac router mac address
     * @return node segment id, or -1 if not found in config
     */
    public int getSegmentId(MacAddress routerMac) {
        for (Map.Entry<DeviceId, SegmentRouterInfo> entry:
                    deviceConfigMap.entrySet()) {
            if (entry.getValue().mac.equals(routerMac)) {
                return entry.getValue().nodeSid;
            }
        }

        return -1;
    }

    /**
     * Returns the Node segment id of a segment router given its Router ip address.
     *
     * @param routerAddress router ip address
     * @return node segment id, or -1 if not found in config
     */
    public int getSegmentId(Ip4Address routerAddress) {
        for (Map.Entry<DeviceId, SegmentRouterInfo> entry:
            deviceConfigMap.entrySet()) {
            if (entry.getValue().ip.equals(routerAddress)) {
                return entry.getValue().nodeSid;
            }
        }

        return -1;
    }

    /**
     * Returns the router mac of a segment router.
     *
     * @param deviceId device identifier
     * @return router mac address
     */
    @Override
    public MacAddress getDeviceMac(DeviceId deviceId) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            log.trace("getDeviceMac for device{} is {}", deviceId, srinfo.mac);
            return srinfo.mac;
        } else {
            log.warn("getDeviceMac for device {} "
                    + "throwing IllegalStateException "
                    + "because device does not exist in config", deviceId);
            throw new IllegalStateException();
        }
    }

    /**
     * Returns the router ip address of a segment router.
     *
     * @param deviceId device identifier
     * @return router ip address
     */
    public Ip4Address getRouterIp(DeviceId deviceId) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            log.trace("getDeviceIp for device{} is {}", deviceId, srinfo.ip);
            return srinfo.ip;
        } else {
            log.warn("getRouterIp for device {} "
                    + "throwing IllegalStateException "
                    + "because device does not exist in config", deviceId);
            throw new IllegalStateException();
        }
    }

    /**
     * Indicates if the segment router is a edge router or
     * a core/backbone router.
     *
     * @param deviceId device identifier
     * @return boolean
     */
    @Override
    public boolean isEdgeDevice(DeviceId deviceId) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            log.trace("isEdgeDevice for device{} is {}", deviceId, srinfo.isEdge);
            return srinfo.isEdge;
        } else {
            log.warn("isEdgeDevice for device {} "
                    + "throwing IllegalStateException "
                    + "because device does not exist in config", deviceId);
            throw new IllegalStateException();
        }
    }

    /**
     * Returns the node segment ids of all configured segment routers.
     *
     * @return list of node segment ids
     */
    @Override
    public List<Integer> getAllDeviceSegmentIds() {
        return allSegmentIds;
    }

    /**
     * Returns the device identifier or data plane identifier (dpid)
     * of a segment router given its segment id.
     *
     * @param sid segment id
     * @return deviceId device identifier
     */
    public DeviceId getDeviceId(int sid) {
        for (Map.Entry<DeviceId, SegmentRouterInfo> entry:
            deviceConfigMap.entrySet()) {
            if (entry.getValue().nodeSid == sid) {
                return entry.getValue().deviceId;
            }
        }

        return null;
    }

    /**
     * Returns the device identifier or data plane identifier (dpid)
     * of a segment router given its router ip address.
     *
     * @param ipAddress router ip address
     * @return deviceId device identifier
     */
    public DeviceId getDeviceId(Ip4Address ipAddress) {
        for (Map.Entry<DeviceId, SegmentRouterInfo> entry:
            deviceConfigMap.entrySet()) {
            if (entry.getValue().ip.equals(ipAddress)) {
                return entry.getValue().deviceId;
            }
        }

        return null;
    }

    /**
     * Returns the configured port ip addresses for a segment router.
     * These addresses serve as gateway IP addresses for the subnets configured
     * on those ports.
     *
     * @param deviceId device identifier
     * @return list of ip addresses configured on the ports or null if not found
     */
    public List<Ip4Address> getPortIPs(DeviceId deviceId) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            log.trace("getSubnetGatewayIps for device{} is {}", deviceId,
                      srinfo.gatewayIps.values());
            return new ArrayList<>(srinfo.gatewayIps.values());
        }
        return null;
    }

    /**
     * Returns the configured IP addresses per port
     * for a segment router.
     *
     * @param deviceId device identifier
     * @return map of port to gateway IP addresses or null if not found
     */
    public Map<PortNumber, Ip4Address> getPortIPMap(DeviceId deviceId) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            return srinfo.gatewayIps;
        }
        return null;
    }

    /**
     * Returns the configured subnet prefixes for a segment router.
     *
     * @param deviceId device identifier
     * @return list of ip prefixes or null if not found
     */
    public List<Ip4Prefix> getSubnets(DeviceId deviceId) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            log.trace("getSubnets for device{} is {}", deviceId,
                      srinfo.subnets.values());
            return new ArrayList<>(srinfo.subnets.values());
        }
        return null;
    }

    /**
     *  Returns the configured subnet on the given port, or null if no
     *  subnet has been configured on the port.
     *
     *  @param deviceId device identifier
     *  @param pnum  port identifier
     *  @return configured subnet on port, or null
     */
    public Ip4Prefix getPortSubnet(DeviceId deviceId, PortNumber pnum) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            return srinfo.subnets.get(pnum);
        }
        return null;
    }

    /**
     * Returns the router ip address of segment router that has the
     * specified ip address in its subnets.
     *
     * @param destIpAddress target ip address
     * @return router ip address
     */
    public Ip4Address getRouterIpAddressForASubnetHost(Ip4Address destIpAddress) {
        for (Map.Entry<DeviceId, SegmentRouterInfo> entry:
                    deviceConfigMap.entrySet()) {
            for (Ip4Prefix prefix:entry.getValue().subnets.values()) {
                if (prefix.contains(destIpAddress)) {
                    return entry.getValue().ip;
                }
            }
        }

        log.debug("No router was found for {}", destIpAddress);
        return null;
    }

    /**
     * Returns the router mac address of segment router that has the
     * specified ip address as one of its subnet gateway ip address.
     *
     * @param gatewayIpAddress router gateway ip address
     * @return router mac address or null if not found
     */
    public MacAddress getRouterMacForAGatewayIp(Ip4Address gatewayIpAddress) {
        for (Map.Entry<DeviceId, SegmentRouterInfo> entry:
                deviceConfigMap.entrySet()) {
            if (entry.getValue().gatewayIps.
                    values().contains(gatewayIpAddress)) {
                return entry.getValue().mac;
            }
        }

        log.debug("Cannot find a router for {}", gatewayIpAddress);
        return null;
    }


    /**
     * Checks if the host is in the subnet defined in the router with the
     * device ID given.
     *
     * @param deviceId device identification of the router
     * @param hostIp   host IP address to check
     * @return true if the host is within the subnet of the router,
     * false if no subnet is defined under the router or if the host is not
     * within the subnet defined in the router
     */
    public boolean inSameSubnet(DeviceId deviceId, Ip4Address hostIp) {

        List<Ip4Prefix> subnets = getSubnets(deviceId);
        if (subnets == null) {
            return false;
        }

        for (Ip4Prefix subnet: subnets) {
            if (subnet.contains(hostIp)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the ports corresponding to the adjacency Sid given.
     *
     * @param deviceId device identification of the router
     * @param sid adjacency Sid
     * @return list of port numbers
     */
    public List<Integer> getPortsForAdjacencySid(DeviceId deviceId, int sid) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            for (AdjacencySid asid : srinfo.adjacencySids) {
                if (asid.getAsid() == sid) {
                    return asid.getPorts();
                }
            }
        }

        return Lists.newArrayList();
    }

    /**
     * Check if the Sid given is whether adjacency Sid of the router device or not.
     *
     * @param deviceId device identification of the router
     * @param sid Sid to check
     * @return true if the Sid given is the adjacency Sid of the device,
     * otherwise false
     */
    public boolean isAdjacencySid(DeviceId deviceId, int sid) {
        SegmentRouterInfo srinfo = deviceConfigMap.get(deviceId);
        if (srinfo != null) {
            if (srinfo.adjacencySids.isEmpty()) {
                return false;
            } else {
                for (AdjacencySid asid:
                        srinfo.adjacencySids) {
                    if (asid.getAsid() == sid) {
                        return true;
                    }
                }
                return false;
            }
        }

        return false;
    }
}
/*
 * Copyright 2018-present Open Networking Foundation
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
package org.onosproject.openstacknetworking.impl;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.junit.TestUtils;
import org.onlab.packet.DeserializationException;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreServiceAdapter;
import org.onosproject.core.DefaultApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.packet.DefaultInboundPacket;
import org.onosproject.net.packet.DefaultPacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketServiceAdapter;
import org.onosproject.openstacknetworking.api.Constants;
import org.onosproject.openstacknetworking.api.ExternalPeerRouter;
import org.onosproject.openstacknetworking.api.InstancePort;
import org.onosproject.openstacknetworking.util.OpenstackNetworkingUtil;
import org.onosproject.openstacknode.api.OpenstackNode;
import org.onosproject.openstacknode.api.OpenstackNodeAdapter;
import org.onosproject.store.service.TestStorageService;
import org.openstack4j.model.network.ExternalGateway;
import org.openstack4j.model.network.Port;
import org.openstack4j.model.network.Router;
import org.openstack4j.model.network.RouterInterface;
import org.openstack4j.model.network.Subnet;
import org.openstack4j.openstack.networking.domain.NeutronPort;
import org.openstack4j.openstack.networking.domain.NeutronRouter;
import org.openstack4j.openstack.networking.domain.NeutronSubnet;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.onlab.packet.Ethernet.TYPE_IPV4;
import static org.onlab.packet.ICMP.TYPE_ECHO_REPLY;
import static org.onlab.packet.ICMP.TYPE_ECHO_REQUEST;
import static org.onosproject.net.NetTestTools.connectPoint;

public class OpenstackRoutingIcmpHandlerTest {
    private OpenstackRoutingIcmpHandler icmpHandler;
    private static final byte CODE_ECHO_REQUEST = 0x00;
    private static final byte CODE_ECHO_REPLY = 0x00;

    protected PacketProcessor packetProcessor;
    private InstancePort instancePort1;
    private InstancePort instancePort2;
    private InstancePort instancePort3;
    private RouterInterface routerInterface1;
    private Router router1;

    private MacAddress srcMacPort1 = MacAddress.valueOf("11:22:33:44:55:66");
    private IpAddress srcIpPort1 = IpAddress.valueOf("10.0.0.3");
    private DeviceId srcDeviceId1 = DeviceId.deviceId("of:000000000000000a");
    private PortNumber srcPortNum1 = PortNumber.portNumber(1);
    private IpAddress targetIpToGw = IpAddress.valueOf("10.0.0.1");
    private IpAddress targetIpToExternal = IpAddress.valueOf("8.8.8.8");
    private IpAddress sNatIp = IpAddress.valueOf("172.27.0.13");
    private MacAddress targetMac = Constants.DEFAULT_GATEWAY_MAC;
    private MacAddress peerRouterMac = Constants.DEFAULT_EXTERNAL_ROUTER_MAC;
    private Port port1;
    private Port externalPort;
    private Subnet subnet1;

    Map<String, Port> portMap = Maps.newHashMap();
    Map<String, InstancePort> instancePortMap = Maps.newHashMap();
    Map<String, Router> osRouterMap = Maps.newHashMap();
    Map<String, RouterInterface> osRouterInterfaceMap = Maps.newHashMap();

    /**
     * Initial setup for this unit test.
     */
    @Before
    public void setUp() {

        icmpHandler = new OpenstackRoutingIcmpHandler();

        icmpHandler.coreService = new TestCoreService();
        icmpHandler.packetService = new TestPacketService();
        icmpHandler.storageService = new TestStorageService();
        icmpHandler.osNodeService = new TestOpenstackNodeService();
        icmpHandler.instancePortService = new TestInstancePortService();
        icmpHandler.osNetworkService = new TestOpenstackNetworkService();
        icmpHandler.osRouterService = new TestOpenstackRouterService();
        TestUtils.setField(icmpHandler, "eventExecutor", MoreExecutors.newDirectExecutorService());
        icmpHandler.activate();

        createPort();
        createSubnet();
        createInstancePortMap();
        createRouterInterfaceMap();
        createRouterMap();
    }

    /**
     * Tears down all of this unit test.
     */
    @After
    public void tearDown() {
        icmpHandler.deactivate();

    }

    /**
     * Tests the icmp request to gateway.
     */
    @Test
    public void testRequestToGw() {
        Ethernet icmpRequest = constructIcmpRequestPacket(srcIpPort1,
                srcMacPort1,
                targetIpToGw,
                targetMac,
                TYPE_ECHO_REQUEST);
        sendPacket(icmpRequest);
    }

    /**
     * Tests the icmp request to external.
     */
    @Test
    public void testRequestToExternal() {
        Ethernet icmpRequest = constructIcmpRequestPacket(srcIpPort1,
                srcMacPort1,
                targetIpToExternal,
                targetMac,
                TYPE_ECHO_REQUEST);

        sendPacket(icmpRequest);

        Ethernet icmpResponse = constructIcmpRequestPacket(targetIpToExternal,
                peerRouterMac,
                sNatIp,
                targetMac,
                TYPE_ECHO_REPLY);

        sendPacket(icmpResponse);
    }

    private void sendPacket(Ethernet ethernet) {
        final ByteBuffer byteBuffer = ByteBuffer.wrap(ethernet.serialize());
        InboundPacket inPacket = new DefaultInboundPacket(connectPoint(srcDeviceId1.toString(),
                Integer.parseInt(srcPortNum1.toString())),
                ethernet,
                byteBuffer);

        PacketContext context = new TestPacketContext(127L, inPacket, null, false);
        packetProcessor.process(context);
    }

    private void validatePacket(Ethernet ethernet) {
        IPv4 ipPacket = (IPv4) ethernet.getPayload();

        if (IPv4.fromIPv4Address(ipPacket.getSourceAddress()).equals(targetIpToGw.toString())) {
            validateIcmpReqToGw(ipPacket);
        } else if (IPv4.fromIPv4Address(ipPacket.getSourceAddress())
                .equals(sNatIp.toString())) {
            validateIcmpReqToExternal(ipPacket);
        } else if (IPv4.fromIPv4Address(ipPacket.getSourceAddress())
                .equals(targetIpToExternal.toString())) {
            validateIcmpRespFromExternal(ipPacket);
        }
    }

    private void validateIcmpRespFromExternal(IPv4 ipPacket) {
        ICMP icmpResp = (ICMP) ipPacket.getPayload();
        short icmpId = ByteBuffer.wrap(icmpResp.serialize(), 4, 2).getShort();
        short seqNum = ByteBuffer.wrap(icmpResp.serialize(), 6, 2).getShort();

        assertEquals(icmpResp.getIcmpType(), TYPE_ECHO_REPLY);
        assertEquals(icmpResp.getIcmpCode(), CODE_ECHO_REPLY);
        assertEquals(icmpId, 0);
        assertEquals(seqNum, 0);
        assertEquals(IPv4.fromIPv4Address(ipPacket.getSourceAddress()), targetIpToExternal.toString());
        assertEquals(IPv4.fromIPv4Address(ipPacket.getDestinationAddress()), srcIpPort1.toString());
    }

    private void validateIcmpReqToExternal(IPv4 ipPacket) {
        ICMP icmpReq = (ICMP) ipPacket.getPayload();
        short icmpId = ByteBuffer.wrap(icmpReq.serialize(), 4, 2).getShort();
        short seqNum = ByteBuffer.wrap(icmpReq.serialize(), 6, 2).getShort();


        assertEquals(icmpReq.getIcmpType(), TYPE_ECHO_REQUEST);
        assertEquals(icmpReq.getIcmpCode(), CODE_ECHO_REQUEST);
        assertEquals(icmpId, 0);
        assertEquals(seqNum, 0);
        assertEquals(IPv4.fromIPv4Address(ipPacket.getSourceAddress()), sNatIp.toString());
        assertEquals(IPv4.fromIPv4Address(ipPacket.getDestinationAddress()), targetIpToExternal.toString());

    }
    private void validateIcmpReqToGw(IPv4 ipPacket) {
        ICMP icmpReq = (ICMP) ipPacket.getPayload();
        short icmpId = ByteBuffer.wrap(icmpReq.serialize(), 4, 2).getShort();
        short seqNum = ByteBuffer.wrap(icmpReq.serialize(), 6, 2).getShort();

        assertEquals(icmpReq.getIcmpType(), TYPE_ECHO_REPLY);
        assertEquals(icmpReq.getIcmpCode(), CODE_ECHO_REPLY);
        assertEquals(icmpId, 0);
        assertEquals(seqNum, 0);
        assertEquals(IPv4.fromIPv4Address(ipPacket.getSourceAddress()), targetIpToGw.toString());
        assertEquals(IPv4.fromIPv4Address(ipPacket.getDestinationAddress()), srcIpPort1.toString());
    }

    private Ethernet constructIcmpRequestPacket(IpAddress srcIp,
                                                MacAddress srcMac,
                                                IpAddress dstIp,
                                                MacAddress dstMac, byte icmpType) {
        try {
            IcmpEcho icmp = new IcmpEcho();
            if (icmpType == TYPE_ECHO_REQUEST) {
                icmp.setIcmpType(TYPE_ECHO_REQUEST)
                        .setIcmpCode(CODE_ECHO_REQUEST);
            } else {
                icmp.setIcmpType(TYPE_ECHO_REPLY)
                        .setIcmpCode(CODE_ECHO_REPLY);
            }

            icmp.setChecksum((short) 0)
                    .setIdentifier((short) 0)
                    .setSequenceNum((short) 0);

            ByteBuffer bb = ByteBuffer.wrap(icmp.serialize());

            IPv4 iPacket = new IPv4();
            iPacket.setDestinationAddress(dstIp.toString());
            iPacket.setSourceAddress(srcIp.toString());
            iPacket.setTtl((byte) 64);
            iPacket.setChecksum((short) 0);
            iPacket.setDiffServ((byte) 0);
            iPacket.setProtocol(IPv4.PROTOCOL_ICMP);

            iPacket.setPayload(ICMP.deserializer().deserialize(bb.array(), 0, 8));

            Ethernet ethPacket = new Ethernet();

            ethPacket.setEtherType(TYPE_IPV4);
            ethPacket.setSourceMACAddress(srcMac);
            ethPacket.setDestinationMACAddress(dstMac);
            ethPacket.setPayload(iPacket);

            return ethPacket;
        } catch (DeserializationException e) {
            return null;
        }
    }

    private class TestCoreService extends CoreServiceAdapter {
        @Override
        public ApplicationId registerApplication(String name) {
            return new DefaultApplicationId(200, "test");
        }
    }

    /**
     * Mocks the PacketService.
     */
    private class TestPacketService extends PacketServiceAdapter {
        @Override
        public void addProcessor(PacketProcessor processor, int priority) {
            packetProcessor = processor;
        }

        @Override
        public void emit(OutboundPacket packet) {
            try {
                Ethernet eth = Ethernet.deserializer().deserialize(packet.data().array(),
                        0, packet.data().array().length);
                validatePacket(eth);
            } catch (Exception e) {
                fail(e.getMessage());
            }
        }
    }

    /**
     * Mocks the OpenstackNodeService.
     */
    private class TestOpenstackNodeService extends OpenstackNodeServiceAdapter {

        @Override
        public OpenstackNode node(DeviceId deviceId) {
            return new TestOpenstackNode();
        }
    }

    /**
     * Mocks the InstancePortService.
     */
    private class TestInstancePortService extends InstancePortServiceAdapter {
        @Override
        public InstancePort instancePort(MacAddress macAddress) {
            return instancePortMap.values().stream()
                    .filter(port -> Objects.equals(port.macAddress(), macAddress))
                    .findAny().orElse(null);
        }

        @Override
        public InstancePort instancePort(IpAddress ipAddress, String osNetId) {
            return instancePortMap.values().stream()
                    .filter(port -> port.networkId().equals(osNetId))
                    .filter(port -> port.ipAddress().equals(ipAddress))
                    .findFirst().orElse(null);
        }

        @Override
        public InstancePort instancePort(String osPortId) {
            return instancePortMap.get(osPortId);
        }

        @Override
        public Set<InstancePort> instancePorts() {
            return ImmutableSet.copyOf(instancePortMap.values());
        }

        @Override
        public Set<InstancePort> instancePorts(String osNetId) {
            Set<InstancePort> ports = instancePortMap.values().stream()
                    .filter(port -> port.networkId().equals(osNetId))
                    .collect(Collectors.toSet());

            return ImmutableSet.copyOf(ports);
        }
    }

    /**
     * Mocks the OpenstackNetworkService.
     */
    private class TestOpenstackNetworkService extends OpenstackNetworkServiceAdapter {
        @Override
        public Set<Port> ports(String netId) {
            return ImmutableSet.copyOf(portMap.values());
        }
        @Override
        public Port port(String portId) {

            return port1;
        }

        @Override
        public Subnet subnet(String subnetId) {

            return subnet1;
        }

        @Override
        public ExternalPeerRouter externalPeerRouter(ExternalGateway externalGateway) {
            return DefaultExternalPeerRouter.builder()
                    .ipAddress(IpAddress.valueOf("172.27.0.1"))
                    .macAddress(peerRouterMac)
                    .vlanId(VlanId.NONE)
                    .build();
        }
    }

    /**
     * Mocks the OpenstackRouterService.
     */
    private class TestOpenstackRouterService extends OpenstackRouterServiceAdapter {
        @Override
        public Set<RouterInterface> routerInterfaces() {
            return ImmutableSet.copyOf(osRouterInterfaceMap.values());
        }

        @Override
        public Router router(String osRouterId) {
            return osRouterMap.get(osRouterId);
        }
        public Set<RouterInterface> routerInterfaces(String osRouterId) {
            return osRouterInterfaceMap.values().stream()
                    .filter(iface -> iface.getId().equals(osRouterId))
                    .collect(Collectors.toSet());
        }

    }

    /**
     * Mocks the DefaultPacket context.
     */
    private final class TestPacketContext extends DefaultPacketContext {
        private TestPacketContext(long time, InboundPacket inPkt,
                                  OutboundPacket outPkt, boolean block) {
            super(time, inPkt, outPkt, block);
        }

        @Override
        public void send() {
            // We don't send anything out.
        }
    }


    private void createPort() {
        InputStream portJsonStream1 = OpenstackRoutingIcmpHandlerTest.class
                .getResourceAsStream("openstack-port-1.json");
        port1 = (Port) OpenstackNetworkingUtil.jsonToModelEntity(portJsonStream1, NeutronPort.class);

        InputStream portJsonStream2 = OpenstackRoutingIcmpHandlerTest.class
                .getResourceAsStream("openstack-port-external.json");
        externalPort = (Port) OpenstackNetworkingUtil.jsonToModelEntity(portJsonStream2, NeutronPort.class);

        portMap.put(port1.getId(), port1);
        portMap.put(externalPort.getId(), externalPort);

    }

    private void createSubnet() {
        InputStream subnetJsonStream1 = OpenstackRoutingIcmpHandlerTest.class
                .getResourceAsStream("openstack-subnet-1.json");
        subnet1 = (Subnet) OpenstackNetworkingUtil.jsonToModelEntity(subnetJsonStream1, NeutronSubnet.class);
    }

    private void createRouterInterfaceMap() {
        routerInterface1 = new TestRouterInterface("router-id-1",
                "subnet-id-1",
                "router-interface-id-1",
                "tenant-id-1");

        osRouterInterfaceMap.put(routerInterface1.getPortId(), routerInterface1);
    }

    private void createRouterMap() {
        InputStream routerStream1 = OpenstackRoutingIcmpHandlerTest.class
                .getResourceAsStream("openstack-router-1.json");
        router1 = (Router)
                OpenstackNetworkingUtil.jsonToModelEntity(routerStream1, NeutronRouter.class);
        osRouterMap.put(router1.getId(), router1);

    }
    private void createInstancePortMap() {
        instancePort1 = DefaultInstancePort.builder()
                .networkId("net-id-1")
                .portId("ce705c24-c1ef-408a-bda3-7bbd946164ab")
                .deviceId(srcDeviceId1)
                .portNumber(srcPortNum1)
                .ipAddress(srcIpPort1)
                .macAddress(srcMacPort1)
                .state(InstancePort.State.valueOf("ACTIVE"))
                .build();

        instancePort2 = DefaultInstancePort.builder()
                .networkId("net-id-2")
                .portId("port-id-2")
                .deviceId(DeviceId.deviceId("of:000000000000000b"))
                .portNumber(PortNumber.portNumber(2))
                .ipAddress(IpAddress.valueOf("10.10.10.2"))
                .macAddress(MacAddress.valueOf("22:33:44:55:66:11"))
                .state(InstancePort.State.valueOf("ACTIVE"))
                .build();

        instancePort3 = DefaultInstancePort.builder()
                .networkId("net-id-3")
                .portId("port-id-3")
                .deviceId(DeviceId.deviceId("of:000000000000000c"))
                .oldDeviceId(DeviceId.deviceId("of:000000000000000d"))
                .oldPortNumber(PortNumber.portNumber(4, "tap-4"))
                .portNumber(PortNumber.portNumber(3, "tap-3"))
                .ipAddress(IpAddress.valueOf("10.10.10.3"))
                .macAddress(MacAddress.valueOf("33:44:55:66:11:22"))
                .state(InstancePort.State.valueOf("ACTIVE"))
                .build();

        instancePortMap.put(instancePort1.portId(), instancePort1);
        instancePortMap.put(instancePort2.portId(), instancePort2);
        instancePortMap.put(instancePort3.portId(), instancePort3);
    }


    private class TestOpenstackNode extends OpenstackNodeAdapter {
        public TestOpenstackNode() {
            super();
        }
        @Override
        public PortNumber uplinkPortNum() {
            return PortNumber.portNumber(1);
        }
    }
}

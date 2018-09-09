package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaHandlerListener;
import gnu.io.NRSerialPort;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.DiscoveredAdapter;
import org.sputnikdev.bluetooth.manager.DiscoveredDevice;
import org.sputnikdev.bluetooth.manager.transport.Device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest({NRSerialPort.class, BluegigaHandler.class, BluegigaAdapter.class})
public class BluegigaFactoryTest {

    private static final String ADAPTER_PORT_NAME_1 = "/dev/tty.usbmodem1";
    private static final String ADAPTER_PORT_NAME_2 = "/dev/tty.usbmodem2";

    private static final URL ADAPTER_URL_1 = new URL("bluegiga:/12:34:56:78:90:11");
    private static final URL ADAPTER_URL_2 = new URL("bluegiga:/11:22:33:44:55:66");
    private static final URL ADAPTER_1_DEVICE_1_URL = ADAPTER_URL_1.copyWithDevice("11:11:11:11:11:11");
    private static final URL ADAPTER_1_DEVICE_2_URL = ADAPTER_URL_1.copyWithDevice("22:22:22:22:22:22");
    private static final URL ADAPTER_2_DEVICE_1_URL = ADAPTER_URL_2.copyWithDevice("33:33:33:33:33:33");

    private static final URL CHARATERISTIC_1_URL =
        ADAPTER_1_DEVICE_1_URL.copyWith("0000180f-0000-1000-8000-00805f9b34fb",
            "00002a19-0000-1000-8000-00805f9b34fb");
    private static final URL CHARATERISTIC_2_URL =
        ADAPTER_1_DEVICE_1_URL.copyWith("00001804-0000-1000-8000-00805f9b34fb",
            "00002a07-0000-1000-8000-00805f9b34fb");
    private static final URL CHARATERISTIC_3_URL =
        ADAPTER_2_DEVICE_1_URL.copyWith("00001804-0000-1000-8000-00805f9b34fb",
            "00002a07-0000-1000-8000-00805f9b34fb");


    private static final Set<String> PORT_NAMES =
        Stream.of(ADAPTER_PORT_NAME_1, ADAPTER_PORT_NAME_2, "/dev/rdisk0s2").collect(Collectors.toSet());

    @Mock
    private BluegigaHandler bluegigaHandler1;
    @Mock
    private BluegigaHandler bluegigaHandler2;

    @InjectMocks
    private BluegigaFactory bluegigaFactory = spy(new BluegigaFactory(BluegigaFactory.PORT_NAMES_REGEX));

    @Before
    public void setUp() {
        PowerMockito.mockStatic(NRSerialPort.class, BluegigaHandler.class);
        when(NRSerialPort.getAvailableSerialPorts()).thenReturn(PORT_NAMES);

        bluegigaHandler1 = mockHandler(ADAPTER_PORT_NAME_1, ADAPTER_URL_1);
        bluegigaHandler2 = mockHandler(ADAPTER_PORT_NAME_2, ADAPTER_URL_2);

        doAnswer(new Answer<BluegigaAdapter>() {
            @Override
            public BluegigaAdapter answer(InvocationOnMock invocationOnMock) throws Throwable {
                BluegigaAdapter adapter = (BluegigaAdapter) invocationOnMock.callRealMethod();
                return adapter != null ? spy(adapter) : null;
            }
        }).when(bluegigaFactory).createAdapter(anyString());
    }

    @Test
    public void testBlugigaFactoryConstructor() {
        BluegigaFactory bluegigaFactory = new BluegigaFactory(BluegigaFactory.OSX_SERIAL_PORT_NAMES_REGEX);

        assertFalse(bluegigaFactory.matchPort("/dev/ttyS1"));
        assertFalse(bluegigaFactory.matchPort("/dev/ttyUSB2"));
        assertFalse(bluegigaFactory.matchPort("/dev/ttyACM3"));
        assertFalse(bluegigaFactory.matchPort("/dev/ttyAMA4"));
        assertFalse(bluegigaFactory.matchPort("/dev/rfcomm15"));

        assertFalse(bluegigaFactory.matchPort("COM1"));
        assertFalse(bluegigaFactory.matchPort("COM2"));

        assertFalse(bluegigaFactory.matchPort("/dev/tty.serial1"));
        assertFalse(bluegigaFactory.matchPort("/dev/tty.usbserial2"));
        assertTrue(bluegigaFactory.matchPort("/dev/tty.usbmodem3"));
    }

    @Test
    public void testGetAdapter() throws Exception {
        Map<URL, DiscoveredAdapter> adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));

        assertEquals(2, adapters.size());
        BluegigaAdapter bluegigaAdapter1 = bluegigaFactory.getAdapter(ADAPTER_URL_1);
        BluegigaAdapter bluegigaAdapter2 = bluegigaFactory.getAdapter(ADAPTER_URL_2);
        assertNotNull(bluegigaAdapter1);
        assertNotNull(bluegigaAdapter2);

        when(bluegigaAdapter1.isAlive()).thenReturn(false);

        BluegigaAdapter bluegigaAdapter3 = bluegigaFactory.getAdapter(ADAPTER_URL_1);
        assertTrue(bluegigaAdapter1 != bluegigaAdapter3);
        assertNotNull(bluegigaAdapter3);
        verify(bluegigaAdapter1).dispose();

        when(bluegigaAdapter2.isAlive()).thenReturn(false);
        when(NRSerialPort.getAvailableSerialPorts()).thenReturn(
            Stream.of(ADAPTER_PORT_NAME_1, "/dev/rdisk0s2").collect(Collectors.toSet()));

        assertNull(bluegigaFactory.getAdapter(ADAPTER_URL_2));
        verify(bluegigaAdapter2).dispose();

        when(bluegigaAdapter3.isAlive()).thenReturn(false);
        PowerMockito.doThrow(new RuntimeException()).when(NRSerialPort.class);
        NRSerialPort.getAvailableSerialPorts();
        assertNull(bluegigaFactory.getAdapter(ADAPTER_URL_1));
        verify(bluegigaAdapter3).dispose();
    }

    @Test
    public void testMatchPort() {
        assertFalse(bluegigaFactory.matchPort("/dev/ttyS1"));
        assertFalse(bluegigaFactory.matchPort("/dev/ttyUSB2"));
        assertTrue(bluegigaFactory.matchPort("/dev/ttyACM3"));
        assertFalse(bluegigaFactory.matchPort("/dev/ttyAMA4"));
        assertFalse(bluegigaFactory.matchPort("/dev/rfcomm15"));

        assertTrue(bluegigaFactory.matchPort("COM1"));
        assertTrue(bluegigaFactory.matchPort("COM2"));

        assertFalse(bluegigaFactory.matchPort("/dev/tty.serial1"));
        assertFalse(bluegigaFactory.matchPort("/dev/tty.usbserial2"));
        assertTrue(bluegigaFactory.matchPort("/dev/tty.usbmodem3"));

        assertFalse(bluegigaFactory.matchPort("/dev/ttys000"));
        assertFalse(bluegigaFactory.matchPort("/dev/disk0s3"));
        assertFalse(bluegigaFactory.matchPort("/dev/tty23"));
        assertFalse(bluegigaFactory.matchPort("/tty.usbmodem3"));
    }

    @Test
    public void testGetDiscoveredAdapters() throws Exception {
        Map<URL, DiscoveredAdapter> adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));

        assertEquals(2, adapters.size());
        assertTrue(adapters.containsKey(ADAPTER_URL_1));
        assertTrue(adapters.containsKey(ADAPTER_URL_2));

        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_1);
        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_2);

        adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));
        assertEquals(2, adapters.size());

        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_1);
        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_2);

        URL thirdHandlerURL = new URL("bluegiga://77:77:77:77:77:77");
        Set<String> ports = new HashSet<>(PORT_NAMES);
        ports.add("/dev/ttyACM3");
        BluegigaHandler bluegigaHandler3 = mockHandler("/dev/ttyACM3", thirdHandlerURL);
        when(NRSerialPort.getAvailableSerialPorts()).thenReturn(ports);

        adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));
        assertEquals(3, adapters.size());

        assertTrue(adapters.containsKey(thirdHandlerURL));
        assertTrue(adapters.containsKey(ADAPTER_URL_1));
        assertTrue(adapters.containsKey(ADAPTER_URL_2));

        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_1);
        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_2);
        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create("/dev/ttyACM3");

    }

    @Test
    public void testGetDiscoveredAdaptersCleanUpStaleAdapters() throws Exception {
        Map<URL, DiscoveredAdapter> adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));

        assertEquals(2, adapters.size());
        assertTrue(adapters.containsKey(ADAPTER_URL_1));
        assertTrue(adapters.containsKey(ADAPTER_URL_2));

        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_1);
        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_2);

        BluegigaAdapter bluegigaAdapter1 = bluegigaFactory.getAdapter(ADAPTER_URL_1);
        BluegigaAdapter bluegigaAdapter2 = bluegigaFactory.getAdapter(ADAPTER_URL_2);
        when(bluegigaHandler1.isAlive()).thenReturn(false);

        adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));
        assertEquals(1, adapters.size());
        assertTrue(adapters.containsKey(ADAPTER_URL_2));
        verify(bluegigaAdapter1).dispose();
        verify(bluegigaAdapter2, never()).dispose();

        doThrow(new RuntimeException()).when(bluegigaAdapter2).dispose();
        when(bluegigaHandler2.isAlive()).thenReturn(false);
        adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));
        assertEquals(0, adapters.size());
        verify(bluegigaAdapter2).dispose();
    }

    @Test
    public void testGetDiscoveredAdaptersCleanUpChangedPort() throws Exception {
        Map<URL, DiscoveredAdapter> adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));

        assertEquals(2, adapters.size());
        assertTrue(adapters.containsKey(ADAPTER_URL_1));
        assertTrue(adapters.containsKey(ADAPTER_URL_2));
        assertEquals(ADAPTER_PORT_NAME_1, bluegigaFactory.getAdapter(ADAPTER_URL_1).getPortName());
        assertEquals(ADAPTER_PORT_NAME_2, bluegigaFactory.getAdapter(ADAPTER_URL_2).getPortName());

        BluegigaAdapter adapter1 = bluegigaFactory.getAdapter(ADAPTER_URL_1);
        BluegigaAdapter adapter2 = bluegigaFactory.getAdapter(ADAPTER_URL_2);

        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_1);
        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_2);

        String newPort = "/dev/tty.usbmodem3";
        Set<String> ports = new HashSet<>(PORT_NAMES);
        ports.remove(ADAPTER_PORT_NAME_1);
        ports.add(newPort);
        BluegigaHandler newBluegigaHandler = mockHandler(newPort, ADAPTER_URL_1);
        when(NRSerialPort.getAvailableSerialPorts()).thenReturn(ports);

        adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));
        assertEquals(2, adapters.size());

        assertTrue(adapters.containsKey(ADAPTER_URL_1));
        assertTrue(adapters.containsKey(ADAPTER_URL_2));
        assertEquals(newPort, bluegigaFactory.getAdapter(ADAPTER_URL_1).getPortName());
        assertEquals(ADAPTER_PORT_NAME_2, bluegigaFactory.getAdapter(ADAPTER_URL_2).getPortName());

        verify(adapter1).dispose();
        verify(adapter2, never()).dispose();
    }

    @Test
    public void testGetDiscoveredAdaptersErrorCreatingHandler() throws Exception {
        PowerMockito.doThrow(new IllegalStateException()).when(BluegigaHandler.class);
        BluegigaHandler.create(ADAPTER_PORT_NAME_2);

        Map<URL, DiscoveredAdapter> adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));

        assertEquals(1, adapters.size());
        assertTrue(adapters.containsKey(ADAPTER_URL_1));

        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_1);
        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_2);
    }

    @Test
    public void testGetDiscoveredAdaptersErrorCreatingAdapter() throws Exception {
        PowerMockito.mockStatic(BluegigaAdapter.class, CALLS_REAL_METHODS);

        PowerMockito.doThrow(new IllegalStateException()).when(BluegigaAdapter.class);
        BluegigaAdapter.create(bluegigaHandler2);

        Map<URL, DiscoveredAdapter> adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));

        assertEquals(1, adapters.size());
        assertTrue(adapters.containsKey(ADAPTER_URL_1));

        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_1);
        PowerMockito.verifyStatic(times(1));
        BluegigaHandler.create(ADAPTER_PORT_NAME_2);
    }

    @Test
    public void testHandleErrorsFromBluegigaSerialHandler() {
        ArgumentCaptor<BlueGigaHandlerListener> handlerListener =
            ArgumentCaptor.forClass(BlueGigaHandlerListener.class);
        doNothing().when(bluegigaHandler1).addHandlerListener(handlerListener.capture());

        Map<URL, DiscoveredAdapter> adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));
        assertTrue(adapters.containsKey(ADAPTER_URL_1));
        assertTrue(adapters.containsKey(ADAPTER_URL_2));
        assertNotNull(bluegigaFactory.getAdapter(ADAPTER_URL_1));
        assertNotNull(bluegigaFactory.getAdapter(ADAPTER_URL_2));

        verify(bluegigaHandler1).addHandlerListener(handlerListener.getValue());

        handlerListener.getValue().bluegigaClosed(new RuntimeException());
        assertNull(bluegigaFactory.getAdapter(ADAPTER_URL_1));
        assertNotNull(bluegigaFactory.getAdapter(ADAPTER_URL_2));

        when(bluegigaHandler1.isAlive()).thenReturn(false);

        adapters = bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));
        assertEquals(1, adapters.size());
        assertTrue(adapters.containsKey(ADAPTER_URL_2));
    }

    @Test
    public void testGetDiscoveredDevices() throws Exception {
        mockDevices();

        Map<URL, DiscoveredDevice> discovered = bluegigaFactory.getDiscoveredDevices().stream()
            .collect(Collectors.toMap(DiscoveredDevice::getURL, Function.identity()));

        assertEquals(3, discovered.size());
        assertTrue(discovered.containsKey(ADAPTER_1_DEVICE_1_URL));
        assertTrue(discovered.containsKey(ADAPTER_1_DEVICE_2_URL));
        assertTrue(discovered.containsKey(ADAPTER_2_DEVICE_1_URL));

        BluegigaDevice bluegigaDevice = (BluegigaDevice) bluegigaFactory.getAdapter(ADAPTER_URL_1).getDevices().get(0);
        when(bluegigaDevice.getLastDiscovered()).thenReturn(null);
        discovered = bluegigaFactory.getDiscoveredDevices().stream()
                .collect(Collectors.toMap(DiscoveredDevice::getURL, Function.identity()));
        assertEquals(2, discovered.size());
        assertTrue(discovered.containsKey(ADAPTER_1_DEVICE_2_URL));
        assertTrue(discovered.containsKey(ADAPTER_2_DEVICE_1_URL));

        when(bluegigaHandler1.isAlive()).thenReturn(false);

        discovered = bluegigaFactory.getDiscoveredDevices().stream()
            .collect(Collectors.toMap(DiscoveredDevice::getURL, Function.identity()));

        assertEquals(1, discovered.size());
        assertTrue(discovered.containsKey(ADAPTER_2_DEVICE_1_URL));
    }

    @Test
    public void testGetDevice() throws Exception {
        mockDevices();

        assertEquals(ADAPTER_1_DEVICE_1_URL, bluegigaFactory.getDevice(ADAPTER_1_DEVICE_1_URL).getURL());
        assertEquals(ADAPTER_1_DEVICE_2_URL, bluegigaFactory.getDevice(ADAPTER_1_DEVICE_2_URL).getURL());
        assertEquals(ADAPTER_2_DEVICE_1_URL, bluegigaFactory.getDevice(ADAPTER_2_DEVICE_1_URL).getURL());

        // devices should be created even if they have not discovered - not true for now
        //assertNotNull(bluegigaFactory.getDevice(ADAPTER_URL_1.copyWithDevice("55:55:55:55:55:55")));
    }

    @Test
    public void testGetCharacteristic() {
        mockDevices();

        BluegigaDevice device1 = bluegigaFactory.getDevice(ADAPTER_1_DEVICE_1_URL);
        BluegigaDevice device2 = bluegigaFactory.getDevice(ADAPTER_2_DEVICE_1_URL);
        BluegigaService bluegigaService1 = mockService(CHARATERISTIC_1_URL.getServiceURL(),
            mockCharacteristic(CHARATERISTIC_1_URL));
        BluegigaService bluegigaService2 = mockService(CHARATERISTIC_2_URL.getServiceURL(),
            mockCharacteristic(CHARATERISTIC_2_URL));
        when(device1.getService(CHARATERISTIC_1_URL.getServiceURL())).thenReturn(bluegigaService1);
        when(device1.getService(CHARATERISTIC_2_URL.getServiceURL())).thenReturn(bluegigaService2);

        BluegigaService bluegigaService3 = mockService(CHARATERISTIC_3_URL.getServiceURL(),
            mockCharacteristic(CHARATERISTIC_3_URL));
        when(device2.getService(CHARATERISTIC_3_URL.getServiceURL())).thenReturn(bluegigaService3);

        assertNotNull(bluegigaFactory.getCharacteristic(CHARATERISTIC_1_URL));
        assertNotNull(bluegigaFactory.getCharacteristic(CHARATERISTIC_2_URL));
        assertNotNull(bluegigaFactory.getCharacteristic(CHARATERISTIC_3_URL));
    }


    @Test
    public void testGetProtocolName() throws Exception {
        assertEquals("bluegiga", bluegigaFactory.getProtocolName());
    }

    private BluegigaService mockService(URL url, BluegigaCharacteristic... characteristics) {
        BluegigaService service = mock(BluegigaService.class);
        when(service.getURL()).thenReturn(url);
        when(service.getCharacteristics()).thenReturn(Arrays.asList(characteristics));
        for (BluegigaCharacteristic characteristic : characteristics) {
            //doReturn(characteristic).when(service).getCharacteristic(characteristic.getURL());
            URL characteristicURL = characteristic.getURL();
            when(service.getCharacteristic(characteristicURL)).thenReturn(characteristic);
        }
        return service;
    }

    private BluegigaCharacteristic mockCharacteristic(URL url) {
        BluegigaCharacteristic characteristic = mock(BluegigaCharacteristic.class);
        when(characteristic.getURL()).thenReturn(url);
        return characteristic;
    }

    private static BluegigaHandler mockHandler(String portName, URL url) {
        BluegigaHandler bluegigaHandler = mock(BluegigaHandler.class);
        when(BluegigaHandler.create(portName)).thenReturn(bluegigaHandler);
        when(bluegigaHandler.getPortName()).thenReturn(portName);
        when(bluegigaHandler.getAdapterAddress()).thenReturn(url);
        when(bluegigaHandler.isAlive()).thenReturn(true);
        return bluegigaHandler;
    }

    private void mockDevices() {
        bluegigaFactory.getDiscoveredAdapters().stream()
            .collect(Collectors.toMap(DiscoveredAdapter::getURL, Function.identity()));

        BluegigaDevice bluegigaDevice1 = mockDevice(ADAPTER_1_DEVICE_1_URL);
        BluegigaDevice bluegigaDevice2 = mockDevice(ADAPTER_1_DEVICE_2_URL);
        List<Device> devices = new ArrayList<>();
        devices.add(bluegigaDevice1);
        devices.add(bluegigaDevice2);
        BluegigaAdapter bluegigaAdapter1 = bluegigaFactory.getAdapter(ADAPTER_URL_1);
        when(bluegigaAdapter1.getDevices()).thenReturn(devices);
        doReturn(bluegigaDevice1).when(bluegigaAdapter1).getDevice(ADAPTER_1_DEVICE_1_URL);
        doReturn(bluegigaDevice2).when(bluegigaAdapter1).getDevice(ADAPTER_1_DEVICE_2_URL);

        devices = new ArrayList<>();
        BluegigaDevice bluegigaDevice3 = mockDevice(ADAPTER_2_DEVICE_1_URL);
        devices.add(bluegigaDevice3);
        BluegigaAdapter bluegigaAdapter2 = bluegigaFactory.getAdapter(ADAPTER_URL_2);
        when(bluegigaAdapter2.getDevices()).thenReturn(devices);
        doReturn(bluegigaDevice3).when(bluegigaAdapter2).getDevice(ADAPTER_2_DEVICE_1_URL);
    }


    private BluegigaDevice mockDevice(URL url) {
        BluegigaDevice bluegigaDevice = mock(BluegigaDevice.class);
        when(bluegigaDevice.getURL()).thenReturn(url);
        when(bluegigaDevice.getLastDiscovered()).thenReturn(Instant.now());
        return bluegigaDevice;
    }

}
package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaGroupFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaConnectionStatusEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectedEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirDataType;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BluetoothAddressType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.Whitebox;
import org.mockito.runners.MockitoJUnitRunner;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.BluetoothObject;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Notification;
import org.sputnikdev.bluetooth.manager.transport.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class BluegigaDeviceTest {

    private static final URL DEVICE_URL = new URL("/12:34:56:78:90:12/11:22:33:44:55:66");
    private static final int CONNECTION_HANDLE = 1;
    private static final int[] EIR_SMARTLOCK_PACKET = {2, 1, 4, 10, 8, 83, 109, 97, 114, 116, 108, 111, 99, 107};
    private static final int[] BATTERY_LEVEL_CHARACTERISTIC_DECLARATION =
        {CharacteristicAccessType.READ.getBitField() | CharacteristicAccessType.NOTIFY.getBitField(), 3, 0, 0x19, 0x2a};

    private static final URL BATTERY_SERVICE_URL = DEVICE_URL.copyWithService("0000180f-0000-1000-8000-00805f9b34fb");
    private static final URL BATTERY_LEVEL_CHARACTERISTIC_URL =
        BATTERY_SERVICE_URL.copyWithCharacteristic("00002a19-0000-1000-8000-00805f9b34fb");
    private static final URL BATTERY_SERVICE_PRIMARY_SERVICE_DECLARATION_URL =
        BATTERY_SERVICE_URL.copyWithCharacteristic("00002800-0000-1000-8000-00805f9b34fb");
    private static final URL BATTERY_LEVEL_CHARACTERISTIC_DESCRIPTOR_URL =
        BATTERY_SERVICE_URL.copyWithCharacteristic("00002902-0000-1000-8000-00805f9b34fb");

    private static final URL TX_POWER_SERVICE_URL = DEVICE_URL.copyWithService("00001804-0000-1000-8000-00805f9b34fb");
    private static final URL TX_POWER_LEVEL_CHARACTERISTIC_URL =
        TX_POWER_SERVICE_URL.copyWithCharacteristic("00002a07-0000-1000-8000-00805f9b34fb");
    private static final URL TX_POWER_PRIMARY_SERVICE_DECLARATION_URL =
        TX_POWER_SERVICE_URL.copyWithCharacteristic("00002800-0000-1000-8000-00805f9b34fb");
    private static final URL TX_POWER_CHARACTERISTIC_DESCRIPTOR_URL =
        TX_POWER_SERVICE_URL.copyWithCharacteristic("00002902-0000-1000-8000-00805f9b34fb");

    @Mock
    private BluegigaHandler bluegigaHandler;

    private BluegigaDevice bluegigaDevice;

    private BlueGigaConnectionStatusEvent connectionStatusEvent;
    private Notification<Boolean> booleanNotification = (Notification<Boolean>) mock(Notification.class);

    @Captor
    private ArgumentCaptor<Map<String, byte[]>> serviceDataCaptor;
    @Mock
    private Notification<Map<String, byte[]>> serviceDataNotification;
    @Captor
    private ArgumentCaptor<Map<Short, byte[]>> manufacturerDataCaptor;
    @Mock
    private Notification<Map<Short, byte[]>> manufacturerDataNotification;

    @Before
    public void setUp() {
        when(bluegigaHandler.isAlive()).thenReturn(true);

        bluegigaDevice = new BluegigaDevice(bluegigaHandler, DEVICE_URL);

        connectionStatusEvent = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(eq(DEVICE_URL), any(BluetoothAddressType.class))).thenReturn(connectionStatusEvent);

        List<BlueGigaGroupFoundEvent> serviceEvents = new ArrayList<>();
        serviceEvents.add(mockServiceEvent(BATTERY_SERVICE_URL, 1, 10));
        serviceEvents.add(mockServiceEvent(TX_POWER_SERVICE_URL, 11, 15));
        when(bluegigaHandler.getServices(CONNECTION_HANDLE)).thenReturn(serviceEvents);

        List<BlueGigaFindInformationFoundEvent> characteristicEvents = new ArrayList<>();
        characteristicEvents.add(mockCharacteristicEvent(BATTERY_SERVICE_PRIMARY_SERVICE_DECLARATION_URL, 1));
        characteristicEvents.add(mockCharacteristicEvent(BATTERY_LEVEL_CHARACTERISTIC_URL, 2));
        characteristicEvents.add(mockCharacteristicEvent(BATTERY_LEVEL_CHARACTERISTIC_DESCRIPTOR_URL, 3));

        characteristicEvents.add(mockCharacteristicEvent(TX_POWER_PRIMARY_SERVICE_DECLARATION_URL, 11));
        characteristicEvents.add(mockCharacteristicEvent(TX_POWER_LEVEL_CHARACTERISTIC_URL, 12));
        characteristicEvents.add(mockCharacteristicEvent(TX_POWER_CHARACTERISTIC_DESCRIPTOR_URL, 13));

        when(bluegigaHandler.getCharacteristics(CONNECTION_HANDLE)).thenReturn(characteristicEvents);

        List<BlueGigaAttributeValueEvent> declarations = new ArrayList<>();
        declarations.add(mockDeclarationEvent(4, BATTERY_LEVEL_CHARACTERISTIC_DECLARATION));
        when(bluegigaHandler.getDeclarations(CONNECTION_HANDLE)).thenReturn(declarations);

        when(bluegigaHandler.getConnectionStatus(CONNECTION_HANDLE)).thenReturn(connectionStatusEvent);

        verify(bluegigaHandler).addEventListener(bluegigaDevice);

        bluegigaDevice = spy(bluegigaDevice);

        doAnswer(invocation -> {
            return invocation.getArgumentAt(0, Supplier.class).get();
        }).when(bluegigaHandler).runInSynchronizedContext(any(Supplier.class));
        doAnswer(invocation -> {
            invocation.getArgumentAt(0, Runnable.class).run();
            return null;
        }).when(bluegigaHandler).runInSynchronizedContext(any(Runnable.class));

        doNothing().when(serviceDataNotification).notify(serviceDataCaptor.capture());
        doNothing().when(manufacturerDataNotification).notify(manufacturerDataCaptor.capture());
    }

    @Test
    public void testConnect() {
        bluegigaDevice.enableConnectedNotifications(booleanNotification);
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(DEVICE_URL, BluetoothAddressType.UNKNOWN)).thenReturn(event);

        assertTrue(bluegigaDevice.connect());

        verify(bluegigaDevice).establishConnection();
        // if address type is unknown (default value), then we try to optimize it by guessing that the address is public
        verify(bluegigaHandler).connect(DEVICE_URL, BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC);
        verify(booleanNotification).notify(true);

        // attributes should not be discovered as a part of connect procedure,
        // a connection event should trigger this instead
        verify(bluegigaHandler, never()).getServices(CONNECTION_HANDLE);
        verify(bluegigaHandler, never()).getCharacteristics(CONNECTION_HANDLE);
        verify(bluegigaHandler, never()).getDeclarations(CONNECTION_HANDLE);

        assertEquals(CONNECTION_HANDLE, bluegigaDevice.getConnectionHandle());
    }

    @Test
    public void testConnectionEventReceived() {
        bluegigaDevice.enableConnectedNotifications(booleanNotification);
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(DEVICE_URL, BluetoothAddressType.UNKNOWN)).thenReturn(event);

        assertFalse(bluegigaDevice.isConnected());

        bluegigaDevice.bluegigaEventReceived(event);

        assertServices();
        assertCharacteristics();
        assertDescriptors();
        assertTrue(bluegigaDevice.isServicesResolved());
        verify(bluegigaHandler, never()).connect(eq(DEVICE_URL), any(BluetoothAddressType.class));
        verify(bluegigaHandler).getServices(CONNECTION_HANDLE);
        verify(bluegigaHandler).getCharacteristics(CONNECTION_HANDLE);
        verify(bluegigaHandler).getDeclarations(CONNECTION_HANDLE);
        verify(booleanNotification).notify(true);
        assertEquals(CONNECTION_HANDLE, bluegigaDevice.getConnectionHandle());

        // next connection event must be disregarded
        bluegigaDevice.bluegigaEventReceived(event);
        assertTrue(bluegigaDevice.isServicesResolved());
        verify(bluegigaHandler, never()).connect(eq(DEVICE_URL), any(BluetoothAddressType.class));
        verify(bluegigaHandler).getServices(CONNECTION_HANDLE);
        verify(bluegigaHandler).getCharacteristics(CONNECTION_HANDLE);
        verify(bluegigaHandler).getDeclarations(CONNECTION_HANDLE);
        verify(booleanNotification).notify(true);
        assertEquals(CONNECTION_HANDLE, bluegigaDevice.getConnectionHandle());
    }

    @Test
    public void testConnectAddressType() {
        bluegigaDevice.enableConnectedNotifications(booleanNotification);
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(eq(DEVICE_URL), any())).thenReturn(event);

        assertTrue(bluegigaDevice.connect());
        // if address type is unknown, we are trying to guess that it is public first
        verify(bluegigaHandler).connect(DEVICE_URL, BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC);
        bluegigaDevice.disconnect();

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100,
                BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC));
        assertTrue(bluegigaDevice.connect());
        verify(bluegigaHandler, times(2)).connect(DEVICE_URL, BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC);
        bluegigaDevice.disconnect();

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100,
                BluetoothAddressType.GAP_ADDRESS_TYPE_RANDOM));
        assertTrue(bluegigaDevice.connect());
        verify(bluegigaHandler).connect(DEVICE_URL, BluetoothAddressType.GAP_ADDRESS_TYPE_RANDOM);
        bluegigaDevice.disconnect();
    }

    @Test
    public void testConnectAddressTypeRetryBluegigaTimeoutException() {
        assertConnectAddressTypeRetry(BluegigaTimeoutException.class);
    }

    @Test
    public void testConnectAddressTypeRetryBluegigaProcedureException() {
        assertConnectAddressTypeRetry(BluegigaProcedureException.class);
    }

    @Test
    public void testConnectStopDiscovery() {
        // this test not only perform testing of the connection procedure but also includes testing for the workaround
        // of a Bluegiga bug, read some comments in the BluegigaDevice.connect method.

        when(bluegigaHandler.isDiscovering()).thenReturn(true);

        // mocking some stuff for the connection procedure
        // performing the invocation
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(DEVICE_URL, BluetoothAddressType.UNKNOWN)).thenReturn(event);

        assertTrue(bluegigaDevice.connect());

        InOrder inOrder = Mockito.inOrder(bluegigaHandler);
        inOrder.verify(bluegigaHandler).isDiscovering();
        inOrder.verify(bluegigaHandler).connect(DEVICE_URL, BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC);
        inOrder.verify(bluegigaHandler).bgStopProcedure();
        inOrder.verify(bluegigaHandler).bgStartScanning();
    }

    @Test
    public void testGetBluetoothClass() {
        assertEquals(0, bluegigaDevice.getBluetoothClass());
        int[] eir = {2, EirDataType.EIR_DEVICE_CLASS.getKey(), 10};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, eir));
        assertEquals(10, bluegigaDevice.getBluetoothClass());
    }

    @Test
    public void testGetName() {
        assertEquals(DEVICE_URL.getDeviceAddress(), bluegigaDevice.getName());
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100));
        assertEquals("Smartlock", bluegigaDevice.getName());
    }

    @Test
    public void testGetAddressType() {
        assertEquals(org.sputnikdev.bluetooth.manager.BluetoothAddressType.UNKNOWN, bluegigaDevice.getAddressType());

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100,
                BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC));
        assertEquals(org.sputnikdev.bluetooth.manager.BluetoothAddressType.PUBLIC, bluegigaDevice.getAddressType());

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100,
                BluetoothAddressType.GAP_ADDRESS_TYPE_RANDOM));
        assertEquals(org.sputnikdev.bluetooth.manager.BluetoothAddressType.RANDOM, bluegigaDevice.getAddressType());
    }

    @Test
    public void testGetSetAlias() {
        // Aliases are not supported by Bluegiga, but we use just a variable to cache it
        assertNull(bluegigaDevice.getAlias());
        bluegigaDevice.setAlias("alias");
        assertEquals("alias", bluegigaDevice.getAlias());
        verifyNoMoreInteractions(bluegigaHandler);
    }

    @Test
    public void testIsBleEnabled() {
        // a packet without EIR flags
        int[] eir = {10, 8, 83, 109, 97, 114, 116, 108, 111, 99, 107};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, eir));
        assertFalse(bluegigaDevice.isBleEnabled());

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, EIR_SMARTLOCK_PACKET));
        assertTrue(bluegigaDevice.isBleEnabled());
    }

    @Test
    public void testServiceResolvedData() {


        assertTrue(bluegigaDevice.getServiceData().isEmpty());
        bluegigaDevice.enableServiceDataNotifications(serviceDataNotification);

        int[] eir16 = {/* length */ 0x04, /* service data 16 bit UUID*/ 0x16, 0x0F, 0x18, 0x45};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, eir16));

        Map<String, byte[]> serviceData = bluegigaDevice.getServiceData();
        Map<String, byte[]> notified = serviceDataCaptor.getValue();
        assertEquals(1, serviceData.size());
        assertEquals(1, notified.size());
        assertTrue(serviceData.containsKey("0000180f-0000-1000-8000-00805f9b34fb"));
        assertTrue(notified.containsKey("0000180f-0000-1000-8000-00805f9b34fb"));
        assertArrayEquals(new byte[] {0x45}, serviceData.get("0000180f-0000-1000-8000-00805f9b34fb"));
        assertArrayEquals(new byte[] {0x45}, notified.get("0000180f-0000-1000-8000-00805f9b34fb"));

        int[] eir32 = {/* length */ 0xa,
                /* service data 32 bit UUID*/ 0x20,
                /* UUID */ 0x10, 0x8e, 0xe7, 0x74,
                /* data */ 0x74, 0x01, 0x0d, 0x01, (byte) 0xec};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, eir32));
        serviceData = bluegigaDevice.getServiceData();
        notified = serviceDataCaptor.getAllValues().get(1);
        assertEquals(2, serviceData.size());
        assertEquals(2, notified.size());
        assertTrue(serviceData.containsKey("74e78e10-0000-1000-8000-00805f9b34fb"));
        assertTrue(notified.containsKey("74e78e10-0000-1000-8000-00805f9b34fb"));
        assertArrayEquals(new byte[] {0x74, 0x01, 0x0d, 0x01, (byte) 0xec},
                serviceData.get("74e78e10-0000-1000-8000-00805f9b34fb"));
        assertArrayEquals(new byte[] {0x74, 0x01, 0x0d, 0x01, (byte) 0xec},
                notified.get("74e78e10-0000-1000-8000-00805f9b34fb"));

        int[] eir128 = {/* length */ 0x16,
                /* service data 128 bit UUID*/ 0x21,
                /* UUID */ 0x6d, 0x66, 0x70, 0x44, 0x73, 0x66, 0x62, 0x75, 0x66, 0x45, 0x76, 0x64, 0x55, 0xaa, 0x6c, 0x22,
                /* data */ 0x74, 0x01, 0x0d, 0x01, (byte) 0xec};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, eir128));
        serviceData = bluegigaDevice.getServiceData();
        notified = serviceDataCaptor.getAllValues().get(2);
        assertEquals(3, serviceData.size());
        assertEquals(3, notified.size());
        assertTrue(serviceData.containsKey("226caa55-6476-4566-7562-66734470666d"));
        assertTrue(notified.containsKey("226caa55-6476-4566-7562-66734470666d"));
        assertArrayEquals(new byte[] {0x74, 0x01, 0x0d, 0x01, (byte) 0xec},
                serviceData.get("226caa55-6476-4566-7562-66734470666d"));
        assertArrayEquals(new byte[] {0x74, 0x01, 0x0d, 0x01, (byte) 0xec},
                notified.get("226caa55-6476-4566-7562-66734470666d"));

        assertTrue(serviceData.containsKey("0000180f-0000-1000-8000-00805f9b34fb"));
        assertTrue(serviceData.containsKey("74e78e10-0000-1000-8000-00805f9b34fb"));
        assertTrue(serviceData.containsKey("226caa55-6476-4566-7562-66734470666d"));
    }

    @Test
    public void testGetManufacturerData() {
        bluegigaDevice.enableManufacturerDataNotifications(manufacturerDataNotification);
        assertTrue(bluegigaDevice.getManufacturerData().isEmpty());
        int[] data = {/* length */ 0x05,
                /* manufacturer data ID */ 0xFF,
                /* manufacturer ID */ 0xFF, 0x02,
                /* data */ 0x00, 0xFF};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, data));

        Map<Short, byte[]> manufacturerData = bluegigaDevice.getManufacturerData();
        Map<Short, byte[]> notified = manufacturerDataCaptor.getValue();
        assertEquals(1, manufacturerData.size());
        assertEquals(1, notified.size());
        assertTrue(manufacturerData.containsKey((short) 0x02FF));
        assertTrue(notified.containsKey((short) 0x02FF));
        assertArrayEquals(new byte[]{0x00, (byte) 0xFF}, manufacturerData.get((short) 0x02FF));
        assertArrayEquals(new byte[]{0x00, (byte) 0xFF}, notified.get((short) 0x02FF));
    }

    @Test
    public void testBlocked() {
        // blicking is not supported by Bluegiga
        bluegigaDevice.enableBlockedNotifications(null);
        bluegigaDevice.disableBlockedNotifications();
        bluegigaDevice.setBlocked(true);
        assertFalse(bluegigaDevice.isBlocked());
    }

    @Test
    public void testGetRSSI() {
        doReturn(false).when(bluegigaDevice).isConnected();

        assertEquals(0, bluegigaDevice.getRSSI());
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((byte) -88));
        assertEquals(-88, bluegigaDevice.getRSSI());

        Whitebox.setInternalState(bluegigaDevice, "lastDiscovered",
            Instant.now().minusSeconds(BluegigaDevice.DISCOVERY_TIMEOUT + 1));
        assertEquals(0, bluegigaDevice.getRSSI());

        verifyNoMoreInteractions(bluegigaHandler);

        when(bluegigaHandler.bgGetRssi(anyInt())).thenReturn((short) -77);
        bluegigaDevice.connect();

        assertEquals(-77, bluegigaDevice.getRSSI());
    }

    @Test
    public void testRSSINotifications() {
        Notification<Short> notification = (Notification<Short>) mock(Notification.class);
        doThrow(RuntimeException.class).when(notification).notify(anyShort());
        bluegigaDevice.enableRSSINotifications(notification);

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -87));
        verify(notification).notify((short) -87);

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -77));
        verify(notification).notify((short) -77);

        bluegigaDevice.disableRSSINotifications();
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -66));
        verifyNoMoreInteractions(notification);
    }

    @Test
    public void testIsConnected() {
        when(bluegigaHandler.disconnect(CONNECTION_HANDLE)).thenReturn(mock(BlueGigaDisconnectedEvent.class));

        assertFalse(bluegigaDevice.isConnected());

        assertTrue(bluegigaDevice.connect());
        assertTrue(bluegigaDevice.isConnected());

        assertTrue(bluegigaDevice.disconnect());
        assertFalse(bluegigaDevice.isConnected());

        verify(connectionStatusEvent).getConnection();
        verify(bluegigaHandler).connect(DEVICE_URL, BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC);
        verify(bluegigaHandler).disconnect(CONNECTION_HANDLE);
    }

    @Test
    public void testIsConnectedInconsistency() {
        when(bluegigaHandler.disconnect(CONNECTION_HANDLE)).thenReturn(mock(BlueGigaDisconnectedEvent.class));

        assertFalse(bluegigaDevice.isConnected());

        assertTrue(bluegigaDevice.connect());
        assertTrue(bluegigaDevice.isConnected());

        when(connectionStatusEvent.getAddress()).thenReturn("wrong address");
        assertFalse(bluegigaDevice.isConnected());
    }

    @Test
    public void testConnectedNotifications() {
        doThrow(RuntimeException.class).when(booleanNotification).notify(anyBoolean());
        bluegigaDevice.enableConnectedNotifications(booleanNotification);

        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(DEVICE_URL, BluetoothAddressType.UNKNOWN)).thenReturn(event);

        bluegigaDevice.connect();
        verify(booleanNotification).notify(true);

        bluegigaDevice.disconnect();
        verify(booleanNotification).notify(false);

        bluegigaDevice.disableConnectedNotifications();
        bluegigaDevice.connect();
        bluegigaDevice.disconnect();

        verifyNoMoreInteractions(booleanNotification);
    }

    @Test
    public void testServicesResolvedNotification() {
        doThrow(RuntimeException.class).when(booleanNotification).notify(anyBoolean());
        bluegigaDevice.enableServicesResolvedNotifications(booleanNotification);

        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        bluegigaDevice.bluegigaEventReceived(event);
        verify(booleanNotification).notify(true);

        bluegigaDevice.disconnect();
        verify(booleanNotification).notify(false);

        bluegigaDevice.disableServicesResolvedNotifications();
        bluegigaDevice.bluegigaEventReceived(event);
        bluegigaDevice.disconnect();

        verifyNoMoreInteractions(booleanNotification);
    }

    @Test
    public void testGetURL() {
        assertEquals(DEVICE_URL, bluegigaDevice.getURL());
    }

    @Test
    public void testDispose() {
        bluegigaDevice.connect();
        bluegigaDevice.dispose();
        verify(bluegigaDevice).disconnect();
    }

    @Test
    public void testGetService() {
        assertFalse(bluegigaDevice.isConnected());
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        bluegigaDevice.bluegigaEventReceived(event);
        assertTrue(bluegigaDevice.isConnected());
        assertNotNull(bluegigaDevice.getService(BATTERY_SERVICE_URL));
    }

    @Test
    public void testBluegigaEventReceivedConnect() {
        Notification<Boolean> connectedNotification = mock(Notification.class);
        bluegigaDevice.enableConnectedNotifications(connectedNotification);
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();

        bluegigaDevice.bluegigaEventReceived(event);

        assertTrue(bluegigaDevice.isConnected());
        verify(connectedNotification).notify(true);
    }

    @Test
    public void testBluegigaEventReceivedDisconnect() {
        Notification<Boolean> servicesResovedNotification = mock(Notification.class);
        Notification<Boolean> connectedNotification = mock(Notification.class);

        bluegigaDevice.enableConnectedNotifications(connectedNotification);
        bluegigaDevice.enableServicesResolvedNotifications(servicesResovedNotification);

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100));
        assertEquals(-100, bluegigaDevice.getRSSI());

        // connecting
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        bluegigaDevice.bluegigaEventReceived(event);

        assertTrue(bluegigaDevice.isConnected());

        BlueGigaDisconnectedEvent disconnectedEvent = mock(BlueGigaDisconnectedEvent.class);
        bluegigaDevice.bluegigaEventReceived(disconnectedEvent);
        assertTrue(bluegigaDevice.isConnected());
        verify(connectedNotification, never()).notify(false);
        verify(servicesResovedNotification, never()).notify(false);

        when(disconnectedEvent.getConnection()).thenReturn(CONNECTION_HANDLE);
        when(disconnectedEvent.getReason()).thenReturn(BgApiResponse.UNKNOWN);
        bluegigaDevice.bluegigaEventReceived(disconnectedEvent);
        assertFalse(bluegigaDevice.isConnected());
        verify(connectedNotification).notify(false);
        verify(servicesResovedNotification).notify(false);

        // next events must be disregarded
        bluegigaDevice.bluegigaEventReceived(disconnectedEvent);
        assertFalse(bluegigaDevice.isConnected());
        verify(connectedNotification).notify(false);
        verify(servicesResovedNotification).notify(false);
    }

    @Test
    public void testBluegigaEventReceivedNotConnectedException() {
        Notification<Boolean> connectedNotification = mock(Notification.class);
        bluegigaDevice.enableConnectedNotifications(connectedNotification);

        BluegigaProcedureException ex = new BluegigaProcedureException("error", BgApiResponse.NOT_CONNECTED);
        doThrow(ex).when(bluegigaDevice).discoverAttributes();

        // connecting
        bluegigaDevice.connect();
        assertTrue(bluegigaDevice.isConnected());
        verify(connectedNotification).notify(true);

        // receiving an event
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        bluegigaDevice.bluegigaEventReceived(event);

        assertFalse(bluegigaDevice.isConnected());
        verify(connectedNotification).notify(false);
    }

    @Test
    public void testBluegigaEventReceivedUnexpectedException() {
        Notification<Boolean> connectedNotification = mock(Notification.class);
        bluegigaDevice.enableConnectedNotifications(connectedNotification);

        doThrow(RuntimeException.class).when(bluegigaDevice).discoverAttributes();

        // connecting
        bluegigaDevice.connect();
        assertTrue(bluegigaDevice.isConnected());
        verify(connectedNotification).notify(true);

        // receiving an event, device is still connected
        BlueGigaConnectionStatusEvent eventConnected = mockConnectionStatusEvent();
        BlueGigaConnectionStatusEvent statusEvent = mockConnectionStatusEvent();
        when(statusEvent.getAddress()).thenReturn(DEVICE_URL.getDeviceAddress());
        when(bluegigaHandler.getConnectionStatus(CONNECTION_HANDLE)).thenReturn(statusEvent);
        bluegigaDevice.bluegigaEventReceived(eventConnected);

        assertTrue(bluegigaDevice.isConnected());
        verify(connectedNotification, never()).notify(false);

        // receiving an event, device is not connected
        eventConnected = mockConnectionStatusEvent();
        statusEvent = mockConnectionStatusEvent();
        when(statusEvent.getAddress()).thenReturn("wrong address");
        when(bluegigaHandler.getConnectionStatus(CONNECTION_HANDLE)).thenReturn(statusEvent);
        bluegigaDevice.bluegigaEventReceived(eventConnected);

        assertFalse(bluegigaDevice.isConnected());
        verify(connectedNotification).notify(false);
    }

    @Test
    public void testGetTxPower() {
        assertEquals(0, bluegigaDevice.getTxPower());
        int[] eir = {2, EirDataType.EIR_TXPOWER.getKey(), -60};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, eir));
        assertEquals(-60, bluegigaDevice.getTxPower());
    }

    private void assertServices() {
        List<Service> services = bluegigaDevice.getServices();
        assertEquals(2, services.size());
        Map<URL, Service> serviceMap =
            services.stream().collect(Collectors.toMap(BluetoothObject::getURL, Function.identity()));
        assertTrue(serviceMap.containsKey(BATTERY_SERVICE_URL));
        assertTrue(serviceMap.containsKey(TX_POWER_SERVICE_URL));

        verify(bluegigaHandler).getServices(CONNECTION_HANDLE);
    }

    private void assertCharacteristics() {
        Map<URL, Service> serviceMap = bluegigaDevice.getServices().stream().collect(
            Collectors.toMap(BluetoothObject::getURL, Function.identity()));
        List<Characteristic> characteristics = serviceMap.get(BATTERY_SERVICE_URL).getCharacteristics();
        assertEquals(1, characteristics.size());
        assertEquals(BATTERY_LEVEL_CHARACTERISTIC_URL, characteristics.get(0).getURL());
        characteristics = serviceMap.get(TX_POWER_SERVICE_URL).getCharacteristics();
        assertEquals(1, characteristics.size());
        assertEquals(TX_POWER_LEVEL_CHARACTERISTIC_URL, characteristics.get(0).getURL());
    }

    private void assertDescriptors() {
        Map<URL, BluegigaService> serviceMap = (Map) bluegigaDevice.getServices().stream().collect(
            Collectors.toMap(BluetoothObject::getURL, Function.identity()));

        List<BluegigaCharacteristic> characteristics = (List) serviceMap.get(BATTERY_SERVICE_URL).getCharacteristics();
        Set<CharacteristicAccessType> accessTypes = characteristics.get(0).getFlags();
        assertEquals(2, accessTypes.size());
        assertTrue(accessTypes.contains(CharacteristicAccessType.NOTIFY));
        assertTrue(accessTypes.contains(CharacteristicAccessType.READ));
    }

    private BlueGigaGroupFoundEvent mockServiceEvent(URL url, int startHandle, int endHandle) {
        BlueGigaGroupFoundEvent event = mock(BlueGigaGroupFoundEvent.class);
        when(event.getUuid()).thenReturn(UUID.fromString(url.getServiceUUID()));
        when(event.getStart()).thenReturn(startHandle);
        when(event.getEnd()).thenReturn(endHandle);
        return event;
    }

    private BlueGigaFindInformationFoundEvent mockCharacteristicEvent(URL url, int handle) {
        BlueGigaFindInformationFoundEvent event = mock(BlueGigaFindInformationFoundEvent.class);
        when(event.getUuid()).thenReturn(UUID.fromString(url.getCharacteristicUUID()));
        when(event.getChrHandle()).thenReturn(handle);
        return event;
    }

    private BlueGigaAttributeValueEvent mockDeclarationEvent(int characteristicHandle, int[] flags) {
        BlueGigaAttributeValueEvent event = mock(BlueGigaAttributeValueEvent.class);
        when(event.getValue()).thenReturn(flags);
        when(event.getAttHandle()).thenReturn(characteristicHandle);

        return event;
    }

    private BlueGigaScanResponseEvent mockScanResponse(short rssi) {
        return mockScanResponse(rssi, EIR_SMARTLOCK_PACKET);
    }

    private BlueGigaScanResponseEvent mockScanResponse(short rssi, BluetoothAddressType addressType) {
        return mockScanResponse(rssi, EIR_SMARTLOCK_PACKET, addressType);
    }

    private BlueGigaScanResponseEvent mockScanResponse(short rssi, int[] eir) {
        return mockScanResponse(rssi, eir, null);
    }

    private BlueGigaScanResponseEvent mockScanResponse(short rssi, int[] eir, BluetoothAddressType addressType) {
        BlueGigaScanResponseEvent event = mock(BlueGigaScanResponseEvent.class);
        when(event.getSender()).thenReturn(DEVICE_URL.getDeviceAddress());
        when(event.getData()).thenReturn(eir);
        when(event.getRssi()).thenReturn((int) rssi);
        when(event.getAddressType()).thenReturn(addressType);
        return event;
    }

    private BlueGigaConnectionStatusEvent mockConnectionStatusEvent() {
        BlueGigaConnectionStatusEvent event = mock(BlueGigaConnectionStatusEvent.class);
        when(event.getConnection()).thenReturn(CONNECTION_HANDLE);
        when(event.getAddress()).thenReturn(DEVICE_URL.getDeviceAddress());
        when(bluegigaHandler.connect(DEVICE_URL, BluetoothAddressType.UNKNOWN)).thenReturn(event);
        return event;
    }

    private void assertConnectAddressTypeRetry(Class<? extends Exception> expected) {
        doThrow(expected).when(bluegigaHandler).connect(eq(DEVICE_URL), any());
        try {
            bluegigaDevice.connect();
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getClass().isAssignableFrom(expected));
        }
        // if address type is unknown, we are trying to guess that it is public first
        verify(bluegigaHandler).connect(DEVICE_URL, BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC);
        // BluegigaTimeoutException has thrown, we try again but with random address now
        verify(bluegigaHandler).connect(DEVICE_URL, BluetoothAddressType.GAP_ADDRESS_TYPE_RANDOM);
    }

}
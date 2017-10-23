package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaGroupFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaConnectionStatusEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectedEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirDataType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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

    @Before
    public void setUp() {
        when(bluegigaHandler.isAlive()).thenReturn(true);

        bluegigaDevice = new BluegigaDevice(bluegigaHandler, DEVICE_URL);

        connectionStatusEvent = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(DEVICE_URL)).thenReturn(connectionStatusEvent);

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

        verify(bluegigaHandler).addEventListener(bluegigaDevice);

        bluegigaDevice = spy(bluegigaDevice);
    }

    @Test
    public void testConnectOrderOfOperations() throws Exception {
        assertTrue(bluegigaDevice.connect());

        InOrder inOrder = Mockito.inOrder(bluegigaDevice);
        inOrder.verify(bluegigaDevice).performConnection();
        inOrder.verify(bluegigaDevice).discoverServices();
        inOrder.verify(bluegigaDevice).discoverCharacteristics();
        inOrder.verify(bluegigaDevice).discoverDeclarations();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testConnect() throws Exception {
        bluegigaDevice.enableConnectedNotifications(booleanNotification);
        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(DEVICE_URL)).thenReturn(event);

        assertTrue(bluegigaDevice.connect());

        assertServices();

        assertCharacteristics();

        assertDescriptors();

        assertTrue(bluegigaDevice.isServicesResolved());

        verify(bluegigaHandler).connect(DEVICE_URL);
        verify(bluegigaHandler).getServices(CONNECTION_HANDLE);
        verify(bluegigaHandler).getCharacteristics(CONNECTION_HANDLE);
        verify(bluegigaHandler).getDeclarations(CONNECTION_HANDLE);
        verify(booleanNotification).notify(true);
        assertEquals(CONNECTION_HANDLE, bluegigaDevice.getConnectionHandle());
    }

    @Test
    public void disconnect() throws Exception {
    }

    @Test
    public void testGetBluetoothClass() throws Exception {
        assertEquals(0, bluegigaDevice.getBluetoothClass());
        int[] eir = {2, EirDataType.EIR_DEVICE_CLASS.getKey(), 10};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, eir));
        assertEquals(10, bluegigaDevice.getBluetoothClass());
    }

    @Test
    public void testGetName() throws Exception {
        assertEquals(DEVICE_URL.getDeviceAddress(), bluegigaDevice.getName());
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100));
        assertEquals("Smartlock", bluegigaDevice.getName());
    }

    @Test
    public void testAlias() throws Exception {
        //TODO use 2a00 characteristic to set/get device alias (if possible at all)
        bluegigaDevice.setAlias("test");
        assertNull(bluegigaDevice.getAlias());
    }

    @Test
    public void testIsBleEnabled() throws Exception {
        // a packet without EIR flags
        int[] eir = {10, 8, 83, 109, 97, 114, 116, 108, 111, 99, 107};
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, eir));
        assertFalse(bluegigaDevice.isBleEnabled());

        bluegigaDevice.bluegigaEventReceived(mockScanResponse((short) -100, EIR_SMARTLOCK_PACKET));
        assertTrue(bluegigaDevice.isBleEnabled());
    }

    @Test
    public void testBlocked() throws Exception {
        // blicking is not supported by Bluegiga
        bluegigaDevice.enableBlockedNotifications(null);
        bluegigaDevice.disableBlockedNotifications();
        bluegigaDevice.setBlocked(true);
        assertFalse(bluegigaDevice.isBlocked());
    }

    @Test
    public void testGetRSSI() throws Exception {
        doReturn(false).when(bluegigaDevice).isConnected();

        assertEquals(0, bluegigaDevice.getRSSI());
        bluegigaDevice.bluegigaEventReceived(mockScanResponse((byte) -88));
        assertEquals(-88, bluegigaDevice.getRSSI());

        Whitebox.setInternalState(bluegigaDevice, "lastDiscovered",
            Instant.now().minusSeconds(BluegigaDevice.DISCOVERY_TIMEOUT + 1));
        assertEquals(0, bluegigaDevice.getRSSI());

        verifyNoMoreInteractions(bluegigaHandler);

        when(bluegigaHandler.bgGetRssi(anyInt())).thenReturn((short) -77);
        doReturn(true).when(bluegigaDevice).isConnected();

        assertEquals(-77, bluegigaDevice.getRSSI());
    }

    @Test
    public void testRSSINotifications() throws Exception {
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
    public void testIsConnected() throws Exception {

        when(bluegigaHandler.disconnect(CONNECTION_HANDLE)).thenReturn(mock(BlueGigaDisconnectedEvent.class));

        assertFalse(bluegigaDevice.isConnected());

        assertTrue(bluegigaDevice.connect());
        assertTrue(bluegigaDevice.isConnected());

        assertTrue(bluegigaDevice.disconnect());
        assertFalse(bluegigaDevice.isConnected());

        verify(connectionStatusEvent).getConnection();
        verify(bluegigaHandler).connect(DEVICE_URL);
        verify(bluegigaHandler).disconnect(CONNECTION_HANDLE);
    }

    @Test
    public void testConnectedNotifications() throws Exception {
        doThrow(RuntimeException.class).when(booleanNotification).notify(anyBoolean());
        bluegigaDevice.enableConnectedNotifications(booleanNotification);

        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(DEVICE_URL)).thenReturn(event);

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
    public void testServicesResolvedNotification() throws Exception {
        doThrow(RuntimeException.class).when(booleanNotification).notify(anyBoolean());
        bluegigaDevice.enableServicesResolvedNotifications(booleanNotification);

        BlueGigaConnectionStatusEvent event = mockConnectionStatusEvent();
        when(bluegigaHandler.connect(DEVICE_URL)).thenReturn(event);

        bluegigaDevice.connect();
        verify(booleanNotification).notify(true);

        bluegigaDevice.disconnect();
        verify(booleanNotification).notify(false);

        bluegigaDevice.disableServicesResolvedNotifications();
        bluegigaDevice.connect();
        bluegigaDevice.disconnect();

        verifyNoMoreInteractions(booleanNotification);
    }

    @Test
    public void getServices() throws Exception {
    }

    @Test
    public void getURL() throws Exception {
        assertEquals(DEVICE_URL, bluegigaDevice.getURL());
    }

    @Test
    public void dispose() throws Exception {
    }

    @Test
    public void getService() throws Exception {
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

    private BlueGigaScanResponseEvent mockScanResponse(short rssi, int[] eir) {
        BlueGigaScanResponseEvent event = mock(BlueGigaScanResponseEvent.class);
        when(event.getSender()).thenReturn(DEVICE_URL.getDeviceAddress());
        when(event.getData()).thenReturn(eir);
        when(event.getRssi()).thenReturn((int) rssi);
        return event;
    }

    private BlueGigaConnectionStatusEvent mockConnectionStatusEvent() {
        BlueGigaConnectionStatusEvent event = mock(BlueGigaConnectionStatusEvent.class);
        when(event.getConnection()).thenReturn(CONNECTION_HANDLE);
        when(bluegigaHandler.connect(DEVICE_URL)).thenReturn(event);
        return event;
    }



}
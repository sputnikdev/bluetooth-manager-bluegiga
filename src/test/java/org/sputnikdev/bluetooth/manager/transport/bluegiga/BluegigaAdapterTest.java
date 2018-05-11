package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.internal.util.reflection.Whitebox;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.List;

import static junit.framework.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
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
public class BluegigaAdapterTest {

    private static final String PORT_NAME = "/dev/ttyACM1";
    private static final URL ADAPTER_URL = new URL("12:34:56:78:90:12", null);
    private static final String ADAPTER_NAME = "Bluegiga Adapter";
    private static final int ADAPTER_MAJOR_VERSION = 1;
    private static final int ADAPTER_MINOR_VERSION = 3;

    @Mock
    private BluegigaHandler bluegigaHandler;
    @Mock
    private BlueGigaGetInfoResponse info;
    @Mock
    private Notification<Boolean> discoveringNotification;

    private BluegigaAdapter bluegigaAdapter;

    @Before
    public void setUp() {

        when(bluegigaHandler.getAdapterAddress()).thenReturn(ADAPTER_URL);
        when(bluegigaHandler.getPortName()).thenReturn(PORT_NAME);
        when(bluegigaHandler.isAlive()).thenReturn(true);

        when(info.getMajor()).thenReturn(ADAPTER_MAJOR_VERSION);
        when(info.getMinor()).thenReturn(ADAPTER_MINOR_VERSION);

        when(bluegigaHandler.bgGetInfo()).thenReturn(info);

        bluegigaAdapter = BluegigaAdapter.create(bluegigaHandler);


        verify(bluegigaHandler).bgGetInfo();
        verify(bluegigaHandler).addEventListener(bluegigaAdapter);

        bluegigaAdapter = spy(bluegigaAdapter);

        doAnswer(invocation -> {
            invocation.getArgumentAt(0, Runnable.class).run();
            return null;
        }).when(bluegigaHandler).runInSynchronizedContext(any(Runnable.class));
    }

    @Test
    public void testGetPortName() throws Exception {
        assertEquals(PORT_NAME, bluegigaAdapter.getPortName());
        verify(bluegigaHandler).getPortName();
    }

    @Test
    public void testIsAlive() throws Exception {
        assertTrue(bluegigaAdapter.isAlive());
        verify(bluegigaHandler).isAlive();
    }

    @Test
    public void testGetName() throws Exception {
        assertEquals(BluegigaAdapter.BLUEGIGA_NAME + " v" + ADAPTER_MAJOR_VERSION + "." + ADAPTER_MINOR_VERSION,
            bluegigaAdapter.getName());

        Whitebox.setInternalState(bluegigaAdapter, "info", null);

        assertEquals(BluegigaAdapter.BLUEGIGA_NAME, bluegigaAdapter.getName());
    }

    @Test
    public void testGetSetAlias() throws Exception {
        // Aliases are not supported by Bluegiga, but we use just a variable to cache it
        assertNull(bluegigaAdapter.getAlias());
        bluegigaAdapter.setAlias("alias");
        assertEquals("alias", bluegigaAdapter.getAlias());
        verifyNoMoreInteractions(bluegigaHandler);
    }

    @Test
    public void testStartStopDiscovering() throws Exception {
        when(bluegigaHandler.bgStartScanning()).thenReturn(false).thenReturn(true);
        when(bluegigaHandler.bgStopProcedure()).thenReturn(false).thenReturn(true);

        bluegigaAdapter.enableDiscoveringNotifications(discoveringNotification);

        bluegigaAdapter.startDiscovery();
        verify(discoveringNotification, never()).notify(true);
        assertFalse(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler).bgStartScanning();

        bluegigaAdapter.startDiscovery();
        verify(discoveringNotification).notify(true);
        assertTrue(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler, times(2)).bgStartScanning();

        bluegigaAdapter.stopDiscovery();
        verify(discoveringNotification).notify(false);
        assertFalse(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler).bgStopProcedure();

        bluegigaAdapter.disableDiscoveringNotifications();
        bluegigaAdapter.startDiscovery();
        assertTrue(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler, times(3)).bgStartScanning();

        bluegigaAdapter.stopDiscovery();
        assertFalse(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler, times(2)).bgStopProcedure();

        verifyNoMoreInteractions(discoveringNotification);
    }

    @Test
    public void testNotifyDiscoveringWithException() {
        when(bluegigaHandler.bgStartScanning()).thenReturn(true);

        Notification<Boolean> notification = mock(Notification.class);
        doThrow(IllegalStateException.class).when(notification).notify(true);

        bluegigaAdapter.enableDiscoveringNotifications(notification);

        bluegigaAdapter.startDiscovery();
        verify(notification).notify(true);
    }

    @Test
    public void testPowered() throws Exception {
        // Setting adapter powered state is not supported by Bluegiga
        bluegigaAdapter.enablePoweredNotifications(new Notification<Boolean>() {
            @Override
            public void notify(Boolean aBoolean) { }
        });

        assertTrue(bluegigaAdapter.isPowered());
        bluegigaAdapter.setPowered(false);
        assertTrue(bluegigaAdapter.isPowered());

        bluegigaAdapter.disablePoweredNotifications();

        verify(bluegigaHandler, times(2)).checkAlive();

        verifyNoMoreInteractions(bluegigaHandler);
    }

    @Test
    public void testGetDevices() throws Exception {
        String deviceAddress = "11:22:33:44:55:66";

        assertTrue(bluegigaAdapter.getDevices().isEmpty());

        bluegigaAdapter.bluegigaEventReceived(mockDevice(deviceAddress));

        List<Device> devices = bluegigaAdapter.getDevices();
        assertEquals(1, devices.size());
        assertEquals(ADAPTER_URL.copyWithDevice(deviceAddress), devices.get(0).getURL());
    }

    @Test
    public void testGetURL() throws Exception {
        assertEquals(ADAPTER_URL, bluegigaAdapter.getURL());
        verify(bluegigaHandler).getAdapterAddress();
    }

    @Test
    public void testDispose() throws Exception {
        URL deviceURL = ADAPTER_URL.copyWithDevice("11:22:33:44:55:66");
        BluegigaDevice device = mock(BluegigaDevice.class);
        when(device.getURL()).thenReturn(deviceURL);
        doReturn(device).when(bluegigaAdapter).createDevice(deviceURL);
        bluegigaAdapter.bluegigaEventReceived(mockDevice(deviceURL.getDeviceAddress()));
        bluegigaAdapter.dispose();
        verify(bluegigaHandler).removeEventListener(bluegigaAdapter);
        verify(bluegigaHandler).dispose();
        verify(bluegigaAdapter).stopDiscovery();
        verify(device).dispose();
    }

    @Test
    public void testBluegigaEventReceived() throws Exception {
        URL deviceURL = ADAPTER_URL.copyWithDevice("11:22:33:44:55:66");
        BluegigaDevice device = mock(BluegigaDevice.class);
        when(device.getURL()).thenReturn(deviceURL);
        when(device.getName()).thenReturn("device name");
        doReturn(device).when(bluegigaAdapter).createDevice(deviceURL);
        BlueGigaScanResponseEvent event = mockDevice(deviceURL.getDeviceAddress());

        bluegigaAdapter.bluegigaEventReceived(event);

        assertEquals(device, bluegigaAdapter.getDevices().get(0));

        verify(device).bluegigaEventReceived(event);
        verify(bluegigaAdapter).createDevice(deviceURL);
    }

    @Test
    public void testGetDevice() throws Exception {
        String existingDeviceAddress = "11:22:33:44:55:66";
        String newDeviceAddress = "11:11:11:11:11:11";

        bluegigaAdapter.bluegigaEventReceived(mockDevice(existingDeviceAddress));

        Device existingDevice = bluegigaAdapter.getDevice(ADAPTER_URL.copyWithDevice(existingDeviceAddress));
        assertNotNull(existingDevice);
        assertEquals(existingDeviceAddress, existingDevice.getURL().getDeviceAddress());

        Device newDevice = bluegigaAdapter.getDevice(ADAPTER_URL.copyWithDevice(newDeviceAddress));
        assertNull(newDevice);
    }

    private BlueGigaScanResponseEvent mockDevice(String address) {
        BlueGigaScanResponseEvent scanEvent = mock(BlueGigaScanResponseEvent.class);
        when(scanEvent.getSender()).thenReturn(address);
        return scanEvent;
    }

}
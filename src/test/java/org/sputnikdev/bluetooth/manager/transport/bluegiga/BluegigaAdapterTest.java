package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.internal.util.reflection.Whitebox;
import org.mockito.runners.MockitoJUnitRunner;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
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

        bluegigaAdapter = new BluegigaAdapter(bluegigaHandler);

        verify(bluegigaHandler).bgGetInfo();
        verify(bluegigaHandler).addEventListener(bluegigaAdapter);
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
        // Aliases are not supported by Bluegiga
        bluegigaAdapter.setAlias("alias");
        assertNull(bluegigaAdapter.getAlias());
        verifyNoMoreInteractions(bluegigaHandler);
    }

    @Test
    public void testStartStopDiscovering() throws Exception {
        when(bluegigaHandler.bgStartScanning(true)).thenReturn(false).thenReturn(true);
        when(bluegigaHandler.bgStopProcedure()).thenReturn(false).thenReturn(true);

        bluegigaAdapter.enableDiscoveringNotifications(discoveringNotification);

        bluegigaAdapter.startDiscovery();
        verify(discoveringNotification, never()).notify(true);
        assertFalse(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler).bgStartScanning(true);

        bluegigaAdapter.startDiscovery();
        verify(discoveringNotification).notify(true);
        assertTrue(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler, times(2)).bgStartScanning(true);

        bluegigaAdapter.stopDiscovery();
        verify(discoveringNotification, never()).notify(false);
        assertTrue(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler).bgStopProcedure();

        bluegigaAdapter.stopDiscovery();
        verify(discoveringNotification).notify(false);
        assertFalse(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler, times(2)).bgStopProcedure();

        bluegigaAdapter.disableDiscoveringNotifications();
        bluegigaAdapter.startDiscovery();
        assertTrue(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler, times(3)).bgStartScanning(true);

        bluegigaAdapter.stopDiscovery();
        assertFalse(bluegigaAdapter.isDiscovering());
        verify(bluegigaHandler, times(3)).bgStopProcedure();

        verifyNoMoreInteractions(discoveringNotification, bluegigaHandler);
    }

    @Test
    public void testSetPowered() throws Exception {
    }

    @Test
    public void testIsPowered() throws Exception {
    }

    @Test
    public void testEnablePoweredNotifications() throws Exception {
    }

    @Test
    public void testDisablePoweredNotifications() throws Exception {
    }

    @Test
    public void testGetDevices() throws Exception {
    }

    @Test
    public void testGetURL() throws Exception {
    }

    @Test
    public void testDispose() throws Exception {
    }

    @Test
    public void testBluegigaEventReceived() throws Exception {
    }

    @Test
    public void testGetDevice() throws Exception {
    }

}
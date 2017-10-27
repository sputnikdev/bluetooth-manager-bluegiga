package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaProcedureCompletedEvent;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;

import java.util.EnumSet;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class BluegigaDescriptorTest {

    private static final URL DESCRIPTOR_URL = new URL("/12:34:56:78:90:12/11:22:33:44:55:66/" +
        "0000180f-0000-1000-8000-00805f9b34fb/00002902-0000-1000-8000-00805f9b34fb");
    private static final int CONNECTION_HANDLE = 1;
    private static final int DESCRIPTOR_HANDLE = 3;

    @Mock
    private BluegigaHandler bluegigaHandler;

    private BluegigaDescriptor descriptor;

    @Before
    public void setUp() {
        descriptor = new BluegigaDescriptor(bluegigaHandler, DESCRIPTOR_URL, CONNECTION_HANDLE, DESCRIPTOR_HANDLE);
    }

    @Test
    public void readValue() throws Exception {
        int[] data = {0b01};
        BlueGigaAttributeValueEvent event = mock(BlueGigaAttributeValueEvent.class);
        when(event.getValue()).thenReturn(data);

        when(bluegigaHandler.readCharacteristic(CONNECTION_HANDLE, DESCRIPTOR_HANDLE)).thenReturn(event);

        assertArrayEquals(new byte[] {0b01}, descriptor.readValue());

        verify(bluegigaHandler).readCharacteristic(CONNECTION_HANDLE, DESCRIPTOR_HANDLE);
    }

    @Test
    public void writeValue() throws Exception {
        byte[] byteData = {12, 34};
        int[] intData = {12, 34};

        BlueGigaProcedureCompletedEvent event = mock(BlueGigaProcedureCompletedEvent.class);
        when(event.getResult()).thenReturn(BgApiResponse.SUCCESS);

        when(bluegigaHandler.writeCharacteristic(
            CONNECTION_HANDLE, DESCRIPTOR_HANDLE, intData)).thenReturn(event);

        assertTrue(descriptor.writeValue(byteData));

        when(event.getResult()).thenReturn(BgApiResponse.TIMEOUT);

        assertFalse(descriptor.writeValue(byteData));

        verify(bluegigaHandler, times(2))
            .writeCharacteristic(CONNECTION_HANDLE, DESCRIPTOR_HANDLE, intData);
    }

    @Test
    public void getURL() throws Exception {
        assertEquals(DESCRIPTOR_URL, descriptor.getURL());
    }

    @Test
    public void dispose() throws Exception {
        descriptor.dispose();
    }

}
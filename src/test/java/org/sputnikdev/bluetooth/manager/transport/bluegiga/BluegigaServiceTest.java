package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class BluegigaServiceTest {

    private static final URL SERVICE_URL =
        new URL("/12:34:56:78:90:12/11:22:33:44:55:66/0000180f-0000-1000-8000-00805f9b34fb");
    private static final URL CHARACTERISTIC_URL =
        SERVICE_URL.copyWithCharacteristic("00002a19-0000-1000-8000-00805f9b34fb");
    private static final int HANDLE_START = 1;
    private static final int HANDLE_END = 10;

    @Mock
    BluegigaCharacteristic characteristic;

    @InjectMocks
    private BluegigaService bluegigaService = new BluegigaService(SERVICE_URL, HANDLE_START, HANDLE_END);

    @Before
    public void setUp() {
        when(characteristic.getURL()).thenReturn(CHARACTERISTIC_URL);
    }

    @Test
    public void testGetURL() throws Exception {
        assertEquals(SERVICE_URL, bluegigaService.getURL());
    }

    @Test
    public void testAddGetCharacteristic() throws Exception {
        bluegigaService.addCharacteristic(characteristic);

        List<Characteristic> chars = bluegigaService.getCharacteristics();
        assertEquals(1, chars.size());
        assertEquals(CHARACTERISTIC_URL, chars.get(0).getURL());
    }

    @Test
    public void testGetCharacteristic() throws Exception {
        bluegigaService.addCharacteristic(characteristic);

        assertEquals(CHARACTERISTIC_URL, bluegigaService.getCharacteristic(CHARACTERISTIC_URL).getURL());
    }

    @Test
    public void testFindCharacteristicByShortUUID() throws Exception {
        bluegigaService.addCharacteristic(characteristic);

        assertEquals(CHARACTERISTIC_URL, bluegigaService.findCharacteristicByShortUUID("2a19").getURL());
    }

    @Test
    public void testGetHandleStart() throws Exception {
        assertEquals(HANDLE_START, bluegigaService.getHandleStart());
    }

    @Test
    public void testGetHandleEnd() throws Exception {
        assertEquals(HANDLE_END, bluegigaService.getHandleEnd());
    }

}
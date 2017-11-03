package org.sputnikdev.bluetooth.manager.transport.bluegiga;

import org.junit.Ignore;
import org.junit.Test;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Device;

import java.io.UnsupportedEncodingException;

import static org.junit.Assert.*;

@Ignore
public class BluegigaAdapterIntegrationTest {

    @Test
    public void testCreateAdapter() throws UnsupportedEncodingException, InterruptedException {
        String portName = "/dev/tty.usbmodem1";
        BluegigaAdapter bluetoothAdapter = BluegigaAdapter.create(BluegigaHandler.create(portName));
        assertTrue(bluetoothAdapter.isAlive());
        assertEquals(portName, bluetoothAdapter.getPortName());
        assertEquals(new URL("bluegiga://88:6B:0F:01:90:CA"), bluetoothAdapter.getURL());
        assertEquals("Bluegiga v1.3", bluetoothAdapter.getName());
        assertNull(bluetoothAdapter.getAlias());
        assertTrue(bluetoothAdapter.isPowered());

        //bluetoothAdapter.startDiscovery();

        URL lockURL = bluetoothAdapter.getURL().copyWithDevice("CF:FC:9E:B2:0E:63");
        URL biscuitURL = bluetoothAdapter.getURL().copyWithDevice("E7:AC:07:C9:F7:3F");

//        while (bluetoothAdapter.getDevice(lockURL) == null
//                || bluetoothAdapter.getDevice(biscuitURL) == null) {
//            Thread.sleep(100);
//        }

        //bluetoothAdapter.stopDiscovery();

        for (Device device : bluetoothAdapter.getDevices()) {
            System.out.println(device.getURL().getDeviceAddress() + " " + device.getName() +
                    " " + device.getRSSI() + " " + device.isBleEnabled());
        }

        BluegigaDevice lock =
                bluetoothAdapter.getDevice(lockURL);
        BluegigaDevice biscuit =
                bluetoothAdapter.getDevice(biscuitURL);

        //assertEquals("Smartlock", lock.getName());

        assertTrue(lock.connect());
        assertTrue(lock.isConnected());

        assertTrue(biscuit.connect());
        assertTrue(biscuit.isConnected());

        assertTrue(lock.disconnect());
        assertFalse(lock.isConnected());

        assertTrue(biscuit.disconnect());
        assertFalse(biscuit.isConnected());

    }

}
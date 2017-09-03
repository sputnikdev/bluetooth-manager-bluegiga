package org.sputnikdev.bluetooth.manager.transport.bluegiga;

/*-
 * #%L
 * org.sputnikdev:bluetooth-manager
 * %%
 * Copyright (C) 2017 Sputnik Dev
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaCommand;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaEventListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaSerialHandler;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.*;
import com.zsmartsystems.bluetooth.bluegiga.command.system.*;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.*;
import gnu.io.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Adapter;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 *
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
public class BluegigaAdapter implements Adapter, BlueGigaEventListener {

    private static final String BLUEGIGA_NAME = "Bluegiga";

    private final Logger logger = LoggerFactory.getLogger(BluegigaAdapter.class);

    // The Serial port name
    private String portName;

    // The serial port.
    private SerialPort serialPort;

    // The serial port input stream.
    private InputStream inputStream;

    // The serial port output stream.
    private OutputStream outputStream;

    // The BlueGiga API handler
    private BlueGigaSerialHandler bgHandler;

    // Our BT address
    private URL url;

    private BlueGigaGetInfoResponse info;
    private boolean discovering;

    private static final int PASSIVE_SCAN_INTERVAL = 0x40;
    private static final int PASSIVE_SCAN_WINDOW = 0x08;

    private static final int ACTIVE_SCAN_INTERVAL = 0x40;
    private static final int ACTIVE_SCAN_WINDOW = 0x20;

    private final Map<URL, BluegigaDevice> devices = new HashMap<>();

    public BluegigaAdapter(String portName) {
        this.portName = portName;
        init();
    }

    public boolean isAlive() {
        return url != null && bgHandler != null && bgHandler.isAlive();
    }

    public String getPortName() {
        return portName;
    }

    @Override
    public String getName() {
        return BLUEGIGA_NAME + Optional.ofNullable(info).map(i -> " v" + i.getMajor() + "." + i.getMinor()).orElse("");
    }

    @Override
    public String getAlias() {
        return null;
    }

    @Override
    public void setAlias(String s) { }

    @Override
    public boolean isDiscovering() {
        return discovering;
    }

    @Override
    public void enableDiscoveringNotifications(Notification<Boolean> notification) {

    }

    @Override
    public void disableDiscoveringNotifications() {

    }

    @Override
    public boolean startDiscovery() {
        return bgStartScanning(true, ACTIVE_SCAN_INTERVAL, ACTIVE_SCAN_WINDOW);
    }

    @Override
    public boolean stopDiscovery() {
        return bgStopProcedure();
    }

    /*
       Note: It is unknown if it is possible to control "powered" state of BG dongles,
       can "USB Enable" be used (see BG spec)?
     */

    @Override
    public void setPowered(boolean b) { }

    @Override
    public boolean isPowered() {
        return true;
    }

    @Override
    public void enablePoweredNotifications(Notification<Boolean> notification) { }

    @Override
    public void disablePoweredNotifications() { }

    @Override
    public List<Device> getDevices() {
        return new ArrayList<>(devices.values());
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void dispose() {
        synchronized (devices) {
            devices.values().stream().forEach(BluegigaUtils::dispose);
            devices.clear();
        }

        if (bgHandler != null) {
            bgStopProcedure();
            closeAllConnections();
            bgHandler.close();
            bgHandler = null;
            url = null;
        }

        try {
            if (inputStream != null) {
                inputStream.close();
                outputStream.close();
                inputStream = null;
                outputStream = null;
            }
        } catch (IOException e) {
            logger.error("Could not close input/output stream", e);
        }

        if (serialPort != null) {
            // Note: this will fail with a SIGSEGV error on OSX:
            // Problematic frame: C  [librxtxSerial.jnilib+0x312f]  Java_gnu_io_RXTXPort_interruptEventLoop+0x6b
            // It is a known issue of the librxtxSerial lib
            serialPort.close();
            serialPort = null;
        }
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        if (event instanceof BlueGigaScanResponseEvent) {
            BlueGigaScanResponseEvent scanEvent = (BlueGigaScanResponseEvent) event;
            if (!isAlive()) {
                return;
            }
            synchronized (devices) {
                URL deviceURL = url.copyWithDevice(scanEvent.getSender());
                if (!devices.containsKey(deviceURL)) {
                    BluegigaDevice bluegigaDevice = new BluegigaDevice(bgHandler, deviceURL);
                    devices.put(deviceURL, bluegigaDevice);
                    // let the device to set its name and RSSI
                    bluegigaDevice.bluegigaEventReceived(scanEvent);
                    logger.debug("Discovered: {} ({}) {} ", bluegigaDevice.getURL().getDeviceAddress(),
                            bluegigaDevice.getName(), bluegigaDevice.getRSSI());
                }
            }
        }
    }

    BluegigaDevice getDevice(URL url) {
        URL deviceURL = url.getDeviceURL();
        synchronized (devices) {
            BluegigaDevice bluegigaDevice;
            if (devices.containsKey(deviceURL)) {
                return devices.get(deviceURL);
            } else {
                bluegigaDevice = new BluegigaDevice(bgHandler, deviceURL);
                devices.put(deviceURL, bluegigaDevice);
                return bluegigaDevice;
            }
        }
    }

    private void init() {

        if (bgHandler != null) {
            dispose();
        }

        openSerialPort(portName, 115200);

        // Create the handler
        bgHandler = new BlueGigaSerialHandler(inputStream, outputStream);
        bgHandler.addEventListener(this);

        // Stop any procedures that are running
        bgStopProcedure();

        // Close all transactions
        closeAllConnections();

        // Get our Bluetooth address
        url = getAdapterAddress();

        info = getInfo();

        // Set mode to non-discoverable etc.
        // Not doing this will cause connection failures later
        bgSetMode(GapConnectableMode.GAP_NON_CONNECTABLE, GapDiscoverableMode.GAP_NON_DISCOVERABLE);
    }

    private URL getAdapterAddress() {
        BlueGigaAddressGetResponse addressResponse = (BlueGigaAddressGetResponse) bgHandler
                .sendTransaction(new BlueGigaAddressGetCommand());
        if (addressResponse != null) {
            return new URL(BluegigaFactory.BLUEGIGA_PROTOCOL_NAME, addressResponse.getAddress(), null);
        }
        throw new IllegalStateException("Could not get adapter address");
    }

    private BlueGigaGetInfoResponse getInfo() {
        return (BlueGigaGetInfoResponse) bgHandler.sendTransaction(new BlueGigaGetInfoCommand());
    }


    private void closeAllConnections() {

        int maxConnections = 0;
        BlueGigaCommand command = new BlueGigaGetConnectionsCommand();
        BlueGigaGetConnectionsResponse connectionsResponse = (BlueGigaGetConnectionsResponse) bgHandler
                .sendTransaction(command);
        if (connectionsResponse != null) {
            maxConnections = connectionsResponse.getMaxconn();
        }

        // Close all connections so we start from a known position
        for (int connection = 0; connection < maxConnections; connection++) {
            bgDisconnect(connection);
        }
    }

    private void openSerialPort(final String serialPortName, int baudRate) {
        logger.info("Connecting to serial port [{}]", serialPortName);
        try {
            CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(serialPortName);
            CommPort commPort = portIdentifier.open("org.sputnikdev.bluetooth", 2000);
            serialPort = (gnu.io.SerialPort) commPort;
            serialPort.setSerialPortParams(baudRate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                    gnu.io.SerialPort.PARITY_NONE);
            serialPort.setFlowControlMode(gnu.io.SerialPort.FLOWCONTROL_RTSCTS_OUT);

            serialPort.enableReceiveThreshold(1);
            serialPort.enableReceiveTimeout(2000);

            // RXTX serial port library causes high CPU load
            // Start event listener, which will just sleep and slow down event loop
            serialPort.notifyOnDataAvailable(true);

            logger.info("Serial port [{}] is initialized.", portName);
        } catch (NoSuchPortException e) {
            logger.warn("Could not find serial port {}", serialPortName);
            throw new BluegigaException(e);
        } catch (PortInUseException e) {
            logger.warn("Serial port is in use {}", serialPortName);
            throw new BluegigaException(e);
        } catch (UnsupportedCommOperationException e) {
            logger.warn("Generic serial port error {}", serialPortName);
            throw new BluegigaException(e);
        }

        try {
            inputStream = serialPort.getInputStream();
            outputStream = serialPort.getOutputStream();
        } catch (IOException e) {
            logger.error("Error getting serial streams", e);
            throw new BluegigaException(e);
        }
    }

    /*
     * The following methods are private methods for handling the BlueGiga protocol
     */
    private boolean bgStopProcedure() {
        BlueGigaCommand command = new BlueGigaEndProcedureCommand();
        BlueGigaEndProcedureResponse response = (BlueGigaEndProcedureResponse) bgHandler.sendTransaction(command);

        if (response.getResult() == BgApiResponse.SUCCESS) {
            discovering = false;
            return true;
        }
        return false;
    }

    private boolean bgSetMode(GapConnectableMode connectableMode, GapDiscoverableMode discoverableMode) {
        BlueGigaSetModeCommand command = new BlueGigaSetModeCommand();
        command.setConnect(connectableMode);
        command.setDiscover(discoverableMode);
        BlueGigaSetModeResponse response = (BlueGigaSetModeResponse) bgHandler.sendTransaction(command);

        return response.getResult() == BgApiResponse.SUCCESS;
    }

    /**
     * Starts scanning on the dongle
     *
     * @param active true for active scanning
     */
    private boolean bgStartScanning(boolean active, int interval, int window) {
        BlueGigaSetScanParametersCommand scanCommand = new BlueGigaSetScanParametersCommand();
        scanCommand.setActiveScanning(active);
        scanCommand.setScanInterval(interval);
        scanCommand.setScanWindow(window);
        bgHandler.sendTransaction(scanCommand);

        BlueGigaDiscoverCommand discoverCommand = new BlueGigaDiscoverCommand();
        discoverCommand.setMode(GapDiscoverMode.GAP_DISCOVER_OBSERVATION);
        BlueGigaDiscoverResponse response = (BlueGigaDiscoverResponse)  bgHandler.sendTransaction(discoverCommand);
        return discovering = response.getResult() == BgApiResponse.SUCCESS;
    }

    /**
     * Close a connection using {@link BlueGigaDisconnectCommand}
     *
     * @param connectionHandle an ID of connection
     * @return true if the disconnection process has started
     */
    private boolean bgDisconnect(int connectionHandle) {
        BlueGigaDisconnectCommand command = new BlueGigaDisconnectCommand();
        command.setConnection(connectionHandle);
        BlueGigaDisconnectResponse response = (BlueGigaDisconnectResponse) bgHandler.sendTransaction(command);

        return response.getResult() == BgApiResponse.SUCCESS;
    }

}

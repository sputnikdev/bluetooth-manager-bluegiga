package org.sputnikdev.bluetooth.manager.transport.bluegiga;


import com.zsmartsystems.bluetooth.bluegiga.BlueGigaCommand;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaEventListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaSerialHandler;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.*;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.*;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.*;
import com.zsmartsystems.bluetooth.bluegiga.command.system.*;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.*;
import gnu.io.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Thread-safe version of the Bluegiga Serial Handler
 */
class BluegigaHandler implements BlueGigaEventListener {

    /**
     * This value (milliseconds) is used in conversion of an async process to be synchronous.
     * //TODO refine and come up with a proper value
     */
    private static final int WAIT_EVENT_TIMEOUT = 10000;

    private final Logger logger = LoggerFactory.getLogger(BluegigaAdapter.class);

    // The Serial port name
    private String portName;

    // The serial port.
    private SerialPort serialPort;

    // The serial port input stream.
    private InputStream inputStream;

    // The serial port output stream.
    private OutputStream outputStream;

    // Our BT address
    private URL adapterAddress;

    // The BlueGiga API handler
    private BlueGigaSerialHandler bgHandler;

    private static final int ACTIVE_SCAN_INTERVAL = 0x40;
    private static final int ACTIVE_SCAN_WINDOW = 0x20;

    // synchronisation objects (used in conversion of async processes to be synchronous)
    private final AtomicReference<BlueGigaResponse> responseHolder = new AtomicReference<>();

    BluegigaHandler(String portName) {
        this.portName = portName;
        this.init();
    }

    String getPortName() {
        return this.portName;
    }

    void addEventListener(BlueGigaEventListener listener) {
        this.bgHandler.addEventListener(listener);
    }

    void removeEventListener(BlueGigaEventListener listener) {
        this.bgHandler.removeEventListener(listener);
    }

    boolean isAlive() {
        return this.bgHandler.isAlive();
    }

    URL getAdapterAddress() {
        return adapterAddress;
    }

    BlueGigaConnectionStatusEvent connect(URL url) {
        return syncCall(() -> bgConnect(url));
    }

    BlueGigaDisconnectedEvent disconnect(int connectionHandle) {
        return syncCall(() -> bgDisconnect(connectionHandle));
    }

    List<BlueGigaGroupFoundEvent> getServices(int connectionHandle) {
        return syncCallProcedure(() -> bgFindPrimaryServices(connectionHandle), BlueGigaGroupFoundEvent.class);
    }


    List<BlueGigaFindInformationFoundEvent> getCharacteristics(int connectionHandle) {
        return syncCallProcedure(() -> bgFindCharacteristics(connectionHandle),
                BlueGigaFindInformationFoundEvent.class);
    }

    List<BlueGigaAttributeValueEvent> getDeclarations(int connectionHandle) {
        return syncCallProcedure(() -> bgFindDeclarations(connectionHandle),
                BlueGigaAttributeValueEvent.class);
    }

    <T extends BlueGigaResponse> T syncCall(Supplier<BgApiResponse> initialCommand) {
        synchronized (responseHolder) {
            BgApiResponse response = initialCommand.get();
            if (response == BgApiResponse.SUCCESS) {
                try {
                    responseHolder.wait(WAIT_EVENT_TIMEOUT);
                    T result = (T) responseHolder.get();
                    if (result == null) {
                        throw new BluegigaException("Could not receive expected event");
                    }
                    responseHolder.set(null);
                    return result;
                } catch (InterruptedException e) {
                    throw new BluegigaException("Event receiving process has been interrupted", e);
                }
            }
            throw new BluegigaException("Could not initiate process");
        }
    }

    <T extends BlueGigaResponse> List<T> syncCallProcedure(Supplier<BgApiResponse> initialCommand, Class<T> expectedEvent) {
        synchronized (responseHolder) {
            BgApiResponse response = initialCommand.get();
            if (response == BgApiResponse.SUCCESS) {
                try {
                    List<T> events = new ArrayList<>();
                    while (true) {
                        responseHolder.wait(WAIT_EVENT_TIMEOUT);
                        BlueGigaResponse event = responseHolder.get();
                        if (event == null) {
                            throw new BluegigaException("Could not receive expected event");
                        }
                        if (event instanceof BlueGigaProcedureCompletedEvent)  {
                            responseHolder.set(null);
                            return events;
                        } else if (expectedEvent.isInstance(event)) {
                            events.add((T) event);
                        }
                    }
                } catch (InterruptedException e) {
                    throw new BluegigaException("Event receiving process has been interrupted", e);
                }
            }
            throw new BluegigaException("Could not initiate process");
        }
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        synchronized (responseHolder) {
            responseHolder.set(event);
            responseHolder.notifyAll();
        }
    }

    URL bgGetAdapterAddress() {
        BlueGigaAddressGetResponse addressResponse = (BlueGigaAddressGetResponse) bgHandler
                .sendTransaction(new BlueGigaAddressGetCommand());
        if (addressResponse != null) {
            return new URL(BluegigaFactory.BLUEGIGA_PROTOCOL_NAME, addressResponse.getAddress(), null);
        }
        throw new IllegalStateException("Could not get adapter address");
    }

    BlueGigaGetInfoResponse bgGetInfo() {
        return (BlueGigaGetInfoResponse) bgHandler.sendTransaction(new BlueGigaGetInfoCommand());
    }

    /**
     * Starts scanning on the dongle
     *
     * @param active true for active scanning
     */
    boolean bgStartScanning(boolean active) {
        BlueGigaSetScanParametersCommand scanCommand = new BlueGigaSetScanParametersCommand();
        scanCommand.setActiveScanning(active);
        scanCommand.setScanInterval(ACTIVE_SCAN_INTERVAL);
        scanCommand.setScanWindow(ACTIVE_SCAN_WINDOW);
        bgHandler.sendTransaction(scanCommand);

        BlueGigaDiscoverCommand discoverCommand = new BlueGigaDiscoverCommand();
        discoverCommand.setMode(GapDiscoverMode.GAP_DISCOVER_OBSERVATION);
        BlueGigaDiscoverResponse response = (BlueGigaDiscoverResponse)  bgHandler.sendTransaction(discoverCommand);
        return response.getResult() == BgApiResponse.SUCCESS;
    }

    BgApiResponse bgConnect(URL url) {
        // Connect...
        //TODO revise these constants, especially the "latency" as it may improve devices energy consumption
        // BG spec: This parameter configures the slave latency. Slave latency defines how many connection intervals
        // a slave device can skip. Increasing slave latency will decrease the energy consumption of the slave
        // in scenarios where slave does not have data to send at every connection interval.
        int connIntervalMin = 60;
        int connIntervalMax = 100;
        int latency = 0;
        int timeout = 100;

        BlueGigaConnectDirectCommand connect = new BlueGigaConnectDirectCommand();
        connect.setAddress(url.getDeviceAddress());
        connect.setAddrType(BluetoothAddressType.UNKNOWN);
        connect.setConnIntervalMin(connIntervalMin);
        connect.setConnIntervalMax(connIntervalMax);
        connect.setLatency(latency);
        connect.setTimeout(timeout);
        BlueGigaConnectDirectResponse connectResponse = (BlueGigaConnectDirectResponse) bgHandler
                .sendTransaction(connect);
        return connectResponse.getResult();
    }

    /**
     * Close a connection using {@link BlueGigaDisconnectCommand}
     *
     * @param connectionHandle an ID of connection
     * @return true if the disconnection process has started
     */
    private BgApiResponse bgDisconnect(int connectionHandle) {
        BlueGigaDisconnectCommand command = new BlueGigaDisconnectCommand();
        command.setConnection(connectionHandle);
        return ((BlueGigaDisconnectResponse) bgHandler.sendTransaction(command)).getResult();
    }

    short bgGetRssi(int connectionHandle) {
        synchronized (responseHolder) {
            BlueGigaGetRssiCommand rssiCommand = new BlueGigaGetRssiCommand();
            rssiCommand.setConnection(connectionHandle);
            return (short) ((BlueGigaGetRssiResponse) bgHandler.sendTransaction(rssiCommand)).getRssi();
        }
    }

    boolean bgStopProcedure() {
        BlueGigaCommand command = new BlueGigaEndProcedureCommand();
        BlueGigaEndProcedureResponse response = (BlueGigaEndProcedureResponse) bgHandler.sendTransaction(command);

        if (response.getResult() == BgApiResponse.SUCCESS) {
            return true;
        }
        return false;
    }

    /*
     * The following methods are private methods for handling the BlueGiga protocol
     */
    private boolean bgSetMode(GapConnectableMode connectableMode, GapDiscoverableMode discoverableMode) {
        BlueGigaSetModeCommand command = new BlueGigaSetModeCommand();
        command.setConnect(connectableMode);
        command.setDiscover(discoverableMode);
        BlueGigaSetModeResponse response = (BlueGigaSetModeResponse) bgHandler.sendTransaction(command);

        return response.getResult() == BgApiResponse.SUCCESS;
    }

    /**
     * Start a read of all primary services using {@link BlueGigaReadByGroupTypeCommand}
     *
     * @return true if successful
     */
    BgApiResponse bgFindPrimaryServices(int connectionHandle) {
        logger.debug("BlueGiga FindPrimary: connection {}", connectionHandle);
        BlueGigaReadByGroupTypeCommand command = new BlueGigaReadByGroupTypeCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        command.setUuid(UUID.fromString("00002800-0000-0000-0000-000000000000"));
        BlueGigaReadByGroupTypeResponse response = (BlueGigaReadByGroupTypeResponse) bgHandler.sendTransaction(command);
        return response.getResult();
    }

    /**
     * Start a read of all characteristics using {@link BlueGigaFindInformationCommand}
     *
     * @return true if successful
     */
    BgApiResponse bgFindCharacteristics(int connectionHandle) {
        logger.debug("BlueGiga Find: connection {}", connectionHandle);
        BlueGigaFindInformationCommand command = new BlueGigaFindInformationCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        BlueGigaFindInformationResponse response = (BlueGigaFindInformationResponse) bgHandler.sendTransaction(command);
        return response.getResult();
    }

    BgApiResponse bgFindDeclarations(int connectionHandle) {
        logger.debug("BlueGiga FindDeclarations: connection {}", connectionHandle);
        BlueGigaReadByTypeCommand command = new BlueGigaReadByTypeCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        command.setUuid(UUID.fromString("00002803-0000-0000-0000-000000000000"));
        BlueGigaReadByTypeResponse response = (BlueGigaReadByTypeResponse) bgHandler.sendTransaction(command);
        return response.getResult();
    }

    /**
     * Read a characteristic using {@link BlueGigaReadByHandleCommand}
     *
     * @param connectionHandle
     * @param characteristicHandle
     * @return true if successful
     */
    boolean bgReadCharacteristic(int connectionHandle, int characteristicHandle) {
        logger.debug("BlueGiga Read: connection {}, characteristicHandle {}", connectionHandle, characteristicHandle);
        BlueGigaReadByHandleCommand command = new BlueGigaReadByHandleCommand();
        command.setConnection(connectionHandle);
        command.setChrHandle(characteristicHandle);
        BlueGigaReadByHandleResponse response = (BlueGigaReadByHandleResponse) bgHandler.sendTransaction(command);

        return response.getResult() == BgApiResponse.SUCCESS;
    }

    /**
     * Write a characteristic using {@link BlueGigaAttributeWriteCommand}
     *
     * @param connectionHandle
     * @param handle
     * @param value
     * @return true if successful
     */
    boolean bgWriteCharacteristic(int connectionHandle, int handle, int[] value) {
        logger.debug("BlueGiga Write: connection {}, characteristicHandle {}", connectionHandle, handle);
        BlueGigaAttributeWriteCommand command = new BlueGigaAttributeWriteCommand();
        command.setConnection(connectionHandle);
        command.setAttHandle(handle);
        command.setData(value);
        BlueGigaAttributeWriteResponse response = (BlueGigaAttributeWriteResponse) bgHandler.sendTransaction(command);

        return response.getResult() == BgApiResponse.SUCCESS;
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

    private void init() {

        if (bgHandler != null) {
            dispose();
        }

        openSerialPort(portName, 115200);

        // Create the handler
        bgHandler = new BlueGigaSerialHandler(inputStream, outputStream);

        // Stop any procedures that are running
        bgStopProcedure();

        // Close all transactions
        closeAllConnections();

        // Set mode to non-discoverable etc.
        // Not doing this will cause connection failures later
        bgSetMode(GapConnectableMode.GAP_NON_CONNECTABLE, GapDiscoverableMode.GAP_NON_DISCOVERABLE);

        this.adapterAddress = bgGetAdapterAddress();

        bgHandler.addEventListener(this);
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

    void dispose() {
        if (bgHandler != null) {
            bgStopProcedure();
            closeAllConnections();
            bgHandler.close();
            bgHandler = null;
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

        this.adapterAddress = null;
    }

}

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
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
    private final EventCaptor eventsCaptor = new EventCaptor();

    BluegigaHandler(String portName) {
        this.portName = portName;
        this.init();
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        this.eventsCaptor.handleEvent(event);
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
        return syncCall(BlueGigaConnectionStatusEvent.class, p -> p.getAddress().equals(url.getDeviceAddress()),
                () -> bgConnect(url));
    }

    BlueGigaDisconnectedEvent disconnect(int connectionHandle) {
        return syncCall(BlueGigaDisconnectedEvent.class, p -> p.getConnection() == connectionHandle,
                () -> bgDisconnect(connectionHandle));
    }

    List<BlueGigaGroupFoundEvent> getServices(int connectionHandle) {
        return syncCallProcedure(BlueGigaGroupFoundEvent.class, a -> a.getConnection() == connectionHandle,
                BlueGigaProcedureCompletedEvent.class, p -> p.getConnection() == connectionHandle,
                () -> bgFindPrimaryServices(connectionHandle));
    }


    List<BlueGigaFindInformationFoundEvent> getCharacteristics(int connectionHandle) {
        return syncCallProcedure(BlueGigaFindInformationFoundEvent.class, p -> p.getConnection() == connectionHandle,
                BlueGigaProcedureCompletedEvent.class, p -> p.getConnection() == connectionHandle,
                () -> bgFindCharacteristics(connectionHandle));
    }

    List<BlueGigaAttributeValueEvent> getDeclarations(int connectionHandle) {
        return syncCallProcedure(BlueGigaAttributeValueEvent.class, p -> p.getConnection() == connectionHandle,
                BlueGigaProcedureCompletedEvent.class, p -> p.getConnection() == connectionHandle,
                () -> bgFindDeclarations(connectionHandle));
    }

    BlueGigaAttributeValueEvent readCharacteristic(int connectionHandle, int characteristicHandle) {
        return syncCall(BlueGigaAttributeValueEvent.class,
                (p) -> p.getConnection() == connectionHandle && p.getAttHandle() == characteristicHandle,
                () -> bgReadCharacteristic(connectionHandle, characteristicHandle));
    }

    BlueGigaProcedureCompletedEvent writeCharacteristic(int connectionHandle, int characteristicHandle,
                                                                    int[] data) {
        return syncCall(BlueGigaProcedureCompletedEvent.class,
                (p) -> p.getConnection() == connectionHandle && p.getChrHandle() == characteristicHandle,
                () -> bgWriteCharacteristic(connectionHandle, characteristicHandle, data));
    }

    boolean writeCharacteristicWithoutResponse(int connectionHandle, int characteristicHandle, int[] data) {
        return bgWriteCharacteristic(connectionHandle, characteristicHandle, data) == BgApiResponse.SUCCESS;
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

    short bgGetRssi(int connectionHandle) {
        synchronized (eventsCaptor) {
            BlueGigaGetRssiCommand rssiCommand = new BlueGigaGetRssiCommand();
            rssiCommand.setConnection(connectionHandle);
            return (short) ((BlueGigaGetRssiResponse) bgHandler.sendTransaction(rssiCommand)).getRssi();
        }
    }

    boolean bgStopProcedure() {
        BlueGigaCommand command = new BlueGigaEndProcedureCommand();
        BlueGigaEndProcedureResponse response = (BlueGigaEndProcedureResponse) bgHandler.sendTransaction(command);
        return response.getResult() == BgApiResponse.SUCCESS;
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

    // Bluegiga API specific methods

    private <T extends BlueGigaResponse> T syncCall(Class<T> completedEventType, Predicate<T> completionPredicate,
                                                    Supplier<BgApiResponse> initialCommand) {
        synchronized (eventsCaptor) {
            eventsCaptor.setCompletedEventType(completedEventType);
            eventsCaptor.setCompletionPredicate(completionPredicate);
            BgApiResponse response = initialCommand.get();
            if (response == BgApiResponse.SUCCESS) {
                try {
                    BlueGigaResponse event = (BlueGigaResponse)
                            eventsCaptor.getEvents().poll(WAIT_EVENT_TIMEOUT, TimeUnit.MILLISECONDS);
                    if (event == null) {
                        throw new BluegigaException("Could not receive expected event");
                    }
                    if (eventsCaptor.isCompleted()) {
                        eventsCaptor.reset();
                        return (T) event;
                    } else {
                        throw new IllegalStateException("Bluegiga procedure has not been completed");
                    }
                } catch (InterruptedException e) {
                    throw new BluegigaException("Bluegiga procedure has been interrupted", e);
                }
            }
            throw new BluegigaException("Could not initiate Bluegiga process: " + response);
        }
    }

    private <E extends BlueGigaResponse, C extends BlueGigaResponse> List<E> syncCallProcedure(
            Class<E> aggregatedEventType, Predicate<E> aggregationPredicate,
            Class<C> completedEventType, Predicate<C> completionPredicate,
            Supplier<BgApiResponse> initialCommand) {
        synchronized (eventsCaptor) {
            eventsCaptor.setAggregatedEventType(aggregatedEventType);
            eventsCaptor.setAggregationPredicate(aggregationPredicate);
            eventsCaptor.setCompletedEventType(completedEventType);
            eventsCaptor.setCompletionPredicate(completionPredicate);
            BgApiResponse response = initialCommand.get();
            if (response == BgApiResponse.SUCCESS) {
                try {
                    List<E> events = new ArrayList<>();
                    BlueGigaResponse event;
                    while (true) {
                        event = (BlueGigaResponse) eventsCaptor.getEvents().poll(
                                WAIT_EVENT_TIMEOUT, TimeUnit.MILLISECONDS);
                        if (event == null) {
                            throw new BluegigaException("Could not receive expected event");
                        }
                        if (eventsCaptor.isCompleted()) {
                            eventsCaptor.reset();
                            return events;
                        } else {
                            events.add((E) event);
                        }
                    }
                } catch (InterruptedException e) {
                    throw new BluegigaException("Event receiving process has been interrupted", e);
                }
            }
            throw new BluegigaException("Could not initiate process: " + response);
        }
    }

    private URL bgGetAdapterAddress() {
        BlueGigaAddressGetResponse addressResponse = (BlueGigaAddressGetResponse) bgHandler
                .sendTransaction(new BlueGigaAddressGetCommand());
        if (addressResponse != null) {
            return new URL(BluegigaFactory.BLUEGIGA_PROTOCOL_NAME, addressResponse.getAddress(), null);
        }
        throw new IllegalStateException("Could not get adapter address");
    }

    private BgApiResponse bgConnect(URL url) {
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
    private BgApiResponse bgFindPrimaryServices(int connectionHandle) {
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
    private BgApiResponse bgFindCharacteristics(int connectionHandle) {
        logger.debug("BlueGiga Find: connection {}", connectionHandle);
        BlueGigaFindInformationCommand command = new BlueGigaFindInformationCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        BlueGigaFindInformationResponse response = (BlueGigaFindInformationResponse) bgHandler.sendTransaction(command);
        return response.getResult();
    }

    private BgApiResponse bgFindDeclarations(int connectionHandle) {
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
    private BgApiResponse bgReadCharacteristic(int connectionHandle, int characteristicHandle) {
        logger.debug("BlueGiga Read: connection {}, characteristicHandle {}", connectionHandle, characteristicHandle);
        BlueGigaReadByHandleCommand command = new BlueGigaReadByHandleCommand();
        command.setConnection(connectionHandle);
        command.setChrHandle(characteristicHandle);
        BlueGigaReadByHandleResponse response = (BlueGigaReadByHandleResponse) bgHandler.sendTransaction(command);

        return response.getResult();
    }

    /**
     * Write a characteristic using {@link BlueGigaAttributeWriteCommand}
     *
     * @param connectionHandle
     * @param handle
     * @param value
     * @return true if successful
     */
    private BgApiResponse bgWriteCharacteristic(int connectionHandle, int handle, int[] value) {
        logger.debug("BlueGiga Write: connection {}, characteristicHandle {}", connectionHandle, handle);
        BlueGigaAttributeWriteCommand command = new BlueGigaAttributeWriteCommand();
        command.setConnection(connectionHandle);
        command.setAttHandle(handle);
        command.setData(value);
        BlueGigaAttributeWriteResponse response = (BlueGigaAttributeWriteResponse) bgHandler.sendTransaction(command);

        return response.getResult();
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
            NRSerialPort nrSerialPort = new NRSerialPort(serialPortName, baudRate);
            if (!nrSerialPort.connect()) {
                throw new BluegigaException("Could not open serial port: " + serialPortName);
            }
            serialPort = nrSerialPort.getSerialPortInstance();
            serialPort.setSerialPortParams(baudRate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                SerialPort.PARITY_NONE);
            serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_OUT);

            serialPort.enableReceiveThreshold(1);
            serialPort.enableReceiveTimeout(2000);

            // RXTX serial port library causes high CPU load
            // Start event listener, which will just sleep and slow down event loop
            serialPort.notifyOnDataAvailable(true);

            logger.info("Serial port [{}] is initialized.", portName);

        } catch (NativeResourceException e) {
            logger.warn("Native resource exception {}; {}", serialPortName, e.toString());
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

    private class EventCaptor<A extends BlueGigaResponse, C extends BlueGigaResponse> {

        private Class<A> aggregatedEventType;
        private Class<C> completedEventType;
        private Predicate<A> aggregationPredicate;
        private Predicate<C> completionPredicate;
        private BlockingQueue<BlueGigaResponse> events = new LinkedBlockingDeque<>();
        private boolean completed;

        void setAggregatedEventType(Class<A> aggregatedEventType) {
            this.aggregatedEventType = aggregatedEventType;
        }

        void setCompletedEventType(Class<C> completedEventType) {
            this.completedEventType = completedEventType;
        }

        void setAggregationPredicate(Predicate<A> aggregationPredicate) {
            this.aggregationPredicate = aggregationPredicate;
        }

        void setCompletionPredicate(Predicate<C> completionPredicate) {
            this.completionPredicate = completionPredicate;
        }

        BlockingQueue<BlueGigaResponse> getEvents() {
            return events;
        }

        boolean isCompleted() {
            return completed;
        }

        void handleEvent(BlueGigaResponse event) {
            if (completedEventType != null && completedEventType.isInstance(event) &&
                    completionPredicate.test((C) event)) {
                events.add(event);
                completed = true;
            } else if (aggregatedEventType != null && aggregatedEventType.isInstance(event) &&
                    aggregationPredicate.test((A) event)) {
                events.add(event);
            }
        }

        void reset() {
            this.aggregatedEventType = null;
            this.completedEventType = null;
            this.aggregationPredicate = null;
            this.completionPredicate = null;
            this.completed = false;
            this.events.clear();
        }

    }

}

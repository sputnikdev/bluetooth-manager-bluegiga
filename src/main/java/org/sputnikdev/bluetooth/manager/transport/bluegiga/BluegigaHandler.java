package org.sputnikdev.bluetooth.manager.transport.bluegiga;

/*-
 * #%L
 * org.sputnikdev:bluetooth-manager-bluegiga
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
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaException;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaHandlerListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaSerialHandler;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeWriteCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeWriteResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaGroupFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaProcedureCompletedEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByGroupTypeCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByGroupTypeResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByHandleCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByHandleResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByTypeCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaReadByTypeResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaConnectionStatusEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectedEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaGetRssiCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaGetRssiResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaGetStatusCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaGetStatusResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaConnectDirectCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaConnectDirectResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaDiscoverCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaDiscoverResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaEndProcedureCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaEndProcedureResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaSetModeCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaSetModeResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaSetScanParametersCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaSetScanParametersResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaAddressGetCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaAddressGetResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetConnectionsCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetConnectionsResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaHelloCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaHelloResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaResetCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaResetResponse;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.AttributeValueType;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BluetoothAddressType;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.GapConnectableMode;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.GapDiscoverMode;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.GapDiscoverableMode;
import gnu.io.NRSerialPort;
import gnu.io.NativeResourceException;
import gnu.io.RXTXPort;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Thread-safe version of the Bluegiga Serial Handler.
 */
class BluegigaHandler implements BlueGigaEventListener {

    private static final long DEFAULT_WAIT_TIME = 5000;

    private final Logger logger = LoggerFactory.getLogger(BluegigaHandler.class);

    private static final int ACTIVE_SCAN_INTERVAL = 0x40;
    private static final int ACTIVE_SCAN_WINDOW = 0x20;

    // The Serial port name
    private String portName;

    private NRSerialPort nrSerialPort;

    // Our BT address
    private URL adapterAddress;

    // The BlueGiga API handler
    private BlueGigaSerialHandler bgHandler;

    private boolean discovering;

    // synchronisation objects (used in conversion of async processes to be synchronous)
    private final EventCaptor eventsCaptor = new EventCaptor();

    // a timeout in milliseconds that specify for how long a blugiga procedure should wait between expeced events
    private long eventWaitTimeout = DEFAULT_WAIT_TIME;

    protected BluegigaHandler(String portName) {
        this.portName = portName;
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        eventsCaptor.handleEvent(event);
    }

    protected static BluegigaHandler create(String portName) {
        BluegigaHandler bluegigaHandler = new BluegigaHandler(portName);

        try {
            bluegigaHandler.init();
        } catch (Exception ex) {
            throw new BluegigaException("Could not initialize blugiga handler for port: " + portName, ex);
        }

        if (!bluegigaHandler.isAlive()) {
            throw new BluegigaException("Serial port " + portName + " most likely does not represent a "
                + "Bluegiga compatible device");
        }
        return bluegigaHandler;
    }

    protected String getPortName() {
        return portName;
    }

    protected void addHandlerListener(BlueGigaHandlerListener listener) {
        bgHandler.addHandlerListener(listener);
    }

    protected void addEventListener(BlueGigaEventListener listener) {
        bgHandler.addEventListener(listener);
    }

    protected void removeEventListener(BlueGigaEventListener listener) {
        bgHandler.removeEventListener(listener);
    }

    protected URL getAdapterAddress() {
        return adapterAddress;
    }

    protected boolean isDiscovering() {
        return discovering;
    }

    protected void runInSynchronizedContext(Runnable task) {
        synchronized (eventsCaptor) {
            task.run();
        }
    }

    protected <V> V runInSynchronizedContext(Supplier<V> task) {
        synchronized (eventsCaptor) {
            return task.get();
        }
    }

    protected BlueGigaConnectionStatusEvent connect(URL url, BluetoothAddressType bluetoothAddressType) {
        return syncCall(BlueGigaConnectionStatusEvent.class,
            statusEvent -> statusEvent.getAddress().equals(url.getDeviceAddress()),
            () -> bgConnect(url, bluetoothAddressType));
    }

    protected BlueGigaDisconnectedEvent disconnect(int connectionHandle) {
        return syncCall(BlueGigaDisconnectedEvent.class, p -> p.getConnection() == connectionHandle,
            () -> bgDisconnect(connectionHandle));
    }

    protected List<BlueGigaGroupFoundEvent> getServices(int connectionHandle) {
        return syncCallProcedure(BlueGigaGroupFoundEvent.class, a -> a.getConnection() == connectionHandle,
                BlueGigaProcedureCompletedEvent.class, p -> p.getConnection() == connectionHandle,
            () -> bgFindPrimaryServices(connectionHandle));
    }


    protected List<BlueGigaFindInformationFoundEvent> getCharacteristics(int connectionHandle) {
        return syncCallProcedure(BlueGigaFindInformationFoundEvent.class, p -> p.getConnection() == connectionHandle,
                BlueGigaProcedureCompletedEvent.class, p -> p.getConnection() == connectionHandle,
            () -> bgFindCharacteristics(connectionHandle));
    }

    protected List<BlueGigaAttributeValueEvent> getDeclarations(int connectionHandle) {
        return syncCallProcedure(BlueGigaAttributeValueEvent.class, p -> p.getConnection() == connectionHandle
                        && p.getType() == AttributeValueType.ATTCLIENT_ATTRIBUTE_VALUE_TYPE_READ_BY_TYPE,
                BlueGigaProcedureCompletedEvent.class, p -> p.getConnection() == connectionHandle,
            () -> bgFindDeclarations(connectionHandle));
    }

    protected BlueGigaAttributeValueEvent readCharacteristic(int connectionHandle, int characteristicHandle) {
        return syncCall(BlueGigaAttributeValueEvent.class,
            p -> p.getConnection() == connectionHandle && p.getAttHandle() == characteristicHandle,
            () -> bgReadCharacteristic(connectionHandle, characteristicHandle));
    }

    protected BlueGigaProcedureCompletedEvent writeCharacteristic(int connectionHandle,
                                                                  int characteristicHandle, int[] data) {
        return syncCall(BlueGigaProcedureCompletedEvent.class,
            p -> p.getConnection() == connectionHandle && p.getChrHandle() == characteristicHandle,
            () -> bgWriteCharacteristic(connectionHandle, characteristicHandle, data));
    }

    protected boolean writeCharacteristicWithoutResponse(int connectionHandle, int characteristicHandle, int[] data) {
        synchronized (eventsCaptor) {
            return bgWriteCharacteristic(connectionHandle, characteristicHandle, data) == BgApiResponse.SUCCESS;
        }
    }

    protected BlueGigaGetInfoResponse bgGetInfo() {
        synchronized (eventsCaptor) {
            return sendTransaction(new BlueGigaGetInfoCommand(), BlueGigaGetInfoResponse.class);
        }
    }

    /**
     * Starts scanning on the dongle.
     */
    protected boolean bgStartScanning() {
        synchronized (eventsCaptor) {
            BlueGigaSetScanParametersCommand scanCommand = new BlueGigaSetScanParametersCommand();
            scanCommand.setActiveScanning(true);
            scanCommand.setScanInterval(ACTIVE_SCAN_INTERVAL);
            scanCommand.setScanWindow(ACTIVE_SCAN_WINDOW);
            sendTransaction(scanCommand, BlueGigaSetScanParametersResponse.class);

            BlueGigaDiscoverCommand discoverCommand = new BlueGigaDiscoverCommand();
            discoverCommand.setMode(GapDiscoverMode.GAP_DISCOVER_OBSERVATION);
            BlueGigaDiscoverResponse response = sendTransaction(discoverCommand, BlueGigaDiscoverResponse.class);
            discovering = response.getResult() == BgApiResponse.SUCCESS;
            return discovering;
        }
    }

    protected short bgGetRssi(int connectionHandle) {
        synchronized (eventsCaptor) {
            BlueGigaGetRssiCommand rssiCommand = new BlueGigaGetRssiCommand();
            rssiCommand.setConnection(connectionHandle);
            return (short) sendTransaction(rssiCommand, BlueGigaGetRssiResponse.class).getRssi();
        }
    }

    protected boolean bgStopProcedure() {
        synchronized (eventsCaptor) {
            BlueGigaEndProcedureResponse response =
                    sendTransaction(new BlueGigaEndProcedureCommand(), BlueGigaEndProcedureResponse.class);
            discovering = false;
            return response.getResult() == BgApiResponse.SUCCESS;
        }
    }

    protected BlueGigaConnectionStatusEvent getConnectionStatus(int connectionHandle) {
        return syncCall(BlueGigaConnectionStatusEvent.class, p -> p.getConnection() == connectionHandle,
            () -> bgGetStatus(connectionHandle));
    }

    protected void dispose() {
        synchronized (eventsCaptor) {
            if (bgHandler != null && bgHandler.isAlive()) {
                try {
                    bgStopProcedure();
                } catch (Exception ex) {
                    logger.debug("Could not stop discovery: {}", ex.getMessage());
                }
                try {
                    closeAllConnections();
                } catch (Exception ex) {
                    logger.debug("Could not close all connections: {}", ex.getMessage());
                }
            }
            closeBGHandler();
        }
    }

    protected long getEventWaitTimeout() {
        return DEFAULT_WAIT_TIME;
    }

    protected boolean isAlive() {
        synchronized (eventsCaptor) {
            try {
                return bgHandler.isAlive()
                        && sendTransaction(new BlueGigaHelloCommand(), BlueGigaHelloResponse.class) != null;
            } catch (BlueGigaException ex) {
                logger.warn("Error occurred while checking if BlueGiga handler is alive: {}", ex.getMessage());
                return false;
            }
        }
    }

    protected void checkAlive() {
        if (!isAlive()) {
            throw new BluegigaException("BlueGiga handler is dead.");
        }
    }

    private <T extends BlueGigaResponse> T sendTransaction(BlueGigaCommand command, Class<T> expected) {
        try {
            return bgHandler.sendTransaction(command, expected, eventWaitTimeout);
        } catch (TimeoutException timeout) {
            logger.warn("Timeout has happened while sending a transaction, retry one more time: {}",
                    command.getClass().getSimpleName());
            try {
                return bgHandler.sendTransaction(command, expected, eventWaitTimeout);
            } catch (TimeoutException timeout2) {
                logger.warn("Timeout has happened second time, giving up: {}", command.getClass().getSimpleName());
                closeBGHandler();
                throw new BlueGigaException("Bluegiga adapter does not respond for a transaction: "
                        + command.getClass().getSimpleName(), timeout2);
            } catch (Exception ex) {
                closeBGHandler();
                throw new BlueGigaException("Error occurred while retrying to send a transaction: "
                        + command.getClass().getSimpleName(), ex);
            }
        } catch (Exception e) {
            logger.warn("Error occurred while sending a transaction: {} : {} : {}",
                    command.getClass().getSimpleName(), e.getClass().getSimpleName(), e.getMessage());
            closeBGHandler();
            throw new BlueGigaException("Fatal error in communication with BlueGiga adapter.", e);
        }
    }

    // Bluegiga API specific methods

    private <T extends BlueGigaResponse> T syncCall(Class<T> completedEventType, Predicate<T> completionPredicate,
                                                    Supplier<BgApiResponse> initialCommand) {
        synchronized (eventsCaptor) {
            eventsCaptor.setCompletedEventType(completedEventType);
            eventsCaptor.setCompletionPredicate(completionPredicate);
            try {
                return callProcedure(completedEventType, initialCommand);
            } catch (BluegigaTimeoutException ignore) {
                logger.warn("Timeout received while calling simple procedure: {}. Trying one more time",
                        completedEventType.getSimpleName());
                return callProcedure(completedEventType, initialCommand);
            }
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
            try {
                return callProcedure(aggregatedEventType, completedEventType, initialCommand);
            } catch (BluegigaTimeoutException ignore) {
                logger.warn("Timeout received while calling complex procedure: {} / {}. Trying one more time",
                        aggregatedEventType.getSimpleName(), completedEventType.getSimpleName());
                return callProcedure(aggregatedEventType, completedEventType, initialCommand);
            }
        }
    }

    private <T extends BlueGigaResponse> T callProcedure(Class<T> completedEventType,
                                                         Supplier<BgApiResponse> initialCommand) {
        BgApiResponse response = initialCommand.get();
        if (response == BgApiResponse.UNKNOWN) {
            logger.warn("UNKNOWN response received, trying to listen to events anyway: {}",
                    completedEventType.getSimpleName());
        }
        if (response == BgApiResponse.SUCCESS
                // sometimes BlueGiga sends UNKNOWN response, we will try to listen to events,
                // but most likely it will time out, the caller of this method (syncCall) will retry 1 time
                || response == BgApiResponse.UNKNOWN) {
            try {
                BlueGigaResponse event = eventsCaptor.poll(eventWaitTimeout);
                if (event == null) {
                    throw new BluegigaTimeoutException("Could not receive expected event: "
                            + completedEventType.getSimpleName());
                }
                eventsCaptor.reset();
                return (T) event;
            } catch (InterruptedException e) {
                throw new BluegigaException("Bluegiga procedure has been interrupted", e);
            }
        }
        throw new BluegigaException("Could not initiate process: "
                + completedEventType.getSimpleName() + " / " + response);
    }

    private <E extends BlueGigaResponse, C extends BlueGigaResponse> List<E> callProcedure(
            Class<E> aggregatedEventType, Class<C> completedEventType, Supplier<BgApiResponse> initialCommand) {
        BgApiResponse response = initialCommand.get();
        if (response == BgApiResponse.UNKNOWN) {
            logger.warn("UNKNOWN response received, trying to listen to events anyway: {} / {}",
                    aggregatedEventType.getSimpleName(), completedEventType.getSimpleName());
        }
        if (response == BgApiResponse.SUCCESS
                // sometimes BlueGiga sends UNKNOWN response, we will try to listen to events,
                // but most likely it will time out, the caller of this method (syncCallProcedure) will retry 1 time
                || response == BgApiResponse.UNKNOWN) {
            try {
                List<E> events = new ArrayList<>();
                BlueGigaResponse event;
                while (true) {
                    event = eventsCaptor.poll(eventWaitTimeout);
                    if (event == null) {
                        throw new BluegigaTimeoutException("Could not receive expected event: "
                                + aggregatedEventType.getSimpleName() + " or " + completedEventType.getSimpleName());
                    }
                    if (eventsCaptor.isCompletionEvent(event)) {
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
        throw new BluegigaException("Could not initiate process: "
                + aggregatedEventType.getSimpleName() + " / "
                + completedEventType.getSimpleName() + " / " + response);
    }

    /*
     * The following methods are private methods for handling the BlueGiga protocol
     */

    private URL bgGetAdapterAddress() {
        BlueGigaAddressGetResponse addressResponse = sendTransaction(new BlueGigaAddressGetCommand(),
                BlueGigaAddressGetResponse.class);
        if (addressResponse != null) {
            return new URL(BluegigaFactory.BLUEGIGA_PROTOCOL_NAME, addressResponse.getAddress(), null);
        }
        throw new IllegalStateException("Could not get adapter address");
    }

    private BgApiResponse bgConnect(URL url, BluetoothAddressType bluetoothAddressType) {
        // Connect...
        //TODO revise these constants, especially the "latency" as it may improve devices energy consumption
        // BG spec: This parameter configures the slave latency. Slave latency defines how many connection intervals
        // a slave device can skip. Increasing slave latency will decrease the energy consumption of the slave
        // in scenarios where slave does not have data to send at every connection interval.
        int connIntervalMin = 60;
        int connIntervalMax = 100;
        int latency = 0;
        int timeout = 1000;

        BlueGigaConnectDirectCommand connect = new BlueGigaConnectDirectCommand();
        connect.setAddress(url.getDeviceAddress());
        connect.setAddrType(bluetoothAddressType);
        connect.setConnIntervalMin(connIntervalMin);
        connect.setConnIntervalMax(connIntervalMax);
        connect.setLatency(latency);
        connect.setTimeout(timeout);
        BlueGigaConnectDirectResponse connectResponse = sendTransaction(connect, BlueGigaConnectDirectResponse.class);
        return connectResponse.getResult();
    }

    private BgApiResponse bgDisconnect(int connectionHandle) {
        BlueGigaDisconnectCommand command = new BlueGigaDisconnectCommand();
        command.setConnection(connectionHandle);
        return sendTransaction(command, BlueGigaDisconnectResponse.class).getResult();
    }

    private boolean bgSetMode(GapConnectableMode connectableMode, GapDiscoverableMode discoverableMode) {
        BlueGigaSetModeCommand command = new BlueGigaSetModeCommand();
        command.setConnect(connectableMode);
        command.setDiscover(discoverableMode);
        BlueGigaSetModeResponse response = sendTransaction(command, BlueGigaSetModeResponse.class);

        return response.getResult() == BgApiResponse.SUCCESS;
    }

    private BgApiResponse bgGetStatus(int connectionHandle) {
        BlueGigaGetStatusCommand command = new BlueGigaGetStatusCommand();
        command.setConnection(connectionHandle);
        BlueGigaGetStatusResponse response = sendTransaction(command, BlueGigaGetStatusResponse.class);

        return response.getConnection() == connectionHandle
                ? BgApiResponse.getBgApiResponse(BgApiResponse.SUCCESS.getKey())
                : BgApiResponse.getBgApiResponse(BgApiResponse.UNKNOWN.getKey());
    }

    private BgApiResponse bgFindPrimaryServices(int connectionHandle) {
        logger.debug("BlueGiga FindPrimary: connection {}", connectionHandle);
        BlueGigaReadByGroupTypeCommand command = new BlueGigaReadByGroupTypeCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        command.setUuid(UUID.fromString("00002800-0000-0000-0000-000000000000"));
        BlueGigaReadByGroupTypeResponse response = sendTransaction(command, BlueGigaReadByGroupTypeResponse.class);
        return response.getResult();
    }

    private BgApiResponse bgFindCharacteristics(int connectionHandle) {
        logger.debug("BlueGiga Find: connection {}", connectionHandle);
        BlueGigaFindInformationCommand command = new BlueGigaFindInformationCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        BlueGigaFindInformationResponse response = sendTransaction(command, BlueGigaFindInformationResponse.class);
        return response.getResult();
    }

    private BgApiResponse bgFindDeclarations(int connectionHandle) {
        logger.debug("BlueGiga FindDeclarations: connection {}", connectionHandle);
        BlueGigaReadByTypeCommand command = new BlueGigaReadByTypeCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        command.setUuid(UUID.fromString("00002803-0000-0000-0000-000000000000"));
        BlueGigaReadByTypeResponse response = sendTransaction(command, BlueGigaReadByTypeResponse.class);
        return response.getResult();
    }

    private BgApiResponse bgReadCharacteristic(int connectionHandle, int characteristicHandle) {
        logger.debug("BlueGiga Read: connection {}, characteristicHandle {}", connectionHandle, characteristicHandle);
        BlueGigaReadByHandleCommand command = new BlueGigaReadByHandleCommand();
        command.setConnection(connectionHandle);
        command.setChrHandle(characteristicHandle);
        BlueGigaReadByHandleResponse response = sendTransaction(command, BlueGigaReadByHandleResponse.class);

        return response.getResult();
    }

    private BgApiResponse bgWriteCharacteristic(int connectionHandle, int handle, int[] value) {
        logger.debug("BlueGiga Write: connection {}, characteristicHandle {}", connectionHandle, handle);
        BlueGigaAttributeWriteCommand command = new BlueGigaAttributeWriteCommand();
        command.setConnection(connectionHandle);
        command.setAttHandle(handle);
        command.setData(value);
        BlueGigaAttributeWriteResponse response = sendTransaction(command, BlueGigaAttributeWriteResponse.class);

        return response.getResult();
    }

    private boolean bgReset() {
        logger.debug("BlueGiga RESET");
        BlueGigaResetCommand command = new BlueGigaResetCommand();
        try {
            bgHandler.sendTransaction(command, BlueGigaResetResponse.class, 1000);
            return true;
        } catch (TimeoutException expected) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void closeAllConnections() {

        int maxConnections = 0;
        BlueGigaCommand command = new BlueGigaGetConnectionsCommand();
        BlueGigaGetConnectionsResponse connectionsResponse =
                sendTransaction(command, BlueGigaGetConnectionsResponse.class);
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
        bgHandler = new BlueGigaSerialHandler(nrSerialPort.getInputStream(), nrSerialPort.getOutputStream());

        // Stop any procedures that are running
        bgStopProcedure();

        // Close all transactions
        closeAllConnections();

        // Set mode to non-discoverable etc.
        // Not doing this will cause connection failures later
        bgSetMode(GapConnectableMode.GAP_NON_CONNECTABLE, GapDiscoverableMode.GAP_NON_DISCOVERABLE);

        adapterAddress = bgGetAdapterAddress();

        bgHandler.addEventListener(this);
    }

    private void openSerialPort(final String serialPortName, int baudRate) {
        logger.info("Connecting to serial port [{}]", serialPortName);
        try {
            nrSerialPort = new NRSerialPort(serialPortName, baudRate);
            if (!nrSerialPort.connect()) {
                throw new BluegigaException("Could not open serial port: " + serialPortName);
            }
            RXTXPort serialPort = nrSerialPort.getSerialPortInstance();
            serialPort.setSerialPortParams(baudRate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                SerialPort.PARITY_NONE);
            serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_OUT);

            serialPort.enableReceiveThreshold(1);
            serialPort.enableReceiveTimeout(2000);

            //RXTX serial port library causes high CPU load
            //Start event listener, which will just sleep and slow down event loop
            serialPort.notifyOnDataAvailable(true);

            logger.info("Serial port [{}] is initialized.", portName);

        } catch (NativeResourceException e) {
            throw new BluegigaException(String.format("Native resource exception %s", serialPortName), e);
        }  catch (UnsupportedCommOperationException e) {
            throw new BluegigaException(String.format("Generic serial port error %s", serialPortName), e);
        }
    }

    private void closeBGHandler() {
        if (bgHandler != null) {
            bgHandler.close(10000);
        }
        if (nrSerialPort != null) {
            RXTXPort serialPort = nrSerialPort.getSerialPortInstance();
            if (serialPort != null) {
                try {
                    //serialPort.disableReceiveTimeout();
                    serialPort.removeEventListener();
                } catch (Exception ex) {
                    logger.warn("Could not dispose serial port object: {}", ex.getMessage());
                }
            }
            // Note: this will fail with a SIGSEGV error on OSX:
            // Problematic frame: C  [librxtxSerial.jnilib+0x312f]  Java_gnu_io_RXTXPort_interruptEventLoop+0x6b
            // It is a known issue of the librxtxSerial lib
            try {
                CompletableFuture.runAsync(() -> {
                    nrSerialPort.disconnect();
                }).get(5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                logger.warn("Could not disconnect serial port");
            }
            nrSerialPort = null;
        }
    }

    private class EventCaptor<A extends BlueGigaResponse, C extends BlueGigaResponse> {

        private Class<A> aggregatedEventType;
        private Class<C> completedEventType;
        private Predicate<A> aggregationPredicate;
        private Predicate<C> completionPredicate;
        private final LinkedBlockingDeque<BlueGigaResponse> events = new LinkedBlockingDeque<>();

        private void setAggregatedEventType(Class<A> aggregatedEventType) {
            this.aggregatedEventType = aggregatedEventType;
        }

        private void setCompletedEventType(Class<C> completedEventType) {
            this.completedEventType = completedEventType;
        }

        private void setAggregationPredicate(Predicate<A> aggregationPredicate) {
            this.aggregationPredicate = aggregationPredicate;
        }

        private void setCompletionPredicate(Predicate<C> completionPredicate) {
            this.completionPredicate = completionPredicate;
        }

        private BlueGigaResponse poll(long timeout) throws InterruptedException {
            return events.poll(timeout, TimeUnit.MILLISECONDS);
        }

        private void handleEvent(BlueGigaResponse event) {
            if (isAggregatedEvent(event) || isCompletionEvent(event)) {
                events.add(event);
            }
        }

        private void reset() {
            aggregatedEventType = null;
            completedEventType = null;
            aggregationPredicate = null;
            completionPredicate = null;
            events.clear();
        }

        private boolean isCompletionEvent(BlueGigaResponse event) {
            return completedEventType != null && completedEventType.isInstance(event)
                && completionPredicate.test((C) event);
        }

        private boolean isAggregatedEvent(BlueGigaResponse event) {
            return aggregatedEventType != null && aggregatedEventType.isInstance(event)
                && aggregationPredicate.test((A) event);
        }

    }

}

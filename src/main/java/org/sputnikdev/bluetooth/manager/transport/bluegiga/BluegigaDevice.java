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

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaEventListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaSerialHandler;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.*;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.*;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaConnectDirectCommand;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaConnectDirectResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirDataType;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirFlags;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirPacket;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BluetoothAddressType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;
import org.sputnikdev.bluetooth.manager.transport.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 *
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
class BluegigaDevice implements Device, BlueGigaEventListener {

    /**
     * This value (milliseconds) is used in conversion of an async process to be synchronous.
     * //TODO refine and come up with a proper value
     */
    private static final int WAIT_EVENT_TIMEOUT = 10000;

    private final Logger logger = LoggerFactory.getLogger(BluegigaDevice.class);
    private final URL url;
    private final BlueGigaSerialHandler bgHandler;
    private String name;
    private short rssi;
    private int bluetoothClass;
    private boolean bleEnabled;
    private boolean servicesResolved;
    private boolean characteristicsResolved;
    private final Map<URL, BluegigaService> services = new HashMap<>();

    // Notifications/listeners
    private Notification<Short> rssiNotification;
    private Notification<Boolean> connectedNotification;
    private Notification<Boolean> serviceResolvedNotification;


    // BG specific variables
    private int connectionHandle = -1;
    private BluetoothAddressType addressType = BluetoothAddressType.UNKNOWN;

    // synchronisation objects (used in conversion of async processes to be synchronous)
    private final AtomicReference<BlueGigaConnectionStatusEvent> connectionStatusEventHolder = new AtomicReference<>();
    private final AtomicReference<BlueGigaDisconnectedEvent> disconnectedEventHolder = new AtomicReference<>();

    BluegigaDevice(BlueGigaSerialHandler bgHandler, URL url) {
        this.bgHandler = bgHandler;
        this.url = url;
        bgHandler.addEventListener(this);
    }

    @Override
    public int getBluetoothClass() {
        return bluetoothClass;
    }

    @Override
    public boolean connect() {
        return syncCall(connectionStatusEventHolder, this::bgConnect, (event) -> {
            this.connectionHandle = event.getConnection();
            this.addressType = event.getAddressType();
            return true;
        });
    }

    @Override
    public boolean disconnect() {
        return syncCall(disconnectedEventHolder, this::bgDisconnect, (event) -> {
            connectionHandle = -1;
            return true;
        });
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getAlias() {
        return null;
    }

    @Override
    public void setAlias(String alias) {

    }

    @Override
    public boolean isBlocked() {
        return false;
    }

    @Override
    public void setBlocked(boolean blocked) {
    }

    @Override
    public boolean isBleEnabled() {
        return bleEnabled;
    }

    @Override
    public void enableBlockedNotifications(Notification<Boolean> notification) {

    }

    @Override
    public void disableBlockedNotifications() {

    }

    @Override
    public short getRSSI() {
        if (isConnected()) {
            rssi = bgGetRssi();
        }
        return rssi;
    }

    @Override
    public void enableRSSINotifications(Notification<Short> notification) {
        this.rssiNotification = notification;
    }

    @Override
    public void disableRSSINotifications() {
        this.rssiNotification = null;
    }

    @Override
    public boolean isConnected() {
        return bgHandler.isAlive() && connectionHandle >= 0;
    }

    @Override
    public void enableConnectedNotifications(Notification<Boolean> notification) {
        this.connectedNotification = notification;
    }

    @Override
    public void disableConnectedNotifications() {
        this.connectedNotification = null;
    }

    @Override
    public boolean isServicesResolved() {
        return servicesResolved && characteristicsResolved;
    }

    @Override
    public void enableServicesResolvedNotifications(Notification<Boolean> notification) {
        this.serviceResolvedNotification = notification;
    }

    @Override
    public void disableServicesResolvedNotifications() {
        this.serviceResolvedNotification = null;
    }

    @Override
    public List<Service> getServices() {
        synchronized (services) {
            return new ArrayList<>(services.values());
        }
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void dispose() {
        disconnect();
        bgHandler.removeEventListener(this);
        servicesUnresolved();
        this.connectedNotification = null;
        this.rssiNotification = null;
        this.serviceResolvedNotification = null;
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        if (event instanceof BlueGigaScanResponseEvent) {
            handleEvent((BlueGigaScanResponseEvent) event);
        } else if (event instanceof BlueGigaConnectionStatusEvent) {
            handleEvent((BlueGigaConnectionStatusEvent) event);
        } else if (event instanceof BlueGigaDisconnectedEvent) {
            handleEvent((BlueGigaDisconnectedEvent) event);
        } else if (event instanceof BlueGigaGroupFoundEvent) {
            handleEvent((BlueGigaGroupFoundEvent) event);
        } else if (event instanceof BlueGigaProcedureCompletedEvent) {
            handleEvent((BlueGigaProcedureCompletedEvent) event);
        } else if (event instanceof BlueGigaFindInformationFoundEvent) {
            handleEvent((BlueGigaFindInformationFoundEvent) event);
        }
    }


    BluegigaService getService(URL url) {
        synchronized (services) {
            return services.get(url.getServiceURL());
        }
    }

    private <T> boolean syncCall(final AtomicReference<T> notifier, Supplier<BgApiResponse> initialCommand,
                                 Function<T, Boolean> eventHandler) {
        if (!isConnected()) {
            return false;
        }
        synchronized (notifier) {
            BgApiResponse response = initialCommand.get();
            if (response == BgApiResponse.SUCCESS) {
                try {
                    notifier.wait(WAIT_EVENT_TIMEOUT);
                    T event = notifier.get();
                    if (event == null) {
                        logger.warn("Procedure failed, event has not received: " + url);
                        return false;
                    }
                    notifier.set(null);
                    return eventHandler.apply(event);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return false;
        }
    }

    private void handleEvent(BlueGigaScanResponseEvent scanEvent) {
        if (url.getDeviceAddress().equals(scanEvent.getSender())) {
            if (scanEvent.getData() != null) {
                Map<EirDataType, Object> eir = new EirPacket(scanEvent.getData()).getRecords();

                if (eir.containsKey(EirDataType.EIR_NAME_LONG) || eir.containsKey(EirDataType.EIR_NAME_SHORT)) {
                    this.name = String.valueOf(eir.getOrDefault(EirDataType.EIR_NAME_LONG,
                            eir.getOrDefault(EirDataType.EIR_NAME_SHORT, null)));
                }

                if (eir.containsKey(EirDataType.EIR_DEVICE_CLASS)) {
                    this.bluetoothClass = (int) eir.get(EirDataType.EIR_DEVICE_CLASS);
                }

                if (eir.containsKey(EirDataType.EIR_FLAGS)) {
                    List<EirFlags> eirFlags = (List<EirFlags>) eir.get(EirDataType.EIR_FLAGS);
                    // any flag would mean that the device is BLE enabled
                    this.bleEnabled = !eirFlags.isEmpty();
                }
            }
            this.rssi = (short) scanEvent.getRssi();
            notifyRSSIChanged(this.rssi);
        }
    }

    private void handleEvent(BlueGigaConnectionStatusEvent connectionEvent) {
        if (url.getDeviceAddress().equals(connectionEvent.getAddress())) {
            synchronized (connectionStatusEventHolder) {
                this.connectionHandle = connectionEvent.getConnection();
                this.addressType = connectionEvent.getAddressType();
                connectionStatusEventHolder.set(connectionEvent);
                connectionStatusEventHolder.notifyAll();
            }
            notifyConnected(true);
            bgFindPrimaryServices();
        }
    }

    private void handleEvent(BlueGigaDisconnectedEvent disconnectedEvent) {
        if (this.connectionHandle == disconnectedEvent.getConnection()) {
            synchronized (disconnectedEventHolder) {
                this.connectionHandle = -1;
                disconnectedEventHolder.set(disconnectedEvent);
                disconnectedEventHolder.notifyAll();
            }
            servicesUnresolved();
            notifyServicesResolved(false);
            notifyConnected(false);
        }
    }

    private void handleEvent(BlueGigaGroupFoundEvent serviceEvent) {
        // A service has been discovered
        if (connectionHandle != serviceEvent.getConnection()) {
            return;
        }

        synchronized (services) {
            URL serviceURL = url.copyWith(serviceEvent.getUuid().toString(), null);
            services.put(serviceURL, new BluegigaService(serviceURL));
        }
    }

    private void handleEvent(BlueGigaProcedureCompletedEvent completedEvent) {
        if (connectionHandle != completedEvent.getConnection()) {
            return;
        }

        if (!this.servicesResolved) {
            this.servicesResolved = true;
            bgFindCharacteristics();
        } else {
            this.characteristicsResolved = true;
            notifyServicesResolved(true);
        }
    }

    private void handleEvent(BlueGigaFindInformationFoundEvent infoEvent) {
        // A Characteristic has been discovered

        // If this is not our connection handle then ignore.
        if (connectionHandle != infoEvent.getConnection()) {
            return;
        }

        URL serviceURL = url.copyWithService(infoEvent.getUuid().toString());
        synchronized (services) {
            services.get(serviceURL).handleEvent(infoEvent);
        }
    }

    private void servicesUnresolved() {
        this.servicesResolved = false;
        this.characteristicsResolved = false;
        synchronized (services) {
            services.clear();
        }
    }

    private BgApiResponse bgConnect() {
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
        connect.setAddrType(addressType);
        connect.setConnIntervalMin(connIntervalMin);
        connect.setConnIntervalMax(connIntervalMax);
        connect.setLatency(latency);
        connect.setTimeout(timeout);
        BlueGigaConnectDirectResponse connectResponse = (BlueGigaConnectDirectResponse) bgHandler
                .sendTransaction(connect);
        return connectResponse.getResult();
    }

    private BgApiResponse bgDisconnect() {
        BlueGigaDisconnectCommand command = new BlueGigaDisconnectCommand();
        command.setConnection(connectionHandle);
        return ((BlueGigaDisconnectResponse) bgHandler.sendTransaction(command)).getResult();
    }

    private short bgGetRssi() {
        if (isConnected()) {
            BlueGigaGetRssiCommand rssiCommand = new BlueGigaGetRssiCommand();
            rssiCommand.setConnection(connectionHandle);
            return (short) ((BlueGigaGetRssiResponse) bgHandler.sendTransaction(rssiCommand)).getRssi();
        }
        return 0;
    }

    /**
     * Start a read of all primary services using {@link BlueGigaReadByGroupTypeCommand}
     *
     * @return true if successful
     */
    private boolean bgFindPrimaryServices() {
        logger.debug("BlueGiga FindPrimary: connection {}", connectionHandle);
        BlueGigaReadByGroupTypeCommand command = new BlueGigaReadByGroupTypeCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        command.setUuid(UUID.fromString("00002800-0000-0000-0000-000000000000"));
        BlueGigaReadByGroupTypeResponse response = (BlueGigaReadByGroupTypeResponse) bgHandler.sendTransaction(command);

        return response.getResult() == BgApiResponse.SUCCESS;
    }

    /**
     * Start a read of all characteristics using {@link BlueGigaFindInformationCommand}
     *
     * @return true if successful
     */
    private boolean bgFindCharacteristics() {
        logger.debug("BlueGiga Find: connection {}", connectionHandle);
        BlueGigaFindInformationCommand command = new BlueGigaFindInformationCommand();
        command.setConnection(connectionHandle);
        command.setStart(1);
        command.setEnd(65535);
        BlueGigaFindInformationResponse response = (BlueGigaFindInformationResponse) bgHandler.sendTransaction(command);

        return response.getResult() == BgApiResponse.SUCCESS;
    }

    private void notifyRSSIChanged(short rssi) {
        Notification<Short> notification = rssiNotification;
        if (notification != null) {
            try {
                notification.notify(rssi);
            } catch (Exception ex) {
                logger.error("Error while triggering RSSI notification", ex);
            }
        }
    }

    private void notifyConnected(boolean status) {
        Notification<Boolean> notification = connectedNotification;
        if (notification != null) {
            try {
                notification.notify(status);
            } catch (Exception ex) {
                logger.error("Error while triggering connected notification", ex);
            }
        }
    }

    private void notifyServicesResolved(boolean status) {
        Notification<Boolean> notification = serviceResolvedNotification;
        if (notification != null) {
            try {
                notification.notify(status);
            } catch (Exception ex) {
                logger.error("Error while triggering services resolved notification", ex);
            }
        }
    }
}

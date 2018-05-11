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

import com.zsmartsystems.bluetooth.bluegiga.BlueGigaEventListener;
import com.zsmartsystems.bluetooth.bluegiga.BlueGigaResponse;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaGroupFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaConnectionStatusEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaDisconnectedEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirDataType;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirFlags;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirPacket;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BgApiResponse;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BluetoothAddressType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.DataConversionUtils;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;
import org.sputnikdev.bluetooth.manager.transport.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bluegiga transport device.
 *
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
class BluegigaDevice implements Device, BlueGigaEventListener {

    protected static final int DISCOVERY_TIMEOUT = 10;
    private static final Pattern DEFAULT_UUID_REPLACEMENT =
        Pattern.compile("-0000-0000-0000-000000000000", Pattern.LITERAL);
    private static final String DEFAULT_UUID = "-0000-1000-8000-00805f9b34fb";

    private final Logger logger = LoggerFactory.getLogger(BluegigaDevice.class);
    private final URL url;
    private final BluegigaHandler bgHandler;
    private boolean disposed;
    private String name;
    private BluetoothAddressType addressType = BluetoothAddressType.UNKNOWN;
    private short rssi;
    private short txPower;
    private Instant lastDiscovered;
    private int bluetoothClass;
    private boolean bleEnabled;
    private boolean servicesResolved;
    private final Map<URL, BluegigaService> services = new ConcurrentHashMap<>();
    // just a local cache, BlueGiga adapters do not support aliases
    private String alias;
    private Map<Short, byte[]> manufacturerData = new ConcurrentHashMap<>();
    private Map<String, byte[]> serviceData = new ConcurrentHashMap<>();

    // Notifications/listeners
    private Notification<Short> rssiNotification;
    private Notification<Boolean> connectedNotification;
    private Notification<Boolean> serviceResolvedNotification;
    private Notification<Map<String, byte[]>> serviceDataNotification;
    private Notification<Map<Short, byte[]>> manufacturerDataNotification;

    // BG specific variables
    private int connectionHandle = -1;

    // locks
    private ReentrantLock serviceDiscoveryLock = new ReentrantLock();

    BluegigaDevice(BluegigaHandler bgHandler, URL url) {
        this.bgHandler = bgHandler;
        this.url = url;
        this.bgHandler.addEventListener(this);
    }

    BluegigaDevice(BluegigaHandler bgHandler, URL url, int connectionHandle, BluetoothAddressType addressType) {
        this.bgHandler = bgHandler;
        this.url = url;
        this.bgHandler.addEventListener(this);
        this.connectionHandle = connectionHandle;
        this.addressType = addressType;
        lastDiscovered = Instant.now();
    }

    @Override
    public boolean connect() {
        logger.debug("Connecting: {}", url);
        boolean established = getHandler().runInSynchronizedContext(() -> {
            if (!isConnected()) {
                // a workaround for a BGAPI bug when adapter becomes unstable when discovery is enabled within
                // an attempt to connect to a device
                // we first stop any current procedure (discovery), then do the connection procedure
                // and then restore discovery process
                boolean wasDiscovering = getHandler().isDiscovering();
                getHandler().bgStopProcedure();
                try {
                    establishConnection();
                    return true;
                } finally {
                    // restore discovery process if it was enabled
                    if (wasDiscovering) {
                        getHandler().bgStopProcedure();
                        getHandler().bgStartScanning();
                    }
                }
            }
            return false;
        });
        if (established) {
            notifyConnected(true);
        }
        return true;
    }

    @Override
    public boolean disconnect() {
        logger.debug("Disconnecting: {}", url);
        boolean changed = getHandler().runInSynchronizedContext(() -> {
            if (connectionHandle >= 0) {
                getHandler().disconnect(connectionHandle);
                connectionHandle = -1;
                return true;
            }
            return false;
        });
        if (changed) {
            if (servicesResolved) {
                servicesUnresolved();
            }
            notifyConnected(false);
        }
        return true;
    }

    @Override
    public int getBluetoothClass() {
        return bluetoothClass;
    }

    @Override
    public String getName() {
        return name == null ? url.getDeviceAddress() : name;
    }

    @Override
    public boolean isBleEnabled() {
        return bleEnabled;
    }

    @Override
    public short getRSSI() {
        boolean connected = connectionHandle != -1;
        logger.trace("Getting device RSSI: {} : {} (connected) : {} (rssi)", url, connected, rssi);
        if (connected) {
            rssi = getHandler().bgGetRssi(connectionHandle);
        } else if (lastDiscovered == null || lastDiscovered.isBefore(Instant.now().minusSeconds(DISCOVERY_TIMEOUT))) {
            logger.debug("Device has not reported RSSI for a long time: {}", url);
            return 0;
        }
        return rssi;
    }

    @Override
    public short getTxPower() {
        return txPower;
    }

    @Override
    public void enableRSSINotifications(Notification<Short> notification) {
        logger.debug("Enable RSSI notifications: {}", url);
        rssiNotification = notification;
    }

    @Override
    public void disableRSSINotifications() {
        logger.debug("Disable RSSI notifications: {}", url);
        rssiNotification = null;
    }

    @Override
    public boolean isConnected() {
        logger.trace("Checking if device connected: {} : {}", url, connectionHandle);
        if (connectionHandle == -1) {
            return false;
        }
        BlueGigaConnectionStatusEvent connectionStatusEvent = getHandler().getConnectionStatus(connectionHandle);
        if (url.getDeviceAddress().equals(connectionStatusEvent.getAddress())) {
            logger.debug("Device is connected: {} : {}", url, connectionStatusEvent.getFlags());
            return true;
        } else {
            logger.warn("Device is not connected: {} : {} : {}",
                    url, connectionStatusEvent.getAddress(), connectionHandle);
            return false;
        }

    }

    @Override
    public void enableConnectedNotifications(Notification<Boolean> notification) {
        logger.debug("Enable connected notifications: {}", url);
        connectedNotification = notification;
    }

    @Override
    public void disableConnectedNotifications() {
        logger.debug("Disable connected notifications: {}", url);
        connectedNotification = null;
    }

    @Override
    public boolean isServicesResolved() {
        return servicesResolved;
    }

    @Override
    public void enableServicesResolvedNotifications(Notification<Boolean> notification) {
        logger.debug("Enable service resolved notifications: {}", url);
        serviceResolvedNotification = notification;
    }

    @Override
    public void disableServicesResolvedNotifications() {
        logger.debug("Disable service resolved notifications: {}", url);
        serviceResolvedNotification = null;
    }

    @Override
    public List<Service> getServices() {
        if (!servicesResolved) {
            return Collections.emptyList();
        }
        return new ArrayList<>(services.values());
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        try {
            if (event instanceof BlueGigaScanResponseEvent) {
                handleScanEvent((BlueGigaScanResponseEvent) event);
            } else if (event instanceof BlueGigaDisconnectedEvent) {
                handleDisconnectedEvent((BlueGigaDisconnectedEvent) event);
            } else if (event instanceof BlueGigaConnectionStatusEvent) {
                handleConnectionStatusEvent((BlueGigaConnectionStatusEvent) event);
            }
        } catch (BluegigaProcedureException ex) {
            logger.debug("Bluegiga procedure exception occurred while handling bluegiga event: {} : {} : {}",
                    url, event, ex.getMessage());
            // events can lead to some procedures (e.g. service discovery etc) that can cause disconnections
            // no disconnection events are issued in that case
            if (ex.getResponse() == BgApiResponse.NOT_CONNECTED) {
                connectionHandle = -1;
                notifyConnected(false);
            }
        } catch (Exception ex) {
            logger.warn("Unexpected exception occurred while handling bluegiga event: {} : {} : {}",
                    url, event, ex.getMessage());
            if (connectionHandle != -1 && !isConnected()) {
                // looks like we have been disconnected but the disconnection event had been missed
                connectionHandle = -1;
                notifyConnected(false);
            }
        }
    }

    /*
      Aliases are not supported by BlueGiga
     */
    @Override
    public String getAlias() {
        return alias;
    }

    @Override
    public void setAlias(String alias) {
        this.alias = alias;
    }

    /*
        Blocking is not supported by Bluegiga devices
     */
    @Override
    public void setBlocked(boolean blocked) { /* do nothing */ }

    @Override
    public boolean isBlocked() {
        return false;
    }

    @Override
    public void enableBlockedNotifications(Notification<Boolean> notification) { /* do nothing */ }

    @Override
    public void disableBlockedNotifications() { /* do nothing */ }

    @Override
    public Map<String, byte[]> getServiceData() {
        return new HashMap<>(serviceData);
    }

    @Override
    public Map<Short, byte[]> getManufacturerData() {
        return new HashMap<>(manufacturerData);
    }

    @Override
    public org.sputnikdev.bluetooth.manager.BluetoothAddressType getAddressType() {
        switch (addressType != null ? addressType : BluetoothAddressType.UNKNOWN) {
            case GAP_ADDRESS_TYPE_PUBLIC: return org.sputnikdev.bluetooth.manager.BluetoothAddressType.PUBLIC;
            case GAP_ADDRESS_TYPE_RANDOM: return org.sputnikdev.bluetooth.manager.BluetoothAddressType.RANDOM;
            default: return org.sputnikdev.bluetooth.manager.BluetoothAddressType.UNKNOWN;
        }
    }

    @Override
    public void enableServiceDataNotifications(Notification<Map<String, byte[]>> notification) {
        logger.debug("Enable service data notifications: {}", url);
        serviceDataNotification = notification;
    }

    @Override
    public void disableServiceDataNotifications() {
        logger.debug("Disable service data notifications: {}", url);
        serviceDataNotification = null;
    }

    @Override
    public void enableManufacturerDataNotifications(Notification<Map<Short, byte[]>> notification) {
        logger.debug("Enable manufacturer data notifications: {}", url);
        manufacturerDataNotification = notification;
    }

    @Override
    public void disableManufacturerDataNotifications() {
        logger.debug("Disable manufacturer data notifications: {}", url);
        manufacturerDataNotification = null;
    }

    protected Instant getLastDiscovered() {
        return lastDiscovered;
    }

    protected void dispose() {
        logger.debug("Disposing device: {} / {}", url, Integer.toHexString(hashCode()));
        try {
            disconnect();
        } catch (Exception ignore) { /* do nothing */ }
        connectionHandle = -1;
        bgHandler.removeEventListener(this);
        disposeServices();
        // just helping GC to release resources
        rssiNotification = null;
        connectedNotification = null;
        serviceResolvedNotification = null;
        serviceDataNotification = null;
        manufacturerDataNotification = null;
        manufacturerData = null;
        serviceData = null;
        disposed = true;
        logger.debug("Device disposed: {}", url);
    }

    protected BluegigaService getService(URL url) {
        if (!servicesResolved) {
            return null;
        }
        return services.get(url.getServiceURL());
    }

    protected void establishConnection() {
        logger.debug("Trying to connect: {} : {}", url, addressType);
        try {
            tryToConnect(addressType != BluetoothAddressType.UNKNOWN
                    ? addressType : BluetoothAddressType.GAP_ADDRESS_TYPE_PUBLIC);
        } catch (BluegigaTimeoutException | BluegigaProcedureException ex) {
            if (addressType != BluetoothAddressType.UNKNOWN && !isRetriable(ex)) {
                throw ex;
            }
            logger.warn("Exception occurred while connecting to a device. Address type is unknown. "
                    + "Retrying with 'random' address type: {}", url);
            getHandler().bgStopProcedure();
            tryToConnect(BluetoothAddressType.GAP_ADDRESS_TYPE_RANDOM);
        }
        logger.debug("Connected: {}", url);
    }

    protected void discoverAttributes() {
        if (!servicesResolved && connectionHandle != -1) {
            boolean resolved = getHandler().runInSynchronizedContext(() -> {
                if (!servicesResolved) {
                    logger.debug("Resolving services: {}", url);
                    try {
                        discoverServices();
                        List<BluegigaService> servicesTable = services.values().stream().sorted(
                                Comparator.comparingInt(BluegigaService::getHandleStart)).collect(Collectors.toList());
                        discoverCharacteristics(servicesTable);
                        discoverDeclarations(servicesTable);
                        servicesResolved = true;
                        logger.debug("Services resolved: {}", url);
                        return true;
                    } catch (Exception ex) {
                        disposeServices();
                        logger.warn("Could not discover device attributes: {}", url, ex);
                        throw ex;
                    }
                }
                return false;
            });
            if (resolved) {
                notifyServicesResolved(true);
            }
        }
    }

    private boolean isRetriable(BluegigaException ex) {
        return ex instanceof BluegigaTimeoutException
                || ex instanceof BluegigaProcedureException
                        && ((BluegigaProcedureException) ex).getResponse() == BgApiResponse.WRONG_STATE;
    }


    private BluegigaHandler getHandler() {
        if (disposed) {
            throw new BluegigaException("Device has been disposed");
        }
        return bgHandler;
    }

    private void handleConnectionStatusEvent(BlueGigaConnectionStatusEvent event) {
        if (event.getAddress().equals(url.getDeviceAddress())
                && (connectionHandle == -1 || !servicesResolved)) {
            logger.debug("Connection event received: {} : {}", url, event);
            if (connectionHandle == -1) {
                connectionHandle = event.getConnection();
                notifyConnected(true);
            }
            if (serviceDiscoveryLock.tryLock()) {
                try {
                    discoverAttributes();
                } finally {
                    serviceDiscoveryLock.unlock();
                }
            }
        }
    }



    private void tryToConnect(BluetoothAddressType addressType) {
        BlueGigaConnectionStatusEvent event = getHandler().connect(url, addressType);
        connectionHandle = event.getConnection();
        this.addressType = event.getAddressType();
    }

    protected void discoverServices() {
        logger.debug("Discovering services: {}", url);
        // discover services
        getHandler().getServices(connectionHandle)
            .stream().map(this::convert).forEach(service -> services.put(service.getURL(), service));
        logger.debug("Services discovered: {}", services.size());
    }

    protected void discoverCharacteristics(List<BluegigaService> servicesTable) {
        logger.debug("Discovering characteristics: {}", url);
        // discover characteristics and their descriptors
        List<BlueGigaFindInformationFoundEvent> infoEvents = getHandler().getCharacteristics(connectionHandle);
        logger.debug("Info events received: {} : {}", url, infoEvents.size());
        processAttributes(servicesTable, infoEvents);
        logger.debug("Characteristics discovered: {}", url);
    }

    protected void discoverDeclarations(List<BluegigaService> servicesTable) {
        logger.debug("Discovering declarations: {}", url);
        // discover characteristic properties (access flags)
        List<BlueGigaAttributeValueEvent> attEvents = getHandler().getDeclarations(connectionHandle);
        logger.debug("Attribute events received: {} : {}", url, attEvents.size());
        processDeclarations(servicesTable, attEvents);
        logger.debug("Declarations discovered: {}", url);
    }

    protected int getConnectionHandle() {
        return connectionHandle;
    }

    private void handleScanEvent(BlueGigaScanResponseEvent scanEvent) {
        if (url.getDeviceAddress().equals(scanEvent.getSender())) {
            logger.trace("Advertising message received: {}", url);
            rssi = (short) scanEvent.getRssi();
            addressType = scanEvent.getAddressType();
            lastDiscovered = Instant.now();
            notifyRSSIChanged(rssi);
            if (scanEvent.getData() != null) {
                Map<EirDataType, Object> eir = new EirPacket(scanEvent.getData()).getRecords();

                if (eir.containsKey(EirDataType.EIR_NAME_LONG) || eir.containsKey(EirDataType.EIR_NAME_SHORT)) {
                    name = String.valueOf(eir.getOrDefault(EirDataType.EIR_NAME_LONG,
                            eir.getOrDefault(EirDataType.EIR_NAME_SHORT, null)));
                }

                if (eir.containsKey(EirDataType.EIR_DEVICE_CLASS)) {
                    bluetoothClass = (int) eir.get(EirDataType.EIR_DEVICE_CLASS);
                }

                if (eir.containsKey(EirDataType.EIR_FLAGS)) {
                    List<EirFlags> eirFlags = (List<EirFlags>) eir.get(EirDataType.EIR_FLAGS);
                    // any flag would mean that the device is BLE enabled
                    bleEnabled = !eirFlags.isEmpty();
                }

                if (eir.containsKey(EirDataType.EIR_TXPOWER)) {
                    txPower = (short) (int) eir.get(EirDataType.EIR_TXPOWER);
                }
                if (handleServiceData(eir, EirDataType.EIR_SVC_DATA_UUID16)
                        | handleServiceData(eir, EirDataType.EIR_SVC_DATA_UUID32)
                        | handleServiceData(eir, EirDataType.EIR_SVC_DATA_UUID128)) {
                    notifyServiceDataChanged();
                }

                if (eir.containsKey(EirDataType.EIR_MANUFACTURER_SPECIFIC)) {
                    handleManufacturerData(eir);
                }
            }
        }
    }

    private void handleManufacturerData(Map<EirDataType, Object> eir) {
        logger.trace("Manufacturer data changed: {}", url);
        Map<Short, int[]> rawData =
                (Map<Short, int[]>) eir.get(EirDataType.EIR_MANUFACTURER_SPECIFIC);

        if (logger.isTraceEnabled()) {
            logger.trace("Manufacturer data changed: {} : {}", url, rawData.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> DataConversionUtils.convert(BluegigaUtils.fromInts(entry.getValue()), 16))));
        }

        rawData.forEach((id, data) ->
                manufacturerData.compute(id, (key, value) -> BluegigaUtils.fromInts(data)));
        notifyManufacturerDataChanged();
    }

    private boolean handleServiceData(Map<EirDataType, Object> eir, EirDataType type) {
        if (eir.containsKey(type)) {
            Map<UUID, int[]> svcData = (Map<UUID, int[]>) eir.get(type);

            if (logger.isTraceEnabled()) {
                logger.trace("Service data changed: {} : {}", url, svcData.entrySet().stream()
                        .collect(Collectors.toMap(entry -> getUUID(entry.getKey()),
                            entry -> DataConversionUtils.convert(BluegigaUtils.fromInts(entry.getValue()), 16))));
            }

            svcData.forEach((uuid, data) ->
                    serviceData.compute(getUUID(uuid), (key, value) -> BluegigaUtils.fromInts(data)));

            return true;
        }
        return false;
    }

    private void notifyServiceDataChanged() {
        Notification<Map<String, byte[]>> notification = serviceDataNotification;
        if (notification != null) {
            try {
                notification.notify(new HashMap<>(serviceData));
            } catch (Exception ex) {
                logger.error("Error while executing service data changed notification", ex);
            }
        }
    }

    private void notifyManufacturerDataChanged() {
        Notification<Map<Short, byte[]>> notification = manufacturerDataNotification;
        if (notification != null) {
            try {
                notification.notify(new HashMap<>(manufacturerData));
            } catch (Exception ex) {
                logger.error("Error while executing manufacturer data changed notification", ex);
            }
        }
    }

    private void handleDisconnectedEvent(BlueGigaDisconnectedEvent event) {
        if (connectionHandle == event.getConnection()) {
            logger.warn("Disconnection event received {}. Reason: {}.", url, event.getReason());
            if (connectionHandle != -1) {
                connectionHandle = -1;
                servicesUnresolved();
                notifyConnected(false);
            }
        }
    }

    private void servicesUnresolved() {
        disposeServices();
        if (servicesResolved) {
            notifyServicesResolved(false);
        }
        servicesResolved = false;
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

    private BluegigaService convert(BlueGigaGroupFoundEvent event) {
        return new BluegigaService(url.copyWith(getUUID(event.getUuid()), null),
            event.getStart(), event.getEnd());
    }

    private void processAttributes(List<BluegigaService> servicesTable,
                                   List<BlueGigaFindInformationFoundEvent> events) {
        /*
        Info on how to match descriptors to characteristics:
        https://www.safaribooksonline.com/library/view/getting-started-with/9781491900550/ch04.html

        Once the boundaries (in terms of handles) of a target characteristic have been established,
        the client can go on to characteristic descriptor discovery.

        A handle is a numeric identifier of an attribute (service, characteristic or descriptor) in the GATT table.
        Services strictly define ranges of handles that they occupy. A range of a service defines what characteristics
        and descriptors that service consists of. All characteristics and descriptors, which belong to
        a specific service, take place within the service handle range.

        The way how to determine which descriptor belongs to which characteristic is a bit tricky. GATT specification
        says that all characteristic and their descriptors are ordered in the GATT table,
        i.e. normally descriptors go after their characteristic. This means that what's between two characteristics
        belongs to the left-side characteristic etc.
         */

        events.sort(Comparator.comparingInt(BlueGigaFindInformationFoundEvent::getChrHandle));

        BluegigaCharacteristic characteristic = null;

        Iterator<BluegigaService> servicesIterator = servicesTable.iterator();
        BluegigaService bluegigaService = servicesIterator.next();
        for (BlueGigaFindInformationFoundEvent event : events) {
            if (event.getChrHandle() > bluegigaService.getHandleEnd()) {
                bluegigaService = servicesIterator.next();
            }
            UUID attributeUUID = event.getUuid();
            // this is a short version of UUID, we need it to find out type of attribute
            long shortUUID = attributeUUID.getMostSignificantBits() >> 32;
            if (shortUUID >= 0x2800 && shortUUID <= 0x280F) {
                // Declarations (https://www.bluetooth.com/specifications/gatt/declarations)
                // we will skip them as we are not interested in them
                logger.debug("Skipping a declaration: " + attributeUUID);
            } else {
                if (bluegigaService == null) {
                    throw new BluegigaException("Could not find a service by characteristic handle: "
                        + event.getChrHandle());
                }
                if (shortUUID >= 0x2900 && shortUUID <= 0x290F) {
                    // Descriptors (https://www.bluetooth.com/specifications/gatt/descriptors)
                    if (characteristic == null) {
                        logger.error("Came across a descriptor, but there is not any characteristic so far... "
                                + "Characteristic should go first followed by its descriptors. {}",
                                attributeUUID);
                        throw new IllegalStateException("A characteristic was expected to go first");
                    }
                    logger.debug("Create a new descriptor: {} : {}", characteristic.getURL(), event.getChrHandle());
                    BluegigaDescriptor descriptor = new BluegigaDescriptor(getHandler(),
                            connectionHandle, event.getChrHandle(), attributeUUID);
                    characteristic.addDescriptor(descriptor);
                } else {
                    // Characteristics
                    URL characteristicURL = bluegigaService.getURL().copyWithCharacteristic(getUUID(event.getUuid()));
                    logger.debug("Create a new characteristic: {}", characteristicURL);
                    characteristic = new BluegigaCharacteristic(getHandler(), characteristicURL,
                            connectionHandle, event.getChrHandle());
                    bluegigaService.addCharacteristic(characteristic);
                }
            }
        }
    }

    private static String getUUID(UUID uuid) {
        return DEFAULT_UUID_REPLACEMENT.matcher(uuid.toString()).replaceAll(Matcher.quoteReplacement(DEFAULT_UUID));
    }

    private void processDeclarations(List<BluegigaService> servicesTable, List<BlueGigaAttributeValueEvent> events) {
        /*
        It always contains a handle, a UUID, and a set of properties. These three elements describe the subsequent
        Characteristic Value Declaration. The handle naturally points to the Characteristic Value Declaration&rsquo;s
        place in the attribute table. The UUID describes what type of information or value we can expect to find
        in the Characteristic Value Declaration. For example, a temperature value, the state of a light switch,
        or some custom arbitrary value. And finally, the properties describe how the characteristic value can be
        interacted with.
        Example: 10-0E-00-37-2A
            0x2A37 is the characteristic UUID
            000E is the declaration handle
            10 is the characteristic properties as per this table:

            Broadcast                       0x01
            Read                            0x02
            Write without response          0x04
            Write                           0x08
            Notify                          0x10
            Indicate                        0x20
            Authenticated signed writes     0x40
            Extended properties             0x80

         Taken from a tutorial: https://devzone.nordicsemi.com/tutorials/17/
         Official spec:
         https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.attribute.gatt.characteristic_declaration.xml
         */

        events.sort(Comparator.comparingInt(BlueGigaAttributeValueEvent::getAttHandle));

        Iterator<BluegigaService> servicesIterator = servicesTable.iterator();
        BluegigaService bluegigaService = servicesIterator.next();
        for (BlueGigaAttributeValueEvent event : events) {
            //  characteristic declaration
            int[] attributeValue = event.getValue();

            if (event.getAttHandle() > bluegigaService.getHandleEnd()) {
                bluegigaService = servicesIterator.next();
            }

            String characteristicUUID = getUUID(BluegigaUtils.deserializeUUID(
                    Arrays.copyOfRange(attributeValue, 3, attributeValue.length)));
            BluegigaCharacteristic bluegigaCharacteristic =
                    bluegigaService.getCharacteristic(
                            bluegigaService.getURL().copyWithCharacteristic(characteristicUUID));
            if (bluegigaCharacteristic != null) {
                bluegigaCharacteristic.setFlags(CharacteristicAccessType.parse(attributeValue[0]));
            } else {
                logger.error("Could not find characteristic: {}", characteristicUUID);
            }

        }
    }

    private void disposeServices() {
        services.values().stream().flatMap(service -> service.getCharacteristics().stream())
                .forEach(characteristic -> ((BluegigaCharacteristic) characteristic).dispose());
        services.clear();
    }

}

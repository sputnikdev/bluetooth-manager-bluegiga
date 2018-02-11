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
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;
import org.sputnikdev.bluetooth.manager.transport.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
    private String name;
    private BluetoothAddressType addressType = BluetoothAddressType.UNKNOWN;
    private short rssi;
    private short txPower;
    private Instant lastDiscovered;
    private int bluetoothClass;
    private boolean bleEnabled;
    private boolean servicesResolved;
    private boolean servicesCached;
    private boolean characteristicCached;
    private boolean declarationsCached;
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

    BluegigaDevice(BluegigaHandler bgHandler, URL url) {
        this.bgHandler = bgHandler;
        this.url = url;
        this.bgHandler.addEventListener(this);
    }

    @Override
    public boolean connect() {
        logger.debug("Connecting: {}", url);
        boolean connected = bgHandler.runInSynchronizedContext(() -> {
            if (!isConnected()) {

                // a workaround for a BGAPI bug when adapter becomes unstable when discovery is enabled within
                // an attempt to connect to a device
                // we first stop any current procedure (discovery), then do the connection procedure
                // and then restore discovery process
                boolean wasDiscovering = bgHandler.isDiscovering();
                bgHandler.bgStopProcedure();

                try {
                    establishConnection();
                } finally {
                    // restore discovery process if it was enabled
                    if (wasDiscovering) {
                        bgHandler.bgStopProcedure();
                        bgHandler.bgStartScanning();
                    }
                }
            }
            return isConnected();
        });
        logger.debug("Connected {}. Resolving services: {}", connected, url);
        notifyConnected(connected);

        if (connected) {
            boolean servicesResolved = bgHandler.runInSynchronizedContext(() -> {
                if (isConnected()) {
                    if (!servicesCached) {
                        discoverServices();
                        servicesCached = true;
                    }

                    if (!characteristicCached) {
                        discoverCharacteristics();
                        characteristicCached = true;
                    }

                    if (!declarationsCached) {
                        discoverDeclarations();
                        declarationsCached = true;
                    }
                    return true;
                }
                return false;
            });
            if (servicesResolved) {
                serviceResolved();
            }
        }
        logger.debug("Services resolved: {} / {}", servicesResolved, url);
        return connected && servicesResolved;
    }

    @Override
    public boolean disconnect() {
        logger.debug("Disconnecting: {}", url);
        boolean result = bgHandler.runInSynchronizedContext(() -> {
            if (connectionHandle >= 0) {
                try {
                    bgHandler.disconnect(connectionHandle);
                } finally {
                    connectionHandle = -1;
                }
            }
            return true;
        });
        servicesUnresolved();
        notifyConnected(false);
        return result;
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
        if (isConnected()) {
            rssi = bgHandler.bgGetRssi(connectionHandle);
        } else if (lastDiscovered == null || lastDiscovered.isBefore(Instant.now().minusSeconds(DISCOVERY_TIMEOUT))) {
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
        bgHandler.checkAlive();
        return connectionHandle >= 0;
    }

    @Override
    public void enableConnectedNotifications(Notification<Boolean> notification) {
        logger.debug("Enable connected notifications: {}", url);
        connectedNotification = notification;
    }

    @Override
    public void disableConnectedNotifications() {
        logger.debug("Disable connected notifications: {}", url);
        this.connectedNotification = null;
    }

    @Override
    public boolean isServicesResolved() {
        return this.servicesResolved;
    }

    @Override
    public void enableServicesResolvedNotifications(Notification<Boolean> notification) {
        logger.debug("Enable service resolved notifications: {}", url);
        this.serviceResolvedNotification = notification;
    }

    @Override
    public void disableServicesResolvedNotifications() {
        logger.debug("Disable service resolved notifications: {}", url);
        this.serviceResolvedNotification = null;
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
    public void dispose() {
        logger.debug("Disposing device: {} / {}", url, Integer.toHexString(hashCode()));
        disconnect();
        services.clear();
        servicesCached = false;
        characteristicCached = false;
        declarationsCached = false;
        logger.debug("Device disposed: {}", url);
    }

    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        if (event instanceof BlueGigaScanResponseEvent) {
            handleScanEvent((BlueGigaScanResponseEvent) event);
        } else if (event instanceof BlueGigaDisconnectedEvent) {
            handleDisconnectedEvent((BlueGigaDisconnectedEvent) event);
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

    protected BluegigaService getService(URL url) {
        if (!servicesResolved) {
            return null;
        }
        return services.get(url.getServiceURL());
    }

    protected void establishConnection() {
        logger.debug("Trying to connect: {}", url);
        BlueGigaConnectionStatusEvent event = bgHandler.connect(url,
                addressType != null ? addressType : BluetoothAddressType.UNKNOWN);
        logger.debug("Connected: {}", url);
        connectionHandle = event.getConnection();
    }

    protected void discoverServices() {
        logger.debug("Discovering services: {}", url);
        // discover services
        bgHandler.getServices(connectionHandle)
            .stream().map(this::convert).forEach(service -> services.put(service.getURL(), service));
        logger.debug("Services discovered: {}", services.size());
    }

    protected void discoverCharacteristics() {
        logger.debug("Discovering characteristics: {}", url);
        // discover characteristics and their descriptors
        processAttributes(bgHandler.getCharacteristics(connectionHandle));
        logger.debug("Characteristics discovered: {}", url);
    }

    protected void discoverDeclarations() {
        logger.debug("Discovering declarations: {}", url);
        // discover characteristic properties (access flags)
        bgHandler.getDeclarations(connectionHandle).forEach(this::processDeclaration);
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
                    logger.trace("Manufacturer data changed: {}", url);
                    manufacturerData.putAll((Map<Short, byte[]>) eir.get(EirDataType.EIR_MANUFACTURER_SPECIFIC));
                    notifyManufacturerDataChanged();
                }
            }
        }
    }

    private boolean handleServiceData(Map<EirDataType, Object> eir, EirDataType type) {
        if (eir.containsKey(type)) {
            logger.trace("Service data changed: {} / {}", url, type);
            Map<UUID, int[]> svcData = (Map<UUID, int[]>) eir.get(type);

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
            logger.debug("Disconnection even received {}. Reason: {}.", url, event.getReason());
            if (event.getReason() != BgApiResponse.CONNECTION_TERMINATED_BY_LOCAL_HOST) {
                servicesUnresolved();
                notifyConnected(false);
            }
            connectionHandle = -1;
        }
    }

    private void servicesUnresolved() {
        if (servicesResolved) {
            servicesResolved = false;
            notifyServicesResolved(false);
        }
    }

    private void serviceResolved() {
        servicesResolved = true;
        notifyServicesResolved(true);
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

    private void processAttributes(List<BlueGigaFindInformationFoundEvent> events) {
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
        // an attribute table ordered by handles
        TreeMap<Integer, BlueGigaFindInformationFoundEvent> attributeTable = new TreeMap<>(events.stream().collect(
                Collectors.toMap(BlueGigaFindInformationFoundEvent::getChrHandle, Function.identity())));

        BluegigaCharacteristic characteristic = null;

        for (Map.Entry<Integer, BlueGigaFindInformationFoundEvent> entry : attributeTable.entrySet()) {
            BlueGigaFindInformationFoundEvent event = entry.getValue();
            UUID attributeUUID = event.getUuid();
            // this is a short version of UUID, we need it to find out type of attribute
            long shortUUID = attributeUUID.getMostSignificantBits() >> 32;
            if (shortUUID >= 0x2800 && shortUUID <= 0x280F) {
                // Declarations (https://www.bluetooth.com/specifications/gatt/declarations)
                // we will skip them as we are not interested in them
                logger.debug("Skipping a declaration: " + attributeUUID);
            } else {
                BluegigaService bluegigaService = getServiceByHandle(event.getChrHandle());
                if (bluegigaService == null) {
                    throw new BluegigaException("Could not find a service by characteristic handle: "
                        + event.getChrHandle());
                }
                URL attributeURL = bluegigaService.getURL().copyWithCharacteristic(getUUID(event.getUuid()));
                if (shortUUID >= 0x2900 && shortUUID <= 0x290F) {
                    // Descriptors (https://www.bluetooth.com/specifications/gatt/descriptors)
                    if (characteristic == null) {
                        logger.error("Came across a descriptor, but there is not any characteristic so far... "
                                + "Characteristic should go first followed by its descriptors. {}",
                                attributeUUID);
                        throw new IllegalStateException("A characteristic was expected to go first");
                    }
                    logger.debug("Create a new descriptor: {}", attributeURL);
                    BluegigaDescriptor descriptor = new BluegigaDescriptor(bgHandler, attributeURL,
                            connectionHandle, event.getChrHandle());
                    characteristic.addDescriptor(descriptor);
                } else {
                    // Characteristics
                    logger.debug("Create a new characteristic: {}", attributeURL);
                    characteristic = new BluegigaCharacteristic(bgHandler, attributeURL,
                            connectionHandle, event.getChrHandle());
                    bluegigaService.addCharacteristic(characteristic);
                }
            }
        }
    }

    private static String getUUID(UUID uuid) {
        return DEFAULT_UUID_REPLACEMENT.matcher(uuid.toString()).replaceAll(Matcher.quoteReplacement(DEFAULT_UUID));
    }

    private void processDeclaration(BlueGigaAttributeValueEvent event) {
        //  characteristic declaration
        int[] attributeValue = event.getValue();

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

        BluegigaService service = getServiceByHandle(event.getAttHandle());
        if (service != null) {
            String characteristicUUID = getUUID(BluegigaUtils.deserializeUUID(
                    Arrays.copyOfRange(attributeValue, 3, attributeValue.length)));
            BluegigaCharacteristic bluegigaCharacteristic =
                    service.getCharacteristic(service.getURL().copyWithCharacteristic(characteristicUUID));
            if (bluegigaCharacteristic != null) {
                bluegigaCharacteristic.setFlags(CharacteristicAccessType.parse(attributeValue[0]));
            } else {
                logger.error("Could not find characteristic: {}", characteristicUUID);
            }

        } else {
            logger.error("Could not find service by handle: {}", event.getAttHandle());
        }
    }

    private BluegigaService getServiceByHandle(int handle) {
        return services.values().stream()
                    .filter(service -> handle >= service.getHandleStart() && handle <= service.getHandleEnd())
                    .findFirst().orElse(null);
    }

}

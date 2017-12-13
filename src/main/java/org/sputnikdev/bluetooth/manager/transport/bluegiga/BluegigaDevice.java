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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bluegiga transport device.
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
    private short rssi;
    private short txPower;
    private Instant lastDiscovered;
    private int bluetoothClass;
    private boolean bleEnabled;
    private boolean servicesResolved;
    private final Map<URL, BluegigaService> services = new HashMap<>();
    // just a local cache, BlueGiga adapters do not support aliases
    private String alias;

    // Notifications/listeners
    private Notification<Short> rssiNotification;
    private Notification<Boolean> connectedNotification;
    private Notification<Boolean> serviceResolvedNotification;

    // BG specific variables
    private int connectionHandle = -1;

    BluegigaDevice(BluegigaHandler bgHandler, URL url) {
        this.bgHandler = bgHandler;
        this.url = url;
        this.bgHandler.addEventListener(this);
    }

    @Override
    public boolean connect() {
        return bgHandler.runInSynchronizedContext(() -> {
            if (!isConnected()) {

                // a workaround for a BGAPI bug when adapter becomes unstable when discovery is enabled within
                // an attempt to connect to a device
                // we first stop any current procedure (discovery), then do the connection procedure
                // and then restore discovery process
                boolean wasDiscovering = bgHandler.isDiscovering();
                bgHandler.bgStopProcedure();

                try {
                    establishConnection();

                    discoverServices();

                    discoverCharacteristics();

                    discoverDeclarations();

                    serviceResolved();
                } finally {
                    // resore discovery process if it was enabled
                    if (wasDiscovering) {
                        bgHandler.bgStopProcedure();
                        bgHandler.bgStartScanning();
                    }
                }
            }
            return true;
        });
    }

    @Override
    public boolean disconnect() {
        return bgHandler.runInSynchronizedContext(() -> {
            if (connectionHandle >= 0) {
                try {
                    bgHandler.disconnect(connectionHandle);
                } finally {
                    connectionHandle = -1;
                }
                servicesUnresolved();
                notifyConnected(false);
            }
            return true;
        });
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
        rssiNotification = notification;
    }

    @Override
    public void disableRSSINotifications() {
        rssiNotification = null;
    }

    @Override
    public boolean isConnected() {
        return bgHandler.isAlive() && connectionHandle >= 0;
    }

    @Override
    public void enableConnectedNotifications(Notification<Boolean> notification) {
        connectedNotification = notification;
    }

    @Override
    public void disableConnectedNotifications() {
        this.connectedNotification = null;
    }

    @Override
    public boolean isServicesResolved() {
        return this.servicesResolved;
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

    protected BluegigaService getService(URL url) {
        synchronized (services) {
            return services.get(url.getServiceURL());
        }
    }

    protected void establishConnection() {
        logger.info("Trying to connect: {}", url);
        BlueGigaConnectionStatusEvent event = bgHandler.connect(url);
        logger.info("Connected: {}", url);

        connectionHandle = event.getConnection();
        notifyConnected(true);
    }

    protected void discoverServices() {
        logger.info("Discovering services: {}", url);
        // discover services
        bgHandler.getServices(connectionHandle)
            .stream().map(this::convert).forEach(service -> services.put(service.getURL(), service));
        logger.info("Services discovered: {}", services.size());
    }

    protected void discoverCharacteristics() {
        logger.info("Discovering characteristics: {}", url);
        // discover characteristics and their descriptors
        processAttributes(bgHandler.getCharacteristics(connectionHandle));
        logger.info("Characteristics discovered");
    }

    protected void discoverDeclarations() {
        logger.info("Discovering declarations: {}", url);
        // discover characteristic properties (access flags)
        bgHandler.getDeclarations(connectionHandle).forEach(this::processDeclaration);
        logger.info("Declarations discovered: {}", url);
    }

    protected int getConnectionHandle() {
        return connectionHandle;
    }

    private void handleScanEvent(BlueGigaScanResponseEvent scanEvent) {
        if (url.getDeviceAddress().equals(scanEvent.getSender())) {
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
            }
            rssi = (short) scanEvent.getRssi();
            lastDiscovered = Instant.now();
            notifyRSSIChanged(rssi);
        }
    }

    private void handleDisconnectedEvent(BlueGigaDisconnectedEvent event) {
        if (connectionHandle == event.getConnection()) {
            logger.info("Disconnecion even received {}. Reason: {}.", url, event.getReason());
            if (event.getReason() != BgApiResponse.CONNECTION_TERMINATED_BY_LOCAL_HOST) {
                servicesUnresolved();
                notifyConnected(false);
            }
            connectionHandle = -1;
        }
    }

    private void servicesUnresolved() {
        if (servicesResolved) {
            synchronized (services) {
                services.clear();
            }
            notifyServicesResolved(false);
            servicesResolved = false;
        }
    }

    private void serviceResolved() {
        notifyServicesResolved(true);
        servicesResolved = true;
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
                        logger.warn("Came across a descriptor, but there is not any characteristic so far... "
                                + "Characteristic should go first followed by its descriptors. {}",
                                attributeUUID);
                        throw new IllegalStateException("A characteristic was expected to go first");
                    }
                    BluegigaDescriptor descriptor = new BluegigaDescriptor(bgHandler, attributeURL,
                            connectionHandle, event.getChrHandle());
                    characteristic.addDescriptor(descriptor);
                } else {
                    // Characteristics
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
        synchronized (services) {
            return services.values().stream()
                    .filter(service -> handle >= service.getHandleStart() && handle <= service.getHandleEnd())
                    .findFirst().orElse(null);
        }
    }

}

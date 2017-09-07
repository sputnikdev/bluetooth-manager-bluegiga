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

import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaAttributeValueEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaFindInformationFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.attributeclient.BlueGigaGroupFoundEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaConnectionStatusEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirDataType;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirFlags;
import com.zsmartsystems.bluetooth.bluegiga.eir.EirPacket;
import com.zsmartsystems.bluetooth.bluegiga.enumeration.BluetoothAddressType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;
import org.sputnikdev.bluetooth.manager.transport.Service;

import java.util.*;

/**
 *
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
class BluegigaDevice implements Device {

    private final Logger logger = LoggerFactory.getLogger(BluegigaDevice.class);
    private final URL url;
    private final BluegigaHandler bgHandler;
    private String name;
    private short rssi;
    private int bluetoothClass;
    private boolean bleEnabled;
    private boolean serviceResolved;
    private final Map<URL, BluegigaService> services = new HashMap<>();

    // Notifications/listeners
    private Notification<Short> rssiNotification;
    private Notification<Boolean> connectedNotification;
    private Notification<Boolean> serviceResolvedNotification;

    // BG specific variables
    private int connectionHandle = -1;
    private BluetoothAddressType addressType = BluetoothAddressType.UNKNOWN;

    BluegigaDevice(BluegigaHandler bgHandler, URL url) {
        this.bgHandler = bgHandler;
        this.url = url;
    }

    @Override
    public boolean connect() {
        if (!isConnected()) {

            logger.info("Trying to connect: {}", url);
            BlueGigaConnectionStatusEvent event = bgHandler.connect(url);
            logger.info("Connected: {}", url);

            this.connectionHandle = event.getConnection();
            this.addressType = event.getAddressType();
            notifyConnected(true);

            logger.info("Discovering services: {}", url);
            // discover services
            bgHandler.getServices(connectionHandle)
                    .stream().map(this::convert).forEach(service -> services.put(service.getURL(), service));
            logger.info("Services discovered: {}", services.size());

            logger.info("Discovering characteristics: {}", url);
            // discover characteristics
            bgHandler.getCharacteristics(connectionHandle).stream().forEach(this::processCharacteristic);
            logger.info("Characteristics discovered");

            logger.info("Discovering declarations: {}", url);
            // discover characteristic properties (access flags)
            bgHandler.getDeclarations(connectionHandle).forEach(this::processDeclaration);
            logger.info("Declarations discovered: {}", url);

            serviceResolved();

            return true;
        }
        return false;
    }

    @Override
    public boolean disconnect() {
        if (isConnected()) {
            bgHandler.disconnect(connectionHandle);
            connectionHandle = -1;
            return true;
        }
        return false;
    }

    @Override
    public int getBluetoothClass() {
        return bluetoothClass;
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
            rssi = bgHandler.bgGetRssi(connectionHandle);
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
        return this.serviceResolved;
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
        servicesUnresolved();
        this.connectedNotification = null;
        this.rssiNotification = null;
        this.serviceResolvedNotification = null;
        this.connectionHandle = -1;
    }

    void handleScanEvent(BlueGigaScanResponseEvent scanEvent) {
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

    BluegigaService getService(URL url) {
        synchronized (services) {
            return services.get(url.getServiceURL());
        }
    }

    private void servicesUnresolved() {
        synchronized (services) {
            services.clear();
        }
        notifyServicesResolved(false);
        this.serviceResolved = false;
    }

    private void serviceResolved() {
        notifyServicesResolved(true);
        this.serviceResolved = true;
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
        return new BluegigaService(url.copyWith(event.getUuid().toString(), null), event.getStart(), event.getEnd());
    }

    private void processCharacteristic(BlueGigaFindInformationFoundEvent event) {
        long uuid = event.getUuid().getMostSignificantBits() >> 32;
        if (uuid >= 0x2800 && uuid <= 0x280F) {
            // Declarations (https://www.bluetooth.com/specifications/gatt/declarations)
            //TODO handle declarations (maybe just skip them)
        } else if (uuid >= 0x2900 && uuid <= 0x290F) {
            // Descriptors
            //TODO handle descriptors
        } else {
            // characteristics
            BluegigaService bluegigaService = getServiceByHandle(event.getChrHandle());
            if (bluegigaService == null) {
                throw new BluegigaException("Could not find a service by characteristic handle: " +
                        event.getChrHandle());
            }
            URL characteristicURL = bluegigaService.getURL().copyWithCharacteristic(event.getUuid().toString());
            BluegigaCharacteristic characteristic = new BluegigaCharacteristic(bgHandler, characteristicURL,
                    connectionHandle, event.getChrHandle());
            bluegigaService.addCharacteristic(characteristic);
        }
    }

    private void processDeclaration(BlueGigaAttributeValueEvent event) {
        //  characteristic declaration
        int[] attributeValue = event.getValue();

            /*
            It always contains a handle, a UUID, and a set of properties. These three elements describe the subsequent
            Characteristic Value Declaration. The handle naturally points to the Characteristic Value Declaration’s
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
            UUID characteristicUUID = BluegigaUtils.deserializeUUID(
                    Arrays.copyOfRange(attributeValue, 3, attributeValue.length));
            BluegigaCharacteristic bluegigaCharacteristic =
                    service.getCharacteristic(service.getURL().copyWithCharacteristic(characteristicUUID.toString()));
            if (bluegigaCharacteristic != null) {
                bluegigaCharacteristic.setFlags(CharacteristicAccessType.parse(attributeValue[0]));
            } else {
                logger.error("Could not find characteristic: " + characteristicUUID);
            }

        } else {
            logger.error("Could not find service by handle: " + event.getAttHandle());
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

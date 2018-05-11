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
import com.zsmartsystems.bluetooth.bluegiga.command.connection.BlueGigaConnectionStatusEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.gap.BlueGigaScanResponseEvent;
import com.zsmartsystems.bluetooth.bluegiga.command.system.BlueGigaGetInfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.transport.Adapter;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bluegiga transport adapter.
 * @author Vlad Kolotov
 * @author Chris Jackson
 */
class BluegigaAdapter implements Adapter, BlueGigaEventListener {

    public static final String BLUEGIGA_NAME = "Bluegiga";

    private final Logger logger = LoggerFactory.getLogger(BluegigaAdapter.class);

    private BlueGigaGetInfoResponse info;
    private boolean discovering;
    private final BluegigaHandler bgHandler;
    private final Map<URL, BluegigaDevice> devices = new HashMap<>();
    private Notification<Boolean> discoveringNotification;
    // just a local cache, BlueGiga adapters do not support aliases
    private String alias;

    private BluegigaAdapter(BluegigaHandler bluegigaHandler) {
        bgHandler = bluegigaHandler;
    }

    public boolean isAlive() {
        return getURL() != null && bgHandler.isAlive();
    }

    @Override
    public String getName() {
        return BLUEGIGA_NAME + Optional.ofNullable(info)
            .map(inf -> " v" + inf.getMajor() + "." + inf.getMinor()).orElse("");
    }

    @Override
    public boolean isDiscovering() {
        bgHandler.checkAlive();
        return discovering;
    }

    @Override
    public void enableDiscoveringNotifications(Notification<Boolean> notification) {
        logger.debug("Enable discovering notifications: {}", getURL());
        discoveringNotification = notification;
    }

    @Override
    public void disableDiscoveringNotifications() {
        logger.debug("Disable discovering notifications: {}", getURL());
        discoveringNotification = null;
    }

    @Override
    public boolean startDiscovery() {
        logger.debug("Starting discovery: {}", getURL());
        boolean discoveryStatus = bgHandler.bgStartScanning();
        if (!discovering && discoveryStatus) {
            logger.debug("Discovery successfully started: {}", getURL());
            discovering = true;
            notifyDiscovering(true);
        }
        return discoveryStatus;
    }

    @Override
    public boolean stopDiscovery() {
        logger.debug("Stopping discovery: {}", getURL());
        if (discovering) {
            discovering = false;
            notifyDiscovering(false);
            if (bgHandler.isAlive()) {
                bgHandler.bgStopProcedure();
            }
        }
        return true;
    }

    @Override
    public List<Device> getDevices() {
        return new ArrayList<>(devices.values());
    }

    @Override
    public URL getURL() {
        return bgHandler.getAdapterAddress();
    }


    @Override
    public void bluegigaEventReceived(BlueGigaResponse event) {
        if (event instanceof BlueGigaScanResponseEvent) {
            BlueGigaScanResponseEvent scanEvent = (BlueGigaScanResponseEvent) event;
            synchronized (devices) {
                URL deviceURL = getURL().copyWithDevice(scanEvent.getSender());
                if (!devices.containsKey(deviceURL)) {
                    logger.debug("New device discovered: {}", deviceURL);
                    BluegigaDevice bluegigaDevice = createDevice(deviceURL);
                    devices.put(deviceURL, bluegigaDevice);
                    // let the device to set its name and RSSI
                    bluegigaDevice.bluegigaEventReceived(scanEvent);
                    logger.debug("Created new device: {} ({}) {} ", bluegigaDevice.getURL().getDeviceAddress(),
                            bluegigaDevice.getName(), bluegigaDevice.getRSSI());
                }
            }
        } else if (event instanceof BlueGigaConnectionStatusEvent) {
            BlueGigaConnectionStatusEvent connectionStatusEvent = (BlueGigaConnectionStatusEvent) event;
            if (!"00:00:00:00:00:00".equals(connectionStatusEvent.getAddress())) {
                synchronized (devices) {
                    URL deviceURL = getURL().copyWithDevice(connectionStatusEvent.getAddress());
                    if (!devices.containsKey(deviceURL)) {
                        logger.debug("A connection event received: {}", deviceURL);
                        BluegigaDevice bluegigaDevice = createDevice(deviceURL, connectionStatusEvent);
                        devices.put(deviceURL, bluegigaDevice);
                        bluegigaDevice.bluegigaEventReceived(connectionStatusEvent);
                        logger.debug("Created new device: {} ({}) {} ", bluegigaDevice.getURL().getDeviceAddress(),
                                bluegigaDevice.getName(), bluegigaDevice.getRSSI());
                    }
                }
            }
        }
    }

    /*
    The following methods are not supporeted by Bluegiga
     */

    @Override
    public void setPowered(boolean powered) { /* do nothing */ }

    @Override
    public boolean isPowered() {
        bgHandler.checkAlive();
        return true;
    }

    @Override
    public void enablePoweredNotifications(Notification<Boolean> notification) { /* do nothing */ }

    @Override
    public void disablePoweredNotifications() { /* do nothing */ }

    @Override
    public String getAlias() {
        return alias;
    }

    @Override
    public void setAlias(String alias) {
        this.alias = alias;
    }

    protected BluegigaDevice getDevice(URL url) {
        logger.debug("Device requested: {}", url);
        URL deviceURL = url.getDeviceURL();
        synchronized (devices) {
            if (devices.containsKey(deviceURL)) {
                logger.debug("Device exists: {}", deviceURL);
                return devices.get(deviceURL);
            }
        }
        return null;
    }

    protected BluegigaDevice createDevice(URL address) {
        logger.debug("Creating a new device: {}", address);
        BluegigaDevice device = new BluegigaDevice(bgHandler, address);
        logger.debug("Device created: {} / {}", address, Integer.toHexString(device.hashCode()));
        return device;
    }

    protected BluegigaDevice createDevice(URL address, BlueGigaConnectionStatusEvent event) {
        logger.debug("Creating a new device from a connection status event: {} : {}",
                address, event.getConnection());
        BluegigaDevice device = new BluegigaDevice(bgHandler, address, event.getConnection(), event.getAddressType());
        logger.debug("Device created: {} / {}", address, Integer.toHexString(device.hashCode()));
        return device;
    }

    protected static BluegigaAdapter create(BluegigaHandler bluegigaHandler) {
        BluegigaAdapter bluegigaAdapter = new BluegigaAdapter(bluegigaHandler);
        bluegigaAdapter.init();
        return bluegigaAdapter;
    }

    protected String getPortName() {
        return bgHandler.getPortName();
    }

    protected void dispose() {
        logger.debug("Disposing adapter: {}", getURL());
        bgHandler.removeEventListener(this);
        try {
            bgHandler.runInSynchronizedContext(() -> {

                try {
                    stopDiscovery();
                } catch (Exception ex) {
                    logger.debug("Error occurred while stopping discovery process: {} : {} ",
                            getURL(), ex.getMessage());
                }

                devices.values().forEach(device -> {
                    try {
                        logger.debug("Disposing device: {}", device.getURL());
                        device.dispose();
                    } catch (Exception ex) {
                        logger.debug("Error occurred while disposing device: {} : {}",
                                device.getURL(), ex.getMessage());
                    }
                });
                devices.clear();

                bgHandler.dispose();
            });
        } catch (Exception ex) {
            logger.debug("Error occurred while disposing adapter: {} : {}", getURL(), ex.getMessage());
        }
        logger.debug("Adapter disposed: {}", getURL());
    }

    protected void disposeDevice(URL url) {
        logger.debug("Disposing device: {}", url);
        BluegigaDevice removed = devices.computeIfPresent(url, (key, device) -> {
            try {
                device.dispose();
            } catch (Exception ex) {
                logger.warn("Error occurred while disposing device: {} : {}", device.getURL(), ex.getMessage());
            }
            return null;
        });
        logger.debug("Device disposed: {} : {} ", url, removed == null);
    }

    private void init() {
        info = bgHandler.bgGetInfo();
        bgHandler.addEventListener(this);
    }

    private void notifyDiscovering(boolean isDiscovering) {
        Notification<Boolean> notification = discoveringNotification;
        if (notification != null) {
            try {
                notification.notify(isDiscovering);
            } catch (Exception ex) {
                logger.error("Error occurred in discovering notification", ex);
            }
        }
    }

}
